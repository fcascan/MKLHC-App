package com.mklhc.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.mklhc.app.demo.DemoKartSession
import com.mklhc.app.demo.DemoTiming
import com.mklhc.app.di.appKoinModules
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.feature.cockpit.presentation.navigation.CockpitRoute
import com.mklhc.feature.link.presentation.navigation.LinkRoute
import com.mklhc.feature.pairing.presentation.navigation.PairingRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's navigation graph, rendered offscreen through [AppContent] inside a Koin application that binds a
 * scripted session: the Link screen is the start destination, its buttons reach the pairing screen and the
 * cockpit, pairing completion moves into the cockpit without leaving the pairing screen on the back stack, and
 * Back and Cancel lead to the Link screen. Two end-to-end tests then run the real [DemoKartSession] (at a fast
 * pace) through the whole UI, the way the desktop demo does.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppNavigationTest {

    private val linkTitle = "Kart link"
    private val pairingInstructions = "Point the kart camera at the code."
    private val cockpitBack = "Leave the cockpit"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Holds the controller the content created, so a test can inspect the back stack. */
    private class Harness(val owner: TestHost = TestHost()) {
        lateinit var navController: NavHostController

        val onLink: Boolean get() = navController.currentBackStackEntry?.destination?.hasRoute<LinkRoute>() == true
        val onPairing: Boolean get() = navController.currentBackStackEntry?.destination?.hasRoute<PairingRoute>() == true
        val onCockpit: Boolean get() = navController.currentBackStackEntry?.destination?.hasRoute<CockpitRoute>() == true
        val isOnlyScreen: Boolean get() = navController.previousBackStackEntry == null
    }

    private fun ComposeUiTest.showApp(session: KartSession): Harness {
        val harness = Harness()
        setContent {
            KoinApplication(application = { modules(appKoinModules() + module { single<KartSession> { session } }) }) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides harness.owner,
                    LocalLifecycleOwner provides harness.owner,
                ) {
                    val controller = rememberNavController()
                    harness.navController = controller
                    AppContent(controller)
                }
            }
        }
        return harness
    }

    // ---- the graph, scripted session ---------------------------------------------------------

    @Test
    fun startsOnTheLinkScreen() = runComposeUiTest {
        val app = showApp(FakeKartSession())

        onNodeWithText(linkTitle).assertExists()
        onNodeWithText("Pair a kart").assertExists()
        assertTrue(app.onLink)
        assertTrue(app.isOnlyScreen)

        app.owner.viewModelStore.clear()
    }

    @Test
    fun theOpenCockpitButtonReachesTheCockpitAndBackReturnsToTheLinkScreen() = runComposeUiTest {
        val session = FakeKartSession(LinkState.KartReady)
        val app = showApp(session)

        onNodeWithText("Open cockpit").performClick()
        waitForIdle()
        onNodeWithContentDescription(cockpitBack).assertExists()
        assertTrue(app.onCockpit)

        onNodeWithContentDescription(cockpitBack).performClick()
        waitForIdle()
        onNodeWithText(linkTitle).assertExists()
        assertTrue(app.onLink)
        assertTrue(app.isOnlyScreen)

        app.owner.viewModelStore.clear()
    }

    @Test
    fun thePairButtonOpensThePairingScreenAndStartsThePairing() = runComposeUiTest {
        val session = FakeKartSession()
        val app = showApp(session)

        onNodeWithText("Pair a kart").performClick()
        waitForIdle()

        onNodeWithText(pairingInstructions).assertExists()
        assertTrue(app.onPairing)
        assertTrue(session.startPairingCalled, "opening the pairing screen starts the pairing flow")

        app.owner.viewModelStore.clear()
    }

    @Test
    fun cancellingThePairingReturnsToTheLinkScreen() = runComposeUiTest {
        val session = FakeKartSession()
        val app = showApp(session)
        onNodeWithText("Pair a kart").performClick()
        waitForIdle()

        onNodeWithText("Cancel").performClick()
        waitForIdle()

        onNodeWithText(linkTitle).assertExists()
        assertTrue(app.onLink)
        assertTrue(app.isOnlyScreen)
        assertEquals(1, session.cancelPairingCount)

        app.owner.viewModelStore.clear()
    }

    @Test
    fun aSecondBackNeverEmptiesTheStack() = runComposeUiTest {
        val session = FakeKartSession(LinkState.KartReady)
        val app = showApp(session)
        onNodeWithText("Open cockpit").performClick()
        waitForIdle()
        assertTrue(app.onCockpit)

        // A fast double tap on Back: the first pop returns to Link, the second must be a no-op, not an empty window.
        runOnIdle {
            app.navController.popBackStackSafely()
            app.navController.popBackStackSafely()
        }
        waitForIdle()

        onNodeWithText(linkTitle).assertExists()
        assertTrue(app.onLink)
        assertTrue(app.isOnlyScreen, "the second back must leave the Link screen in place")

        app.owner.viewModelStore.clear()
    }

    @Test
    fun aCompletedPairingMovesIntoTheCockpitAndBackSkipsThePairingScreen() = runComposeUiTest {
        val session = FakeKartSession()
        val app = showApp(session)
        onNodeWithText("Pair a kart").performClick()
        waitForIdle()

        // The kart scans the code and the session climbs to ready: the pairing completes.
        session.setLinkState(LinkState.Beaconing(KartNetworkKind.PAIRING))
        session.setLinkState(LinkState.KartReady)
        waitForIdle()

        onNodeWithContentDescription(cockpitBack).assertExists()
        assertTrue(app.onCockpit)

        onNodeWithContentDescription(cockpitBack).performClick()
        waitForIdle()

        onNodeWithText(linkTitle).assertExists()
        assertTrue(app.onLink, "Back from the cockpit lands on the Link screen, not on the finished pairing")
        assertTrue(app.isOnlyScreen)

        app.owner.viewModelStore.clear()
    }

    // ---- the whole UI on the demo session ----------------------------------------------------

    /** A demo session at a pace a test can wait for in real time. */
    private fun fastDemoSession(scope: CoroutineScope) = DemoKartSession(
        scope = scope,
        timing = DemoTiming(
            stepDelayMs = 15L,
            pairingScanDelayMs = 60L,
            imuPeriodMs = 10L,
            batteryPeriodMs = 50L,
            videoPeriodMs = 10L,
        ),
    )

    @Test
    fun startingTheDemoSessionBringsTheLinkUpAndTheCockpitShowsLiveData() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = fastDemoSession(scope)
        val app = showApp(session)

        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = WAIT_MILLIS) { isCockpitOpenable() }
        // The kart is identified (the sample kart) and its battery is shown on the Link screen.
        onNodeWithText("DEMO-KART-0001").assertExists()
        onNodeWithContentDescription("Battery, 3 of 4 bars").assertExists()

        onNodeWithText("Open cockpit").performClick()
        waitForIdle()
        onNodeWithContentDescription(cockpitBack).assertExists()
        assertTrue(app.onCockpit)
        // The desktop video surface counts the placeholder access units the demo streams.
        waitUntil(timeoutMillis = WAIT_MILLIS) {
            onAllNodesWithTextStartingWith("Receiving video:")
        }

        app.owner.viewModelStore.clear()
        runBlocking { session.stop() }
        scope.cancel()
    }

    @Test
    fun pairingOnTheDemoSessionShowsTheCodeAndEndsInTheCockpit() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = fastDemoSession(scope)
        val app = showApp(session)

        onNodeWithText("Pair a kart").performClick()
        // The demo kart "scans" the code and the pairing completes into the cockpit by itself.
        waitUntil(timeoutMillis = WAIT_MILLIS) { app.onCockpit }
        waitForIdle()
        onNodeWithContentDescription(cockpitBack).assertExists()

        onNodeWithContentDescription(cockpitBack).performClick()
        waitForIdle()
        assertTrue(app.onLink)
        assertNull(app.navController.previousBackStackEntry)

        app.owner.viewModelStore.clear()
        runBlocking { session.stop() }
        scope.cancel()
    }

    private fun ComposeUiTest.isCockpitOpenable(): Boolean =
        onAllNodes(hasText("Open cockpit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.onAllNodesWithTextStartingWith(prefix: String): Boolean =
        onAllNodes(hasText(prefix, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val WAIT_MILLIS = 15_000L
    }
}
