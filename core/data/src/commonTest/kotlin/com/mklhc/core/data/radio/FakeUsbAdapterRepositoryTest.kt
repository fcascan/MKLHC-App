package com.mklhc.core.data.radio

import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.UnavailableReason
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [FakeUsbAdapterRepository]: the devices are scripted, the resolution follows the real resolver, and every call the
 * radio makes is recorded.
 */
class FakeUsbAdapterRepositoryTest {

    @Test
    fun byDefaultOneOpenableCompatibleAdapterIsResolvedForEveryBringUp() = runTest {
        val fake = FakeUsbAdapterRepository()

        assertEquals(FakeUsbAdapterRepository.DEFAULT_ADAPTER, fake.resolveForBringUp())
        assertEquals(FakeUsbAdapterRepository.DEFAULT_ADAPTER, fake.resolveForBringUp())
        assertEquals(2, fake.resolveCount)
    }

    @Test
    fun scriptedDevicesAndSelectionsResolveLikeTheRealRepository() = runTest {
        val fake = FakeUsbAdapterRepository()

        fake.setDevices(emptyList())
        val failure = assertFailsWith<AdapterUnavailableException> { fake.resolveForBringUp() }
        assertEquals(UnavailableReason.NO_COMPATIBLE_DEVICE, failure.reason)

        val other = UsbId(0x0BDA, 0xC812)
        fake.setDevices(listOf(DetectedUsbDevice(other, bus = 1, address = 2, access = UsbDeviceAccess.OPENABLE)))
        fake.setSelection(AdapterSelection.Manual(other))
        assertEquals(AdapterResolution.Use(fake.overview.value.devices!!.single()), fake.overview.value.resolution)
        assertEquals(other, fake.resolveForBringUp())
        assertEquals(listOf<AdapterSelection>(AdapterSelection.Manual(other)), fake.selections)
    }

    @Test
    fun theRadioReportsAreRecordedAndMarkTheAdapterInUse() {
        val fake = FakeUsbAdapterRepository()

        fake.radioActive(FakeUsbAdapterRepository.DEFAULT_ADAPTER)
        fake.radioActive(null)

        assertEquals(listOf(FakeUsbAdapterRepository.DEFAULT_ADAPTER, null), fake.activeReports)
        assertEquals(null, fake.overview.value.inUse)
    }
}
