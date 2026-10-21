package com.mklhc.app.desktop

import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.Continuation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * [ConfinedKartSession] moves every command of the real session onto the runtime's dispatcher: the suspending commands
 * and `cancelPairing`, called here from the test's own thread, must reach the session behind it on the dispatcher's
 * thread. A `withContext` removed from the wrapper fails [everySuspendCommandRunsOnTheRuntimeDispatcher], and a suspend
 * member added to [KartSession] fails [everySuspendMemberOfKartSessionIsConfined] until it is confined and listed here.
 *
 * The test dispatcher is a single thread, so "on the dispatcher" is "on that thread"; the runtime's own dispatcher runs
 * one task at a time on any pool thread, which the wrapper does not depend on.
 */
class ConfinedKartSessionTest {

    // Compared by identity: the coroutines debug mode appends the coroutine to the thread's name while it runs one.
    @Volatile
    private var runtimeThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, RUNTIME_THREAD).apply { isDaemon = true }.also { runtimeThread = it }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val behind = RecordingKartSession()
    private val session = ConfinedKartSession(behind, dispatcher, scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dispatcher.close()
    }

    @Test
    fun everySuspendCommandRunsOnTheRuntimeDispatcher() = runBlocking<Unit> {
        SUSPEND_COMMANDS.values.forEach { command -> command(session) }

        assertEquals(SUSPEND_COMMANDS.keys.toList(), behind.calls.map { it.command }, "every command reached the session")
        behind.calls.forEach { call ->
            assertSame(runtimeThread, call.thread, "${call.command}() ran on ${call.thread.name}, not on the runtime dispatcher")
        }
    }

    @Test
    fun cancelPairingIsQueuedOnTheRuntimeDispatcher() = runBlocking<Unit> {
        session.cancelPairing()

        val call = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            while (behind.calls.isEmpty()) delay(POLL_MS)
            behind.calls.single()
        }
        assertEquals(CANCEL_PAIRING, call?.command)
        assertSame(runtimeThread, call?.thread, "cancelPairing() ran on ${call?.thread?.name}, not on the runtime dispatcher")
    }

    @Test
    fun everySuspendMemberOfKartSessionIsConfined() {
        // A suspend function compiles to a method whose last parameter is the Continuation.
        val suspendMembers = KartSession::class.java.methods
            .filter { it.parameterTypes.lastOrNull() == Continuation::class.java }
            .map { it.name }
            .toSet()

        assertEquals(
            suspendMembers,
            SUSPEND_COMMANDS.keys,
            "a suspend member of KartSession must switch to the runtime dispatcher in ConfinedKartSession and be listed here",
        )
    }

    /** A call that reached the session behind the wrapper, and the thread it ran on. */
    private class RecordedCall(val command: String, val thread: Thread)

    /** Records the commands it receives and the thread each ran on; everything else is the fake's. */
    private class RecordingKartSession(fake: KartSession = FakeKartSession()) : KartSession by fake {
        val calls = CopyOnWriteArrayList<RecordedCall>()

        override suspend fun start() = record(START)

        override suspend fun startPairing() = record(START_PAIRING)

        override fun cancelPairing() = record(CANCEL_PAIRING)

        override suspend fun sleepKart() = record(SLEEP_KART)

        override suspend fun shutdownKart() = record(SHUTDOWN_KART)

        override suspend fun stop() = record(STOP)

        private fun record(command: String) {
            calls += RecordedCall(command, Thread.currentThread())
        }
    }

    private companion object {
        const val RUNTIME_THREAD = "runtime-dispatcher"
        const val START = "start"
        const val START_PAIRING = "startPairing"
        const val CANCEL_PAIRING = "cancelPairing"
        const val SLEEP_KART = "sleepKart"
        const val SHUTDOWN_KART = "shutdownKart"
        const val STOP = "stop"
        const val CALL_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L

        /** Every suspend member of [KartSession], by name, and how to call it. */
        val SUSPEND_COMMANDS: Map<String, suspend (KartSession) -> Unit> = linkedMapOf(
            START to command { it.start() },
            START_PAIRING to command { it.startPairing() },
            SLEEP_KART to command { it.sleepKart() },
            SHUTDOWN_KART to command { it.shutdownKart() },
            STOP to command { it.stop() },
        )

        /** Types [call] as the suspending call of one command. */
        fun command(call: suspend (KartSession) -> Unit): suspend (KartSession) -> Unit = call
    }
}
