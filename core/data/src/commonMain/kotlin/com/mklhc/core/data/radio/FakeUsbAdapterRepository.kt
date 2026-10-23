package com.mklhc.core.data.radio

import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A scriptable [UsbAdapterRepository] for tests, shipped in main sources so other modules' tests can use it.
 *
 * Business logic: the test scripts the devices ([setDevices]) and the resolution follows the real resolver, so a
 * bring-up gets exactly what the real repository would give for the same devices and selection: the resolved adapter,
 * or an [AdapterUnavailableException]. Nothing is scanned: [resolveForBringUp] resolves against the scripted devices.
 * What the radio did is recorded: [resolveCount], [activeReports] (each [radioActive] call, in order) and
 * [selections] (each [setSelection] call). There is no helper to lose, so [AdapterOverview.helperConnected] is always
 * true. Not thread-safe; drive it from the test's coroutine.
 *
 * @param devices the devices the platform sees; by default one openable [DEFAULT_ADAPTER]. Null means "not scanned
 *   yet".
 * @param selection the starting selection.
 */
class FakeUsbAdapterRepository(
    devices: List<DetectedUsbDevice>? = listOf(DetectedUsbDevice(DEFAULT_ADAPTER, bus = 1, address = 4, access = UsbDeviceAccess.OPENABLE)),
    selection: AdapterSelection = AdapterSelection.Automatic,
) : UsbAdapterRepository {

    private val state = MutableStateFlow(AdapterOverview.of(devices, selection, inUse = null))
    private val reports = ArrayList<UsbId?>()
    private val selectionCalls = ArrayList<AdapterSelection>()

    override val overview: StateFlow<AdapterOverview> = state.asStateFlow()

    /** How many times [resolveForBringUp] was called, including calls that threw. */
    var resolveCount: Int = 0
        private set

    /** Every [radioActive] call, oldest first. */
    val activeReports: List<UsbId?> get() = reports.toList()

    /** Every [setSelection] call, oldest first. */
    val selections: List<AdapterSelection> get() = selectionCalls.toList()

    /** Scripts the devices the platform sees (null: not scanned yet); the resolution follows at once. */
    fun setDevices(devices: List<DetectedUsbDevice>?) {
        state.update { AdapterOverview.of(devices, it.selection, it.inUse) }
    }

    override suspend fun setSelection(selection: AdapterSelection) {
        selectionCalls += selection
        state.update { AdapterOverview.of(it.devices, selection, it.inUse) }
    }

    override suspend fun resolveForBringUp(): UsbId {
        resolveCount++
        return when (val resolution = state.value.resolution) {
            is AdapterResolution.Use -> resolution.device.id
            is AdapterResolution.Unavailable -> throw AdapterUnavailableException(resolution)
        }
    }

    override fun radioActive(adapter: UsbId?) {
        reports += adapter
        state.update { it.copy(inUse = adapter) }
    }

    companion object {
        /** The adapter the default devices hold: a compatible one (the TP-Link Archer T3U's id). */
        val DEFAULT_ADAPTER: UsbId = UsbId(0x2357, 0x012D)
    }
}
