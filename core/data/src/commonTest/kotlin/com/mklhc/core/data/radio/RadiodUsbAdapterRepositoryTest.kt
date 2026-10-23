package com.mklhc.core.data.radio

import co.touchlab.kermit.Severity
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.CapturingLogWriter
import com.mklhc.core.data.capturingLogger
import com.mklhc.core.data.store.InMemoryPairingStore
import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.UnavailableReason
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import com.mklhc.core.radio.FakeRadioLink
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.radio.UsbAccess
import com.mklhc.core.radio.UsbDeviceEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RadiodUsbAdapterRepository] over a [FakeRadioLink] that plays radiod's LIST_DEVICES answers, an in-memory pairing
 * store and a connection flag the test drives, all under virtual time.
 *
 * Business logic: the tests pin when the list is refreshed (when radiod connects, every scan interval while it is
 * connected and the radio is down, right before each bring-up, never while the radio is up or being brought up), what
 * a scan publishes (the devices with their access mapped, the selection and its resolution), what a bring-up gets (the
 * resolved adapter, or an exception naming why there is none), that a selection is persisted and resolved again at
 * once, that a scan radiod does not answer keeps the previous list with a warning, that an unchanged list is not logged
 * again, that a radiod that goes away takes the list with it (unless the radio holds the adapter), that two scans never
 * overlap and a periodic scan queued behind a bring-up is skipped, and that a late answer is never taken for the next
 * scan's.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent / advanceTimeBy drive the scan loop under virtual time
class RadiodUsbAdapterRepositoryTest {

    // ---- when the list is refreshed --------------------------------------------------------------

    @Test
    fun nothingIsScannedBeforeRadiodConnectsAndTheOverviewSaysSo() = runTest {
        val rig = Rig(this)
        runCurrent()

        assertTrue(rig.radio.sentMessages.isEmpty(), "radiod cannot answer yet")
        val overview = rig.repository.overview.value
        assertNull(overview.devices)
        assertEquals(AdapterSelection.Automatic, overview.selection)
        assertEquals(AdapterResolution.Unavailable(UnavailableReason.NOT_SCANNED_YET), overview.resolution)
        assertNull(overview.inUse)
        assertFalse(overview.helperConnected, "the screen can say radiod is not running")
    }

    @Test
    fun aScanRunsWhenRadiodConnectsAndPublishesTheDevicesAndTheirResolution() = runTest {
        val rig = Rig(this)

        rig.connect()

        assertEquals(1, rig.scans(), "one LIST_DEVICES as soon as radiod is connected")
        val overview = rig.repository.overview.value
        assertEquals(listOf(T3U_DEVICE, MOUSE_DEVICE), overview.devices)
        assertEquals(AdapterResolution.Use(T3U_DEVICE), overview.resolution)
        assertTrue(overview.helperConnected)
    }

    @Test
    fun aDisconnectStopsTheScansCancelsTheOneInFlightAndClearsTheListUntilRadiodIsBack() = runTest {
        val rig = Rig(this, selection = AdapterSelection.Manual(T3U))
        rig.connect()
        rig.radio.deviceListReply = null // the next scan stays unanswered
        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(2, rig.scans())
        val inFlight = rig.lastRequestId()

        rig.connection.value = false
        runCurrent()

        val away = rig.repository.overview.value
        assertNull(away.devices, "no stale list while radiod is away")
        assertFalse(away.helperConnected)
        assertEquals(AdapterSelection.Manual(T3U), away.selection, "the selection is kept")
        assertEquals(AdapterResolution.Unavailable(UnavailableReason.NOT_SCANNED_YET), away.resolution)
        rig.radio.emitEvent(RadioMessage.DeviceList(inFlight, listOf(T3U_ENTRY)))
        runCurrent()
        assertNull(rig.repository.overview.value.devices, "the scan in flight was cancelled: its answer is not taken")
        advanceTimeBy(SCAN_INTERVAL_MS * 5)
        runCurrent()
        assertEquals(2, rig.scans(), "no scan while radiod is away")
        assertTrue(rig.logs.entries.none { it.severity == Severity.Warn }, "a cancelled scan is no timeout: ${rig.logs.allText()}")

        rig.radio.deviceListReply = listOf(T3U_ENTRY)
        rig.connect()
        assertEquals(3, rig.scans(), "a reconnect scans at once")
        assertEquals(listOf(T3U_DEVICE), rig.repository.overview.value.devices)
        assertTrue(rig.repository.overview.value.helperConnected)
        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(4, rig.scans(), "and periodically again")
    }

