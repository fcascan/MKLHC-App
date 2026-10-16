package com.mklhc.feature.pairing.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PairingViewModel] against [FakeKartSession]: opening the screen starts the pairing flow, the QR payload and the
 * link states the session publishes flow into the screen state, reaching the game session after the pairing
 * network was beaconed completes the pairing exactly once, and cancelling reaches the session. The main dispatcher
 * is replaced with an unconfined test dispatcher so the ViewModel's collectors and launched commands run eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val payload = ByteArray(62) { (it * 3 + 1).toByte() }
    private val pairingBeacon = LinkState.Beaconing(KartNetworkKind.PAIRING)
    private val gameBeacon = LinkState.Beaconing(KartNetworkKind.GAME)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Walks the session from the pairing beacon up to the kart being ready on the game network. */
    private fun FakeKartSession.walkTheWholePairingLadder() {
        for (step in listOf(
            LinkState.RadioStarting,
            pairingBeacon,
            LinkState.KartAssociated,
            LinkState.KartLeased,
            LinkState.KartHandshaked,
            gameBeacon,
            LinkState.KartAssociated,
            LinkState.KartLeased,
            LinkState.KartHandshaked,
            LinkState.KartReady,
        )) {
            setLinkState(step)
        }
    }

    // ---- starting ---------------------------------------------------------------------------

    @Test
    fun opensByStartingThePairingFlow() {
        val session = FakeKartSession()
        PairingViewModel(session)

        assertTrue(session.startPairingCalled)
        assertEquals(1, session.startPairingCount)
        assertEquals(listOf(FakeKartSession.Call.START_PAIRING), session.calls)
    }

    @Test
    fun startPairingActionRestartsTheFlow() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)

        viewModel.onStartPairing()

        assertEquals(2, session.startPairingCount)
    }

    @Test
    fun startsAsPairingWithTheSessionsCurrentLinkState() {
        val state = PairingViewModel(FakeKartSession()).state.value

        assertNull(state.qrPayload)
        assertEquals(LinkState.NoAdapter, state.linkState)
        assertEquals(LinkState.NoAdapter.toUiText(), state.statusText)
        assertTrue(state.isPairing)
        assertNull(state.errorMessage)
        assertNull(state.navigation)
    }

    @Test
    fun startsFromAQrTheSessionAlreadyPublished() {
        val session = FakeKartSession(initialLinkState = pairingBeacon)
        session.setPairingQr(payload)

        val state = PairingViewModel(session).state.value

        assertContentEquals(payload, state.qrPayload)
        assertEquals(pairingBeacon.toUiText(), state.statusText)
    }

    // ---- QR payload -------------------------------------------------------------------------

    @Test
    fun theSessionsQrFlowsIntoTheStateAndClearsAgain() = runTest {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)

        viewModel.state.test {
            assertNull(awaitItem().qrPayload)

            session.setPairingQr(payload)
            assertContentEquals(payload, awaitItem().qrPayload)

            session.setPairingQr(null)
            assertNull(awaitItem().qrPayload)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- link state -> status ---------------------------------------------------------------

    @Test
    fun linkStateChangesFlowIntoTheStatusText() = runTest {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)

        viewModel.state.test {
            assertEquals(LinkState.NoAdapter.toUiText(), awaitItem().statusText)

            session.setLinkState(pairingBeacon)
            val waiting = awaitItem()
            assertEquals(pairingBeacon, waiting.linkState)
            assertEquals(pairingBeacon.toUiText(), waiting.statusText)
            assertTrue(waiting.isPairing)

            session.setLinkState(LinkState.KartAssociated)
            assertEquals(LinkState.KartAssociated.toUiText(), awaitItem().statusText)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedLinkStopsThePairingAndAnotherAttemptResumesIt() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)

        session.setLinkState(LinkState.Failed("radio fault"))
        assertFalse(viewModel.state.value.isPairing)
        assertEquals(LinkState.Failed("radio fault").toUiText(), viewModel.state.value.statusText)

        viewModel.onStartPairing()
        assertTrue(viewModel.state.value.isPairing)
        assertEquals(2, session.startPairingCount)

        session.setLinkState(LinkState.RadioStarting)
        assertTrue(viewModel.state.value.isPairing)
    }

    // ---- completion -------------------------------------------------------------------------

    @Test
    fun reachingTheKartReadyAfterPairingCompletesIt() = runTest {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)

        viewModel.state.test {
            assertNull(awaitItem().navigation)

            // The pairing handshake: still no navigation on any rung of the pairing ladder or the game ladder.
            for (step in listOf(
                LinkState.RadioStarting,
                pairingBeacon,
                LinkState.KartAssociated,
                LinkState.KartLeased,
                LinkState.KartHandshaked,
                gameBeacon,
                LinkState.KartAssociated,
                LinkState.KartLeased,
                LinkState.KartHandshaked,
            )) {
                session.setLinkState(step)
                assertNull(awaitItem().navigation, "no navigation at $step")
            }

            session.setLinkState(LinkState.KartReady)
            val done = awaitItem()
            assertEquals(PairingNavigation.Paired, done.navigation)
            assertEquals(LinkState.KartReady, done.linkState)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun drivingAfterPairingCompletesItToo() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)

        session.setLinkState(pairingBeacon)
        session.setLinkState(LinkState.Driving)

        assertEquals(PairingNavigation.Paired, viewModel.state.value.navigation)
    }

    @Test
    fun navigationHandledClearsTheEffectAndItDoesNotFireAgain() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)
        session.walkTheWholePairingLadder()
        assertEquals(PairingNavigation.Paired, viewModel.state.value.navigation)

        viewModel.onNavigationHandled()
        assertNull(viewModel.state.value.navigation)

        // The session carries on driving (and may even recover and reach ready again): no second navigation.
        session.setLinkState(LinkState.Driving)
        session.setLinkState(LinkState.Recovering("link lost"))
        session.setLinkState(LinkState.KartReady)
        assertNull(viewModel.state.value.navigation)
    }

    @Test
    fun aReadyLinkLeftOverFromBeforeDoesNotCompleteThePairing() {
        // The screen opens while a game session is up; the session only moves off it once the pairing flow starts.
        val session = FakeKartSession(initialLinkState = LinkState.Driving)
        val viewModel = PairingViewModel(session)
        assertNull(viewModel.state.value.navigation)

        session.setLinkState(LinkState.KartReady)
        assertNull(viewModel.state.value.navigation)

        session.walkTheWholePairingLadder()
        assertEquals(PairingNavigation.Paired, viewModel.state.value.navigation)
    }

    @Test
    fun readyOnTheGameNetworkWithoutEverBeaconingThePairingNetworkIsNotAPairing() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)

        for (step in listOf(LinkState.RadioStarting, gameBeacon, LinkState.KartAssociated, LinkState.KartReady)) {
            session.setLinkState(step)
        }

        assertNull(viewModel.state.value.navigation)
    }

    @Test
    fun anotherAttemptNeedsItsOwnPairingBeaconToComplete() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)
        session.setLinkState(pairingBeacon)
        session.setLinkState(LinkState.Failed("radio fault"))

        viewModel.onStartPairing()
        // The session is still on its old state until it moves; ready now must not count for the new attempt.
        session.setLinkState(LinkState.KartReady)
        assertNull(viewModel.state.value.navigation)
    }

    // ---- cancelling -------------------------------------------------------------------------

    @Test
    fun cancelCancelsThePairingAndAsksToLeave() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)
        session.setLinkState(pairingBeacon)
        session.setPairingQr(payload)

        viewModel.onCancel()

        assertEquals(1, session.cancelPairingCount)
        val state = viewModel.state.value
        assertEquals(PairingNavigation.Cancelled, state.navigation)
        assertFalse(state.isPairing)
        assertNull(state.qrPayload)

        viewModel.onNavigationHandled()
        assertNull(viewModel.state.value.navigation)
    }

    @Test
    fun aCancelledPairingNeverCompletesEvenIfTheLinkKeepsMoving() {
        val session = FakeKartSession()
        val viewModel = PairingViewModel(session)
        session.setLinkState(pairingBeacon)
        viewModel.onCancel()
        viewModel.onNavigationHandled()

        // Teardown settles on a terminal state; a late ready must not turn into a completion.
        session.setLinkState(LinkState.Failed("pairing cancelled"))
        session.setLinkState(LinkState.KartReady)

        assertNull(viewModel.state.value.navigation)
    }

    // ---- a session that fails to start pairing ----------------------------------------------

    /** A session whose startPairing() throws, to exercise the error path; everything else is the recording fake. */
    private class FailingPairingSession(private val delegate: FakeKartSession = FakeKartSession()) : KartSession by delegate {
        override suspend fun startPairing() {
            throw IllegalStateException("adapter busy")
        }
    }

    @Test
    fun aFailingStartShowsTheErrorAndStopsThePairing() {
        val viewModel = PairingViewModel(FailingPairingSession())

        val state = viewModel.state.value
        assertEquals(IllegalStateException("adapter busy").toUiText(), state.errorMessage)
        assertFalse(state.isPairing)
    }

    // ---- leaving the screen without choosing ------------------------------------------------

    private fun viewModelIn(store: ViewModelStore, session: KartSession): PairingViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { PairingViewModel(session) } })[PairingViewModel::class]

    @Test
    fun leavingTheScreenWhilePairingCancelsThePairing() {
        // For example the system back gesture: the beacon must not stay up with nobody showing the QR.
        val session = FakeKartSession()
        val store = ViewModelStore()
        viewModelIn(store, session)

        store.clear()

        assertEquals(1, session.cancelPairingCount)
    }

    @Test
    fun leavingAfterAPairingCompletedLeavesTheSessionRunning() {
        val session = FakeKartSession()
        val store = ViewModelStore()
        viewModelIn(store, session)
        session.walkTheWholePairingLadder()

        store.clear()

        assertEquals(0, session.cancelPairingCount)
    }

    @Test
    fun leavingAfterCancellingDoesNotCancelASecondTime() {
        val session = FakeKartSession()
        val store = ViewModelStore()
        val viewModel = viewModelIn(store, session)
        viewModel.onCancel()

        store.clear()

        assertEquals(1, session.cancelPairingCount)
    }
}
