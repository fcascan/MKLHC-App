package com.mklhc.core.radio

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channel
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * [RadioLink] for desktop: talks to a locally spawned radiod over a Unix domain socket.
 *
 * Business logic: the app is the socket server. [listen] binds a `UnixDomainSocketAddress` at
 * [socketPath] (JDK 16+, works on Linux and Windows 10+) and accepts radiod's connection; the
 * [RadiodSupervisor] spawns radiod with that path and the session [token]. For every accepted
 * connection the link first sends HELLO with the protocol version and token, then frames commands
 * and decodes radiod's messages with [IpcCodec] on IO dispatchers: RX_FRAME goes to [rxFrames],
 * READY/STATUS/ERROR/LOG/DEVICE_LIST go to [events] (LOG is also re-logged under Kermit tag `radiod`). When
 * radiod disconnects or restarts the link drops the connection, clears [connected] and goes back
 * to accepting, so the next radiod instance is picked up automatically; commands issued while
 * disconnected throw [IllegalStateException].
 *
 * A malformed message with a valid length is logged and skipped; a corrupt length (above
 * [IpcCodec.MAX_PAYLOAD_LENGTH]) means the stream is out of sync and the connection is dropped.
 * Flows are hot and drop the oldest item when a collector falls behind.
 *
 * Integration status: this class compiles and is exercised by a loopback test against an
 * in-process fake radiod, but it has never talked to the real radiod binary (it does not exist
 * yet). The end-to-end path is verified in the hardware spike (Task 1.8).
 *
 * @param socketPath where to bind the socket; any existing file at the path is deleted first.
 * @param token the per-session token sent in HELLO and passed to radiod on its command line.
 * @param scope scope that owns the accept and read coroutines; cancel it or call [close] to stop.
 */
