package com.mklhc.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** [resolveAdapter]: which detected USB device the app will use for a selection, or why none. */
class AdapterResolverTest {

    private val t3u = UsbId(0x2357, 0x012D)
    private val c812 = UsbId(0x0BDA, 0xC812)
    private val c811 = UsbId(0x0BDA, 0xC811)
    private val au8812 = UsbId(0x0BDA, 0x8812)
    private val unknown = UsbId(0x1234, 0x5678)

    private var nextAddress = 1

    private fun device(id: UsbId, access: UsbDeviceAccess = UsbDeviceAccess.OPENABLE, bus: Int = 1) =
        DetectedUsbDevice(id, bus, nextAddress++, access)

    // Automatic mode

    @Test
    fun automaticWaitsForTheFirstScan() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NOT_SCANNED_YET),
            resolveAdapter(AdapterSelection.Automatic, null),
        )
    }

    @Test
    fun manualAlsoWaitsForTheFirstScan() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NOT_SCANNED_YET),
            resolveAdapter(AdapterSelection.Manual(t3u), null),
        )
    }

    @Test
    fun automaticWithNoDevicesHasNoCompatibleDevice() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NO_COMPATIBLE_DEVICE),
            resolveAdapter(AdapterSelection.Automatic, emptyList()),
        )
    }

    @Test
    fun automaticPicksTheOnlyCompatibleOpenableDevice() {
        val adapter = device(c812)
        assertEquals(
            AdapterResolution.Use(adapter),
            resolveAdapter(AdapterSelection.Automatic, listOf(device(unknown), adapter)),
        )
    }

    @Test
    fun automaticPrefersCatalogOrderOverScanOrder() {
        val first = device(c812)
        val preferred = device(t3u)
        assertEquals(
            AdapterResolution.Use(preferred),
            resolveAdapter(AdapterSelection.Automatic, listOf(first, preferred)),
        )
    }

    @Test
    fun aCatalogDeviceThatWasNotProbedIsNeverUsed() {
        val notProbed = device(t3u, UsbDeviceAccess.NOT_PROBED)
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, notProbed),
            resolveAdapter(AdapterSelection.Automatic, listOf(notProbed)),
        )
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, notProbed),
            resolveAdapter(AdapterSelection.Manual(t3u), listOf(notProbed)),
        )
    }

    @Test
    fun automaticSkipsACompatibleDeviceThatCannotBeOpenedForTheNextOne() {
        val blocked = device(t3u, UsbDeviceAccess.DRIVER_UNSUPPORTED)
        val usable = device(c812)
        assertEquals(
            AdapterResolution.Use(usable),
            resolveAdapter(AdapterSelection.Automatic, listOf(blocked, usable)),
        )
    }

    @Test
    fun automaticNeverPicksANotRecommendedDevice() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NO_COMPATIBLE_DEVICE),
            resolveAdapter(AdapterSelection.Automatic, listOf(device(c811), device(au8812))),
        )
    }

    @Test
    fun automaticNeverPicksAnUnknownDevice() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NO_COMPATIBLE_DEVICE),
            resolveAdapter(AdapterSelection.Automatic, listOf(device(unknown))),
        )
    }

    @Test
    fun automaticWithCompatibleDevicesNoneOpenableReportsTheFirstInCatalogOrder() {
        val t3uBlocked = device(t3u, UsbDeviceAccess.ACCESS_DENIED)
        val c812Busy = device(c812, UsbDeviceAccess.BUSY)
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, t3uBlocked),
            resolveAdapter(AdapterSelection.Automatic, listOf(c812Busy, t3uBlocked)),
        )
    }

    @Test
    fun automaticNotOpenableIgnoresNotRecommendedOpenableDevices() {
        val blocked = device(c812, UsbDeviceAccess.DRIVER_UNSUPPORTED)
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, blocked),
            resolveAdapter(AdapterSelection.Automatic, listOf(device(c811), blocked)),
        )
    }

    @Test
    fun automaticWithTwoIdenticalAdaptersPrefersTheOpenableOne() {
        val busy = device(t3u, UsbDeviceAccess.BUSY)
        val free = device(t3u)
        assertEquals(
            AdapterResolution.Use(free),
            resolveAdapter(AdapterSelection.Automatic, listOf(busy, free)),
        )
    }

    @Test
    fun automaticWithTwoOpenableIdenticalAdaptersTakesTheFirstDetected() {
        val first = device(t3u)
        val second = device(t3u)
        assertEquals(
            AdapterResolution.Use(first),
            resolveAdapter(AdapterSelection.Automatic, listOf(first, second)),
        )
    }

    // Manual mode

    @Test
    fun manualUsesTheSelectedOpenableDevice() {
        val adapter = device(t3u)
        assertEquals(
            AdapterResolution.Use(adapter),
            resolveAdapter(AdapterSelection.Manual(t3u), listOf(device(c812), adapter)),
        )
    }

    @Test
    fun manualDoesNotFallBackToAnotherCompatibleDevice() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.SELECTED_DEVICE_ABSENT),
            resolveAdapter(AdapterSelection.Manual(t3u), listOf(device(c812))),
        )
    }

    @Test
    fun manualWithNoDevicesReportsTheSelectedDeviceAbsent() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.SELECTED_DEVICE_ABSENT),
            resolveAdapter(AdapterSelection.Manual(c812), emptyList()),
        )
    }

    @Test
    fun manualAcceptsANotRecommendedDevice() {
        val adapter = device(c811)
        assertEquals(
            AdapterResolution.Use(adapter),
            resolveAdapter(AdapterSelection.Manual(c811), listOf(adapter)),
        )
    }

    @Test
    fun manualRejectsAnIdThatIsNotInTheCatalogEvenWhenPresent() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.SELECTED_DEVICE_NOT_SUPPORTED),
            resolveAdapter(AdapterSelection.Manual(unknown), listOf(device(unknown))),
        )
    }

    @Test
    fun manualRejectsAnIdThatIsNotInTheCatalogWhenAbsentToo() {
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.SELECTED_DEVICE_NOT_SUPPORTED),
            resolveAdapter(AdapterSelection.Manual(unknown), emptyList()),
        )
    }

    @Test
    fun manualPresentButNotOpenableCarriesTheDevice() {
        UsbDeviceAccess.entries.filter { it != UsbDeviceAccess.OPENABLE }.forEach { access ->
            val blocked = device(t3u, access)
            assertEquals(
                AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, blocked),
                resolveAdapter(AdapterSelection.Manual(t3u), listOf(blocked)),
                "access=$access",
            )
        }
    }

    @Test
    fun manualWithTwoIdenticalAdaptersPrefersTheOpenableOne() {
        val busy = device(t3u, UsbDeviceAccess.BUSY)
        val free = device(t3u)
        assertEquals(
            AdapterResolution.Use(free),
            resolveAdapter(AdapterSelection.Manual(t3u), listOf(busy, free)),
        )
    }

    @Test
    fun manualWithTwoIdenticalAdaptersNoneOpenableReportsTheFirst() {
        val first = device(t3u, UsbDeviceAccess.ACCESS_DENIED)
        val second = device(t3u, UsbDeviceAccess.BUSY)
        assertEquals(
            AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, first),
            resolveAdapter(AdapterSelection.Manual(t3u), listOf(first, second)),
        )
    }

    @Test
    fun theSelectedDeviceIsMatchedByIdNotByBusPosition() {
        val adapter = device(t3u, bus = 3)
        assertEquals(
            AdapterResolution.Use(adapter),
            resolveAdapter(AdapterSelection.Manual(UsbId(0x2357, 0x012D)), listOf(device(c812, bus = 1), adapter)),
        )
    }
}
