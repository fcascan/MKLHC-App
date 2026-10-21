package com.mklhc.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import com.mklhc.app.desktop.DesktopDataDirectory
import com.mklhc.app.desktop.DesktopKartRuntime
import com.mklhc.app.desktop.DesktopRadioSettings
import com.mklhc.app.di.initKoin
import com.mklhc.core.data.session.KartSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.module.Module
import org.koin.dsl.module
import java.awt.Desktop
import java.awt.desktop.QuitStrategy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Desktop (JVM) entry point, resolved as `com.mklhc.app.MainKt` by the Compose Desktop application block.
 *
 * Business logic: reads the [DesktopRadioSettings] from the environment and the arguments first; a value that cannot be
 * used ends the process before any window opens, with a message naming the variable. In demo mode (`MKLHC_DEMO=1` or
 * `--demo`) the app keeps the scripted demo session the app Koin module binds, and the whole UI runs without hardware;
 * nothing outside the process needs stopping at exit then.
 *
 * Otherwise it locks the data directory first ([DesktopDataDirectory]): when another instance holds it, the process ends
 * with a message saying so, since two instances would fight over the adapter and the pairing file. It then builds the
 * [DesktopKartRuntime] (radiod, the radio, the pairing store and the real session), binds its session in place of the
 * demo through [initKoin]'s extra module (Koin lets a later module override a definition, see [desktopSessionModule])
 * and starts it, so radiod is launched and connects while the window opens. The screens' ViewModels are resolved from
 * Koin, so it is started before the window.
 *
 * Exit: an [ExitStop] stops the radio once, for whichever comes first: the window's close request (which the operating
 * system's quit command reaches too where it can, see [closeAllWindowsOnQuit]), the JVM shutdown hook (a logout, a
 * SIGTERM, Ctrl+C in a terminal) or a start-up that throws between the runtime and the window (Koin, a display that
 * cannot be used). It stops the session (the kart is put to sleep or powered off and the radio shut down), then shuts
 * the runtime down and releases the data directory, each step bounded (see [stopForExit]).
 *
 * @param args the program arguments; `--demo` is the only one.
 */
fun main(args: Array<String>) {
    val log = Logger.withTag(TAG)
    val settings = try {
        DesktopRadioSettings.fromSystem(args.toList())
    } catch (invalid: IllegalArgumentException) {
        log.e { "main(): the configuration cannot be used, not starting -> message=${invalid.message}" }
        exitProcess(EXIT_INVALID_CONFIGURATION)
    }
    if (settings.demo) {
        log.i { "main(): starting -> mode=demo (no radio; unset ${DesktopRadioSettings.ENV_DEMO} and drop ${DesktopRadioSettings.ARG_DEMO} for the radio)" }
        initKoin()
        runAppWindow(title = DEMO_WINDOW_TITLE, exitStop = null, log = log)
        return
    }
    log.i {
        "main(): starting -> mode=radio radio=${settings.radioId} radiod=${settings.radiodExecutable} " +
            "dataDirectory=${settings.dataDirectory}"
    }
    val dataDirectory = lockDataDirectoryOrExit(settings, log)
    val runtime = DesktopKartRuntime(settings)
    val exitStop = ExitStop(log) {
        stopForExit(
            stopSession = { runtime.session.stop() },
            shutdownRuntime = {
                runtime.shutdown()
                // Only once the runtime is down, so a new instance never takes the adapter from a radiod still exiting;
                // when the shutdown is left running at exit, the process exit releases the lock instead.
                dataDirectory.release()
            },
            log = log,
        )
    }
    Runtime.getRuntime().addShutdownHook(Thread({ exitStop.run(TRIGGER_SHUTDOWN_HOOK) }, SHUTDOWN_HOOK_THREAD_NAME))
    try {
        initKoin(extra = desktopSessionModule(runtime.session))
        runBlocking { runtime.start() }
        runAppWindow(title = RADIO_WINDOW_TITLE, exitStop = exitStop, log = log)
    } catch (failure: Exception) {
        log.e(failure) { "main(): the app failed, stopping the radio -> message=${failure.message}" }
        throw failure
    } finally {
        // A normal close ends the process inside application() (the shutdown hook then finds the stop done), so this
        // runs after a failure, or a window that ended without ending the process.
        exitStop.run(TRIGGER_MAIN_END)
    }
}

