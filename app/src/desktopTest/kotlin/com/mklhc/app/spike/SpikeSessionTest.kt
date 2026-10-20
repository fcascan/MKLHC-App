package com.mklhc.app.spike

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.DhcpMessageType
import com.mklhc.core.radio.IpcCodec
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.radio.RadiodConfig
import com.mklhc.core.radio.RadiodLauncher
import com.mklhc.core.radio.RadiodProcess
import com.mklhc.core.wifi_ap.lp2p.Lp2pKeys
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.MacAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [runSpikeSession] end to end against an in-process fake radiod: the real [com.mklhc.core.radio.DesktopRadioLink]
 * socket, the real [com.mklhc.core.radio.RadiodSupervisor], [SpikeRunner] and a real pcap file, with only the radiod
 * process replaced by a thread that speaks the IPC protocol. A fake kart sends its frames through that radiod. This is
 * everything the hardware run does except the adapter, so a failure here is a wiring bug, not a radio problem.
 */
class SpikeSessionTest {

    private val tempDir: Path = Files.createTempDirectory("mklhc-spike-test")
    private val outDir: Path = tempDir.resolve("captures")
    private val radiodExecutable: Path = Files.createFile(tempDir.resolve("radiod-fake"))
    private val launcher = FakeLauncher()

    @AfterTest
    fun tearDown() {
        launcher.processes.forEach { it.crash() }
        tempDir.toFile().deleteRecursively()
    }

    // ---- The GO scenario through the whole composition --------------------------------------------

    @Test
    fun aWholeSessionBeaconsAssociatesTheKartDecryptsItsDhcpAndWritesThePcap() = sessionTest {
        val console = ConsoleCapture()
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) { runSpikeSession(args(), stop, launcher = launcher, nonceTable = knownNonceTable(), console = console::add) }

        // radiod starts, is told to bring the radio up in order, and the pairing beacon goes on air.
        val radiod = launcher.awaitProcess(0)
        val beacon = radiod.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(
            listOf("Hello", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            radiod.messages().map { it::class.simpleName },
        )
        assertEquals(RadioMessage.OpenDevice(0x0BDA, 0xC812), radiod.messages()[1])
        assertEquals(RadioMessage.Init(channel = 6, bandwidthMhz = 20, txPowerIndex = 0), radiod.messages()[2])
        val beaconFrame = assertNotNullFrame(beacon.beacon)
        assertTrue(beaconFrame.isBeacon)
        val identity = radiod.messages()[3] as RadioMessage.SetIdentity
        assertEquals(beaconFrame.addr2, MacAddress.fromBytes(identity.mac), "the BSSID of the beacon is the radio's identity")

        // The console shows the pairing payload; the fake kart derives the network key from it, as the real one would.
        val payload = console.qrPayload()
        val ssid = payload.copyOfRange(0x10, 0x30).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        assertTrue(ssid.startsWith("mklhc-pair_"), ssid)
        assertTrue(console.text().contains("\u2588") || console.text().contains("\u2580") || console.text().contains("\u2584"), "the QR code is drawn")
        val key = Lp2pKeys(primitives).deriveCcmpKey(knownNonceTable().getPlaintextNonce(0), primitives.sha256(payload.copyOf(16)))
        val kart = FakeStation(key, beaconFrame.addr2, primitives = primitives)

        // The kart authenticates, associates and broadcasts a DHCP DISCOVER, all through radiod's RX_FRAME messages.
        radiod.sendRxFrame(kart.authRequest())
        radiod.sendRxFrame(kart.assocRequest(ssid))
        radiod.sendRxFrame(kart.dhcpFrame(kart.discover(), pn = 1))
        val transmitted = radiod.awaitMessages<RadioMessage.TxFrame>(3)
        assertTrue(Ieee80211Frame.parse(transmitted[0].frame)!!.isAuth)
        assertTrue(Ieee80211Frame.parse(transmitted[1].frame)!!.isAssocResponse)
        val offer = kart.dhcpFromAp(transmitted[2].frame)
        assertEquals(DhcpMessageType.OFFER, offer.messageType)
        assertEquals(AddressPlan.KART_LEASE, offer.yourIp)

        // Once the beacon is on air the QR code is printed again, so the code to scan is the last thing on screen.
        assertTrue(console.text().contains("The pairing network is on air"), "the QR code is printed again once on air")
        assertEquals(2, console.text().lines().count { it.startsWith("Pairing QR payload") })

        // Stopping tears everything down in order and succeeds.
        stop.complete(StopRequest("test finished", failed = false))
        assertEquals(EXIT_OK, session.await())

        val afterBeacon = radiod.messages().dropWhile { it !is RadioMessage.StartBeacon }.drop(1).filter { it !is RadioMessage.TxFrame }
        assertEquals(listOf<RadioMessage>(RadioMessage.StopBeacon, RadioMessage.Shutdown), afterBeacon)
        assertEquals(1, launcher.processes.size, "radiod exited by itself after SHUTDOWN and was not launched again")
        assertFalse(Files.exists(launcher.configs.single().socketPath.parent), "the socket and its directory are removed")

        // The pcap on disk is valid, radiotap, and holds the decrypted DISCOVER.
        val pcapFile = Files.list(outDir).use { files -> files.toList() }.single()
        assertTrue(pcapFile.fileName.toString().matches(Regex("spike-\\d{8}-\\d{6}\\.pcap")), pcapFile.toString())
        val capture = parsePcap(Files.readAllBytes(pcapFile))
        assertEquals(127, capture.linkType)
        assertEquals(6, capture.frames.size, "auth req/resp, assoc req/resp, DISCOVER, OFFER")
        val discoverFrame = Ieee80211Frame.parse(capture.frames[4].frame)!!
        assertFalse(discoverFrame.isProtected)
        val discover = dhcpFromBody(discoverFrame.body)
        assertEquals(DhcpMessageType.DISCOVER, discover.messageType)
        assertContentEquals(kart.mac.toByteArray(), discover.clientMac)
    }

