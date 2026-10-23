package com.mklhc.core.data.radio

import co.touchlab.kermit.Logger
import com.mklhc.core.data.store.PairingStore
import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import com.mklhc.core.radio.RadioLink
import com.mklhc.core.radio.UsbAccess
import com.mklhc.core.radio.UsbDeviceEntry
import com.mklhc.core.radio.requestDeviceList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/*
 * The desktop's USB adapter repository: radiod's LIST_DEVICES, the persisted selection and the shared resolver.
 *
 * Original code. It only wires what already exists: RadioLink.requestDeviceList (core/radio), the PairingStore's
 * adapter selection (core/data/store) and resolveAdapter (core/domain).
 */

/**
 * The [UsbAdapterRepository] of the desktop: the devices come from radiod's LIST_DEVICES.
 *
 * Business logic:
 * - When the list refreshes: as soon as radiod is connected ([radiodConnected] turns true), then every
 *   [scanIntervalMs] while it stays connected, so plugging the adapter in, or binding WinUSB to it, shows up by
 *   itself and a Start that waits for an adapter proceeds; and right before each bring-up ([resolveForBringUp]).
 * - No periodic scan runs from the moment [resolveForBringUp] hands an adapter out until the radio reports it down
 *   ([radioActive] with null): radiod is opening, or holds, that adapter, so a scan would only report it BUSY. The list
 *   keeps its last result meanwhile, and [AdapterOverview.inUse] names the adapter once the radio is up. A radiod that
 *   reconnects under a radio that is up is not scanned either: the radio's own bring-up redo scans it.
 * - When radiod goes away ([radiodConnected] turns false) the scan in flight is cancelled and the scans stop until it
 *   is back; [AdapterOverview.helperConnected] says so. Unless the radio holds an adapter, the devices are cleared
 *   (null; the selection and [AdapterOverview.inUse] are kept), so the screen never shows a list nobody is refreshing
 *   and can say that the radio helper is not running. While the radio holds an adapter the list stays, since the
 *   radio's redo scans the new radiod; it is cleared when the radio reports itself down while radiod is still away.
 * - A scan maps radiod's [UsbAccess] to [UsbDeviceAccess] and publishes the devices with the selection's resolution. A
 *   scan radiod does not answer within [scanTimeoutMs], or one that cannot be sent, keeps the previous devices and
 *   logs a warning. A list that differs from the previous one is logged at info, once; an unchanged one is not logged.
 * - The selection is loaded from the [store] (and followed when it changes there); [setSelection] persists it and
 *   resolves again at once against the devices already known, and [resolveForBringUp] reads it from the store, so a
 *   bring-up always uses the persisted choice.
 *
 * Threading: the scan loop and the selection watcher run in [scope] (give it the radio's single-threaded scope); the
 * public members are safe from any coroutine. One [Mutex] serializes the scans, and the periodic loop runs one scan
 * at a time (send, wait, then the interval), so at most one LIST_DEVICES is ever waiting for its answer: a radiod that
 * does not answer costs one request per [scanTimeoutMs] plus [scanIntervalMs], never a pile of them. Each request
 * carries its own id and [requestDeviceList] takes only the DEVICE_LIST that echoes it, so an answer that comes after
 * its scan timed out is ignored instead of being taken for the next scan's; the mutex is still needed so that the
 * periodic scan and a bring-up's scan do not race to publish, and so that the pause a bring-up sets is seen by a
 * periodic scan queued behind it. The lock is held for one scan at most ([scanTimeoutMs]). The overview is one
 * [MutableStateFlow], updated atomically.
 *
 * @param link the link to radiod.
 * @param store where the selection is persisted.
 * @param scope where the scan loop and the selection watcher run; they end with it.
 * @param radiodConnected whether radiod is connected (on desktop `DesktopRadioLink.connected`).
 * @param scanIntervalMs the time between two periodic scans.
 * @param scanTimeoutMs how long a scan waits for radiod's DEVICE_LIST.
 * @param log the logger; injectable so tests can assert what is logged.
 */