class DesktopRadioLink(
    private val socketPath: Path,
    private val token: String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RadioLink, AutoCloseable {

    private val log = Logger.withTag(TAG)
    private val radiodLog = Logger.withTag(RADIOD_TAG)

    private val rxFramesFlow = MutableSharedFlow<RadioFrame>(
        extraBufferCapacity = RX_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val eventsFlow = MutableSharedFlow<RadioMessage>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val connectedFlow = MutableStateFlow(false)

    /** Serializes writes so concurrent commands never interleave their bytes. */
    private val writeMutex = Mutex()

    /** The connection commands are written to; set only after HELLO has been sent. */
    @Volatile
    private var activeChannel: SocketChannel? = null

    @Volatile
    private var serverChannel: ServerSocketChannel? = null
    private var serveJob: Job? = null

    override val rxFrames: Flow<RadioFrame> = rxFramesFlow.asSharedFlow()

    override val events: Flow<RadioMessage> = eventsFlow.asSharedFlow()

    /** True while a radiod connection is established and HELLO has been sent. */
    val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

    /**
     * Binds the socket at [socketPath] and starts accepting radiod connections in the background.
     * Call this before starting radiod so the socket exists when radiod connects.
     *
     * @throws IllegalStateException when already listening.
     */
    suspend fun listen() {
        check(serveJob == null) { "listen(): already listening -> socketPath=$socketPath" }
        val server = withContext(ioDispatcher) {
            Files.deleteIfExists(socketPath)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
                it.bind(UnixDomainSocketAddress.of(socketPath))
            }
        }
        serverChannel = server
        log.i { "listen(): socket bound -> socketPath=$socketPath" }
        serveJob = scope.launch { acceptLoop(server) }
    }

    override suspend fun openDevice(vid: Int, pid: Int) = send(RadioMessage.OpenDevice(vid, pid))

    override suspend fun init(channel: Int, bandwidthMhz: Int, txPowerIndex: Int) =
        send(RadioMessage.Init(channel, bandwidthMhz, txPowerIndex))

    override suspend fun setIdentity(mac: ByteArray) = send(RadioMessage.SetIdentity(mac))

    override suspend fun startBeacon(intervalTu: Int, beacon: ByteArray) =
        send(RadioMessage.StartBeacon(intervalTu, beacon))

    override suspend fun updateBeacon(beacon: ByteArray) = send(RadioMessage.UpdateBeacon(beacon))

    override suspend fun stopBeacon() = send(RadioMessage.StopBeacon)

    override suspend fun txFrame(rate: String, retryLimit: Int, flags: Int, frame: ByteArray) =
        send(RadioMessage.TxFrame(rate, retryLimit, flags, frame))

    override suspend fun shutdown() = send(RadioMessage.Shutdown)

    override suspend fun listDevices(requestId: UInt) = send(RadioMessage.ListDevices(requestId))

    /** Stops accepting, drops the current connection and removes the socket file. */
    override fun close() {
        log.i { "close(): closing radio link -> socketPath=$socketPath connected=${connectedFlow.value}" }
        serveJob?.cancel()
        serveJob = null
        closeQuietly(activeChannel)
        closeQuietly(serverChannel)
        serverChannel = null
        activeChannel = null
        connectedFlow.value = false
        runCatching { Files.deleteIfExists(socketPath) }
    }

    private suspend fun send(message: RadioMessage.AppToRadiod) {
        val bytes = IpcCodec.encode(message)
        writeMutex.withLock {
            val channel = activeChannel
                ?: throw IllegalStateException("send(): radiod is not connected -> type=${message::class.simpleName}")
            withContext(ioDispatcher) { writeFully(channel, bytes) }
        }
    }

    private suspend fun acceptLoop(server: ServerSocketChannel) {
        while (true) {
            val channel = try {
                runInterruptible(ioDispatcher) { server.accept() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedChannelException) {
                log.i { "acceptLoop(): server socket closed -> socketPath=$socketPath" }
                return
            } catch (e: IOException) {
                log.e(e) { "acceptLoop(): accept failed -> socketPath=$socketPath" }
                return
            }
            log.i { "acceptLoop(): radiod connected -> socketPath=$socketPath" }
            try {
                writeMutex.withLock {
                    withContext(ioDispatcher) {
                        writeFully(channel, IpcCodec.encode(RadioMessage.Hello(IpcCodec.PROTOCOL_VERSION, token)))
                    }
                    activeChannel = channel
                }
                connectedFlow.value = true
                runInterruptible(ioDispatcher) { readUntilDisconnect(channel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log.w { "acceptLoop(): radiod connection failed -> error=${e.message}" }
            } finally {
                connectedFlow.value = false
                activeChannel = null
                closeQuietly(channel)
                log.i { "acceptLoop(): radiod disconnected -> socketPath=$socketPath" }
            }
        }
    }

    /** Blocking read loop: returns on clean EOF, throws [IOException] on a broken stream. */
    private fun readUntilDisconnect(channel: SocketChannel) {
        val header = ByteArray(IpcCodec.HEADER_SIZE)
        while (true) {
            if (!readFully(channel, header, 0, header.size, eofAllowed = true)) return
            val payloadLength = try {
                IpcCodec.payloadLengthOf(header)
            } catch (e: IpcDecodeException) {
                throw IOException("stream out of sync: ${e.message}", e)
            }
            val message = ByteArray(IpcCodec.HEADER_SIZE + payloadLength)
            header.copyInto(message)
            readFully(channel, message, IpcCodec.HEADER_SIZE, payloadLength, eofAllowed = false)
            val decoded = IpcCodec.decodeOrNull(message)
            if (decoded == null) {
                log.e { "readUntilDisconnect(): malformed message skipped -> payloadLength=$payloadLength" }
                continue
            }
            dispatchIncoming(decoded)
        }
    }

    private fun dispatchIncoming(message: RadioMessage) {
        when (message) {
            is RadioMessage.RxFrame -> rxFramesFlow.tryEmit(message.toRadioFrame())
            is RadioMessage.Log -> {
                forwardRadiodLog(message)
                eventsFlow.tryEmit(message)
            }
            is RadioMessage.Ready -> {
                log.i { "dispatchIncoming(): radiod ready -> chipId=${message.chipId} firmware=${message.firmware}" }
                eventsFlow.tryEmit(message)
            }
            is RadioMessage.Error -> {
                log.e { "dispatchIncoming(): radiod error -> code=${message.code} text=${message.text}" }
                eventsFlow.tryEmit(message)
            }
            is RadioMessage.Status -> eventsFlow.tryEmit(message)
            // Not logged: a list arrives every 2 s while the radio is off; the adapter repository logs the changes.
            is RadioMessage.DeviceList -> eventsFlow.tryEmit(message)
            is RadioMessage.AppToRadiod ->
                log.w { "dispatchIncoming(): ignoring command sent by radiod -> type=${message::class.simpleName}" }
        }
    }

    private fun forwardRadiodLog(message: RadioMessage.Log) {
        when (message.level) {
            RadioMessage.Log.LEVEL_DEBUG -> radiodLog.d { message.text }
            RadioMessage.Log.LEVEL_INFO -> radiodLog.i { message.text }
            RadioMessage.Log.LEVEL_WARN -> radiodLog.w { message.text }
            else -> radiodLog.e { message.text }
        }
    }

    private fun writeFully(channel: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    /**
     * Reads exactly [length] bytes into [target] at [offset]. Returns false on a clean EOF before
     * the first byte when [eofAllowed]; throws [EOFException] on any other EOF.
     */
    private fun readFully(channel: SocketChannel, target: ByteArray, offset: Int, length: Int, eofAllowed: Boolean): Boolean {
        val buffer = ByteBuffer.wrap(target, offset, length)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) {
                if (eofAllowed && buffer.position() == offset) return false
                throw EOFException("connection closed mid-message")
            }
        }
        return true
    }

    private fun closeQuietly(channel: Channel?) {
        runCatching { channel?.close() }
    }

    private companion object {
        const val TAG = "DesktopRadioLink"
        const val RADIOD_TAG = "radiod"
        const val RX_BUFFER_CAPACITY = 1024
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
