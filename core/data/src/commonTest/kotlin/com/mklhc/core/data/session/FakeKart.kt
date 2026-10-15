package com.mklhc.core.data.session

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.radio.FakeKartRadioAp
import com.mklhc.core.kart_protocol.fuji.FujiControlClient
import com.mklhc.core.kart_protocol.fuji.FujiPairingClient
import com.mklhc.core.kart_protocol.fuji.FujiPorts
import com.mklhc.core.kart_protocol.rcd.RcdClient
import com.mklhc.core.kart_protocol.rcd.RcdErrorBadRequest
import com.mklhc.core.kart_protocol.rcd.RcdMsg
import com.mklhc.core.kart_protocol.rcd.RcdServer
import com.mklhc.core.kart_protocol.rcd.RcdServerInfo
import com.mklhc.core.kart_protocol.rcd.RcdService
import com.mklhc.core.kart_protocol.rcd.asRcdTransport
import com.mklhc.core.kart_protocol.video.LspVideo
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.NetstackConfig
import com.mklhc.core.netstack.NetstackNode
import com.mklhc.core.netstack.UdpEndpoint
import com.mklhc.core.netstack.tcp.TcpConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * A small kart simulator that drives the real orchestration end to end over the paired FrameLink.
 *
 * Business logic: it is the other side of [KartSessionRepository]. It runs its own [NetstackNode] as the
 * kart (it assumes the DHCP lease [AddressPlan.KART_LEASE]), appears associated, connects to the app's RCD
 * server and completes the real RCD handshake as a client (known-device in game mode, first-time in pairing
 * mode), hosts the kart's control / pairing services, accepts the teleop datagrams, and can push telemetry
 * and video datagrams at the app. It is deliberately minimal: just enough of each real protocol to make the
 * repository's sequence run for real, with every interesting value recorded for assertions.
 *
 * Not thread-safe; drive it from the test's (single) dispatcher, like everything it composes.
 *
 * @param radioAp the fake radio whose station end the kart lives on.
 * @param masterSecret the installation master secret (shared with the app) the identity is derived from.
 * @param scope the scope the kart's node, servers and loops run on.
 * @param crypto the crypto primitives (shared with the app so the derived credentials match).
 * @param config the session ports, kept in sync with the app under test.
 * @param deviceName the device name presented in the RCD handshake.
 * @param deviceIdent the 16-octet device identity presented in the RCD handshake.
 * @param serial the kart serial reported in the product code.
 * @param character the character id reported in the product code.
 */
