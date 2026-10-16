package com.mklhc.feature.cockpit.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.Event
import app.cash.turbine.test
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.data.session.SessionConfig
import com.mklhc.core.design_system.drive.driveCommandOf
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.presentation.toUiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CockpitViewModel] against [FakeKartSession]: the link state decides whether the cockpit is driving, the
 * controls turn into drive commands (the raw -1..1 values, released means neutral, and the command kept alive
 * while a control is held), the IMU and battery telemetry turn into the HUD (the IMU conflated to a display
 * rate), and the kart is returned to neutral whenever the link leaves Driving or the screen goes away. The main
 * dispatcher is replaced with an unconfined test dispatcher so the ViewModel's collectors run eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CockpitViewModelTest {

    private val tolerance = 1e-3f

    /** The session's real deadman window, which the keep-alive must beat: the value the repository is configured with. */
    private val deadmanWindowMs = SessionConfig().deadmanTimeout.inWholeMilliseconds

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun drivingSession() = FakeKartSession(initialLinkState = LinkState.Driving)

    // ---- link state -------------------------------------------------------------------------

    @Test
    fun startsFromTheSessionsCurrentStateAndNothingElse() {
        val state = CockpitViewModel(FakeKartSession()).state.value

        assertEquals(LinkState.NoAdapter, state.linkState)
        assertEquals(LinkState.NoAdapter.toUiText(), state.statusText)
        assertFalse(state.isDriving)
        assertEquals(0f, state.steeringFraction)
        assertEquals(0f, state.throttleFraction)
        assertFalse(state.brakeLight)
        assertFalse(state.hasBattery)
    }

    @Test
    fun aCockpitOpenedWhileDrivingStartsDriving() {
        val state = CockpitViewModel(drivingSession()).state.value

        assertTrue(state.isDriving)
        assertEquals(LinkState.Driving.toUiText(), state.statusText)
    }

    @Test
    fun theLinkStateDecidesWhetherTheCockpitIsDriving() = runTest {
        val session = FakeKartSession(initialLinkState = LinkState.KartReady)
        val viewModel = CockpitViewModel(session)

        viewModel.state.test {
            val ready = awaitItem()
            assertFalse(ready.isDriving)

            session.setLinkState(LinkState.Driving)
            val driving = awaitItem()
            assertTrue(driving.isDriving)
            assertEquals(LinkState.Driving.toUiText(), driving.statusText)

            session.setLinkState(LinkState.Recovering("control channel closed"))
            val recovering = awaitItem()
            assertFalse(recovering.isDriving)
            assertEquals(LinkState.Recovering("control channel closed").toUiText(), recovering.statusText)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- controls to drive commands ---------------------------------------------------------

    @Test
    fun steeringIsForwardedAsTheRawValueThroughDriveCommandOf() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        viewModel.onSteering(0.5f)

        assertEquals(driveCommandOf(throttle = 0f, steering = 0.5f, brakeLight = false), session.lastDriveCommand)
        assertEquals(0.5f, viewModel.state.value.steeringFraction)
    }

    @Test
    fun throttleIsForwardedAsTheRawValueWithNoScalingOfItsOwn() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        viewModel.onThrottle(1f)
        assertEquals(DriveCommand(throttle = 127, steering = 0, brakeLight = false), session.lastDriveCommand)

        viewModel.onThrottle(-1f)
        assertEquals(DriveCommand(throttle = -128, steering = 0, brakeLight = false), session.lastDriveCommand)
        assertEquals(-1f, viewModel.state.value.throttleFraction)
    }

    @Test
    fun theAxesAndTheBrakeCombineIntoOneCommand() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        viewModel.onSteering(-0.4f)
        viewModel.onThrottle(0.8f)
        viewModel.onBrake(true)

        assertEquals(driveCommandOf(0.8f, -0.4f, true), session.lastDriveCommand)
    }

    @Test
    fun outOfRangeAndNonFiniteValuesAreSanitised() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        viewModel.onThrottle(3f)
        assertEquals(1f, viewModel.state.value.throttleFraction)

        viewModel.onSteering(Float.NaN)
        assertEquals(0f, viewModel.state.value.steeringFraction)
        assertEquals(driveCommandOf(1f, 0f, false), session.lastDriveCommand)
    }

    @Test
    fun theBrakeLightFollowsTheButton() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        viewModel.onBrake(true)
        assertTrue(session.lastDriveCommand!!.brakeLight)
        assertTrue(viewModel.state.value.brakeLight)

        viewModel.onBrake(false)
        assertFalse(session.lastDriveCommand!!.brakeLight)
        assertFalse(viewModel.state.value.brakeLight)
    }

    // ---- release means neutral --------------------------------------------------------------

    @Test
    fun releasingEverythingSendsNeutralAndCentresTheControls() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onSteering(0.6f)
        viewModel.onThrottle(0.9f)
        viewModel.onBrake(true)

        viewModel.onControlRelease()

        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
        val state = viewModel.state.value
        assertEquals(0f, state.steeringFraction)
        assertEquals(0f, state.throttleFraction)
        assertFalse(state.brakeLight)
    }

    @Test
    fun releasingTheSteeringKeepsTheThrottleOfTheOtherThumb() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onSteering(0.6f)
        viewModel.onThrottle(0.9f)

        viewModel.onSteeringRelease()

        assertEquals(driveCommandOf(0.9f, 0f, false), session.lastDriveCommand)
        assertEquals(0f, viewModel.state.value.steeringFraction)
        assertEquals(0.9f, viewModel.state.value.throttleFraction)
    }

    @Test
    fun releasingTheThrottleKeepsTheSteering() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onSteering(0.6f)
        viewModel.onThrottle(0.9f)

        viewModel.onThrottleRelease()

        assertEquals(driveCommandOf(0f, 0.6f, false), session.lastDriveCommand)
    }

    @Test
    fun releasingTheLastHeldControlIsExactlyNeutral() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onSteering(0.6f)
        viewModel.onThrottle(0.9f)

        viewModel.onSteeringRelease()
        viewModel.onThrottleRelease()

        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
    }

    // ---- not driving ------------------------------------------------------------------------

    @Test
    fun controlInputIsIgnoredWhileTheKartIsNotDriving() {
        val session = FakeKartSession(initialLinkState = LinkState.KartReady)
        val viewModel = CockpitViewModel(session)

        viewModel.onSteering(1f)
        viewModel.onThrottle(1f)
        viewModel.onBrake(true)

        // Whatever the cockpit sent is neutral, and the state shows centred controls.
        assertTrue(session.driveCommands.all { it == DriveCommand.NEUTRAL })
        val state = viewModel.state.value
        assertEquals(0f, state.steeringFraction)
        assertEquals(0f, state.throttleFraction)
        assertFalse(state.brakeLight)
    }

    @Test
    fun leavingDrivingSendsNeutralAndCentresTheControls() = runTest {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onThrottle(1f)
        viewModel.onSteering(-1f)
        viewModel.onBrake(true)
        val afterTheTouch = session.driveCommands.size
        advanceTimeBy(DRIVE_KEEP_ALIVE_MS + 1)
        runCurrent()
        assertTrue(session.driveCommands.size > afterTheTouch, "the held command was being kept alive")

        session.setLinkState(LinkState.Recovering("control channel closed"))

        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
        val state = viewModel.state.value
        assertEquals(0f, state.throttleFraction)
        assertEquals(0f, state.steeringFraction)
        assertFalse(state.brakeLight)
        // The repeat loop stopped with the link: no stale throttle trickles out after the neutral.
        assertNoFurtherDriveCommands(session, viewModel)
    }

    @Test
    fun comingBackToDrivingStartsFromNeutral() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onThrottle(1f)
        session.setLinkState(LinkState.Beaconing(KartNetworkKind.GAME))

        session.setLinkState(LinkState.Driving)

        assertTrue(viewModel.state.value.isDriving)
        assertEquals(0f, viewModel.state.value.throttleFraction)
        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
    }

    // ---- leaving the screen -----------------------------------------------------------------

    private fun viewModelIn(store: ViewModelStore, session: KartSession): CockpitViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { CockpitViewModel(session) } })[CockpitViewModel::class]

    @Test
    fun clearingTheViewModelSendsNeutral() = runTest {
        val session = drivingSession()
        val store = ViewModelStore()
        val viewModel = viewModelIn(store, session)
        viewModel.onThrottle(1f)
        assertEquals(driveCommandOf(1f, 0f, false), session.lastDriveCommand)
        val afterTheTouch = session.driveCommands.size
        advanceTimeBy(DRIVE_KEEP_ALIVE_MS + 1)
        runCurrent()
        assertTrue(session.driveCommands.size > afterTheTouch, "the held command was being kept alive")

        store.clear()

        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
        // The repeat loop died with the screen: the throttle is not refreshed once neutral has been sent.
        assertNoFurtherDriveCommands(session, viewModel)
    }

    // ---- keeping a held command alive -------------------------------------------------------

    @Test
    fun aHeldCommandIsRepeatedBeforeTheSessionsDeadmanCanFire() = runTest {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onThrottle(0.5f)
        val afterTheTouch = session.driveCommands.size

        advanceTimeBy(DRIVE_KEEP_ALIVE_MS * 3 + 1)
        runCurrent()
        val repeats = session.driveCommands.drop(afterTheTouch)
        // Stopped before anything is asserted: a failing assertion must not leave the loop running under runTest.
        stopTheKeepAlive(viewModel)

        // The pedal did not move, but the same command kept arriving: three repeats in three periods.
        assertEquals(3, repeats.size)
        assertTrue(repeats.all { it == driveCommandOf(0.5f, 0f, false) })
        assertTrue(
            DRIVE_KEEP_ALIVE_MS < deadmanWindowMs,
            "the keep-alive period ($DRIVE_KEEP_ALIVE_MS ms) must be shorter than the session's deadman window ($deadmanWindowMs ms)",
        )
    }

    @Test
    fun theRepeatCarriesTheLatestValuesAndStopsOnRelease() = runTest {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onThrottle(0.5f)
        viewModel.onSteering(0.25f)

        advanceTimeBy(DRIVE_KEEP_ALIVE_MS + 1)
        runCurrent()
        assertEquals(driveCommandOf(0.5f, 0.25f, false), session.lastDriveCommand)

        viewModel.onControlRelease()
        val afterTheRelease = session.driveCommands.size
        advanceTimeBy(DRIVE_KEEP_ALIVE_MS * 5)
        runCurrent()

        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
        assertEquals(afterTheRelease, session.driveCommands.size)
    }

    @Test
    fun aHeldBrakeLightIsKeptAliveToo() = runTest {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        viewModel.onBrake(true)
        val afterTheTouch = session.driveCommands.size

        advanceTimeBy(DRIVE_KEEP_ALIVE_MS + 1)
        runCurrent()

        assertTrue(session.driveCommands.size > afterTheTouch)
        assertTrue(session.lastDriveCommand!!.brakeLight)
        stopTheKeepAlive(viewModel)
    }

    @Test
    fun nothingIsRepeatedWhileTheControlsAreNeutral() = runTest {
        val session = drivingSession()
        CockpitViewModel(session)
        val before = session.driveCommands.size

        advanceTimeBy(DRIVE_KEEP_ALIVE_MS * 10)
        runCurrent()

        assertEquals(before, session.driveCommands.size)
    }

    // ---- battery ----------------------------------------------------------------------------

    @Test
    fun theBatteryFollowsTheSession() = runTest {
        val session = FakeKartSession()
        val viewModel = CockpitViewModel(session)

        viewModel.state.test {
            assertFalse(awaitItem().hasBattery)

            session.setBattery(Telemetry.BatteryStatus(cableFlags = 0, batteryBars = 3))
            val reported = awaitItem()
            assertTrue(reported.hasBattery)
            assertEquals(3, reported.batteryBars)
            assertFalse(reported.batteryCharging)

            session.setBattery(Telemetry.BatteryStatus(cableFlags = 1, batteryBars = 4))
            val charging = awaitItem()
            assertEquals(4, charging.batteryBars)
            assertTrue(charging.batteryCharging)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aBatteryReadingOutsideTheGaugeRangeIsClamped() {
        val session = FakeKartSession()
        session.setBattery(Telemetry.BatteryStatus(cableFlags = 0, batteryBars = 9))

        assertEquals(4, CockpitViewModel(session).state.value.batteryBars)
    }

    // ---- IMU to HUD -------------------------------------------------------------------------

    @Test
    fun aLevelRestingKartReadsZeroOnEveryInstrument() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        session.emitImu(imuPacket())

        val state = viewModel.state.value
        assertEquals(0f, state.pitchDegrees, tolerance)
        assertEquals(0f, state.rollDegrees, tolerance)
        assertEquals(0f, state.gX, tolerance)
        assertEquals(0f, state.gY, tolerance)
    }

    @Test
    fun aTiltedKartMovesTheHorizon() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        session.emitImu(imuPacket(quaternion = rotationAbout(ImuAxis.X, 20.0)))

        assertEquals(20f, viewModel.state.value.pitchDegrees, tolerance)
        assertEquals(0f, viewModel.state.value.rollDegrees, tolerance)
    }

    @Test
    fun accelerationMovesTheGMeterWithTheYAxisNegated() {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        session.emitImu(imuPacket(samples = listOf(imuSample(0.5 * STANDARD_GRAVITY, -0.25 * STANDARD_GRAVITY, REST_Z))))

        assertEquals(0.5f, viewModel.state.value.gX, tolerance)
        assertEquals(0.25f, viewModel.state.value.gY, tolerance)
    }

    @Test
    fun aPacketWithNoSamplesKeepsTheLastGReading() = runTest {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)
        session.emitImu(imuPacket(samples = listOf(imuSample(0.5 * STANDARD_GRAVITY, 0.0, REST_Z))))

        // After the publish period, so this packet is published as well.
        advanceTimeBy(HUD_PUBLISH_PERIOD_MS + 1)
        session.emitImu(imuPacket(quaternion = rotationAbout(ImuAxis.X, 10.0), samples = emptyList()))

        assertEquals(10f, viewModel.state.value.pitchDegrees, tolerance)
        assertEquals(0.5f, viewModel.state.value.gX, tolerance)
    }

    @Test
    fun theHudIsPublishedAtADisplayRateNotPerPacket() = runTest {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        // The first packet is shown at once; the burst that follows within one period is conflated.
        session.emitImu(imuPacket(quaternion = rotationAbout(ImuAxis.X, 5.0)))
        assertEquals(5f, viewModel.state.value.pitchDegrees, tolerance)

        session.emitImu(imuPacket(quaternion = rotationAbout(ImuAxis.X, 10.0)))
        session.emitImu(imuPacket(quaternion = rotationAbout(ImuAxis.X, 15.0)))
        session.emitImu(imuPacket(quaternion = rotationAbout(ImuAxis.X, 20.0)))
        assertEquals(5f, viewModel.state.value.pitchDegrees, tolerance)

        // When the period is over only the newest of the burst is published.
        advanceTimeBy(HUD_PUBLISH_PERIOD_MS + 1)
        runCurrent()
        assertEquals(20f, viewModel.state.value.pitchDegrees, tolerance)
    }

    @Test
    fun aBurstOfHundredsOfPacketsPublishesAtMostOncePerPeriod() = runTest {
        val session = drivingSession()
        val viewModel = CockpitViewModel(session)

        viewModel.state.test {
            awaitItem() // the initial state
            // 300 packets, one every tenth of a period: thirty periods in all.
            repeat(300) { step ->
                session.emitImu(imuPacket(quaternion = rotationAbout(ImuAxis.X, 1.0 + step * 0.1)))
                advanceTimeBy(HUD_PUBLISH_PERIOD_MS / 10)
                runCurrent()
            }
            val published = cancelAndConsumeRemainingEvents().count { it is Event.Item }

            // About one publication per period, far fewer than the 300 packets.
            assertTrue(published in 20..40, "published $published HUD updates for 300 packets")
        }
    }

    // ---- video ------------------------------------------------------------------------------

    @Test
    fun theVideoStreamIsThePassThroughOfTheSessionsAndNeverInTheState() = runTest {
        val session = FakeKartSession()
        val viewModel = CockpitViewModel(session)

        viewModel.videoAccessUnits.test {
            session.emitVideoAccessUnit(byteArrayOf(0, 0, 0, 1, 0x65))
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x65), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Ends the repeat loop a held control started. runTest drains the virtual clock when the test body is done, and
     * a loop that repeats forever would spin it (and fill the fake's command log) without end.
     */
    private fun stopTheKeepAlive(viewModel: CockpitViewModel) = viewModel.onControlRelease()

    /**
     * For a test that has just sent the kart to neutral: lets five keep-alive periods go by and checks that nothing
     * more reached the session, i.e. the repeat loop really stopped. A loop that survived is stopped before the check
     * fails, because runTest drains the virtual clock once the body is done and would otherwise spin it (filling the
     * fake's command log) without end instead of reporting the failure.
     */
    private fun TestScope.assertNoFurtherDriveCommands(session: FakeKartSession, viewModel: CockpitViewModel) {
        val afterNeutral = session.driveCommands.size
        advanceTimeBy(DRIVE_KEEP_ALIVE_MS * 5)
        runCurrent()
        val sentSince = session.driveCommands.size - afterNeutral
        if (sentSince != 0) stopTheKeepAlive(viewModel)
        assertEquals(0, sentSince, "$sentSince drive commands were sent after neutral: the keep-alive was not stopped")
    }
}