/**
 * The Koin module that binds [session] as the app's [KartSession].
 *
 * Business logic: loaded after the app's own modules (see [initKoin]), it overrides their demo binding for every screen.
 * Koin (4.1, the version in use) allows a later definition of the same type to override an earlier one by default; the
 * app's Koin test checks that the ViewModels then get the overriding session. The demo definition stays declared but is
 * never created, since nothing asks for it.
 *
 * @param session the session the screens drive.
 */
internal fun desktopSessionModule(session: KartSession): Module = module {
    single<KartSession> { session }
}

/**
 * Locks the data directory for this instance (see [DesktopDataDirectory]), or ends the process with a message saying
 * why it cannot: another instance holds it, or it cannot be created.
 */
private fun lockDataDirectoryOrExit(settings: DesktopRadioSettings, log: Logger): DesktopDataDirectory {
    val locked = try {
        DesktopDataDirectory.lock(settings.dataDirectory)
    } catch (failure: IOException) {
        log.e {
            "lockDataDirectoryOrExit(): the data directory cannot be used, not starting -> dataDirectory=${settings.dataDirectory} " +
                "message=${failure.message} (set ${DesktopRadioSettings.ENV_DATA_DIR} to a directory you can write)"
        }
        exitProcess(EXIT_INVALID_CONFIGURATION)
    }
    if (locked == null) {
        log.e {
            "lockDataDirectoryOrExit(): another MKLHC-App is already running with this data directory, not starting " +
                "-> dataDirectory=${settings.dataDirectory} (close the other one first: two would fight over the adapter " +
                "and the pairing file)"
        }
        exitProcess(EXIT_ALREADY_RUNNING)
    }
    return locked
}

/**
 * Opens the app's window and runs it until it is closed; [exitStop], when there is one, runs before the window goes.
 * Ends the process when the window is closed (Compose Desktop's `application` exits it).
 */
private fun runAppWindow(title: String, exitStop: ExitStop?, log: Logger) {
    closeAllWindowsOnQuit(log)
    application {
        Window(
            onCloseRequest = {
                exitStop?.run(TRIGGER_WINDOW_CLOSE)
                exitApplication()
            },
            title = title,
            state = rememberWindowState(width = 1100.dp, height = 700.dp),
        ) {
            App()
        }
    }
}

/**
 * Makes the operating system's quit command (Cmd+Q on macOS) close the windows instead of ending the JVM at once, so a
 * quit goes through the window's close handler like a click on its close button.
 *
 * Business logic: only macOS offers the quit strategy; elsewhere, and without a display, this does nothing and the JVM
 * shutdown hook covers a quit. Best effort: a failure is logged as a warning and the app goes on.
 */
private fun closeAllWindowsOnQuit(log: Logger) {
    try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
            log.d { "closeAllWindowsOnQuit(): no quit strategy on this platform, the shutdown hook covers a quit" }
            return
        }
        Desktop.getDesktop().setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS)
        log.d { "closeAllWindowsOnQuit(): a quit now closes the windows" }
    } catch (failure: Exception) {
        log.w { "closeAllWindowsOnQuit(): the quit strategy could not be set, the shutdown hook covers a quit -> message=${failure.message}" }
    }
}

/**
 * The stop-for-exit path, run once by whichever trigger asks first: the window's close request, the JVM shutdown hook or
 * the end of [main] after a failed start-up.
 *
 * Business logic: the first trigger runs [teardown]; a later one does not run it again, so a normal close (which ends
 * the process, and with it runs the shutdown hook) stops the radio once. A trigger that fires while the teardown is still
 * running (the shutdown hook during the close handler's stop) waits for it to end, at most [waitMs], so the JVM does not
 * halt half-way through it. Safe to call from any thread.
 *
 * @param log where the triggers are logged.
 * @param waitMs how long a later trigger waits for a teardown still running; [teardown] bounds itself.
 * @param teardown stops the session and the runtime.
 */
internal class ExitStop(
    private val log: Logger,
    private val waitMs: Long = EXIT_STOP_WAIT_MS,
    private val teardown: () -> Unit,
) {
    private val claimed = AtomicBoolean(false)
    private val done = CountDownLatch(1)

    /**
     * Runs the teardown unless a trigger already did, or waits for the one running.
     *
     * @param trigger what asks, for the log.
     * @return true when this call ran the teardown.
     */
    fun run(trigger: String): Boolean {
        if (!claimed.compareAndSet(false, true)) {
            if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
                log.w { "run(): the exit stop is still running, going on -> trigger=$trigger waitMs=$waitMs" }
            } else {
                log.d { "run(): the exit stop already ran -> trigger=$trigger" }
            }
            return false
        }
        log.i { "run(): stopping the kart session and the radio before exit -> trigger=$trigger" }
        try {
            teardown()
        } finally {
            done.countDown()
        }
        return true
    }
}

