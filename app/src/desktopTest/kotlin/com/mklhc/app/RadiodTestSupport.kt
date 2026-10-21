package com.mklhc.app

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.mklhc.core.radio.IpcCodec
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.radio.RadiodConfig
import com.mklhc.core.radio.RadiodLauncher
import com.mklhc.core.radio.RadiodProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/*
 * Test support shared by the spike's and the desktop runtime's tests: an in-process radiod that speaks the IPC protocol
 * over the real Unix domain socket, the launcher that starts it instead of a process, and a log capture.
 *
 * Original code, moved out of SpikeSessionTest (where the fake radiod, its launcher and the log capture were private)
 * so both tests drive the same fake instead of two copies.
 */

/** Starts [FakeRadiod] instances instead of real processes, and records every launch with its configuration. */
internal class FakeRadiodLauncher : RadiodLauncher {
    val processes = CopyOnWriteArrayList<FakeRadiod>()
    val configs = CopyOnWriteArrayList<RadiodConfig>()

    /** What a fake radiod answers to INIT; null for a radiod that never answers. */
    @Volatile
    var initReply: RadioMessage.RadiodToApp? = RadioMessage.Ready("rtl8812cu", "fw-1", "devourer-test")

    override fun launch(config: RadiodConfig): RadiodProcess {
        configs += config
        return FakeRadiod(config, initReply).also { processes += it }
    }

    /**
     * Suspends until the [index]-th radiod has been launched and returns it; fails after [PROCESS_TIMEOUT_MS] with the
     * number launched so far, instead of leaving the test to its own timeout.
     */
    suspend fun awaitProcess(index: Int): FakeRadiod {
        val launched = withTimeoutOrNull(PROCESS_TIMEOUT_MS) {
            while (processes.size <= index) delay(POLL_MS)
            true
        }
        checkNotNull(launched) { "radiod #$index was not launched within $PROCESS_TIMEOUT_MS ms -> launched=${processes.size}" }
        return processes[index]
    }
}

/**
 * The radiod side of the socket, in-process: connects to the link's Unix domain socket like the real radiod, records
 * every message it receives, answers INIT, and exits on SHUTDOWN or when the app closes the socket.
 *
 * Like radiod (radiod/src/main.cpp, `runSession`: SHUTDOWN, EOF and a reset of the connection all return `kExitOk`) it
 * exits with 0 when it ends by itself, and with [KILLED_EXIT_CODE] when it is crashed or killed. [wasKilled] tells a
 * radiod that exited by itself from one the supervisor had to kill.
 */
internal class FakeRadiod(private val config: RadiodConfig, private val initReply: RadioMessage.RadiodToApp?) : RadiodProcess {
    val received = MutableStateFlow<List<RadioMessage>>(emptyList())
    private val exit = CompletableDeferred<Int>()
    private val channel: SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).also {
        it.connect(UnixDomainSocketAddress.of(config.socketPath))
    }

    // Set before this side closes the socket, so the read loop does not take its own close for the app's.
    @Volatile
    private var closedHere = false

    init {
        thread(isDaemon = true, name = "fake-radiod") { readLoop() }
    }

    /** True once this radiod has exited, by SHUTDOWN, the end of the connection, [crash] or [kill]. */
    val hasExited: Boolean get() = exit.isCompleted

    /** The exit code once exited, null while running: 0 when it ended by itself, [KILLED_EXIT_CODE] otherwise. */
    @Volatile
    var exitCode: Int? = null
        private set

    /** True when [kill] ended it while it was still running, rather than it exiting by itself. */
    @Volatile
    var wasKilled: Boolean = false
        private set

    fun messages(): List<RadioMessage> = received.value

    /** Suspends until at least [count] messages of type [T] have arrived and returns them. */
    suspend inline fun <reified T : RadioMessage> awaitMessages(count: Int): List<T> =
        received.first { list -> list.count { it is T } >= count }.filterIsInstance<T>()

    /** Plays radiod delivering a received frame to the app. */
    fun sendRxFrame(frame: ByteArray) {
        send(RadioMessage.RxFrame(rssiDbm = -40, rate = 0, tsf = 1uL, frame = frame))
    }

    /**
     * Delivers [frame] to the app and returns the one frame it transmits in answer (the first TX_FRAME after those
     * already received), for a frame the app answers exactly once. A missing answer fails after
     * [EXCHANGE_TIMEOUT_MS] naming [expected], instead of leaving the test to the session timeout.
     */
    suspend fun exchange(frame: ByteArray, expected: String): ByteArray {
        val before = messages().count { it is RadioMessage.TxFrame }
        sendRxFrame(frame)
        val answer = withTimeoutOrNull(EXCHANGE_TIMEOUT_MS) { awaitMessages<RadioMessage.TxFrame>(before + 1)[before].frame }
        return checkNotNull(answer) {
            "no answer within $EXCHANGE_TIMEOUT_MS ms, expected $expected -> txFramesBefore=$before " +
                "messages=${messages().map { it::class.simpleName }}"
        }
    }

    /** Plays radiod crashing: the socket drops and the process "exits" with an error. */
    fun crash() {
        closedHere = true
        runCatching { channel.close() }
        exitWith(KILLED_EXIT_CODE)
    }

    override suspend fun awaitExit(): Int = exit.await()

    // Like ProcessRadiodLauncher's kill, nothing for a radiod that has exited: the supervisor calls kill after every
    // exit, so only a kill of a running radiod counts as one.
    override suspend fun kill() {
        synchronized(this) {
            if (exit.isCompleted) return
            wasKilled = true
        }
        crash()
    }

    private fun exitWith(code: Int) {
        synchronized(this) {
            if (exit.isCompleted) return
            exitCode = code
            exit.complete(code)
        }
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
                    is RadioMessage.Init -> initReply?.let { send(it) }
                    is RadioMessage.Shutdown -> {
                        closedHere = true
                        runCatching { channel.close() }
                        exitWith(CLEAN_EXIT_CODE)
                        return
                    }
                    else -> Unit
                }
            }
        } catch (closed: IOException) {
            // The app closed its end (EOF, or a reset as Windows reports it): radiod shuts the radio down and exits
            // cleanly. When this side closed the socket, crash() has already set the exit code.
            exitWith(if (closedHere) KILLED_EXIT_CODE else CLEAN_EXIT_CODE)
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
}

/** Collects log lines as `SEVERITY: message`; written from the code under test's threads, read from the test's. */
internal class LogCapture : LogWriter() {
    private val lines = CopyOnWriteArrayList<String>()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        lines += "${severity.name.uppercase()}: $message"
    }

    /** A logger that records everything, to hand to the code under test. */
    fun logger(tag: String = "test"): Logger = Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(this)), tag = tag)

    fun has(fragment: String): Boolean = lines.any { fragment in it }

    fun text(): String = lines.joinToString("\n")

    /** Suspends until a line containing [fragment] is logged; fails the test with the log if it never is. */
    suspend fun awaitLine(fragment: String) {
        val seen = withTimeoutOrNull(LOG_TIMEOUT_MS) {
            while (!has(fragment)) delay(POLL_MS)
            true
        }
        check(seen == true) { "no log line contains '$fragment':\n${text()}" }
    }
}

private const val POLL_MS = 20L
private const val LOG_TIMEOUT_MS = 10_000L
private const val PROCESS_TIMEOUT_MS = 10_000L
private const val CLEAN_EXIT_CODE = 0

/** The exit code of a radiod that was crashed or killed, as a shell reports a SIGKILL. */
private const val KILLED_EXIT_CODE = 137
private const val EXCHANGE_TIMEOUT_MS = 5_000L
