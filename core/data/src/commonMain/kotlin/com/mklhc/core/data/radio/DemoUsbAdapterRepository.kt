package com.mklhc.core.data.radio

import co.touchlab.kermit.Logger
import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * The [UsbAdapterRepository] of the demo session: a fixed, believable list, with no radio behind it.
 *
 * Business logic: the demo runs the whole UI without hardware, so the Kart link screen still needs devices to show.
 * The list holds one device of each kind the screen must handle: a compatible adapter that opens (the TP-Link Archer
 * T3U, which the automatic mode picks), a not recommended adapter that needs the WinUSB driver (an RTL8811CU), and a
 * USB device the app does not know. A selection is kept in memory only (the demo persists nothing) and resolved at once
 * with the real resolver; [resolveForBringUp] answers from the same list. There is no radio helper, so
 * [AdapterOverview.helperConnected] is always true. Android binds it too until its USB enumeration exists (Phase 2).
 *
 * Threading: every member is safe from any thread (one [MutableStateFlow], updated atomically).
 *
 * @param log the logger; injectable for tests.
 */
class DemoUsbAdapterRepository(
    private val log: Logger = Logger.withTag(TAG),
) : UsbAdapterRepository {

    private val state = MutableStateFlow(AdapterOverview.of(DEMO_DEVICES, AdapterSelection.Automatic, inUse = null))

    override val overview: StateFlow<AdapterOverview> = state.asStateFlow()

    override suspend fun setSelection(selection: AdapterSelection) {
        val updated = state.updateAndGet { AdapterOverview.of(it.devices, selection, it.inUse) }
        log.i { "setSelection(): demo selection changed -> selection=$selection resolution=${updated.resolution}" }
    }

    override suspend fun resolveForBringUp(): UsbId = when (val resolution = state.value.resolution) {
        is AdapterResolution.Use -> resolution.device.id
        is AdapterResolution.Unavailable -> throw AdapterUnavailableException(resolution)
    }

    override fun radioActive(adapter: UsbId?) {
        state.update { it.copy(inUse = adapter) }
    }

    private companion object {
        const val TAG = "DemoUsbAdapterRepository"

        /** One device of each kind the Kart link screen shows. */
        val DEMO_DEVICES = listOf(
            DetectedUsbDevice(UsbId(0x2357, 0x012D), bus = 1, address = 4, access = UsbDeviceAccess.OPENABLE),
            DetectedUsbDevice(UsbId(0x0BDA, 0xC811), bus = 1, address = 7, access = UsbDeviceAccess.DRIVER_UNSUPPORTED),
            DetectedUsbDevice(UsbId(0x046D, 0xC52B), bus = 1, address = 2, access = UsbDeviceAccess.DRIVER_UNSUPPORTED),
        )
    }
}
