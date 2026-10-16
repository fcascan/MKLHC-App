package com.mklhc.feature.link.presentation

import app.cash.turbine.test
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.presentation.toUiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LinkViewModel] against [FakeKartSession]: the link states and the kart information the session publishes
 * flow into the screen state (text, button-enablement flags, UI models), and the screen's actions reach the
 * session. The main dispatcher is replaced with an unconfined test dispatcher so the ViewModel's collectors and
 * launched commands run eagerly, in the test's own thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkViewModelTest {

    private val info = KartInfo(
        serial = "SN-0012345",
        character = 2,
        firmwareSystem = 1 to 4,
        firmwareBoot = 0 to 9,
        sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709",
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- initial state ---------------------------------------------------------------------

    @Test
    fun startsWithTheSessionsCurrentStateAndNothingElse() {
        val viewModel = LinkViewModel(FakeKartSession())
        val state = viewModel.state.value
        assertEquals(LinkState.NoAdapter, state.linkState)
        assertEquals(LinkState.NoAdapter.toUiText(), state.linkStatusText)
        assertNull(state.kartInfo)
        assertNull(state.battery)
        assertFalse(state.captureEnabled)
        assertNull(state.errorMessage)
    }

    @Test
    fun startsFromWhateverTheSessionAlreadyPublished() {
        val session = FakeKartSession(initialLinkState = LinkState.Driving)
        session.setKartInfo(info)
        session.setBattery(Telemetry.BatteryStatus(cableFlags = 0, batteryBars = 3))

        val state = LinkViewModel(session).state.value

        assertEquals(LinkState.Driving, state.linkState)
        assertEquals(LinkState.Driving.toUiText(), state.linkStatusText)
        assertEquals("Kart 2", state.kartInfo?.characterLabel)
        assertEquals(BatteryUi(bars = 3, charging = false), state.battery)
    }

    @Test
    fun doesNotStartTheSessionByItself() {
        val session = FakeKartSession()
        LinkViewModel(session)
        assertFalse(session.startCalled)
        assertEquals(emptyList(), session.calls)
    }

    // ---- link state -> status text and flags -----------------------------------------------

    @Test
    fun linkStateChangesFlowIntoTheStatusText() = runTest {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        viewModel.state.test {
            assertEquals(LinkState.NoAdapter.toUiText(), awaitItem().linkStatusText)

            session.setLinkState(LinkState.RadioStarting)
            val starting = awaitItem()
            assertEquals(LinkState.RadioStarting, starting.linkState)
            assertEquals(LinkState.RadioStarting.toUiText(), starting.linkStatusText)

            val beaconing = LinkState.Beaconing(KartNetworkKind.GAME)
            session.setLinkState(beaconing)
            val waiting = awaitItem()
            assertEquals(beaconing, waiting.linkState)
            assertEquals(beaconing.toUiText(), waiting.linkStatusText)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failureAndRecoveryTextsCarryTheirReason() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        session.setLinkState(LinkState.Failed("radio fault"))
        assertEquals(LinkState.Failed("radio fault").toUiText(), viewModel.state.value.linkStatusText)

        session.setLinkState(LinkState.Recovering("control channel closed"))
        assertEquals(LinkState.Recovering("control channel closed").toUiText(), viewModel.state.value.linkStatusText)
    }

    @Test
    fun adapterIsReadyOnceTheRadioHasBeenGrantedAccess() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        for (waiting in listOf(LinkState.NoAdapter, LinkState.AwaitingUsbPermission)) {
            session.setLinkState(waiting)
            assertFalse(viewModel.state.value.isAdapterReady, "adapter must not be ready in $waiting")
        }
        for (ready in listOf(
            LinkState.RadioStarting,
            LinkState.Beaconing(KartNetworkKind.GAME),
            LinkState.KartAssociated,
            LinkState.KartReady,
            LinkState.Driving,
        )) {
            session.setLinkState(ready)
            assertTrue(viewModel.state.value.isAdapterReady, "adapter must be ready in $ready")
        }
    }

    @Test
    fun startIsOfferedOnlyWhileNoFlowIsRunning() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        // Idle or stopped: the user can (re)start.
        for (idle in listOf(LinkState.NoAdapter, LinkState.Failed("pairing cancelled"))) {
            session.setLinkState(idle)
            assertTrue(viewModel.state.value.canStart, "start must be offered in $idle")
        }
        // A flow is already climbing the ladder, driving or recovering by itself: no restart.
        for (running in listOf(
            LinkState.AwaitingUsbPermission,
            LinkState.RadioStarting,
            LinkState.Beaconing(KartNetworkKind.GAME),
            LinkState.Beaconing(KartNetworkKind.PAIRING),
            LinkState.KartAssociated,
            LinkState.KartLeased,
            LinkState.KartHandshaked,
            LinkState.KartReady,
            LinkState.Driving,
            LinkState.Recovering("link lost"),
        )) {
            session.setLinkState(running)
            assertFalse(viewModel.state.value.canStart, "start must not be offered in $running")
        }
    }

    @Test
    fun cockpitIsOfferedOnlyWhenTheKartAcceptsDriving() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        for (notYet in listOf(
            LinkState.NoAdapter,
            LinkState.RadioStarting,
            LinkState.Beaconing(KartNetworkKind.GAME),
            LinkState.KartHandshaked,
            LinkState.Recovering("link lost"),
            LinkState.Failed("radio fault"),
        )) {
            session.setLinkState(notYet)
            assertFalse(viewModel.state.value.canOpenCockpit, "cockpit must not be offered in $notYet")
        }
        for (ready in listOf(LinkState.KartReady, LinkState.Driving)) {
            session.setLinkState(ready)
            assertTrue(viewModel.state.value.canOpenCockpit, "cockpit must be offered in $ready")
        }
    }

    // ---- kart information and battery ------------------------------------------------------

    @Test
    fun kartInfoMapsToItsUiModelAndClearsAgain() = runTest {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        viewModel.state.test {
            assertNull(awaitItem().kartInfo)

            session.setKartInfo(info)
            assertEquals(
                KartInfoUi(serial = "SN-0012345", characterLabel = "Kart 2", firmware = "sys 1.4 / boot 0.9"),
                awaitItem().kartInfo,
            )

            session.setKartInfo(null)
            assertNull(awaitItem().kartInfo)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun batteryFlowsIntoTheState() = runTest {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        viewModel.state.test {
            assertNull(awaitItem().battery)

            session.setBattery(Telemetry.BatteryStatus(cableFlags = 1, batteryBars = 2))
            assertEquals(BatteryUi(bars = 2, charging = true), awaitItem().battery)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- actions ---------------------------------------------------------------------------

    @Test
    fun startClickStartsTheSession() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        viewModel.onStartClick()

        assertTrue(session.startCalled)
        assertEquals(1, session.startCount)
        assertEquals(listOf(FakeKartSession.Call.START), session.calls)
    }

    @Test
    fun startClickDoesNotMoveTheLinkItself() {
        // The screen only reflects what the session publishes; start() drives the session, not the state.
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        viewModel.onStartClick()

        assertEquals(LinkState.NoAdapter, viewModel.state.value.linkState)
    }

    @Test
    fun captureToggleReachesTheSessionAndTheState() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session)

        viewModel.onCaptureToggle(true)
        assertEquals(listOf(true), session.captureEnabledCalls)
        assertTrue(session.captureEnabled)
        assertTrue(viewModel.state.value.captureEnabled)

        viewModel.onCaptureToggle(false)
        assertEquals(listOf(true, false), session.captureEnabledCalls)
        assertFalse(viewModel.state.value.captureEnabled)
    }

    // ---- a session that fails to start -----------------------------------------------------

    /** A session whose start() throws, to exercise the error path; everything else is the recording fake. */
    private class FailingStartSession(private val delegate: FakeKartSession = FakeKartSession()) : KartSession by delegate {
        var failure: Exception? = IllegalStateException("adapter busy")

        override suspend fun start() {
            failure?.let { throw it }
            delegate.start()
        }
    }

    @Test
    fun aFailingStartShowsTheErrorAndALaterStartClearsIt() {
        val session = FailingStartSession()
        val viewModel = LinkViewModel(session)

        viewModel.onStartClick()
        val error = viewModel.state.value.errorMessage
        assertNotNull(error)
        assertEquals(IllegalStateException("adapter busy").toUiText(), error)

        session.failure = null
        viewModel.onStartClick()
        assertNull(viewModel.state.value.errorMessage)
    }
}