class RadiodUsbAdapterRepository(
    private val link: RadioLink,
    private val store: PairingStore,
    private val scope: CoroutineScope,
    private val radiodConnected: StateFlow<Boolean>,
    private val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    private val scanTimeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS,
    private val log: Logger = Logger.withTag(TAG),
) : UsbAdapterRepository {

    private val state = MutableStateFlow(AdapterOverview.INITIAL)

    /** Serializes the scans, so at most one LIST_DEVICES waits for its answer (see the class KDoc). */
    private val scanLock = Mutex()

    // True from a resolveForBringUp() that handed an adapter out until radioActive(null): no periodic scan meanwhile.
    // Written under scanLock by resolveForBringUp(), so a periodic scan queued behind it sees the pause.
    private val radioHolding = MutableStateFlow(false)

    override val overview: StateFlow<AdapterOverview> = state.asStateFlow()

    init {
        scope.launch { watchStoredSelection() }
        scope.launch { runPeriodicScans() }
    }

    override suspend fun setSelection(selection: AdapterSelection) {
        store.setAdapterSelection(selection)
        val updated = publishSelection(selection)
        log.i { "setSelection(): adapter selection changed -> selection=${selection.describe()} resolution=${updated.resolution.describe()}" }
    }

    override suspend fun resolveForBringUp(): UsbId {
        publishSelection(store.getAdapterSelection())
        val resolution = scanLock.withLock {
            scanDevicesLocked("resolveForBringUp()")
            val resolved = state.value.resolution
            // Paused before the lock is released, so a periodic scan waiting for it does not run during the bring-up.
            if (resolved is AdapterResolution.Use) radioHolding.value = true
            resolved
        }
        return when (resolution) {
            is AdapterResolution.Use -> {
                log.i { "resolveForBringUp(): adapter resolved -> adapter=${resolution.device.id} selection=${state.value.selection.describe()}" }
                resolution.device.id
            }
            is AdapterResolution.Unavailable -> {
                log.w { "resolveForBringUp(): no adapter can be used -> ${resolution.describe()} selection=${state.value.selection.describe()}" }
                throw AdapterUnavailableException(resolution)
            }
        }
    }

    override fun radioActive(adapter: UsbId?) {
        state.update { it.copy(inUse = adapter) }
        radioHolding.value = adapter != null
        log.i { "radioActive(): the radio reported its adapter -> inUse=${adapter ?: "none"}" }
    }

    /** Follows the stored selection, so the overview shows the persisted choice from the start. */
    private suspend fun watchStoredSelection() {
        store.observeAdapterSelection().collect { selection -> publishSelection(selection) }
    }

    /**
     * Follows radiod's connection and the radio's hold on the adapter: scans as soon as radiod is connected and then
     * every [scanIntervalMs], while it stays connected and the radio does not hold the adapter; a change of either ends
     * the current wait, and cancels the scan in flight, at once. Publishes [AdapterOverview.helperConnected], and
     * clears the devices while radiod is away and the radio holds nothing.
     */
    private suspend fun runPeriodicScans() {
        combine(radiodConnected, radioHolding) { connected, holding -> connected to holding }
            .distinctUntilChanged()
            .collectLatest { (connected, holding) ->
                publishHelperConnected(connected, clearDevices = !connected && !holding)
                if (!connected || holding) return@collectLatest
                while (true) {
                    scanLock.withLock {
                        // A bring-up that took the lock first paused the scans; this queued scan must not run.
                        if (radioHolding.value) {
                            log.d { "runPeriodicScans(): the radio holds the adapter, scan skipped" }
                        } else {
                            scanDevicesLocked("runPeriodicScans()")
                        }
                    }
                    delay(scanIntervalMs)
                }
            }
    }

    /** Publishes whether radiod is [connected]; with [clearDevices], drops the device list nobody refreshes any more. */
    private fun publishHelperConnected(connected: Boolean, clearDevices: Boolean) {
        val before = state.value
        state.update {
            if (clearDevices) AdapterOverview.of(null, it.selection, it.inUse, connected) else it.copy(helperConnected = connected)
        }
        if (clearDevices && before.devices != null) {
            log.i { "publishHelperConnected(): radiod is not connected, the device list is cleared until it is back" }
        }
    }

    /**
     * Asks radiod for its devices and publishes them; call with [scanLock] held. A scan radiod does not answer, or one
     * that cannot be sent, keeps the previous devices and logs a warning; cancellation propagates.
     */
    private suspend fun scanDevicesLocked(caller: String) {
        val entries = try {
            link.requestDeviceList(scanTimeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.w { "$caller: the device scan could not be sent, keeping the previous list -> message=${failure.message}" }
            return
        }
        if (entries == null) {
            log.w { "$caller: radiod did not answer the device scan, keeping the previous list -> timeoutMs=$scanTimeoutMs" }
            return
        }
        val devices = entries.map { it.toDetectedUsbDevice() }
        val previous = state.value.devices
        val updated = state.updateAndGet { AdapterOverview.of(devices, it.selection, it.inUse, it.helperConnected) }
        if (devices != previous) {
            log.i {
                "$caller: device list changed -> devices=${devices.joinToString(prefix = "[", postfix = "]") { it.describe() }} " +
                    "resolution=${updated.resolution.describe()}"
            }
        }
    }

    /** Publishes [selection] with its resolution against the devices already known, and returns the new overview. */
    private fun publishSelection(selection: AdapterSelection): AdapterOverview =
        state.updateAndGet { AdapterOverview.of(it.devices, selection, it.inUse, it.helperConnected) }

    private fun UsbDeviceEntry.toDetectedUsbDevice() = DetectedUsbDevice(
        id = UsbId(vid, pid),
        bus = bus,
        address = address,
        access = access.toUsbDeviceAccess(),
    )

    private fun UsbAccess.toUsbDeviceAccess(): UsbDeviceAccess = when (this) {
        UsbAccess.OPENABLE -> UsbDeviceAccess.OPENABLE
        UsbAccess.DRIVER_UNSUPPORTED -> UsbDeviceAccess.DRIVER_UNSUPPORTED
        UsbAccess.ACCESS_DENIED -> UsbDeviceAccess.ACCESS_DENIED
        UsbAccess.BUSY -> UsbDeviceAccess.BUSY
        UsbAccess.ERROR -> UsbDeviceAccess.ERROR
        UsbAccess.NOT_PROBED -> UsbDeviceAccess.NOT_PROBED
        UsbAccess.UNKNOWN -> UsbDeviceAccess.UNKNOWN
    }

    private fun DetectedUsbDevice.describe(): String = "$id@$bus.$address $access"

    private fun AdapterSelection.describe(): String = when (this) {
        AdapterSelection.Automatic -> "automatic"
        is AdapterSelection.Manual -> "manual $id"
    }

    private fun AdapterResolution.describe(): String = when (this) {
        is AdapterResolution.Use -> "use ${device.id}"
        is AdapterResolution.Unavailable -> "reason=$reason" + (device?.let { " device=${it.describe()}" } ?: "")
    }

    companion object {
        /** The time between two periodic scans by default. */
        const val DEFAULT_SCAN_INTERVAL_MS = 2_000L

        /** How long a scan waits for radiod's DEVICE_LIST by default. */
        const val DEFAULT_SCAN_TIMEOUT_MS = 3_000L

        private const val TAG = "RadiodUsbAdapterRepository"
    }
}
