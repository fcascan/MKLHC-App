package com.mklhc.core.data.radio

import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.AdapterSupport
import com.mklhc.core.domain.UnavailableReason
import com.mklhc.core.domain.UsbAdapterCatalog
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DemoUsbAdapterRepository]: a believable fixed list (a compatible adapter that opens, a not recommended one that needs
 * the driver, a device the app does not know) that resolves, selects and reports like the real repository.
 */
class DemoUsbAdapterRepositoryTest {

    @Test
    fun theDemoListShowsEachKindOfDeviceAndTheAutomaticModePicksTheCompatibleOne() {
        val overview = DemoUsbAdapterRepository().overview.value
        val devices = checkNotNull(overview.devices)

        val compatible = devices.single { UsbAdapterCatalog.find(it.id)?.support == AdapterSupport.COMPATIBLE }
        assertEquals(UsbDeviceAccess.OPENABLE, compatible.access)
        val notRecommended = devices.single { UsbAdapterCatalog.find(it.id)?.support == AdapterSupport.NOT_RECOMMENDED }
        assertEquals(UsbDeviceAccess.DRIVER_UNSUPPORTED, notRecommended.access, "it needs the WinUSB driver")
        assertTrue(devices.any { UsbAdapterCatalog.find(it.id) == null }, "an unknown device is listed too")
        assertEquals(AdapterSelection.Automatic, overview.selection)
        assertEquals(AdapterResolution.Use(compatible), overview.resolution)
        assertNull(overview.inUse)
    }

    @Test
    fun aSelectionIsResolvedAtOnceAndABringUpGetsItOrFails() = runTest {
        val repository = DemoUsbAdapterRepository()
        val notRecommended = UsbId(0x0BDA, 0xC811)

        repository.setSelection(AdapterSelection.Manual(notRecommended))

        val resolution = repository.overview.value.resolution
        assertEquals(UnavailableReason.NOT_OPENABLE, (resolution as AdapterResolution.Unavailable).reason)
        assertFailsWith<AdapterUnavailableException> { repository.resolveForBringUp() }

        repository.setSelection(AdapterSelection.Automatic)
        assertEquals(UsbId(0x2357, 0x012D), repository.resolveForBringUp())
    }

    @Test
    fun theRadioReportMarksTheAdapterInUse() {
        val repository = DemoUsbAdapterRepository()
        val adapter = UsbId(0x2357, 0x012D)

        repository.radioActive(adapter)
        assertEquals(adapter, repository.overview.value.inUse)

        repository.radioActive(null)
        assertNull(repository.overview.value.inUse)
    }
}