    // ---- Failure and lifecycle --------------------------------------------------------------------

    @Test
    fun aRadiodErrorDuringBringUpEndsTheSessionWithAFailureAndStillShutsRadiodDown() = sessionTest {
        launcher.initReply = RadioMessage.Error(code = 3u, text = "cannot open the adapter")
        val console = ConsoleCapture()

        val code = runSpikeSession(args(), CompletableDeferred(), launcher = launcher, nonceTable = knownNonceTable(), console = console::add)

        assertEquals(EXIT_RADIO_FAILED, code)
        val radiod = launcher.processes.single()
        assertTrue(radiod.messages().any { it is RadioMessage.Shutdown }, "cleanup still shuts radiod down")
        assertTrue(radiod.messages().none { it is RadioMessage.StartBeacon }, "no beacon after a failed bring-up")
        // The pcap file exists and is a valid, empty capture.
        val pcapFile = Files.list(outDir).use { files -> files.toList() }.single()
        assertEquals(0, parsePcap(Files.readAllBytes(pcapFile)).frames.size)
    }

    @Test
    fun aRadiodRestartBringsTheRadioUpAgainOnTheNewConnection() = sessionTest {
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) { runSpikeSession(args(), stop, launcher = launcher, nonceTable = knownNonceTable(), console = ConsoleCapture()::add) }
        val first = launcher.awaitProcess(0)
        val firstBeacon = first.awaitMessages<RadioMessage.StartBeacon>(1).single()

        first.crash() // radiod dies; the supervisor restarts it after its backoff and the link accepts the new one

        val second = launcher.awaitProcess(1)
        val secondBeacon = second.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(
            listOf("Hello", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            second.messages().map { it::class.simpleName },
        )
        assertContentEquals(firstBeacon.beacon, secondBeacon.beacon, "the same pairing network is beaconed again")

        stop.complete(StopRequest("test finished", failed = false))
        assertEquals(EXIT_OK, session.await())
        assertTrue(second.messages().any { it is RadioMessage.Shutdown })
        assertEquals(2, launcher.processes.size, "no third launch during shutdown")
    }

    @Test
    fun theDurationOptionEndsTheSessionByItself() = sessionTest {
        val code = runSpikeSession(args(durationSeconds = 1), CompletableDeferred(), launcher = launcher, nonceTable = knownNonceTable(), console = ConsoleCapture()::add)

        assertEquals(EXIT_OK, code)
        assertTrue(launcher.processes.single().messages().any { it is RadioMessage.Shutdown })
    }

    @Test
    fun aMissingRadiodExecutableIsReportedAndNothingIsStarted() = sessionTest {
        val console = ConsoleCapture()
        val missing = tempDir.resolve("no-such-radiod")

        val code = runSpikeSession(args().copy(radiod = missing), CompletableDeferred(), launcher = launcher, nonceTable = knownNonceTable(), console = console::add)

        assertEquals(EXIT_USAGE, code)
        assertTrue(console.text().contains("radiod executable was not found"), console.text())
        assertTrue(launcher.processes.isEmpty())
        assertFalse(Files.exists(outDir), "no capture file is created")
    }

    // ---- Fakes and helpers ------------------------------------------------------------------------

    private val primitives = CryptoPrimitives()

    private fun args(durationSeconds: Long = 0): SpikeArgs =
        SpikeArgs(radiod = radiodExecutable, outDir = outDir, durationSeconds = durationSeconds)

    /** A nonce table whose random pick is always index 0, so the test can recompute the network key. */
    private fun knownNonceTable() = NonceTable(table = ByteArray(0x10000 * 16), nowEpochSeconds = { 0L }, randomInt = { 0 })