/**
 * Stops the session, then shuts the radio runtime down, each with its own time limit.
 *
 * Business logic: every exit path runs this once (see [ExitStop]) and the process ends right after, whatever happens
 * here. The session's stop puts the kart to sleep (or powers it off, per the user's setting) with a final neutral and
 * sends radiod STOP_BEACON and SHUTDOWN; the runtime shutdown then lets radiod exit and stops its supervisor. Each step
 * runs on a background coroutine, off the UI thread, and is waited for at most its own slice, [sessionTimeoutMs] then
 * [runtimeTimeoutMs], so the whole stop takes at most their sum. The runtime shutdown always runs: after a session stop
 * that failed, and after one still running when its slice expired (the runtime then closes radiod's link under it, and
 * radiod shuts the radio down by itself). Both steps run under NonCancellable inside, so a slice cannot cut them short;
 * a step still running when its slice expires is left to the process exit.
 *
 * @param stopSession stops the kart session.
 * @param shutdownRuntime shuts the radio runtime down.
 * @param log where the outcome is logged.
 * @param sessionTimeoutMs how long the session stop is waited for.
 * @param runtimeTimeoutMs how long the runtime shutdown is waited for, counted from its start.
 * @return true when both finished in time.
 */
internal fun stopForExit(
    stopSession: suspend () -> Unit,
    shutdownRuntime: suspend () -> Unit,
    log: Logger,
    sessionTimeoutMs: Long = SESSION_STOP_TIMEOUT_MS,
    runtimeTimeoutMs: Long = RUNTIME_SHUTDOWN_TIMEOUT_MS,
): Boolean {
    val sessionStopped = runExitStep("stopping the kart session", sessionTimeoutMs, log, stopSession)
    val runtimeShutDown = runExitStep("shutting the radio runtime down", runtimeTimeoutMs, log, shutdownRuntime)
    val finished = sessionStopped && runtimeShutDown
    if (finished) {
        log.i { "stopForExit(): the session and the radio are stopped" }
    } else {
        log.w { "stopForExit(): the teardown did not finish in time, exiting anyway -> sessionStopped=$sessionStopped runtimeShutDown=$runtimeShutDown" }
    }
    return finished
}

/**
 * Runs one exit step on a background coroutine and waits for it at most [timeoutMs]; a failure is logged so the next
 * step still runs.
 *
 * @return true when the step ended (or failed) in time, false when it is still running.
 */
private fun runExitStep(what: String, timeoutMs: Long, log: Logger, step: suspend () -> Unit): Boolean {
    val job = CoroutineScope(Dispatchers.Default).launch {
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) { "runExitStep(): $what failed -> message=${failure.message}" }
        }
    }
    val ended = runBlocking { withTimeoutOrNull(timeoutMs) { job.join() } != null }
    if (!ended) log.w { "runExitStep(): $what did not end in time, going on -> timeoutMs=$timeoutMs" }
    return ended
}

private const val TAG = "Main"
private const val RADIO_WINDOW_TITLE = "MKLHC-App"
private const val DEMO_WINDOW_TITLE = "MKLHC-App (demo)"

// What runs the exit stop, for the log.
private const val TRIGGER_WINDOW_CLOSE = "window close"
private const val TRIGGER_SHUTDOWN_HOOK = "shutdown hook"
private const val TRIGGER_MAIN_END = "end of main"
private const val SHUTDOWN_HOOK_THREAD_NAME = "mklhc-exit-stop"

/** How long the session stop is waited for at exit: the kart's sleep or power-off, then STOP_BEACON and SHUTDOWN. */
private const val SESSION_STOP_TIMEOUT_MS = 6_000L

/**
 * How long the runtime shutdown is waited for at exit: radiod's exit grace (3 s), its kill (2 s) and the pairing file's
 * release, at worst.
 */
private const val RUNTIME_SHUTDOWN_TIMEOUT_MS = 6_000L

/** How long a later exit trigger waits for a stop still running: both slices, and a margin. */
private const val EXIT_STOP_WAIT_MS = SESSION_STOP_TIMEOUT_MS + RUNTIME_SHUTDOWN_TIMEOUT_MS + 1_000L

/** Exit code: an environment variable or a program argument cannot be used, or the data directory cannot be created. */
private const val EXIT_INVALID_CONFIGURATION = 2

/** Exit code: another instance runs with the same data directory. */
private const val EXIT_ALREADY_RUNNING = 3
