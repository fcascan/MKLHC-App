package com.mklhc.feature.link.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.mklhc.core.data.radio.AdapterOverview
import com.mklhc.core.data.radio.FakeUsbAdapterRepository
import com.mklhc.core.data.radio.UsbAdapterRepository
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.design_system.AppTheme
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.LinkState
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
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
 * through its lambda, and [LinkRoot] must wire a Koin-provided [LinkViewModel] to a fake session and a fake adapter
 * repository end to end (the Koin module resolves, the screen follows the session and the adapter list, Start and
 * the adapter choice reach their collaborators, the navigation buttons call the host's callbacks).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LinkScreenTest {

    private val info = KartInfoUi(serial = "SN-0012345", characterLabel = "Kart 1", firmware = "sys 1.4 / boot 0.9")

    private val t3u = UsbId(0x2357, 0x012D)
    private val comfast = UsbId(0x0BDA, 0xC811)
    private val unknown = UsbId(0x1234, 0x5678)

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

    private fun usb(id: UsbId, access: UsbDeviceAccess = UsbDeviceAccess.OPENABLE, address: Int = 2) =
        DetectedUsbDevice(id, bus = 1, address = address, access = access)

    /** An idle link (so the adapter controls are enabled) with the adapter section built from a repository overview. */
    private fun adapterState(
        devices: List<DetectedUsbDevice>?,
        selection: AdapterSelection = AdapterSelection.Automatic,
        inUse: UsbId? = null,
        linkState: LinkState = LinkState.NoAdapter,
    ) = LinkScreenState(
        linkState = linkState,
        linkStatusText = linkState.toUiText(),
        adapter = AdapterOverview.of(devices, selection, inUse).toAdapterSectionUi(),
    )

    @Composable
    private fun Screen(
        state: LinkScreenState,
        onStartClick: () -> Unit = {},
        onCaptureToggle: (Boolean) -> Unit = {},
        onPairClick: () -> Unit = {},
        onCockpitClick: () -> Unit = {},
        onAdapterAutomatic: () -> Unit = {},
        onAdapterManual: () -> Unit = {},
        onAdapterPick: (UsbId) -> Unit = {},
    ) {
        AppTheme {
            LinkScreen(
                state = state,
                onStartClick = onStartClick,
                onCaptureToggle = onCaptureToggle,
                onPairClick = onPairClick,
                onCockpitClick = onCockpitClick,
                onAdapterAutomatic = onAdapterAutomatic,
                onAdapterManual = onAdapterManual,
                onAdapterPick = onAdapterPick,
            )
        }
    }

    @Test
    fun showsTheLinkStatusTheKartInformationAndTheBattery() = runComposeUiTest {
        setContent { Screen(drivingState()) }

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
        setContent { Screen(LinkScreenState()) }

        onNodeWithText("No radio adapter found").assertExists()
        onNodeWithText("No kart identified yet").assertExists()
    }

    @Test
    fun showsTheReasonAStartFailed() = runComposeUiTest {
        val state = LinkScreenState(errorMessage = IllegalStateException("adapter busy").toUiText())
        setContent { Screen(state) }

        onNodeWithText("Something went wrong: adapter busy").assertExists()
    }

    @Test
    fun buttonsFollowTheStateFlags() = runComposeUiTest {
        // Idle: Start and Pair are available, the cockpit is not.
        setContent { Screen(LinkScreenState()) }
        onNodeWithText("Start").assertIsEnabled()
        onNodeWithText("Pair a kart").assertIsEnabled()
        onNodeWithText("Open cockpit").assertIsNotEnabled()
    }

    @Test
    fun buttonsFollowTheStateFlagsWhileDriving() = runComposeUiTest {
        setContent { Screen(drivingState()) }
        onNodeWithText("Start").assertIsNotEnabled()
        onNodeWithText("Pair a kart").assertIsEnabled()
        onNodeWithText("Open cockpit").assertIsEnabled()
    }

    @Test
    fun interactionsAreReportedThroughTheLambdas() = runComposeUiTest {
        val events = mutableListOf<String>()
        var capture = false
        setContent {
            Screen(
                state = LinkScreenState(linkState = LinkState.KartReady, linkStatusText = LinkState.KartReady.toUiText())
                    .copy(captureEnabled = capture),
                onStartClick = { events += "start" },
                onCaptureToggle = { capture = it; events += "capture=$it" },
                onPairClick = { events += "pair" },
                onCockpitClick = { events += "cockpit" },
            )
        }

        onNodeWithText("Pair a kart").performClick()
        onNodeWithText("Open cockpit").performClick()
        onNodeWithText("Capture packets").performScrollTo().performClick()

        assertEquals(listOf("pair", "cockpit", "capture=true"), events)
    }

    @Test
    fun startIsReportedWhenItIsEnabled() = runComposeUiTest {
        var started = 0
        setContent { Screen(LinkScreenState(), onStartClick = { started++ }) }

        onNodeWithText("Start").performClick()

        assertEquals(1, started)
    }

    // ---- the radio adapter section ---------------------------------------------------------

    @Test
    fun theAdapterSectionShowsTheModesAndWhileScanningTheLookingLine() = runComposeUiTest {
        setContent { Screen(adapterState(devices = null)) }

        onNodeWithText("Radio adapter").assertExists()
        onNodeWithText("Automatic").assertExists().assertIsSelected()
        onNodeWithText("Uses the first compatible adapter it finds").assertExists()
        onNodeWithText("Manual").assertExists().assertIsNotSelected()
        onNodeWithText("Uses the adapter you pick").assertExists()
        onNodeWithText("Looking for USB devices…").assertExists()
    }

    @Test
    fun whileTheRadioHelperIsNotRunningTheStatusLineSaysSo() = runComposeUiTest {
        val helperDown = LinkScreenState(
            adapter = AdapterOverview.of(null, AdapterSelection.Automatic, inUse = null, helperConnected = false)
                .toAdapterSectionUi(),
        )
        setContent { Screen(helperDown) }

        onNodeWithText("The radio helper is not running.").assertExists()
        onNodeWithText("Looking for USB devices…").assertDoesNotExist()
        onNodeWithText("Manual").assertIsNotEnabled()
    }

    @Test
    fun twoAdaptersOfTheSameModelBothShowAsRows() = runComposeUiTest {
        setContent {
            Screen(
                adapterState(
                    listOf(usb(t3u, address = 3), usb(t3u, address = 4)),
                    selection = AdapterSelection.Manual(t3u),
                ),
            )
        }

        // Both are listed and both are the chosen model.
        onAllNodesWithText("TP-Link Archer T3U").assertCountEquals(2)
    }

    @Test
    fun anEmptyScanSaysNoDevicesWereFound() = runComposeUiTest {
        setContent { Screen(adapterState(emptyList())) }

        onNodeWithText("No USB devices found.").assertExists()
    }

    @Test
    fun theDeviceRowsShowNameIdChipAndPills() = runComposeUiTest {
        setContent {
            Screen(
                adapterState(
                    listOf(
                        usb(unknown, UsbDeviceAccess.DRIVER_UNSUPPORTED, address = 5),
                        usb(comfast, UsbDeviceAccess.ACCESS_DENIED, address = 4),
                        usb(t3u),
                    ),
                ),
            )
        }

        onNodeWithText("TP-Link Archer T3U").assertExists()
        onNodeWithText("2357:012d").assertExists()
        onNodeWithText("RTL8822BU").assertExists()
        onNodeWithText("Compatible").assertExists()
        onNodeWithText("Will be used").assertExists()

        onNodeWithText("COMFAST CF-811AC").assertExists()
        onNodeWithText("0bda:c811").assertExists()
        onNodeWithText("Not recommended").assertExists()
        onNodeWithText("No permission").assertExists()

        onNodeWithText("Unknown USB device").assertExists()
        onNodeWithText("1234:5678").assertExists()
        onNodeWithText("Needs the WinUSB driver").assertExists()

        onNodeWithText("Using TP-Link Archer T3U (2357:012d).").assertExists()
    }

    @Test
    fun theAdapterInUseShowsInUseAndTheStatusLineNamesIt() = runComposeUiTest {
        val state = adapterState(
            listOf(usb(t3u, UsbDeviceAccess.BUSY)),
            inUse = t3u,
            linkState = LinkState.Driving,
        )
        setContent { Screen(state) }

        onNodeWithText("In use").assertExists()
        onNodeWithText("Using TP-Link Archer T3U (2357:012d).").assertExists()
    }

    @Test
    fun aMissingSelectedAdapterIsExplainedInTheStatusLine() = runComposeUiTest {
        setContent { Screen(adapterState(listOf(usb(unknown)), AdapterSelection.Manual(t3u))) }

        onNodeWithText("The selected adapter, TP-Link Archer T3U (2357:012d), is not plugged in.").assertExists()
    }

    @Test
    fun theModeRowsReportTheirTaps() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            Screen(
                adapterState(listOf(usb(t3u))),
                onAdapterAutomatic = { events += "automatic" },
                onAdapterManual = { events += "manual" },
            )
        }

        onNodeWithText("Manual").performScrollTo().performClick()
        onNodeWithText("Automatic").performScrollTo().performClick()

        assertEquals(listOf("manual", "automatic"), events)
    }

    @Test
    fun manualIsDisabledWhenNoCatalogDeviceIsPlugged() = runComposeUiTest {
        setContent { Screen(adapterState(listOf(usb(unknown)))) }

        onNodeWithText("Manual").assertIsNotEnabled()
        onNodeWithText("Automatic").assertIsEnabled()
    }

    @Test
    fun inManualModeTheCatalogRowsAreRadioButtonsAndPickingOneReportsIt() = runComposeUiTest {
        val picked = mutableListOf<UsbId>()
        setContent {
            Screen(
                adapterState(
                    listOf(usb(t3u), usb(comfast, address = 4), usb(unknown, address = 5)),
                    selection = AdapterSelection.Manual(t3u),
                ),
                onAdapterPick = { picked += it },
            )
        }

        onNodeWithText("Manual").assertIsSelected()
        onNodeWithText("TP-Link Archer T3U").assertIsSelected()
        onNodeWithText("COMFAST CF-811AC").assertIsNotSelected().assertHasClickAction()
        // A device the app does not know is listed but cannot be chosen.
        onNodeWithText("Unknown USB device").assertHasNoClickAction()

        onNodeWithText("COMFAST CF-811AC").performScrollTo().performClick()

        assertEquals(listOf(comfast), picked)
    }

    @Test
    fun inAutomaticModeTheRowsAreNotInteractive() = runComposeUiTest {
        setContent { Screen(adapterState(listOf(usb(t3u)))) }

        onNodeWithText("TP-Link Archer T3U").assertHasNoClickAction()
    }

    @Test
    fun theAdapterControlsAreReadOnlyWhileTheRadioIsUpWithANote() = runComposeUiTest {
        setContent {
            Screen(
                adapterState(
                    listOf(usb(t3u), usb(comfast, address = 4)),
                    selection = AdapterSelection.Manual(t3u),
                    linkState = LinkState.Driving,
                ),
            )
        }

        onNodeWithText("Automatic").assertIsNotEnabled()
        onNodeWithText("Manual").assertIsNotEnabled()
        onNodeWithText("COMFAST CF-811AC").assertIsNotEnabled()
        onNodeWithText("Changes apply when the radio is off.").assertExists()
    }

    @Test
    fun theAdapterControlsAreEnabledWhileTheRadioIsOffWithoutTheNote() = runComposeUiTest {
        setContent { Screen(adapterState(listOf(usb(t3u), usb(comfast, address = 4)), AdapterSelection.Manual(t3u))) }

        onNodeWithText("Automatic").assertIsEnabled()
        onNodeWithText("Manual").assertIsEnabled()
        onNodeWithText("COMFAST CF-811AC").assertIsEnabled()
        onNodeWithText("Changes apply when the radio is off.").assertDoesNotExist()
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
                modules(
                    linkFeatureModule,
                    module {
                        single<KartSession> { session }
                        single<UsbAdapterRepository> { FakeUsbAdapterRepository() }
                    },
                )
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

    @Test
    fun rootShowsTheDetectedAdaptersAndSavesTheChoice() = runComposeUiTest {
        val adapters = FakeUsbAdapterRepository(listOf(usb(t3u), usb(comfast, address = 4)))
        val owner = TestHost()
        setContent {
            KoinApplication(application = {
                modules(
                    linkFeatureModule,
                    module {
                        single<KartSession> { FakeKartSession() }
                        single<UsbAdapterRepository> { adapters }
                    },
                )
            }) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner,
                ) {
                    AppTheme { LinkRoot(onNavigateToCockpit = {}, onNavigateToPairing = {}) }
                }
            }
        }

        // The list comes from the repository, automatic by default.
        onNodeWithText("TP-Link Archer T3U").assertExists()
        onNodeWithText("COMFAST CF-811AC").assertExists()
        onNodeWithText("Using TP-Link Archer T3U (2357:012d).").assertExists()

        // Manual keeps the adapter that would be used; picking another row saves it; Automatic goes back.
        onNodeWithText("Manual").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf<AdapterSelection>(AdapterSelection.Manual(t3u)), adapters.selections)
        onNodeWithText("COMFAST CF-811AC").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Automatic").performScrollTo().performClick()
        waitForIdle()
        assertEquals(
            listOf(AdapterSelection.Manual(t3u), AdapterSelection.Manual(comfast), AdapterSelection.Automatic),
            adapters.selections,
        )
        onNodeWithText("Automatic").assertIsSelected()

        owner.viewModelStore.clear()
    }
}