    private fun sessionTest(block: suspend CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(60.seconds) { block() } }

    private fun assertNotNullFrame(bytes: ByteArray): Ieee80211Frame =
        checkNotNull(Ieee80211Frame.parse(bytes)) { "not an 802.11 frame" }

    /** Collects what the session prints to the console. */
    private class ConsoleCapture {
        private val lines = CopyOnWriteArrayList<String>()

        fun add(line: String) {
            lines += line
        }

        fun text(): String = lines.joinToString("\n")

        /** The 62-octet pairing payload, from the hex line printed after its heading. */
        fun qrPayload(): ByteArray {
            val heading = lines.indexOfFirst { it.startsWith("Pairing QR payload") }
            check(heading >= 0) { "no QR payload heading in the console output:\n${text()}" }
            return lines[heading + 1].hexToByteArray()
        }
    }

    /** Starts [FakeRadiod] instances instead of real processes. */
    private inner class FakeLauncher : RadiodLauncher {
        val processes = CopyOnWriteArrayList<FakeRadiod>()
        val configs = CopyOnWriteArrayList<RadiodConfig>()

        /** What a fake radiod answers to INIT. */
        @Volatile
        var initReply: RadioMessage.RadiodToApp = RadioMessage.Ready("rtl8812cu", "fw-1", "devourer-test")

        override fun launch(config: RadiodConfig): RadiodProcess {
            configs += config
            return FakeRadiod(config, initReply).also { processes += it }
        }

        /** Suspends until the [index]-th radiod has been launched. */
        suspend fun awaitProcess(index: Int): FakeRadiod {
            while (processes.size <= index) delay(POLL_MS)
            return processes[index]
        }
    }

    /**
     * The radiod side of the socket, in-process: connects to the link's Unix domain socket like the real radiod, records
     * every message it receives, answers INIT, and exits on SHUTDOWN or when the socket closes.
     */
    private class FakeRadiod(private val config: RadiodConfig, private val initReply: RadioMessage.RadiodToApp) : RadiodProcess {
        val received = MutableStateFlow<List<RadioMessage>>(emptyList())
        private val exit = CompletableDeferred<Int>()
        private val channel: SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.connect(UnixDomainSocketAddress.of(config.socketPath))
        }

        init {
            thread(isDaemon = true, name = "fake-radiod") { readLoop() }
        }

        fun messages(): List<RadioMessage> = received.value

        /** Suspends until at least [count] messages of type [T] have arrived and returns them. */
        suspend inline fun <reified T : RadioMessage> awaitMessages(count: Int): List<T> =
            received.first { list -> list.count { it is T } >= count }.filterIsInstance<T>()

        /** Plays radiod delivering a received frame to the app. */
        fun sendRxFrame(frame: ByteArray) {
            send(RadioMessage.RxFrame(rssiDbm = -40, rate = 0, tsf = 1uL, frame = frame))
        }

        /** Plays radiod crashing: the socket drops and the process "exits" with an error. */
        fun crash() {
            runCatching { channel.close() }
            exit.complete(KILLED_EXIT_CODE)
        }

        override suspend fun awaitExit(): Int = exit.await()

        override suspend fun kill() {
            crash()
        }

        private fun send(message: RadioMessage) {
            val buffer = ByteBuffer.wrap(IpcCodec.encode(message))
            synchronized(channel) {
                while (buffer.hasRemaining()) channel.write(buffer)
            }
        }

        private fun readLoop() {
            try {
                while (true) {
                    val message = readMessage()
                    received.update { it + message }
                    when (message) {
                        is RadioMessage.Hello -> check(message.token == config.token) { "the session token must reach radiod" }
                        is RadioMessage.Init -> send(initReply)
                        is RadioMessage.Shutdown -> {
                            runCatching { channel.close() }
                            exit.complete(0)
                            return
                        }
                        else -> Unit
                    }
                }
            } catch (closed: IOException) {
                exit.complete(KILLED_EXIT_CODE)
            }
        }

        private fun readMessage(): RadioMessage {
            val header = ByteArray(IpcCodec.HEADER_SIZE)
            readFully(header, 0, header.size)
            val length = IpcCodec.payloadLengthOf(header)
            val bytes = ByteArray(IpcCodec.HEADER_SIZE + length)
            header.copyInto(bytes)
            readFully(bytes, IpcCodec.HEADER_SIZE, length)
            return IpcCodec.decode(bytes)
        }

        private fun readFully(target: ByteArray, offset: Int, length: Int) {
            val buffer = ByteBuffer.wrap(target, offset, length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw EOFException("the app closed the connection")
            }
        }

        private companion object {
            const val KILLED_EXIT_CODE = 137
        }
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
