package com.mklhc.feature.cockpit.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.design_system.AppTheme
import com.mklhc.core.design_system.drive.driveCommandOf
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.presentation.toUiText
import com.mklhc.feature.cockpit.presentation.di.cockpitFeatureModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cockpit rendered offscreen: [CockpitScreen] must show the HUD and the controls for the state it is given,
 * cover the cockpit with the status overlay while the kart is not driving, and report each interaction through its
 * lambda, for the touch controls and the keyboard alike (including the keyboard's releases on a link drop, a focus
 * loss and removal); [CockpitRoot] must wire a Koin-provided [CockpitViewModel] to a fake session end to end (the module
 * resolves, the screen follows the session, the controls reach the session as drive commands, and leaving the
 * screen sends neutral). The video is an empty flow: the desktop video surface is only a placeholder.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class CockpitScreenTest {

    private val waitingHint = "The controls are off until the kart is driving."

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun drivingState() = CockpitState(
        linkState = LinkState.Driving,
        statusText = LinkState.Driving.toUiText(),
        pitchDegrees = 8f,
        rollDegrees = -12f,
        gX = 0.4f,
        gY = 0.3f,
        hasBattery = true,
        batteryBars = 3,
        throttleFraction = 0.6f,
    )

    private fun connectingState(): CockpitState {
        val beaconing = LinkState.Beaconing(KartNetworkKind.GAME)
        return CockpitState(linkState = beaconing, statusText = beaconing.toUiText())
    }

    /** A recording set of lambdas for [CockpitScreen]. */
    private class Calls {
        val steering = mutableListOf<Float>()
        val throttle = mutableListOf<Float>()
        var steeringReleases = 0
        var throttleReleases = 0
        val brake = mutableListOf<Boolean>()
        var backs = 0
    }

    private fun androidx.compose.ui.test.ComposeUiTest.showScreen(state: CockpitState, calls: Calls = Calls()): Calls {
        setContent {
            AppTheme {
                CockpitScreen(
                    state = state,
                    videoAccessUnits = emptyFlow(),
                    onSteering = { calls.steering += it },
                    onThrottle = { calls.throttle += it },
                    onSteeringRelease = { calls.steeringReleases++ },
                    onThrottleRelease = { calls.throttleReleases++ },
                    onBrake = { calls.brake += it },
                    onBack = { calls.backs++ },
                )
            }
        }
        return calls
    }

    // ---- what the screen shows --------------------------------------------------------------

    @Test
    fun showsTheHudTheControlsAndNoOverlayWhileDriving() = runComposeUiTest {
        showScreen(drivingState())

        onNodeWithContentDescription("Artificial horizon, pitch 8 degrees, roll -12 degrees").assertExists()
        onNodeWithContentDescription("G meter, lateral 0.4 g, longitudinal 0.3 g").assertExists()
        onNodeWithContentDescription("Battery, 3 of 4 bars").assertExists()
        onNodeWithText("THROTTLE").assertExists()
        onNodeWithText("60").assertExists()
        onNodeWithContentDescription("Steering").assertExists()
        onNodeWithContentDescription("Throttle").assertExists()
        onNodeWithContentDescription("Brake, hold to stop").assertExists()
        onNodeWithContentDescription("Leave the cockpit").assertExists()
        onNodeWithText("Waiting for video").assertExists()
        onNodeWithText(waitingHint).assertDoesNotExist()
        // A frame must actually be drawn: the instruments and the controls are canvases that only run when rendered.
        val image = onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun coversTheCockpitWithTheLinkStatusWhileNotDriving() = runComposeUiTest {
        showScreen(connectingState())

        onNodeWithText("Waiting for the kart to join").assertExists()
        onNodeWithText(waitingHint).assertExists()
        // The way out stays available.
        onNodeWithContentDescription("Leave the cockpit").assertExists()
    }

    @Test
    fun theBatteryGaugeAppearsOnlyOnceTheKartReportsIt() = runComposeUiTest {
        showScreen(drivingState().copy(hasBattery = false))

        onNodeWithContentDescription("Battery, 3 of 4 bars").assertDoesNotExist()
    }

    @Test
    fun theOverlayFollowsTheStateAsItChanges() = runComposeUiTest {
        var state by mutableStateOf(connectingState())
        setContent {
            AppTheme {
                CockpitScreen(state, emptyFlow(), {}, {}, {}, {}, {}, {})
            }
        }
        onNodeWithText(waitingHint).assertExists()

        state = drivingState()
        waitForIdle()
        onNodeWithText(waitingHint).assertDoesNotExist()

        state = state.copy(linkState = LinkState.Recovering("control channel closed"))
        waitForIdle()
        onNodeWithText(waitingHint).assertExists()
    }

    // ---- what the controls report -----------------------------------------------------------

    @Test
    fun steeringTouchesAreReportedAndTheReleaseIsReportedOnce() = runComposeUiTest {
        val calls = showScreen(drivingState())

        onNodeWithContentDescription("Steering").performTouchInput { down(Offset(width * 0.95f, centerY)) }
        onNodeWithContentDescription("Steering").performTouchInput { up() }

        assertTrue(calls.steering.isNotEmpty() && calls.steering.last() > 0.5f, "steering ${calls.steering}")
        assertEquals(1, calls.steeringReleases)
        assertEquals(0, calls.throttleReleases)
    }

    @Test
    fun throttleTouchesAreReportedAndTheReleaseIsReportedOnce() = runComposeUiTest {
        val calls = showScreen(drivingState())

        // The top of the pedal is full forward.
        onNodeWithContentDescription("Throttle").performTouchInput { down(Offset(centerX, height * 0.02f)) }
        onNodeWithContentDescription("Throttle").performTouchInput { up() }

        assertTrue(calls.throttle.isNotEmpty() && calls.throttle.last() > 0.5f, "throttle ${calls.throttle}")
        assertEquals(1, calls.throttleReleases)
        assertEquals(0, calls.steeringReleases)
    }

    @Test
    fun theBrakeButtonReportsPressAndRelease() = runComposeUiTest {
        val calls = showScreen(drivingState())

        onNodeWithContentDescription("Brake, hold to stop").performTouchInput { down(center) }
        onNodeWithContentDescription("Brake, hold to stop").performTouchInput { up() }

        assertEquals(listOf(true, false), calls.brake)
    }

    @Test
    fun theControlsDoNothingWhileTheKartIsNotDriving() = runComposeUiTest {
        val calls = showScreen(connectingState())

        onNodeWithContentDescription("Steering").performTouchInput { down(Offset(width * 0.95f, centerY)); up() }
        onNodeWithContentDescription("Throttle").performTouchInput { down(Offset(centerX, height * 0.02f)); up() }
        onNodeWithContentDescription("Brake, hold to stop").performTouchInput { down(center); up() }

        assertTrue(calls.steering.isEmpty())
        assertTrue(calls.throttle.isEmpty())
        assertTrue(calls.brake.isEmpty())
    }

    // ---- driving with the keyboard ----------------------------------------------------------

    /** The window's focus, as a test controls it. */
    private class TestWindowInfo : WindowInfo {
        var focused by mutableStateOf(true)
        override val isWindowFocused: Boolean get() = focused
    }

    private fun androidx.compose.ui.test.ComposeUiTest.showScreenWithWindow(
        state: () -> CockpitState,
        window: TestWindowInfo = TestWindowInfo(),
        calls: Calls = Calls(),
        visible: () -> Boolean = { true },
        onFocusManager: (FocusManager) -> Unit = {},
    ): Calls {
        setContent {
            onFocusManager(LocalFocusManager.current)
            CompositionLocalProvider(LocalWindowInfo provides window) {
                if (visible()) AppTheme {
                    CockpitScreen(
                        state = state(),
                        videoAccessUnits = emptyFlow(),
                        onSteering = { calls.steering += it },
                        onThrottle = { calls.throttle += it },
                        onSteeringRelease = { calls.steeringReleases++ },
                        onThrottleRelease = { calls.throttleReleases++ },
                        onBrake = { calls.brake += it },
                        onBack = { calls.backs++ },
                    )
                }
            }
        }
        waitForIdle()
        return calls
    }

    @Test
    fun wasdDrivesBothAxesAtOnceAndEachKeyUpReleasesItsAxis() = runComposeUiTest {
        val calls = showScreenWithWindow({ drivingState() })

        onRoot().performKeyInput { keyDown(Key.W); keyDown(Key.A) }
        assertEquals(listOf(1f), calls.throttle)
        assertEquals(listOf(-1f), calls.steering)

        onRoot().performKeyInput { keyUp(Key.A) }
        assertEquals(1, calls.steeringReleases)
        assertEquals(0, calls.throttleReleases, "the throttle key is still down")

        onRoot().performKeyInput { keyUp(Key.W) }
        assertEquals(1, calls.throttleReleases)

        onRoot().performKeyInput { keyDown(Key.S); keyDown(Key.D); keyUp(Key.S); keyUp(Key.D) }
        assertEquals(listOf(1f, -1f), calls.throttle)
        assertEquals(listOf(-1f, 1f), calls.steering)
    }

    @Test
    fun theArrowKeysDriveAndTheSpaceBarLightsTheBrake() = runComposeUiTest {
        val calls = showScreenWithWindow({ drivingState() })

        onRoot().performKeyInput {
            keyDown(Key.DirectionUp); keyDown(Key.DirectionRight); keyDown(Key.Spacebar)
            keyUp(Key.Spacebar); keyUp(Key.DirectionRight); keyUp(Key.DirectionUp)
            keyDown(Key.DirectionDown); keyDown(Key.DirectionLeft)
        }

        assertEquals(listOf(1f, -1f), calls.throttle)
        assertEquals(listOf(1f, -1f), calls.steering)
        assertEquals(listOf(true, false), calls.brake)
    }

    @Test
    fun theKeysDoNothingWhileTheKartIsNotDriving() = runComposeUiTest {
        val calls = showScreenWithWindow({ connectingState() })

        onRoot().performKeyInput { keyDown(Key.W); keyDown(Key.D); keyDown(Key.Spacebar) }
        onRoot().performKeyInput { keyUp(Key.W); keyUp(Key.D); keyUp(Key.Spacebar) }

        assertTrue(calls.throttle.isEmpty())
        assertTrue(calls.steering.isEmpty())
        assertTrue(calls.brake.isEmpty())
    }

    @Test
    fun aKeyHeldThroughALinkDropDoesNotDriveWhenDrivingResumes() = runComposeUiTest {
        var state by mutableStateOf(drivingState())
        val calls = showScreenWithWindow({ state })
        onRoot().performKeyInput { keyDown(Key.W) }
        assertEquals(listOf(1f), calls.throttle)

        state = state.copy(linkState = LinkState.Recovering("control channel closed"))
        waitForIdle()
        assertEquals(1, calls.throttleReleases, "leaving Driving releases the held key")

        // The auto-repeat of a key still down is covered by KeyboardDriveTest: the test injection refuses a second down.
        // Coming back to Driving drives nothing on its own.
        state = drivingState()
        waitForIdle()
        assertEquals(listOf(1f), calls.throttle, "no new throttle until the key comes up and goes down again")

        onRoot().performKeyInput { keyUp(Key.W); keyDown(Key.W) }
        assertEquals(listOf(1f, 1f), calls.throttle)
    }

    @Test
    fun losingTheWindowFocusReleasesTheHeldKeys() = runComposeUiTest {
        val window = TestWindowInfo()
        val calls = showScreenWithWindow({ drivingState() }, window)
        onRoot().performKeyInput { keyDown(Key.W); keyDown(Key.D); keyDown(Key.Spacebar) }

        // Another window takes the focus: the key-ups go there, so the cockpit lets go by itself.
        window.focused = false
        waitForIdle()

        assertEquals(1, calls.throttleReleases)
        assertEquals(1, calls.steeringReleases)
        assertEquals(listOf(true, false), calls.brake)

        // Back in focus, a key-down may be the auto-repeat of a key held all along: the first press of each key is
        // ignored, the next one drives.
        window.focused = true
        waitForIdle()
        onRoot().performKeyInput { keyDown(Key.S) }
        assertEquals(listOf(1f), calls.throttle)
        onRoot().performKeyInput { keyUp(Key.S); keyDown(Key.S) }
        assertEquals(listOf(1f, -1f), calls.throttle)
    }

    @Test
    fun losingTheCockpitFocusReleasesTheHeldKeys() = runComposeUiTest {
        var focusManager: FocusManager? = null
        val calls = showScreenWithWindow({ drivingState() }, onFocusManager = { focusManager = it })
        onRoot().performKeyInput { keyDown(Key.W) }
        assertEquals(listOf(1f), calls.throttle)

        // A dialog or another control takes the focus inside the window: the key-ups no longer reach the cockpit.
        runOnUiThread { focusManager!!.clearFocus(force = true) }
        waitForIdle()

        assertEquals(1, calls.throttleReleases)
    }

    @Test
    fun removingTheScreenReleasesTheHeldKeys() = runComposeUiTest {
        var shown by mutableStateOf(true)
        val calls = showScreenWithWindow({ drivingState() }, visible = { shown })
        onRoot().performKeyInput { keyDown(Key.D) }
        assertEquals(listOf(1f), calls.steering)

        shown = false
        waitForIdle()

        assertEquals(1, calls.steeringReleases)
    }

    @Test
    fun oppositeKeysCancelToARelease() = runComposeUiTest {
        val calls = showScreenWithWindow({ drivingState() })

        onRoot().performKeyInput { keyDown(Key.W); keyDown(Key.S) }

        assertEquals(listOf(1f), calls.throttle)
        assertEquals(1, calls.throttleReleases)
    }

    @Test
    fun aDriveKeyWithCtrlIsAShortcutNotDriving() = runComposeUiTest {
        val calls = showScreenWithWindow({ drivingState() })

        onRoot().performKeyInput { keyDown(Key.CtrlLeft); keyDown(Key.W); keyUp(Key.W); keyUp(Key.CtrlLeft) }

        assertTrue(calls.throttle.isEmpty())
        assertEquals(0, calls.throttleReleases)
    }

    @Test
    fun theBackButtonIsReported() = runComposeUiTest {
        val calls = showScreen(drivingState())

        onNodeWithContentDescription("Leave the cockpit").performClick()

        assertEquals(1, calls.backs)
    }

    // ---- the Root, end to end through Koin --------------------------------------------------

    /** What a real window provides: a ViewModel store and a lifecycle that is resumed. */
    private class TestHost : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun androidx.compose.ui.test.ComposeUiTest.showRoot(
        session: KartSession,
        owner: TestHost,
        onBack: () -> Unit = {},
        visible: () -> Boolean = { true },
    ) {
        setContent {
            KoinApplication(application = {
                modules(cockpitFeatureModule, module { single<KartSession> { session } })
            }) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner,
                ) {
                    AppTheme {
                        if (visible()) CockpitRoot(onNavigateBack = onBack)
                    }
                }
            }
        }
    }

    @Test
    fun rootWiresAKoinViewModelToTheSession() = runComposeUiTest {
        val session = FakeKartSession(initialLinkState = LinkState.KartReady)
        var backs = 0
        showRoot(session, TestHost(), onBack = { backs++ })

        // Not driving yet: the overlay is up.
        onNodeWithText("Kart ready").assertExists()
        onNodeWithText(waitingHint).assertExists()

        // The session reaches Driving: the overlay goes and the telemetry shows on the HUD.
        session.setLinkState(LinkState.Driving)
        session.emitTelemetry(Telemetry.BatteryStatus(cableFlags = 0, batteryBars = 4))
        waitForIdle()
        onNodeWithText(waitingHint).assertDoesNotExist()
        onNodeWithContentDescription("Battery, 4 of 4 bars").assertExists()

        // The back button reaches the host's callback.
        onNodeWithContentDescription("Leave the cockpit").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun theControlsReachTheSessionAsDriveCommandsAndReleaseMeansNeutral() = runComposeUiTest {
        val session = FakeKartSession(initialLinkState = LinkState.Driving)
        showRoot(session, TestHost())
        waitForIdle()

        // Full forward on the pedal: the raw value goes through, with no scaling of its own.
        onNodeWithContentDescription("Throttle").performTouchInput { down(Offset(centerX, height * 0.02f)) }
        waitForIdle()
        val held = session.lastDriveCommand
        assertTrue(held != null && held.throttle > 100, "held command $held")

        // Finger up: neutral.
        onNodeWithContentDescription("Throttle").performTouchInput { up() }
        waitForIdle()
        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
        assertEquals(driveCommandOf(0f, 0f, false), session.lastDriveCommand)
    }

    @Test
    fun leavingTheScreenSendsNeutral() = runComposeUiTest {
        val session = FakeKartSession(initialLinkState = LinkState.Driving)
        var shown by mutableStateOf(true)
        showRoot(session, TestHost(), visible = { shown })
        waitForIdle()
        onNodeWithContentDescription("Throttle").performTouchInput { down(Offset(centerX, height * 0.02f)) }
        waitForIdle()
        assertTrue(session.lastDriveCommand!!.throttle > 100)

        shown = false
        waitForIdle()

        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
    }

    @Test
    fun theKeysReachTheSessionAsDriveCommandsAndKeyUpMeansNeutral() = runComposeUiTest {
        val session = FakeKartSession(initialLinkState = LinkState.Driving)
        showRoot(session, TestHost())
        waitForIdle()

        onRoot().performKeyInput { keyDown(Key.W) }
        waitForIdle()
        assertEquals(driveCommandOf(throttle = 1f, steering = 0f, brakeLight = false), session.lastDriveCommand)

        onRoot().performKeyInput { keyUp(Key.W) }
        waitForIdle()
        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
    }

    @Test
    fun pausingTheHostSendsNeutral() = runComposeUiTest {
        val session = FakeKartSession(initialLinkState = LinkState.Driving)
        val owner = TestHost()
        showRoot(session, owner)
        waitForIdle()
        onNodeWithContentDescription("Throttle").performTouchInput { down(Offset(centerX, height * 0.02f)) }
        waitForIdle()
        assertTrue(session.lastDriveCommand!!.throttle > 100)

        runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        waitForIdle()

        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
    }
}