class FakeKart(
    private val radioAp: FakeKartRadioAp,
    masterSecret: ByteArray,
    private val scope: CoroutineScope,
    private val crypto: CryptoPrimitives = CryptoPrimitives(),
    private val config: SessionConfig = SessionConfig(),
    val deviceName: String = "FakeKart",
    val deviceIdent: ByteArray = ByteArray(IDENT_SIZE) { (it + 1).toByte() },
    val serial: String = "FK-0001",
    val character: Int = 1,
) {
    private val node = NetstackNode(
        frameLink = radioAp.stationLink,
        scope = scope,
        config = NetstackConfig(ourIp = AddressPlan.KART_LEASE, ourMac = KART_MAC),
    )
    private val serverInfo: RcdServerInfo = deriveKartIdentity(masterSecret, crypto).toRcdServerInfo(crypto)

    /** Every teleop datagram the app sent to the drive port, in order. */
    val teleopPackets = ArrayList<ByteArray>()

    /** All bytes received on the video (LSP) control channel (the app's LVNI keepalives), concatenated. */
    @Volatile
    var lvniBytes: ByteArray = ByteArray(0)
        private set

    /** The `connection_info` value the app set, or null. */
    @Volatile
    var connectionInfo: ByteArray? = null
        private set

    /** The last drive state the app set (1 = drive, 0 = sleep), or null. */
    @Volatile
    var driveState: Int? = null
        private set

    /** True once the app asked the kart to power off. */
    @Volatile
    var shutdownRequested: Boolean = false
        private set

    /** The (ssid, psk) the app handed the kart while pairing, or null. */
    @Volatile
    var groupInfo: Pair<String, ByteArray>? = null
        private set

    private var driveUdp: UdpEndpoint? = null
    private var sendUdp: UdpEndpoint? = null
    private var game: GameSockets? = null

    /** Brings the kart's network stack up; call once before any session. */
    fun start() {
        node.start()
    }

    private suspend fun ensureUdp() {
        if (driveUdp != null) return
        val drive = node.bindUdp(FujiPorts.DRIVE)
        driveUdp = drive
        scope.launch {
            try {
                while (true) teleopPackets += drive.receive().payload
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (closed: ClosedReceiveChannelException) {
                // endpoint closed
            }
        }
        sendUdp = node.bindUdp(KART_SEND_PORT)
    }

    /**
     * Associates, hosts the kart's services and connects to the app's session RCD server, completing the
     * real handshake. Returns once the handshake is done; the kart's services keep running in [scope].
     */
    suspend fun runGameSession() {
        ensureUdp()
        radioAp.setStationAssociated(true)
        val controlListener = node.tcp.listen(FujiPorts.CONTROL)
        val eventListener = node.tcp.listen(FujiPorts.EVENT)
        val sockets = GameSockets(controlListener, eventListener)
        game = sockets

        sockets.jobs += scope.launch {
            val connection = controlListener.accept()
            sockets.controlConn = connection
            val server = RcdServer(connection.asRcdTransport(), listOf(controlService()))
            guard { server.serve() }
        }
        sockets.jobs += scope.launch {
            // On the event channel the kart is the requester; this simulator sends nothing, so it just
            // holds the accepted connection open so the app's event channel has a live peer.
            sockets.eventConn = eventListener.accept()
        }

        val rcdConnection = openAndConnect(KART_LOCAL_SESSION, config.sessionRcdPort)
        sockets.serverConn = rcdConnection
        clientHandshake(RcdClient(rcdConnection.asRcdTransport()), serverInfo, deviceName, deviceIdent, pairing = false, crypto)

        sockets.jobs += scope.launch {
            val lsp = openAndConnect(KART_LOCAL_VIDEO_CONTROL, config.videoControlPort)
            sockets.lspConn = lsp
            guard {
                while (true) {
                    val chunk = lsp.read(READ_CHUNK)
                    if (chunk.isEmpty()) break
                    lvniBytes += chunk
                }
            }
        }
    }

    /**
     * Pairs first: hosts the pairing service, connects to the app's pairing RCD server, completes the
     * first-time handshake, waits for the app to hand over the game credentials, then resets onto the game
     * network and runs the normal game session.
     */
    suspend fun runPairingThenGame() {
        ensureUdp()
        radioAp.setStationAssociated(true)
        val pairingListener = node.tcp.listen(FujiPorts.PAIRING)
        val groupInfoReceived = CompletableDeferred<Unit>()
        val serveJob = scope.launch {
            val connection = pairingListener.accept()
            val server = RcdServer(connection.asRcdTransport(), listOf(pairingService(groupInfoReceived)))
            guard { server.serve() }
        }

        val rcdConnection = openAndConnect(KART_LOCAL_PAIRING, config.pairingRcdPort)
        clientHandshake(RcdClient(rcdConnection.asRcdTransport()), serverInfo, deviceName, deviceIdent, pairing = true, crypto)

        groupInfoReceived.await()

        // Reset onto the game network, as the real kart does once it has the credentials.
        serveJob.cancel()
        runCatching { rcdConnection.abort() }
        pairingListener.close()
        radioAp.setStationAssociated(false)

        runGameSession()
    }

    /** Simulates an RCD loss / kart reset: drops the session sockets and the association. */
    suspend fun resetLink() {
        game?.close()
        game = null
        radioAp.setStationAssociated(false)
    }

    /** Pushes one telemetry datagram at the app's telemetry port. */
    suspend fun sendTelemetry(datagram: ByteArray) {
        sendUdp!!.send(AddressPlan.AP, config.telemetryPort, datagram)
    }

    /** Pushes one video datagram at the app's video stream port. */
    suspend fun sendVideo(datagram: ByteArray) {
        sendUdp!!.send(AddressPlan.AP, config.videoStreamPort, datagram)
    }

    // ---- the kart's RCD services --------------------------------------------------------------

    private fun controlService(): RcdService = object : RcdService {
        override val serviceId: Int = FujiControlClient.SERVICE_ID

        override suspend fun dispatch(msg: RcdMsg): ByteArray = when (msg.command) {
            CMD_GET_SYSTEM_INFO -> systemInfoBytes()
            CMD_SET_PARAM -> {
                recordSetParam(msg.data)
                EMPTY
            }
            CMD_GET_PARAM -> paramResponse(nameOf(msg.data))
            CMD_SET_STATE -> {
                driveState = msg.data[0].toInt() and BYTE_MASK
                EMPTY
            }
            CMD_SHUTDOWN -> {
                shutdownRequested = true
                EMPTY
            }
            else -> throw RcdErrorBadRequest()
        }
    }

    private fun pairingService(groupInfoReceived: CompletableDeferred<Unit>): RcdService = object : RcdService {
        override val serviceId: Int = FujiPairingClient.SERVICE_ID

        override suspend fun dispatch(msg: RcdMsg): ByteArray {
            if (msg.command != CMD_SET_GROUP_INFO) throw RcdErrorBadRequest()
            val ssid = latin1UntilNul(msg.data, 0, SSID_FIELD_SIZE)
            val psk = msg.data.copyOfRange(SSID_FIELD_SIZE, SSID_FIELD_SIZE + PSK_SIZE)
            groupInfo = ssid to psk
            groupInfoReceived.complete(Unit)
            return EMPTY
        }
    }

    private fun systemInfoBytes(): ByteArray =
        byteArrayOf(BOOT_MAJOR, BOOT_MINOR, SYSTEM_MAJOR, SYSTEM_MINOR) + SHA1.encodeToByteArray() + byteArrayOf(0)

    private fun productCodeValue(): ByteArray {
        val header = byteArrayOf(0, 0, character.toByte(), (character ushr Byte.SIZE_BITS).toByte(), 0)
        return header + serial.encodeToByteArray() + byteArrayOf(0)
    }

    private fun paramResponse(name: String): ByteArray {
        val value = if (name == PARAM_PRODUCT_CODE) productCodeValue() else EMPTY
        return padTo(u16BigEndian(value.size), PARAM_LENGTH_SIZE) + value
    }

    private fun recordSetParam(request: ByteArray) {
        val name = nameOf(request)
        if (name != PARAM_CONNECTION_INFO) return
        val size = (request[PARAM_NAME_SIZE].toInt() and BYTE_MASK shl Byte.SIZE_BITS) or
            (request[PARAM_NAME_SIZE + 1].toInt() and BYTE_MASK)
        val valueStart = PARAM_NAME_SIZE + PARAM_LENGTH_SIZE
        connectionInfo = request.copyOfRange(valueStart, valueStart + size)
    }

    private fun nameOf(request: ByteArray): String = latin1UntilNul(request, 0, PARAM_NAME_SIZE)

    /**
     * Opens a connection to the app and connects, retrying on a reset: the app listens on its RCD and
     * video-control ports only after it reaches the right phase, so an eager kart can briefly race it. A
     * fresh local port is used per attempt so a prior reset connection never blocks reuse.
     */
    private suspend fun openAndConnect(localPortBase: Int, peerPort: Int): TcpConnection {
        var attempt = 0
        while (true) {
            val connection = node.tcp.open(localPortBase + attempt, AddressPlan.AP, peerPort)
            try {
                connection.connect()
                return connection
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                runCatching { connection.abort() }
                attempt++
                if (attempt >= MAX_CONNECT_ATTEMPTS) throw failure
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // expected on teardown / peer reset
        }
    }

    private inner class GameSockets(
        private val controlListener: com.mklhc.core.netstack.tcp.TcpListener,
        private val eventListener: com.mklhc.core.netstack.tcp.TcpListener,
    ) {
        val jobs = ArrayList<Job>()
        var controlConn: TcpConnection? = null
        var eventConn: TcpConnection? = null
        var serverConn: TcpConnection? = null
        var lspConn: TcpConnection? = null

        suspend fun close() = withContext(NonCancellable) {
            jobs.forEach { it.cancel() }
            runCatching { serverConn?.abort() }
            runCatching { controlConn?.abort() }
            runCatching { eventConn?.abort() }
            runCatching { lspConn?.abort() }
            controlListener.close()
            eventListener.close()
        }
    }

    companion object {
        private const val IDENT_SIZE = 16
        private const val BYTE_MASK = 0xFF
        private const val READ_CHUNK = 64
        private const val MAX_CONNECT_ATTEMPTS = 40
        private const val RETRY_DELAY_MS = 50L

        private const val KART_SEND_PORT = 50_116
        private const val KART_LOCAL_SESSION = 50_202
        private const val KART_LOCAL_PAIRING = 50_201
        private const val KART_LOCAL_VIDEO_CONTROL = 50_032

        private const val CMD_GET_SYSTEM_INFO = 1
        private const val CMD_SET_PARAM = 2
        private const val CMD_GET_PARAM = 3
        private const val CMD_SET_STATE = 4
        private const val CMD_SHUTDOWN = 9
        private const val CMD_SET_GROUP_INFO = 1

        private const val PARAM_NAME_SIZE = 0x80
        private const val PARAM_LENGTH_SIZE = 0x10
        private const val SSID_FIELD_SIZE = 0x20
        private const val PSK_SIZE = 0x20

        private const val PARAM_PRODUCT_CODE = "product_code"
        private const val PARAM_CONNECTION_INFO = "connection_info"

        private const val BOOT_MAJOR: Byte = 1
        private const val BOOT_MINOR: Byte = 2
        private const val SYSTEM_MAJOR: Byte = 3
        private const val SYSTEM_MINOR: Byte = 4
        private const val SHA1 = "abc123def456"

        private val KART_MAC = byteArrayOf(0x02, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0x01)
        private val EMPTY = ByteArray(0)

        /** Expected product code serial/firmware the app should surface, for assertions. */
        val EXPECTED_FIRMWARE_SYSTEM = 3 to 4
        val EXPECTED_FIRMWARE_BOOT = 1 to 2
        const val EXPECTED_SHA1 = SHA1
    }
}

/**
 * Runs the client side of the RCD handshake over [rcd] against the app's real [RcdHandshakeService].
 *
 * It mirrors the server's block-aligned running hash on the client side from the bytes actually
 * exchanged: begin, negotiate (and, while pairing, get-secret-key), then a finalize carrying the
 * SHA-256 of the 64-octet-aligned prefix of the whole transcript. A known-device offers the pairing id
 * the server derives for it (straight to finalize); a first-time device offers an unknown id and is
 * routed through get-secret-key.
 */
internal suspend fun clientHandshake(
    rcd: RcdClient,
    serverInfo: RcdServerInfo,
    deviceName: String,
    deviceIdent: ByteArray,
    pairing: Boolean,
    crypto: CryptoPrimitives,
) {
    val realPairingId = serverInfo.getPairingKeys(deviceIdent, deviceName).first
    val offeredPairingId = if (pairing) ByteArray(PAIRING_ID_SIZE) else realPairingId

    val beginRequest = ByteArray(BEGIN_PAYLOAD_SIZE)
    beginRequest[0] = HANDSHAKE_VERSION
    deviceName.encodeToByteArray().copyInto(beginRequest, destinationOffset = FIELD)
    deviceIdent.copyInto(beginRequest, destinationOffset = FIELD * 2)
    val beginResponse = rcd.invoke(HANDSHAKE_SERVICE, CMD_BEGIN, beginRequest)

    val offeredVersions = byteArrayOf(2, 1)
    val negotiateRequest = offeredPairingId + byteArrayOf(offeredVersions.size.toByte()) + offeredVersions
    val negotiateResponse = rcd.invoke(HANDSHAKE_SERVICE, CMD_NEGOTIATE, negotiateRequest)

    val transcript = ArrayList<ByteArray>()
    transcript += beginRequest
    transcript += beginResponse
    transcript += negotiateRequest
    transcript += negotiateResponse
    if (pairing) {
        val learnedPairingId = negotiateResponse.copyOf(PAIRING_ID_SIZE)
        val getSecretResponse = rcd.invoke(HANDSHAKE_SERVICE, CMD_GET_SECRET, learnedPairingId)
        transcript += learnedPairingId
        transcript += getSecretResponse
    }

    val concatenated = transcript.reduce { acc, part -> acc + part }
    val aligned = concatenated.copyOf(concatenated.size - concatenated.size % HASH_BLOCK)
    rcd.invoke(HANDSHAKE_SERVICE, CMD_FINALIZE, crypto.sha256(aligned))
}

private const val HANDSHAKE_SERVICE = 0x0001
private const val CMD_BEGIN = 1
private const val CMD_NEGOTIATE = 2
private const val CMD_GET_SECRET = 3
private const val CMD_FINALIZE = 4
private const val HANDSHAKE_VERSION: Byte = 1
private const val BEGIN_PAYLOAD_SIZE = 0x50
private const val PAIRING_ID_SIZE = 0x20
private const val FIELD = 0x10
private const val HASH_BLOCK = 64

/** Zero-pads [bytes] on the right to [length] octets. */
internal fun padTo(bytes: ByteArray, length: Int): ByteArray = bytes.copyOf(length)

/** The two octets of [value] most significant first. */
internal fun u16BigEndian(value: Int): ByteArray = byteArrayOf((value ushr Byte.SIZE_BITS).toByte(), value.toByte())

/** The four octets of [value] most significant first. */
internal fun u32BigEndian(value: Int): ByteArray = ByteArray(4) { (value ushr (Byte.SIZE_BITS * (3 - it))).toByte() }

/** Decodes [bytes] from [from] up to the first NUL (or [limit]) as latin1 text. */
internal fun latin1UntilNul(bytes: ByteArray, from: Int, limit: Int): String {
    val end = minOf(limit, bytes.size)
    val text = StringBuilder()
    for (i in from until end) {
        val octet = bytes[i].toInt() and 0xFF
        if (octet == 0) break
        text.append(octet.toChar())
    }
    return text.toString()
}
