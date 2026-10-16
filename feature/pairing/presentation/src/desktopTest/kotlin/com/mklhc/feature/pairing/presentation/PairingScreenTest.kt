package com.mklhc.feature.pairing.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.toUiText
import com.mklhc.feature.pairing.presentation.di.pairingFeatureModule
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing screen rendered offscreen: [PairingScreen] must show the state it is given, draw a code a scanner can
 * read back to the payload, and report each interaction through its lambda; [PairingRoot] must start the pairing
 * through a Koin-provided [PairingViewModel], follow the session, and perform the navigation effect exactly once.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PairingScreenTest {

    private val qrDescription = "Pairing code for the kart to scan"
    private val pairingBeacon = LinkState.Beaconing(KartNetworkKind.PAIRING)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun waitingState(payload: ByteArray?) = PairingState(
        qrPayload = payload,
        linkState = pairingBeacon,
        statusText = pairingBeacon.toUiText(),
        isPairing = true,
    )

    // ---- the Screen --------------------------------------------------------------------------

    @Test
    fun showsTheInstructionsTheStatusAndACodeThatScansBackToThePayload() = runComposeUiTest {
        val payload = samplePairingPayload()
        // The indeterminate progress bar animates forever; step the clock by hand instead of idling on it.
        mainClock.autoAdvance = false
        setContent { AppTheme { PairingScreen(waitingState(payload), {}, {}) } }
        mainClock.advanceTimeByFrame()

        onNodeWithText("Point the kart camera at the code.").assertExists()
        onNodeWithText("Waiting for the kart to pair").assertExists()
        onNodeWithText("Cancel").assertExists()

        // The code is on screen and, read the way a camera would, carries exactly the payload octets.
        val image = onNodeWithContentDescription(qrDescription).captureToImage()
        assertContentEquals(payload, image.scanQrOctets())
    }

    @Test
    fun showsAPlaceholderInsteadOfTheCodeBeforeThereIsOne() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { AppTheme { PairingScreen(waitingState(payload = null), {}, {}) } }
        mainClock.advanceTimeByFrame()

        onNodeWithText("Preparing the pairing code").assertExists()
        onNodeWithContentDescription(qrDescription).assertDoesNotExist()
    }

    @Test
    fun saysSoWhenThePayloadCannotBeEncodedAsAByteModeCode() = runComposeUiTest {
        mainClock.autoAdvance = false
        // Digits only would come out in numeric mode, which the kart cannot read, so there is no code to show.
        setContent { AppTheme { PairingScreen(waitingState("0123456789".encodeToByteArray()), {}, {}) } }
        mainClock.advanceTimeByFrame()

        onNodeWithText("The pairing code could not be shown.").assertExists()
        onNodeWithContentDescription(qrDescription).assertDoesNotExist()
    }

    @Test
    fun cancelIsReportedAndTryAgainIsOnlyOfferedWhenThePairingIsNotRunning() = runComposeUiTest {
        var cancels = 0
        var restarts = 0
        mainClock.autoAdvance = false
        setContent { AppTheme { PairingScreen(waitingState(samplePairingPayload()), { restarts++ }, { cancels++ }) } }
        mainClock.advanceTimeByFrame()

        onNodeWithText("Try again").assertDoesNotExist()
        onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
        assertEquals(0, restarts)
    }

    @Test
    fun aFailedPairingOffersAnotherAttempt() = runComposeUiTest {
        var restarts = 0
        val failed = LinkState.Failed("pairing timed out")
        val state = PairingState(linkState = failed, statusText = failed.toUiText(), isPairing = false)
        setContent { AppTheme { PairingScreen(state, { restarts++ }, {}) } }

        onNodeWithText("Link failed: pairing timed out").assertExists()
        onNodeWithText("Try again").performClick()
        assertEquals(1, restarts)
    }

    @Test
    fun showsTheReasonThePairingCouldNotBeStarted() = runComposeUiTest {
        val state = PairingState(errorMessage = IllegalStateException("adapter busy").toUiText(), isPairing = false)
        setContent { AppTheme { PairingScreen(state, {}, {}) } }

        onNodeWithText("Something went wrong: adapter busy").assertExists()
    }

    // ---- the Root, end to end through Koin ----------------------------------------------------

    /** What a real window provides: a ViewModel store and a lifecycle that is resumed. */
    private class TestHost : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun rootStartsThePairingShowsTheCodeAndNavigatesOnceWhenPaired() = runComposeUiTest {
        val session = FakeKartSession()
        val host = TestHost()
        val events = mutableListOf<String>()
        mainClock.autoAdvance = false
        setContent {
            KoinApplication(application = {
                modules(pairingFeatureModule, module { single<KartSession> { session } })
            }) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides host,
                    LocalLifecycleOwner provides host,
                ) {
                    AppTheme {
                        PairingRoot(onPaired = { events += "paired" }, onCancel = { events += "cancel" })
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()

        // Opening the screen starts the pairing, once.
        assertEquals(1, session.startPairingCount)
        onNodeWithText("Preparing the pairing code").assertExists()

        // The session publishes the code and the pairing beacon: the code appears and scans back.
        val payload = samplePairingPayload(seedByte = 0x42)
        session.setPairingQr(payload)
        session.setLinkState(pairingBeacon)
        mainClock.advanceTimeByFrame()
        onNodeWithText("Waiting for the kart to pair").assertExists()
        assertContentEquals(payload, onNodeWithContentDescription(qrDescription).captureToImage().scanQrOctets())
        assertTrue(events.isEmpty())

        // The kart pairs and reaches the game session: the host is told once.
        for (step in listOf(LinkState.KartAssociated, LinkState.KartHandshaked, LinkState.KartReady)) {
            session.setLinkState(step)
            mainClock.advanceTimeByFrame()
        }
        assertEquals(listOf("paired"), events)

        // Further link changes do not navigate again, and nothing was cancelled.
        session.setLinkState(LinkState.Driving)
        mainClock.advanceTimeByFrame()
        assertEquals(listOf("paired"), events)
        assertEquals(0, session.cancelPairingCount)

        host.viewModelStore.clear()
        assertEquals(0, session.cancelPairingCount)
    }

    @Test
    fun rootCancelsThroughTheSessionAndNavigatesOnce() = runComposeUiTest {
        val session = FakeKartSession()
        val host = TestHost()
        val events = mutableListOf<String>()
        mainClock.autoAdvance = false
        setContent {
            KoinApplication(application = {
                modules(pairingFeatureModule, module { single<KartSession> { session } })
            }) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides host,
                    LocalLifecycleOwner provides host,
                ) {
                    AppTheme {
                        PairingRoot(onPaired = { events += "paired" }, onCancel = { events += "cancel" })
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        session.setPairingQr(samplePairingPayload())
        session.setLinkState(pairingBeacon)
        mainClock.advanceTimeByFrame()

        onNodeWithText("Cancel").performClick()
        mainClock.advanceTimeByFrame()

        assertEquals(1, session.cancelPairingCount)
        assertEquals(listOf("cancel"), events)
        assertNull(session.pairingQr.value)
        onNodeWithContentDescription(qrDescription).assertDoesNotExist()

        // Leaving after the cancel must not cancel a second time.
        host.viewModelStore.clear()
        assertEquals(1, session.cancelPairingCount)
        assertEquals(listOf("cancel"), events)
    }
}
