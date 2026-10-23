package com.mklhc.feature.link.presentation

import app.cash.turbine.test
import com.mklhc.core.data.radio.AdapterOverview
import com.mklhc.core.data.radio.FakeUsbAdapterRepository
import com.mklhc.core.data.radio.UsbAdapterRepository
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.presentation.UiText
import com.mklhc.core.presentation.toUiText
import com.mklhc.feature.link.presentation.generated.resources.Res
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_absent
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_busy
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_driver
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_error
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_permission
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_helper_down
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_no_compatible
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_no_devices
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_scanning
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_unsupported
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_using
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_unknown_device
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
 * [LinkViewModel] against [FakeKartSession] and [FakeUsbAdapterRepository]: the link states and the kart
 * information the session publishes flow into the screen state (text, button-enablement flags, UI models), the
 * screen's actions reach the session, and the adapter overview becomes the "Radio adapter" section (rows, pills,
 * status line) whose actions reach the repository. The main dispatcher is replaced with an unconfined test dispatcher so the ViewModel's collectors and
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
        val viewModel = LinkViewModel(FakeKartSession(), FakeUsbAdapterRepository())
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

        val state = LinkViewModel(session, FakeUsbAdapterRepository()).state.value

        assertEquals(LinkState.Driving, state.linkState)
        assertEquals(LinkState.Driving.toUiText(), state.linkStatusText)
        assertEquals("Kart 2", state.kartInfo?.characterLabel)
        assertEquals(BatteryUi(bars = 3, charging = false), state.battery)
    }

    @Test
    fun doesNotStartTheSessionByItself() {
        val session = FakeKartSession()
        LinkViewModel(session, FakeUsbAdapterRepository())
        assertFalse(session.startCalled)
        assertEquals(emptyList(), session.calls)
    }

    // ---- link state -> status text and flags -----------------------------------------------

    @Test
    fun linkStateChangesFlowIntoTheStatusText() = runTest {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

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
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

        session.setLinkState(LinkState.Failed("radio fault"))
        assertEquals(LinkState.Failed("radio fault").toUiText(), viewModel.state.value.linkStatusText)

        session.setLinkState(LinkState.Recovering("control channel closed"))
        assertEquals(LinkState.Recovering("control channel closed").toUiText(), viewModel.state.value.linkStatusText)
    }

    @Test
    fun adapterIsReadyOnceTheRadioHasBeenGrantedAccess() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

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
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

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
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

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
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

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
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

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
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

        viewModel.onStartClick()

        assertTrue(session.startCalled)
        assertEquals(1, session.startCount)
        assertEquals(listOf(FakeKartSession.Call.START), session.calls)
    }

    @Test
    fun startClickDoesNotMoveTheLinkItself() {
        // The screen only reflects what the session publishes; start() drives the session, not the state.
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

        viewModel.onStartClick()

        assertEquals(LinkState.NoAdapter, viewModel.state.value.linkState)
    }

    @Test
    fun captureToggleReachesTheSessionAndTheState() {
        val session = FakeKartSession()
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

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
        val viewModel = LinkViewModel(session, FakeUsbAdapterRepository())

        viewModel.onStartClick()
        val error = viewModel.state.value.errorMessage
        assertNotNull(error)
        assertEquals(IllegalStateException("adapter busy").toUiText(), error)

        session.failure = null
        viewModel.onStartClick()
        assertNull(viewModel.state.value.errorMessage)
    }

    // ---- the radio adapter section ---------------------------------------------------------

    private val t3u = UsbId(0x2357, 0x012D)
    private val reference = UsbId(0x0BDA, 0xC812)
    private val notRecommended = UsbId(0x0BDA, 0xC811)
    private val unknownLate = UsbId(0x1234, 0x5678)
    private val unknownEarly = UsbId(0x0001, 0x0002)

    private fun usb(id: UsbId, access: UsbDeviceAccess = UsbDeviceAccess.OPENABLE, address: Int = 2) =
        DetectedUsbDevice(id, bus = 1, address = address, access = access)

    private fun adapterViewModel(
        devices: List<DetectedUsbDevice>?,
        selection: AdapterSelection = AdapterSelection.Automatic,
        session: FakeKartSession = FakeKartSession(),
    ): Pair<LinkViewModel, FakeUsbAdapterRepository> {
        val adapters = FakeUsbAdapterRepository(devices, selection)
        return LinkViewModel(session, adapters) to adapters
    }

    private fun LinkViewModel.row(id: UsbId): AdapterDeviceUi = state.value.adapter.devices.first { it.id == id }

    @Test
    fun beforeTheFirstScanTheSectionIsEmptyAndSaysItIsLooking() {
        val (viewModel, _) = adapterViewModel(devices = null)

        val adapter = viewModel.state.value.adapter
        assertEquals(AdapterMode.AUTOMATIC, adapter.mode)
        assertTrue(adapter.devices.isEmpty())
        assertEquals(UiText.StringResource(Res.string.link_adapter_status_scanning), adapter.status)
        assertFalse(adapter.manualAvailable)
    }

    @Test
    fun anEmptyScannedListSaysNoDevicesWereFound() {
        val (viewModel, _) = adapterViewModel(devices = emptyList())

        val adapter = viewModel.state.value.adapter
        assertTrue(adapter.devices.isEmpty())
        assertEquals(UiText.StringResource(Res.string.link_adapter_status_no_devices), adapter.status)
        assertFalse(adapter.manualAvailable)
    }

    @Test
    fun theListIsSortedCatalogFirstInCatalogOrderThenUnknownDevicesById() {
        val (viewModel, _) = adapterViewModel(
            listOf(
                usb(unknownLate, address = 5),
                usb(notRecommended, address = 4),
                usb(unknownEarly, address = 6),
                usb(reference, address = 3),
                usb(t3u, address = 2),
            ),
        )

        assertEquals(
            listOf(t3u, reference, notRecommended, unknownEarly, unknownLate),
            viewModel.state.value.adapter.devices.map { it.id },
        )
    }

    @Test
    fun aKnownDeviceShowsItsCatalogNameIdAndChip() {
        val (viewModel, _) = adapterViewModel(listOf(usb(t3u)))

        val row = viewModel.row(t3u)
        assertEquals(UiText.DynamicString("TP-Link Archer T3U"), row.name)
        assertEquals("2357:012d", row.idText)
        assertEquals("RTL8822BU", row.chip)
    }

    @Test
    fun anUnknownDeviceShowsAGenericNameItsIdAndNoChipOrSupportPill() {
        val (viewModel, _) = adapterViewModel(listOf(usb(unknownLate)))

        val row = viewModel.row(unknownLate)
        assertEquals(UiText.StringResource(Res.string.link_adapter_unknown_device), row.name)
        assertEquals("1234:5678", row.idText)
        assertNull(row.chip)
        assertEquals(emptyList(), row.pills)
    }

    @Test
    fun supportAndAccessPillsFollowTheDevice() {
        val (viewModel, _) = adapterViewModel(
            listOf(
                usb(t3u),
                usb(reference, UsbDeviceAccess.DRIVER_UNSUPPORTED),
                usb(notRecommended, UsbDeviceAccess.ACCESS_DENIED),
                usb(unknownEarly, UsbDeviceAccess.BUSY),
                usb(unknownLate, UsbDeviceAccess.ERROR),
            ),
        )

        // Automatic: the first compatible adapter that opens is the one that will be used.
        assertEquals(listOf(AdapterPill.COMPATIBLE, AdapterPill.WILL_BE_USED), viewModel.row(t3u).pills)
        assertEquals(listOf(AdapterPill.COMPATIBLE, AdapterPill.NEEDS_DRIVER), viewModel.row(reference).pills)
        assertEquals(listOf(AdapterPill.NOT_RECOMMENDED, AdapterPill.NO_PERMISSION), viewModel.row(notRecommended).pills)
        assertEquals(listOf(AdapterPill.IN_USE), viewModel.row(unknownEarly).pills)
        assertEquals(listOf(AdapterPill.CANNOT_BE_OPENED), viewModel.row(unknownLate).pills)
    }

    @Test
    fun anUnscannedAccessStatusReadsAsCannotBeOpened() {
        val (viewModel, _) = adapterViewModel(listOf(usb(unknownLate, UsbDeviceAccess.UNKNOWN)))

        assertEquals(listOf(AdapterPill.CANNOT_BE_OPENED), viewModel.row(unknownLate).pills)
    }

    @Test
    fun theAdapterTheRadioHasOpenShowsInUseInsteadOfAnAccessPill() {
        // The scan reports the adapter the radio holds as busy; the screen must say "in use", once.
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u, UsbDeviceAccess.BUSY), usb(reference)))

        adapters.radioActive(t3u)

        assertEquals(listOf(AdapterPill.COMPATIBLE, AdapterPill.IN_USE), viewModel.row(t3u).pills)
        assertEquals(listOf(AdapterPill.COMPATIBLE), viewModel.row(reference).pills)
    }

    @Test
    fun anOpenableAdapterInUseStillShowsInUseAndNotWillBeUsedTwice() {
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u)))

        adapters.radioActive(t3u)

        assertEquals(listOf(AdapterPill.COMPATIBLE, AdapterPill.IN_USE), viewModel.row(t3u).pills)
    }

    @Test
    fun whileTheRadioIsUpTheStatusLineNamesTheAdapterInUse() {
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u, UsbDeviceAccess.BUSY)))

        adapters.radioActive(t3u)

        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_using, listOf("TP-Link Archer T3U", "2357:012d")),
            viewModel.state.value.adapter.status,
        )
    }

    @Test
    fun inManualModeOnlyCatalogRowsAreSelectableAndTheChosenOneIsSelected() {
        val (viewModel, _) = adapterViewModel(
            listOf(usb(t3u), usb(notRecommended), usb(unknownLate)),
            selection = AdapterSelection.Manual(notRecommended),
        )

        val adapter = viewModel.state.value.adapter
        assertEquals(AdapterMode.MANUAL, adapter.mode)
        assertEquals(listOf(true, true, false), adapter.devices.map { it.selectable })
        assertEquals(listOf(false, true, false), adapter.devices.map { it.selected })
        assertEquals(listOf(AdapterPill.NOT_RECOMMENDED, AdapterPill.WILL_BE_USED), viewModel.row(notRecommended).pills)
        assertEquals(listOf(AdapterPill.COMPATIBLE), viewModel.row(t3u).pills)
    }

    @Test
    fun inAutomaticModeNoRowIsSelectable() {
        val (viewModel, _) = adapterViewModel(listOf(usb(t3u), usb(unknownLate)))

        assertEquals(listOf(false, false), viewModel.state.value.adapter.devices.map { it.selectable })
        assertEquals(listOf(false, false), viewModel.state.value.adapter.devices.map { it.selected })
    }

    @Test
    fun manualIsOfferedOnlyWithACatalogDeviceOrWhenAlreadyManual() {
        val (onlyUnknown, _) = adapterViewModel(listOf(usb(unknownLate)))
        assertFalse(onlyUnknown.state.value.adapter.manualAvailable)

        val (withCatalog, _) = adapterViewModel(listOf(usb(unknownLate), usb(notRecommended)))
        assertTrue(withCatalog.state.value.adapter.manualAvailable)

        // Already manual with nothing plugged in: the row stays enabled so the state is never a dead end.
        val (manualEmpty, _) = adapterViewModel(emptyList(), AdapterSelection.Manual(t3u))
        assertTrue(manualEmpty.state.value.adapter.manualAvailable)
    }

    @Test
    fun theStatusLineFollowsTheResolution() {
        fun statusOf(devices: List<DetectedUsbDevice>, selection: AdapterSelection = AdapterSelection.Automatic) =
            adapterViewModel(devices, selection).first.state.value.adapter.status

        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_no_compatible),
            statusOf(listOf(usb(notRecommended), usb(unknownLate))),
        )
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_absent, listOf("TP-Link Archer T3U", "2357:012d")),
            statusOf(listOf(usb(unknownLate)), AdapterSelection.Manual(t3u)),
        )
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_unsupported),
            statusOf(listOf(usb(unknownLate)), AdapterSelection.Manual(unknownLate)),
        )
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_using, listOf("TP-Link Archer T3U", "2357:012d")),
            statusOf(listOf(usb(t3u))),
        )
    }

    @Test
    fun whileTheRadioHelperIsNotConnectedTheStatusLineSaysSoInsteadOfLooking() {
        val helperDown = UiText.StringResource(Res.string.link_adapter_status_helper_down)

        val noScan = AdapterOverview.of(devices = null, selection = AdapterSelection.Automatic, inUse = null, helperConnected = false)
        assertEquals(helperDown, noScan.toAdapterSectionUi().status)

        // A stale list kept while the helper is away does not change the sentence.
        val stale = AdapterOverview.of(listOf(usb(t3u)), AdapterSelection.Automatic, inUse = null, helperConnected = false)
        assertEquals(helperDown, stale.toAdapterSectionUi().status)

        // Before anything connected, the section starts at "helper not running", not at "looking".
        assertEquals(helperDown, AdapterSectionUi.INITIAL.status)

        // Connected and not scanned yet is the only "looking" case.
        val looking = AdapterOverview.of(devices = null, selection = AdapterSelection.Automatic, inUse = null, helperConnected = true)
        assertEquals(UiText.StringResource(Res.string.link_adapter_status_scanning), looking.toAdapterSectionUi().status)
    }

    @Test
    fun twoAdaptersOfTheSameIdAreTwoRowsToldApartByBusAndAddress() {
        val (viewModel, _) = adapterViewModel(listOf(usb(t3u, address = 7), usb(t3u, address = 3)))

        val rows = viewModel.state.value.adapter.devices
        assertEquals(listOf(3, 7), rows.map { it.address })
        assertEquals(2, rows.map { Triple(it.id, it.bus, it.address) }.toSet().size)
    }

    @Test
    fun aDeviceThatCannotBeOpenedGivesAStatusWithItsReason() {
        fun statusFor(access: UsbDeviceAccess) =
            adapterViewModel(listOf(usb(t3u, access))).first.state.value.adapter.status

        val name = listOf("TP-Link Archer T3U")
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_cannot_open_driver, name),
            statusFor(UsbDeviceAccess.DRIVER_UNSUPPORTED),
        )
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_cannot_open_permission, name),
            statusFor(UsbDeviceAccess.ACCESS_DENIED),
        )
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_cannot_open_busy, name),
            statusFor(UsbDeviceAccess.BUSY),
        )
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_cannot_open_error, name),
            statusFor(UsbDeviceAccess.ERROR),
        )
        assertEquals(
            UiText.StringResource(Res.string.link_adapter_status_cannot_open_error, name),
            statusFor(UsbDeviceAccess.UNKNOWN),
        )
    }

    @Test
    fun theSectionFollowsTheRepository() {
        val (viewModel, adapters) = adapterViewModel(devices = null)
        assertTrue(viewModel.state.value.adapter.devices.isEmpty())

        adapters.setDevices(listOf(usb(t3u)))

        assertEquals(listOf(t3u), viewModel.state.value.adapter.devices.map { it.id })
        assertEquals(listOf(AdapterPill.COMPATIBLE, AdapterPill.WILL_BE_USED), viewModel.row(t3u).pills)
    }

    @Test
    fun theSectionIsReadyFromTheRepositorysCurrentValue() {
        val adapters = FakeUsbAdapterRepository(listOf(usb(t3u)), AdapterSelection.Manual(t3u))

        val state = LinkViewModel(FakeKartSession(), adapters).state.value

        assertEquals(AdapterMode.MANUAL, state.adapter.mode)
        assertEquals(listOf(t3u), state.adapter.devices.map { it.id })
    }

    // ---- the adapter actions ---------------------------------------------------------------

    @Test
    fun automaticSavesTheAutomaticSelection() {
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u)), AdapterSelection.Manual(t3u))

        viewModel.onAdapterAutomatic()

        assertEquals(listOf<AdapterSelection>(AdapterSelection.Automatic), adapters.selections)
        assertEquals(AdapterMode.AUTOMATIC, viewModel.state.value.adapter.mode)
    }

    @Test
    fun pickingACatalogDeviceSavesItAsTheManualSelection() {
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u), usb(notRecommended)), AdapterSelection.Manual(t3u))

        viewModel.onAdapterPick(notRecommended)

        assertEquals(listOf<AdapterSelection>(AdapterSelection.Manual(notRecommended)), adapters.selections)
        assertTrue(viewModel.row(notRecommended).selected)
    }

    @Test
    fun anUnknownDeviceCannotBePicked() {
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u), usb(unknownLate)))

        viewModel.onAdapterPick(unknownLate)

        assertEquals(emptyList(), adapters.selections)
    }

    @Test
    fun manualKeepsTheAdapterInUse() {
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u), usb(reference, address = 3)))
        adapters.radioActive(reference)

        viewModel.onAdapterManual()

        assertEquals(listOf<AdapterSelection>(AdapterSelection.Manual(reference)), adapters.selections)
    }

    @Test
    fun manualKeepsTheAdapterAutomaticWouldUse() {
        // The T3U cannot be opened, so automatic would use the reference adapter: manual starts from that one.
        val (viewModel, adapters) = adapterViewModel(
            listOf(usb(t3u, UsbDeviceAccess.DRIVER_UNSUPPORTED), usb(reference, address = 3)),
        )

        viewModel.onAdapterManual()

        assertEquals(listOf<AdapterSelection>(AdapterSelection.Manual(reference)), adapters.selections)
    }

    @Test
    fun manualFallsBackToTheFirstCatalogDeviceOfTheList() {
        // Nothing is usable, so there is neither an adapter in use nor one resolved: the first catalog row wins.
        val (viewModel, adapters) = adapterViewModel(
            listOf(usb(unknownEarly), usb(notRecommended, UsbDeviceAccess.ACCESS_DENIED), usb(reference, UsbDeviceAccess.ERROR)),
        )

        viewModel.onAdapterManual()

        assertEquals(listOf<AdapterSelection>(AdapterSelection.Manual(reference)), adapters.selections)
    }

    @Test
    fun manualDoesNothingWhenTheListHoldsNoCatalogDevice() {
        val (empty, emptyAdapters) = adapterViewModel(emptyList())
        empty.onAdapterManual()
        assertEquals(emptyList(), emptyAdapters.selections)

        val (unknownOnly, unknownAdapters) = adapterViewModel(listOf(usb(unknownLate)))
        unknownOnly.onAdapterManual()
        assertEquals(emptyList(), unknownAdapters.selections)

        val (unscanned, unscannedAdapters) = adapterViewModel(devices = null)
        unscanned.onAdapterManual()
        assertEquals(emptyList(), unscannedAdapters.selections)
    }

    @Test
    fun manualDoesNotReplaceAnExistingManualChoice() {
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u), usb(reference, address = 3)), AdapterSelection.Manual(reference))

        viewModel.onAdapterManual()

        assertEquals(emptyList(), adapters.selections)
    }

    @Test
    fun theAdapterControlsAreOnlyAvailableWhileTheRadioIsOff() {
        val session = FakeKartSession()
        val (viewModel, adapters) = adapterViewModel(listOf(usb(t3u), usb(reference, address = 3)), session = session)

        for (idle in listOf(LinkState.NoAdapter, LinkState.Failed("radio fault"))) {
            session.setLinkState(idle)
            assertTrue(viewModel.state.value.canChangeAdapter, "the adapter can be changed in $idle")
        }
        for (running in listOf(LinkState.RadioStarting, LinkState.KartReady, LinkState.Driving, LinkState.Recovering("link lost"))) {
            session.setLinkState(running)
            assertFalse(viewModel.state.value.canChangeAdapter, "the adapter is read-only in $running")
            viewModel.onAdapterAutomatic()
            viewModel.onAdapterManual()
            viewModel.onAdapterPick(reference)
        }

        assertEquals(emptyList(), adapters.selections)
    }

    /** A repository whose setSelection fails, to exercise the error path; everything else is the fake. */
    private class FailingSelectionRepository(
        private val delegate: FakeUsbAdapterRepository,
    ) : UsbAdapterRepository by delegate {
        var failure: Exception? = IllegalStateException("store unavailable")

        override suspend fun setSelection(selection: AdapterSelection) {
            failure?.let { throw it }
            delegate.setSelection(selection)
        }
    }

    @Test
    fun aFailingSelectionShowsTheErrorAndALaterActionClearsIt() {
        val adapters = FailingSelectionRepository(FakeUsbAdapterRepository(listOf(usb(t3u))))
        val viewModel = LinkViewModel(FakeKartSession(), adapters)

        viewModel.onAdapterAutomatic()
        assertEquals(IllegalStateException("store unavailable").toUiText(), viewModel.state.value.errorMessage)

        adapters.failure = null
        viewModel.onAdapterAutomatic()
        assertNull(viewModel.state.value.errorMessage)
    }
}