    @Test
    fun aDisconnectWhileTheRadioHoldsTheAdapterKeepsTheList() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.repository.radioActive(T3U)

        rig.connection.value = false
        runCurrent()

        val overview = rig.repository.overview.value
        assertEquals(listOf(T3U_DEVICE, MOUSE_DEVICE), overview.devices, "the radio's bring-up redo will scan the new radiod")
        assertEquals(T3U, overview.inUse)
        assertFalse(overview.helperConnected)

        rig.repository.radioActive(null) // the radio stops while radiod is still away
        runCurrent()
        assertNull(rig.repository.overview.value.devices, "now nothing holds the list")
    }

    @Test
    fun theListIsScannedAgainEveryIntervalWhileRadiodIsConnected() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.radio.deviceListReply = emptyList()

        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(2, rig.scans())
        assertEquals(emptyList(), rig.repository.overview.value.devices, "the adapter was unplugged")
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NO_COMPATIBLE_DEVICE),
            rig.repository.overview.value.resolution,
        )

        rig.radio.deviceListReply = listOf(T3U_ENTRY)
        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(3, rig.scans())
        assertEquals(AdapterResolution.Use(T3U_DEVICE), rig.repository.overview.value.resolution, "plugged in again")

        rig.connection.value = false
        runCurrent()
        advanceTimeBy(SCAN_INTERVAL_MS * 5)
        runCurrent()
        assertEquals(3, rig.scans(), "no scan while radiod is away")
    }

    @Test
    fun noPeriodicScanRunsWhileTheRadioIsUpAndTheAdapterIsMarkedInUse() = runTest {
        val rig = Rig(this)
        rig.connect()

        rig.repository.radioActive(T3U)
        runCurrent()
        advanceTimeBy(SCAN_INTERVAL_MS * 10)
        runCurrent()

        assertEquals(1, rig.scans(), "radiod's own radio holds the adapter: a scan would only say BUSY")
        assertEquals(T3U, rig.repository.overview.value.inUse)
        assertEquals(AdapterResolution.Use(T3U_DEVICE), rig.repository.overview.value.resolution, "the last answer stands")

        rig.repository.radioActive(null)
        runCurrent()
        assertEquals(2, rig.scans(), "the scans resume at once when the radio stops")
        assertNull(rig.repository.overview.value.inUse)
    }

    // ---- the bring-up ----------------------------------------------------------------------------

    @Test
    fun resolveForBringUpScansAfreshAndReturnsTheResolvedAdapter() = runTest {
        val rig = Rig(this)
        rig.radio.deviceListReply = emptyList()
        rig.connect()
        rig.radio.deviceListReply = listOf(C812_ENTRY)

        val adapter = rig.repository.resolveForBringUp()

        assertEquals(C812, adapter, "the adapter plugged in since the last scan is found")
        assertEquals(2, rig.scans())
        assertEquals(AdapterResolution.Use(C812_DEVICE), rig.repository.overview.value.resolution)
    }

    @Test
    fun noPeriodicScanRunsBetweenTheResolutionAndTheEndOfTheBringUp() = runTest {
        val rig = Rig(this)
        rig.connect()

        rig.repository.resolveForBringUp()
        advanceTimeBy(SCAN_INTERVAL_MS * 10)
        runCurrent()
        assertEquals(2, rig.scans(), "radiod is opening the adapter: a scan would report it BUSY")
        assertNull(rig.repository.overview.value.inUse, "not in use until the radio is up")

        rig.repository.radioActive(null) // the bring-up failed
        runCurrent()
        assertEquals(3, rig.scans(), "the scans resume")
    }

    @Test
    fun resolveForBringUpFailsNamingTheReasonWhenNoAdapterCanBeUsed() = runTest {
        val rig = Rig(this)
        rig.radio.deviceListReply = listOf(MOUSE_ENTRY)
        rig.connect()

        val none = assertFailsWith<AdapterUnavailableException> { rig.repository.resolveForBringUp() }
        assertEquals(UnavailableReason.NO_COMPATIBLE_DEVICE, none.reason)
        assertTrue("NO_COMPATIBLE_DEVICE" in none.message.orEmpty(), none.message)

        rig.radio.deviceListReply = listOf(C812_ENTRY.copy(access = UsbAccess.DRIVER_UNSUPPORTED))
        val blocked = assertFailsWith<AdapterUnavailableException> { rig.repository.resolveForBringUp() }
        assertEquals(UnavailableReason.NOT_OPENABLE, blocked.reason)
        assertTrue("0bda:c812" in blocked.message.orEmpty(), "the message names the device: ${blocked.message}")
        assertTrue("DRIVER_UNSUPPORTED" in blocked.message.orEmpty(), blocked.message)

        val scans = rig.scans()
        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(scans + 1, rig.scans(), "a failed resolution does not pause the scans")
    }

    @Test
    fun resolveForBringUpUsesTheStoredSelection() = runTest {
        val rig = Rig(this, devices = listOf(T3U_ENTRY, C811_ENTRY))
        rig.connect()
        rig.store.setAdapterSelection(AdapterSelection.Manual(C811)) // written behind the repository's back

        assertEquals(C811, rig.repository.resolveForBringUp())
    }

    // ---- the selection ---------------------------------------------------------------------------

    @Test
    fun theStoredSelectionIsLoaded() = runTest {
        val rig = Rig(this, selection = AdapterSelection.Manual(C811), devices = listOf(T3U_ENTRY, C811_ENTRY))

        rig.connect()

        assertEquals(AdapterSelection.Manual(C811), rig.repository.overview.value.selection)
        assertEquals(AdapterResolution.Use(C811_DEVICE), rig.repository.overview.value.resolution)
    }

    @Test
    fun setSelectionPersistsItAndResolvesAgainAtOnceWithoutAScan() = runTest {
        val rig = Rig(this, devices = listOf(T3U_ENTRY, C811_ENTRY))
        rig.connect()

        rig.repository.setSelection(AdapterSelection.Manual(C811))

        assertEquals(AdapterSelection.Manual(C811), rig.store.getAdapterSelection(), "persisted")
        assertEquals(AdapterSelection.Manual(C811), rig.repository.overview.value.selection)
        assertEquals(AdapterResolution.Use(C811_DEVICE), rig.repository.overview.value.resolution, "resolved at once")
        assertEquals(1, rig.scans(), "against the devices already known")

        rig.repository.setSelection(AdapterSelection.Manual(C812))
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.SELECTED_DEVICE_ABSENT),
            rig.repository.overview.value.resolution,
            "a manual choice is never swapped for another adapter",
        )
    }

    // ---- scans that go wrong, and the log --------------------------------------------------------

    @Test
    fun aScanRadiodDoesNotAnswerKeepsThePreviousDevicesWithAWarning() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.radio.deviceListReply = null // radiod stays silent

        advanceTimeBy(SCAN_INTERVAL_MS + SCAN_TIMEOUT_MS)
        runCurrent()

        assertEquals(listOf(T3U_DEVICE, MOUSE_DEVICE), rig.repository.overview.value.devices)
        val warning = rig.logs.entries.single { it.severity == Severity.Warn }
        assertTrue("keeping the previous list" in warning.message && "timeoutMs=$SCAN_TIMEOUT_MS" in warning.message, warning.message)
    }

    @Test
    fun aScanThatCannotBeSentKeepsThePreviousDevicesWithAWarning() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.radio.failure = IllegalStateException("radiod is not connected")

        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf(T3U_DEVICE, MOUSE_DEVICE), rig.repository.overview.value.devices)
        assertTrue(rig.logs.entries.any { it.severity == Severity.Warn && "radiod is not connected" in it.message }, rig.logs.allText())
    }

    @Test
    fun aChangedListIsLoggedOnceAtInfoAndAnUnchangedOneNotAgain() = runTest {
        val rig = Rig(this)
        rig.connect()
        advanceTimeBy(SCAN_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(4, rig.scans())

        assertEquals(1, rig.changeLines(), "four scans of the same list, one line: ${rig.logs.allText()}")
        val line = rig.logs.entries.first { "device list changed" in it.message }.message
        assertTrue("2357:012d" in line && "OPENABLE" in line, line)

        rig.radio.deviceListReply = listOf(MOUSE_ENTRY)
        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(2, rig.changeLines())
    }

    @Test
    fun everyAccessRadiodReportsIsMapped() = runTest {
        val accesses = UsbAccess.entries
        val rig = Rig(this, devices = accesses.mapIndexed { index, access -> MOUSE_ENTRY.copy(address = index + 1, access = access) })

        rig.connect()

        assertEquals(
            listOf(
                UsbDeviceAccess.OPENABLE,
                UsbDeviceAccess.DRIVER_UNSUPPORTED,
                UsbDeviceAccess.ACCESS_DENIED,
                UsbDeviceAccess.BUSY,
                UsbDeviceAccess.ERROR,
                UsbDeviceAccess.NOT_PROBED,
                UsbDeviceAccess.UNKNOWN,
            ),
            rig.repository.overview.value.devices?.map { it.access },
        )
    }

    @Test
    fun twoScansNeverOverlap() = runTest {
        // radiod is played by hand here, answering each LIST_DEVICES with its own request id.
        val rig = Rig(this, devices = null)
        rig.connect()
        assertEquals(1, rig.scans(), "the scan on connect waits for its answer")

        var resolved: UsbId? = null
        backgroundScope.launch { resolved = rig.repository.resolveForBringUp() }
        runCurrent()
        assertEquals(1, rig.scans(), "the bring-up's scan waits for the first one to end")

        rig.radio.emitEvent(RadioMessage.DeviceList(rig.lastRequestId(), listOf(MOUSE_ENTRY)))
        runCurrent()
        assertEquals(2, rig.scans(), "and is sent once it has")
        assertNull(resolved)

        rig.radio.emitEvent(RadioMessage.DeviceList(rig.lastRequestId(), listOf(T3U_ENTRY)))
        runCurrent()
        assertEquals(T3U, resolved, "the bring-up got the answer to its own request")
    }

    @Test
    fun aPeriodicScanQueuedBehindTheBringUpIsSkipped() = runTest {
        val rig = Rig(this, devices = null)
        rig.connect()
        rig.radio.emitEvent(RadioMessage.DeviceList(rig.lastRequestId(), listOf(T3U_ENTRY)))
        runCurrent()
        assertEquals(1, rig.scans())

        // The bring-up's scan is in flight when the next periodic scan is due: that one queues on the lock.
        var resolved: UsbId? = null
        backgroundScope.launch { resolved = rig.repository.resolveForBringUp() }
        runCurrent()
        assertEquals(2, rig.scans())
        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(2, rig.scans(), "the periodic scan waits behind the bring-up's")

        rig.radio.emitEvent(RadioMessage.DeviceList(rig.lastRequestId(), listOf(T3U_ENTRY)))
        runCurrent()

        assertEquals(T3U, resolved)
        assertEquals(2, rig.scans(), "radiod is opening the adapter: the queued scan does not run")
        assertTrue(rig.logs.allText().contains("runPeriodicScans(): the radio holds the adapter, scan skipped"), rig.logs.allText())
    }

    @Test
    fun aLateAnswerToAnOldRequestDoesNotAnswerTheNextScan() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.radio.deviceListReply = null
        advanceTimeBy(SCAN_INTERVAL_MS + SCAN_TIMEOUT_MS)
        runCurrent()
        val timedOut = rig.lastRequestId()
        advanceTimeBy(SCAN_INTERVAL_MS)
        runCurrent()
        assertEquals(3, rig.scans(), "the next scan is sent after the timeout and the interval")

        rig.radio.emitEvent(RadioMessage.DeviceList(timedOut, listOf(MOUSE_ENTRY)))
        runCurrent()
        assertEquals(listOf(T3U_DEVICE, MOUSE_DEVICE), rig.repository.overview.value.devices, "the late answer is ignored")

        rig.radio.emitEvent(RadioMessage.DeviceList(rig.lastRequestId(), listOf(C812_ENTRY)))
        runCurrent()
        assertEquals(listOf(C812_DEVICE), rig.repository.overview.value.devices, "the answer to the scan in flight is taken")
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The repository over a fake radiod answering with [devices] (nothing when null), with a store at [selection]. */
    private class Rig(
        test: TestScope,
        selection: AdapterSelection = AdapterSelection.Automatic,
        devices: List<UsbDeviceEntry>? = listOf(T3U_ENTRY, MOUSE_ENTRY),
    ) {
        private val testScope = test
        val radio = FakeRadioLink().also { it.deviceListReply = devices }
        val connection = MutableStateFlow(false)
        val logs = CapturingLogWriter()
        val store = InMemoryPairingStore(CryptoPrimitives(), adapterSelection = selection)
        val repository = RadiodUsbAdapterRepository(
            link = radio,
            store = store,
            scope = test.backgroundScope,
            radiodConnected = connection,
            scanIntervalMs = SCAN_INTERVAL_MS,
            scanTimeoutMs = SCAN_TIMEOUT_MS,
            log = capturingLogger(logs, "RadiodUsbAdapterRepository"),
        )

        /** Plays radiod connecting, and lets the scan it triggers run. */
        fun connect() {
            connection.value = true
            testScope.runCurrent()
        }

        /** How many LIST_DEVICES radiod received. */
        fun scans(): Int = radio.sentOfType<RadioMessage.ListDevices>().size

        /** The request id of the latest LIST_DEVICES, which radiod's DEVICE_LIST echoes. */
        fun lastRequestId(): UInt = radio.sentOfType<RadioMessage.ListDevices>().last().requestId

        /** How many "device list changed" lines were logged. */
        fun changeLines(): Int = logs.entries.count { it.severity == Severity.Info && "device list changed" in it.message }
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 2_000L
        const val SCAN_TIMEOUT_MS = 3_000L

        val T3U = UsbId(0x2357, 0x012D)
        val C812 = UsbId(0x0BDA, 0xC812)
        val C811 = UsbId(0x0BDA, 0xC811)

        val T3U_ENTRY = UsbDeviceEntry(vid = 0x2357, pid = 0x012D, bus = 1, address = 4, deviceClass = 0, access = UsbAccess.OPENABLE)
        val C812_ENTRY = UsbDeviceEntry(vid = 0x0BDA, pid = 0xC812, bus = 2, address = 3, deviceClass = 0, access = UsbAccess.OPENABLE)
        val C811_ENTRY = UsbDeviceEntry(vid = 0x0BDA, pid = 0xC811, bus = 1, address = 6, deviceClass = 0, access = UsbAccess.OPENABLE)
        val MOUSE_ENTRY = UsbDeviceEntry(vid = 0x046D, pid = 0xC077, bus = 1, address = 2, deviceClass = 0, access = UsbAccess.DRIVER_UNSUPPORTED)

        val T3U_DEVICE = DetectedUsbDevice(T3U, bus = 1, address = 4, access = UsbDeviceAccess.OPENABLE)
        val C812_DEVICE = DetectedUsbDevice(C812, bus = 2, address = 3, access = UsbDeviceAccess.OPENABLE)
        val C811_DEVICE = DetectedUsbDevice(C811, bus = 1, address = 6, access = UsbDeviceAccess.OPENABLE)
        val MOUSE_DEVICE = DetectedUsbDevice(UsbId(0x046D, 0xC077), bus = 1, address = 2, access = UsbDeviceAccess.DRIVER_UNSUPPORTED)
    }
}
