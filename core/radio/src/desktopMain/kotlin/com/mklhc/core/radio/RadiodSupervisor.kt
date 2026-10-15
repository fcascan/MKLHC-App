package com.mklhc.core.radio

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * How to start radiod: the executable [executable], the Unix domain socket [socketPath] the app
 * listens on, the session [token] the app will present in HELLO, and any [extraArguments].
 *
 * The command line is `<executable> --socket <socketPath> --token <token> <extraArguments...>`.
 * The token is on the command line, so it must never be logged ([toString] masks it).
 */
data class RadiodConfig(
    val executable: Path,
    val socketPath: Path,
    val token: String,
    val extraArguments: List<String> = emptyList(),
) {
    /** The full command line used to spawn radiod. */
    fun commandLine(): List<String> =
        listOf(executable.toString(), "--socket", socketPath.toString(), "--token", token) + extraArguments

    override fun toString(): String =
        "RadiodConfig(executable=$executable, socketPath=$socketPath, token=***, extraArguments=$extraArguments)"
}

/** A running radiod process as seen by [RadiodSupervisor]. */
interface RadiodProcess {
    /** Suspends until the process exits and returns its exit code. */
    suspend fun awaitExit(): Int

    /** Terminates the process if it is still running and waits for it to go away. Safe to call twice. */
    suspend fun kill()
}

/** Starts radiod processes; replaceable so supervision can be tested without a real binary. */
fun interface RadiodLauncher {
    /** Starts radiod according to [config]. Throws if the process cannot be started. */
    fun launch(config: RadiodConfig): RadiodProcess
}

/**
 * Exponential restart delay: starts at [initialDelayMs], doubles on every call and is capped at
 * [maxDelayMs]; [reset] returns to the initial delay. The default schedule is 0.5, 1, 2, 4, 8, 8... s.
 */
class RestartBackoff(
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    private var nextDelayMs = initialDelayMs

    /** Returns the delay to wait before the next restart and advances the schedule. */
    fun nextDelayMs(): Long {
        val delayMs = nextDelayMs
        nextDelayMs = minOf(nextDelayMs * 2, maxDelayMs)
        return delayMs
    }

    /** Returns the schedule to its initial delay. */
    fun reset() {
        nextDelayMs = initialDelayMs
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 500L
        const val DEFAULT_MAX_DELAY_MS = 8_000L
    }
}

/**
 * Keeps the radiod helper process alive on desktop.
 *
 * Business logic: [start] spawns radiod through [launcher]; when it exits unexpectedly the
 * supervisor waits an exponentially growing delay (0.5 s doubling to 8 s, see [RestartBackoff]) and
 * spawns it again, forever, until [stop] kills it. A process that ran for at least
 * [stableRunMs] before dying counts as healthy and resets the delay to 0.5 s, so a crash loop backs
 * off but an occasional crash restarts fast. Observers watch [state]: a move from [State.Running]
 * to [State.WaitingToRestart] is a radiod restart, on which the drive loop must go neutral and the
 * session back to `RadioStarting`.
 *
 * Wiring: the app listens on the socket (see [DesktopRadioLink.listen]) before [start], radiod
 * connects to it, and radiod exits by itself on socket EOF.
 *
 * Integration status: this class and [ProcessRadiodLauncher] compile and the restart logic is unit
 * tested against a fake launcher, but nothing here has run against the real radiod binary (it does
 * not exist yet). The end-to-end path is verified in the hardware spike (Task 1.8).
 */
