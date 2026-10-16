package com.mklhc.feature.link.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.presentation.toUiText
import com.mklhc.feature.link.presentation.di.linkFeatureModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The link screen rendered offscreen: [LinkScreen] must show the state it is given and report each interaction
 * through its lambda, and [LinkRoot] must wire a Koin-provided [LinkViewModel] to a fake session end to end (the
 * Koin module resolves, the screen follows the session, Start reaches the session, the navigation buttons call
 * the host's callbacks).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LinkScreenTest {

    private val info = KartInfoUi(serial = "SN-0012345", characterLabel = "Kart 1", firmware = "sys 1.4 / boot 0.9")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun drivingState() = LinkScreenState(
        linkState = LinkState.Driving,
        linkStatusText = LinkState.Driving.toUiText(),
        kartInfo = info,
        battery = BatteryUi(bars = 3, charging = false),
    )

    @Test
    fun showsTheLinkStatusTheKartInformationAndTheBattery() = runComposeUiTest {
        setContent { AppTheme { LinkScreen(drivingState(), {}, {}, {}, {}) } }

        onNodeWithText("Driving").assertExists()
        onNodeWithText("SN-0012345").assertExists()
        onNodeWithText("Kart 1").assertExists()
        onNodeWithText("sys 1.4 / boot 0.9").assertExists()
        onNodeWithContentDescription("Battery, 3 of 4 bars").assertExists()
        // A frame must actually be drawn: the battery gauge is a canvas that only runs when rendered.
        val image = onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun showsAHintInsteadOfKartInformationBeforeTheKartIsIdentified() = runComposeUiTest {
        setContent { AppTheme { LinkScreen(LinkScreenState(), {}, {}, {}, {}) } }

        onNodeWithText("No radio adapter found").assertExists()
        onNodeWithText("No kart identified yet").assertExists()
    }

    @Test
    fun showsTheReasonAStartFailed() = runComposeUiTest {
        val state = LinkScreenState(errorMessage = IllegalStateException("adapter busy").toUiText())
        setContent { AppTheme { LinkScreen(state, {}, {}, {}, {}) } }

        onNodeWithText("Something went wrong: adapter busy").assertExists()
    }

    @Test
    fun buttonsFollowTheStateFlags() = runComposeUiTest {
        // Idle: Start and Pair are available, the cockpit is not.
        setContent { AppTheme { LinkScreen(LinkScreenState(), {}, {}, {}, {}) } }
        onNodeWithText("Start").assertIsEnabled()
        onNodeWithText("Pair a kart").assertIsEnabled()
        onNodeWithText("Open cockpit").assertIsNotEnabled()
    }

    @Test
    fun buttonsFollowTheStateFlagsWhileDriving() = runComposeUiTest {
        setContent { AppTheme { LinkScreen(drivingState(), {}, {}, {}, {}) } }
        onNodeWithText("Start").assertIsNotEnabled()
        onNodeWithText("Pair a kart").assertIsEnabled()
        onNodeWithText("Open cockpit").assertIsEnabled()
    }

    @Test
    fun interactionsAreReportedThroughTheLambdas() = runComposeUiTest {
        val events = mutableListOf<String>()
        var capture = false
        setContent {
            AppTheme {
                LinkScreen(
                    state = LinkScreenState(linkState = LinkState.KartReady, linkStatusText = LinkState.KartReady.toUiText())
                        .copy(captureEnabled = capture),
                    onStartClick = { events += "start" },
                    onCaptureToggle = { capture = it; events += "capture=$it" },
                    onPairClick = { events += "pair" },
                    onCockpitClick = { events += "cockpit" },
                )
            }
        }

        onNodeWithText("Pair a kart").performClick()
        onNodeWithText("Open cockpit").performClick()
        onNodeWithText("Capture packets").performClick()

        assertEquals(listOf("pair", "cockpit", "capture=true"), events)
    }

    @Test
    fun startIsReportedWhenItIsEnabled() = runComposeUiTest {
        var started = 0
        setContent { AppTheme { LinkScreen(LinkScreenState(), { started++ }, {}, {}, {}) } }

        onNodeWithText("Start").performClick()

        assertEquals(1, started)
    }

    // ---- the Root, end to end through Koin --------------------------------------------------

    /** What a real window provides: a ViewModel store and a lifecycle that is resumed. */
    private class TestHost : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun rootWiresAKoinViewModelToTheSession() = runComposeUiTest {
        val session = FakeKartSession()
        val owner = TestHost()
        val navigation = mutableListOf<String>()
        setContent {
            KoinApplication(application = {
                modules(linkFeatureModule, module { single<KartSession> { session } })
            }) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner,
                ) {
                    AppTheme {
                        LinkRoot(
                            onNavigateToCockpit = { navigation += "cockpit" },
                            onNavigateToPairing = { navigation += "pairing" },
                        )
                    }
                }
            }
        }

        // The screen follows the session.
        onNodeWithText("No radio adapter found").assertExists()
        session.setLinkState(LinkState.KartReady)
        session.setKartInfo(KartInfo("SN-77", 2, 1 to 4, 0 to 9, "da39a3ee5e6b4b0d3255bfef95601890afd80709"))
        session.emitTelemetry(Telemetry.BatteryStatus(cableFlags = 0, batteryBars = 4))
        waitForIdle()
        onNodeWithText("Kart ready").assertExists()
        onNodeWithText("Kart 2").assertExists()
        onNodeWithContentDescription("Battery, 4 of 4 bars").assertExists()

        // Navigation buttons call the host callbacks, and Start is not offered while the kart is ready.
        onNodeWithText("Pair a kart").performClick()
        onNodeWithText("Open cockpit").performClick()
        assertEquals(listOf("pairing", "cockpit"), navigation)
        onNodeWithText("Start").assertIsNotEnabled()

        // Back to a failed link: Start is offered again and reaches the session.
        session.setLinkState(LinkState.Failed("radio fault"))
        waitForIdle()
        onNodeWithText("Start").assertIsEnabled().performClick()
        waitForIdle()
        assertTrue(session.startCalled)

        owner.viewModelStore.clear()
    }
}
