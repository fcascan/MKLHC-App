package com.mklhc.app

import co.touchlab.kermit.Logger
import com.mklhc.app.di.appKoinModules
import com.mklhc.core.data.radio.FakeUsbAdapterRepository
import com.mklhc.core.data.radio.UsbAdapterRepository
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.LinkState
import com.mklhc.feature.link.presentation.LinkViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The desktop entry point's pieces that run without a window: the Koin module that binds the real session in place of
 * the demo, [stopForExit], the teardown every exit path runs with a time slice per step, and [ExitStop], which runs it
 * once whichever of the window close, the shutdown hook or the end of main asks first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopEntryPointTest {

    private val log = Logger.withTag("DesktopEntryPointTest")

    @BeforeTest
    fun setUp() {
        // The ViewModels collect in viewModelScope, which runs on the main dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- The session binding -------------------------------------------------------------------------

    @Test
    fun theDesktopSessionModuleOverridesTheDemoSessionAndAdaptersForEveryScreen() {
        val real = FakeKartSession()
        val adapters = FakeUsbAdapterRepository()
        val koin = koinApplication { modules(appKoinModules() + desktopSessionModule(real, adapters)) }.koin

        assertSame(real, koin.get<KartSession>())
        assertSame(adapters, koin.get<UsbAdapterRepository>(), "the screen lists radiod's devices, not the demo's")
        val viewModel = koin.get<LinkViewModel>()
        real.setLinkState(LinkState.KartReady)
        assertEquals(LinkState.KartReady, viewModel.state.value.linkState, "the screen follows the bound session")
        koin.close()
    }

    // ---- The exit teardown ---------------------------------------------------------------------------

    @Test
    fun theSessionIsStoppedBeforeTheRuntimeIsShutDown() {
        val calls = CopyOnWriteArrayList<String>()

        val finished = stopForExit(stopSession = { calls += "stop session" }, shutdownRuntime = { calls += "shut runtime down" }, log = log)

        assertTrue(finished)
        assertEquals(listOf("stop session", "shut runtime down"), calls)
    }

    @Test
    fun aFailingSessionStopStillShutsTheRuntimeDown() {
        val calls = CopyOnWriteArrayList<String>()

        val finished = stopForExit(
            stopSession = { throw IllegalStateException("the kart did not answer") },
            shutdownRuntime = { calls += "shut runtime down" },
            log = log,
        )

        assertTrue(finished)
        assertEquals(listOf("shut runtime down"), calls)
    }

    @Test
    fun aSessionStopThatDoesNotEndStillLeavesTheRuntimeShutdownItsOwnSlice() = runBlocking<Unit> {
        val releaseStop = CompletableDeferred<Unit>()
        val runtimeShutDown = CompletableDeferred<Unit>()

        val startedAt = System.nanoTime()
        val finished = stopForExit(
            // Like the real stop: under NonCancellable, so the slice cannot cut it short, only stop waiting for it.
            stopSession = { withContext(NonCancellable) { releaseStop.await() } },
            // Slower than nothing, well within its own slice: it must not depend on what the session stop used.
            shutdownRuntime = {
                delay(RUNTIME_SHUTDOWN_MS)
                runtimeShutDown.complete(Unit)
            },
            log = log,
            sessionTimeoutMs = SHORT_TIMEOUT_MS,
            runtimeTimeoutMs = SLACK_MS,
        )
        val elapsedMs = elapsedMsSince(startedAt)

        assertFalse(finished, "the session stop did not finish")
        assertTrue(runtimeShutDown.isCompleted, "the runtime is shut down although the session stop is still running")
        assertTrue(elapsedMs >= SHORT_TIMEOUT_MS, "the session stop got its slice first -> elapsedMs=$elapsedMs")
        assertTrue(elapsedMs < SHORT_TIMEOUT_MS + SLACK_MS, "the wait is bounded -> elapsedMs=$elapsedMs")
        releaseStop.complete(Unit)
    }

    @Test
    fun aRuntimeShutdownThatDoesNotEndIsLeftBehindOnceItsOwnSliceExpires() = runBlocking<Unit> {
        val releaseShutdown = CompletableDeferred<Unit>()
        val runtimeShutDown = CompletableDeferred<Unit>()

        val startedAt = System.nanoTime()
        val finished = stopForExit(
            stopSession = {},
            shutdownRuntime = {
                withContext(NonCancellable) { releaseShutdown.await() }
                runtimeShutDown.complete(Unit)
            },
            log = log,
            sessionTimeoutMs = SLACK_MS,
            runtimeTimeoutMs = SHORT_TIMEOUT_MS,
        )
        val elapsedMs = elapsedMsSince(startedAt)

        assertFalse(finished)
        assertTrue(elapsedMs < SHORT_TIMEOUT_MS + SLACK_MS, "the wait is bounded by the runtime's slice -> elapsedMs=$elapsedMs")
        // The shutdown goes on in the background, and ends once it can.
        releaseShutdown.complete(Unit)
        withTimeout(SLACK_MS) { runtimeShutDown.await() }
    }

    // ---- One exit stop, whatever triggers it -----------------------------------------------------------

    @Test
    fun theExitStopRunsOnceWhicheverTriggerFiresFirst() {
        listOf(
            listOf("window close", "shutdown hook", "end of main"),
            listOf("shutdown hook", "window close", "end of main"),
            listOf("end of main", "shutdown hook", "window close"),
        ).forEach { triggers ->
            var runs = 0
            val exitStop = ExitStop(log) { runs++ }

            val ran = triggers.map { exitStop.run(it) }

            assertEquals(1, runs, "the teardown runs once -> triggers=$triggers")
            assertEquals(listOf(true, false, false), ran, "the first trigger runs it -> triggers=$triggers")
        }
    }

    @Test
    fun aTriggerThatFiresWhileTheStopRunsWaitsForItToEnd() {
        val runs = AtomicInteger()
        val stopStarted = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val exitStop = ExitStop(log) {
            runs.incrementAndGet()
            stopStarted.countDown()
            releaseStop.await()
        }
        val windowClose = thread(name = "window-close") { exitStop.run("window close") }
        assertTrue(stopStarted.await(SLACK_MS, TimeUnit.MILLISECONDS), "the close handler started the stop")

        val hookReturned = CountDownLatch(1)
        val hookRan = AtomicBoolean(true)
        val shutdownHook = thread(name = "shutdown-hook") {
            hookRan.set(exitStop.run("shutdown hook"))
            hookReturned.countDown()
        }

        assertFalse(hookReturned.await(SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the hook waits while the stop runs")
        releaseStop.countDown()
        assertTrue(hookReturned.await(SLACK_MS, TimeUnit.MILLISECONDS), "the hook returns once the stop ended")
        windowClose.join(SLACK_MS)
        shutdownHook.join(SLACK_MS)
        assertFalse(hookRan.get(), "the hook did not run the stop again")
        assertEquals(1, runs.get())
    }

    private fun elapsedMsSince(startedAt: Long): Long = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

    private companion object {
        const val SHORT_TIMEOUT_MS = 200L
        const val RUNTIME_SHUTDOWN_MS = 100L
        const val SLACK_MS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
