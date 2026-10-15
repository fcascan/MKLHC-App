package com.mklhc.core.radio

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test

/**
 * Tests for [RadiodSupervisor] restart logic (against a fake launcher and virtual time), for
 * [RestartBackoff] and [RadiodConfig], and one check that [ProcessRadiodLauncher] can start and
 * reap a real process. The real radiod binary is not involved (see Task 1.8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadiodSupervisorTest {

    private class FakeProcess : RadiodProcess {
        val exit = CompletableDeferred<Int>()
        var killed = false

        override suspend fun awaitExit(): Int = exit.await()

        override suspend fun kill() {
            killed = true
        }
    }

    private class FakeLauncher : RadiodLauncher {
        val processes = mutableListOf<FakeProcess>()
        var failures = 0

        override fun launch(config: RadiodConfig): RadiodProcess {
            if (failures > 0) {
                failures--
                throw IOException("cannot run program")
            }
            return FakeProcess().also { processes += it }
        }
    }

    private val config = RadiodConfig(
        executable = Paths.get("radiod"),
        socketPath = Paths.get("radiod.sock"),
        token = "secret-token",
    )

    private fun TestScope.supervisorWith(launcher: FakeLauncher) = RadiodSupervisor(
        config = config,
        scope = backgroundScope,
        launcher = launcher,
        clock = { testScheduler.currentTime },
    )

    @Test
    fun `start launches radiod and reports Running`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)

        supervisor.start()
        runCurrent()

        assertThat(launcher.processes.size).isEqualTo(1)
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.Running(launchCount = 1))
    }

    @Test
    fun `starting twice launches only one process`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)

        supervisor.start()
        supervisor.start()
        runCurrent()

        assertThat(launcher.processes.size).isEqualTo(1)
    }

    @Test
    fun `an unexpected exit restarts radiod with exponential backoff capped at 8 seconds`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)
        supervisor.start()
        runCurrent()

        val expectedDelays = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L)
        expectedDelays.forEachIndexed { index, delayMs ->
            val launchCount = index + 1
            launcher.processes.last().exit.complete(1)
            runCurrent()
            assertThat(supervisor.state.value)
                .isEqualTo(RadiodSupervisor.State.WaitingToRestart(launchCount, delayMs))

            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertThat(launcher.processes.size).isEqualTo(launchCount)

            advanceTimeBy(1)
            runCurrent()
            assertThat(launcher.processes.size).isEqualTo(launchCount + 1)
            assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.Running(launchCount + 1))
        }
    }

    @Test
    fun `an exited process is killed so nothing is left behind`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)
        supervisor.start()
        runCurrent()

        launcher.processes[0].exit.complete(0)
        runCurrent()

        assertThat(launcher.processes[0].killed).isTrue()
    }

    @Test
    fun `a stable run resets the backoff to the initial delay`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)
        supervisor.start()
        runCurrent()

        // Two quick crashes: delays 500 then 1000.
        launcher.processes[0].exit.complete(1)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        launcher.processes[1].exit.complete(1)
        runCurrent()
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.WaitingToRestart(2, 1_000L))
        advanceTimeBy(1_000)
        runCurrent()

        // The third process runs long enough to count as healthy, then dies.
        advanceTimeBy(10_000)
        launcher.processes[2].exit.complete(1)
        runCurrent()

        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.WaitingToRestart(3, 500L))
    }

    @Test
    fun `a launch failure is retried with backoff`() = runTest {
        val launcher = FakeLauncher().apply { failures = 2 }
        val supervisor = supervisorWith(launcher)

        supervisor.start()
        runCurrent()
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.WaitingToRestart(1, 500L))

        advanceTimeBy(500)
        runCurrent()
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.WaitingToRestart(2, 1_000L))

        advanceTimeBy(1_000)
        runCurrent()
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.Running(3))
        assertThat(launcher.processes.size).isEqualTo(1)
    }

    @Test
    fun `stop kills the running process and does not restart it`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)
        supervisor.start()
        runCurrent()

        supervisor.stop()

        assertThat(launcher.processes[0].killed).isTrue()
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.Stopped)

        advanceTimeBy(60_000)
        runCurrent()
        assertThat(launcher.processes.size).isEqualTo(1)
    }

    @Test
    fun `stop during the backoff wait cancels the pending restart`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)
        supervisor.start()
        runCurrent()
        launcher.processes[0].exit.complete(1)
        runCurrent()

        supervisor.stop()
        advanceTimeBy(60_000)
        runCurrent()

        assertThat(launcher.processes.size).isEqualTo(1)
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.Stopped)
    }

    @Test
    fun `the supervisor can be started again after stop`() = runTest {
        val launcher = FakeLauncher()
        val supervisor = supervisorWith(launcher)
        supervisor.start()
        runCurrent()
        supervisor.stop()

        supervisor.start()
        runCurrent()

        assertThat(launcher.processes.size).isEqualTo(2)
        assertThat(supervisor.state.value).isEqualTo(RadiodSupervisor.State.Running(launchCount = 1))
    }

    @Test
    fun `backoff doubles from 500 ms up to 8 s and can be reset`() {
        val backoff = RestartBackoff()

        val delays = List(7) { backoff.nextDelayMs() }
        assertThat(delays).isEqualTo(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L))

        backoff.reset()
        assertThat(backoff.nextDelayMs()).isEqualTo(500L)
    }

    @Test
    fun `config builds the command line and hides the token in toString`() {
        val args = config.copy(extraArguments = listOf("--verbose")).commandLine()

        assertThat(args).isEqualTo(
            listOf("radiod", "--socket", "radiod.sock", "--token", "secret-token", "--verbose"),
        )
        assertThat(config.toString()).doesNotContain("secret-token")
        assertThat(config.toString()).contains("token=***")
    }

    @Test
    fun `ProcessRadiodLauncher starts a real process and reports its exit code`() = runBlocking {
        val javaBinary: Path = Paths.get(System.getProperty("java.home"), "bin", "java")
        // java rejects the radiod-style arguments and exits non-zero, which is all this test needs.
        val process = ProcessRadiodLauncher().launch(config.copy(executable = javaBinary))

        val exitCode = withTimeout(60_000) { process.awaitExit() }
        process.kill()

        assertThat(exitCode).isGreaterThan(0)
    }
}