class RadiodSupervisor(
    private val config: RadiodConfig,
    private val scope: CoroutineScope,
    private val launcher: RadiodLauncher = ProcessRadiodLauncher(),
    private val backoff: RestartBackoff = RestartBackoff(),
    private val stableRunMs: Long = DEFAULT_STABLE_RUN_MS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** What the supervisor is doing right now. */
    sealed interface State {
        /** Not started, or stopped by [stop]. */
        data object Stopped : State

        /** radiod is running; [launchCount] is the number of launches so far (1 for the first). */
        data class Running(val launchCount: Int) : State

        /** radiod exited (or failed to start); the next launch happens after [delayMs]. */
        data class WaitingToRestart(val launchCount: Int, val delayMs: Long) : State
    }

    private val log = Logger.withTag(TAG)
    private val stateFlow = MutableStateFlow<State>(State.Stopped)
    private var job: Job? = null

    /** Current supervision state. */
    val state: StateFlow<State> = stateFlow.asStateFlow()

    /** Starts supervising. Does nothing when already started. */
    fun start() {
        if (job?.isActive == true) return
        log.i { "start(): supervising radiod -> config=$config" }
        job = scope.launch { superviseLoop() }
    }

    /** Stops supervising and kills radiod; returns once the process is gone. */
    suspend fun stop() {
        log.i { "stop(): stopping radiod supervision -> state=${stateFlow.value}" }
        job?.cancelAndJoin()
        job = null
        stateFlow.value = State.Stopped
    }

    private suspend fun superviseLoop() {
        var launchCount = 0
        while (true) {
            launchCount++
            val startedAtMs = clock()
            val process = launchOrNull()
            if (process != null) {
                stateFlow.value = State.Running(launchCount)
                try {
                    val exitCode = process.awaitExit()
                    log.w { "superviseLoop(): radiod exited -> exitCode=$exitCode launchCount=$launchCount" }
                } finally {
                    // Also runs on cancellation (stop): the process must not outlive the supervisor.
                    withContext(NonCancellable) { process.kill() }
                }
                if (clock() - startedAtMs >= stableRunMs) {
                    backoff.reset()
                }
            }
            val delayMs = backoff.nextDelayMs()
            stateFlow.value = State.WaitingToRestart(launchCount, delayMs)
            log.i { "superviseLoop(): restarting radiod after backoff -> delayMs=$delayMs launchCount=$launchCount" }
            delay(delayMs)
        }
    }

    private fun launchOrNull(): RadiodProcess? =
        try {
            launcher.launch(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "launchOrNull(): cannot start radiod -> executable=${config.executable}" }
            null
        }

    private companion object {
        const val TAG = "RadiodSupervisor"
        const val DEFAULT_STABLE_RUN_MS = 10_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * [RadiodLauncher] that starts radiod as a real operating-system process.
 *
 * radiod's stdout and stderr are merged and re-logged line by line at debug level under tag
 * `radiod-output` (useful for crashes before the IPC channel is up; normal radiod logs travel as
 * IPC LOG messages). [RadiodProcess.kill] asks the process to terminate, then forces it after
 * [killGraceMs].
 *
 * Integration status: not yet run against the real radiod binary; see [RadiodSupervisor].
 */
class ProcessRadiodLauncher(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val killGraceMs: Long = DEFAULT_KILL_GRACE_MS,
) : RadiodLauncher {
    private val outputLog = Logger.withTag(OUTPUT_TAG)

    override fun launch(config: RadiodConfig): RadiodProcess {
        val process = ProcessBuilder(config.commandLine())
            .redirectErrorStream(true)
            .start()
        Logger.withTag(TAG).i {
            "launch(): radiod process started -> pid=${process.pid()} executable=${config.executable}"
        }
        startOutputDrain(process)
        return object : RadiodProcess {
            override suspend fun awaitExit(): Int = runInterruptible(ioDispatcher) { process.waitFor() }

            override suspend fun kill() {
                withContext(ioDispatcher) {
                    if (!process.isAlive) return@withContext
                    process.destroy()
                    if (!process.waitFor(killGraceMs, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor()
                    }
                }
            }
        }
    }

    /** Reads radiod's merged output on a daemon thread so the pipe never fills and blocks radiod. */
    private fun startOutputDrain(process: Process) {
        Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> outputLog.d { line } }
                }
            }
        }.apply {
            name = "radiod-output"
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val TAG = "ProcessRadiodLauncher"
        const val OUTPUT_TAG = "radiod-output"
        const val DEFAULT_KILL_GRACE_MS = 2_000L
    }
}
