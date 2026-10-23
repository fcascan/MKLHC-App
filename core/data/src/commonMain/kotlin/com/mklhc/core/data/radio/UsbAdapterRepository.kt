package com.mklhc.core.data.radio

import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.UnavailableReason
import com.mklhc.core.domain.UsbId
import com.mklhc.core.domain.resolveAdapter
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app knows about the USB adapters at one moment: what the platform sees, what the user chose and what that
 * resolves to.
 *
 * Business logic: one value, so the Kart link screen and the adapter state always agree. [resolution] is always
 * `resolveAdapter(selection, devices)`; build it with [of] rather than by hand.
 *
 * @property devices every USB device of the latest scan (hubs left out), or null while there is no current list: no
 *   scan has answered yet, or the helper that scans went away (see [helperConnected]).
 * @property selection the user's choice, as persisted.
 * @property resolution which device the app would use now, or why none.
 * @property inUse the adapter the radio has open right now, or null while the radio is down. The platform scan would
 *   report it as busy (the radio holds it), so the screen says "in use" instead.
 * @property helperConnected whether the platform part that lists the devices is reachable (on desktop, radiod is
 *   connected). False with no [devices] means "the radio helper is not running", not "still looking". Platforms with
 *   no separate helper (the demo) always report true.
 */
data class AdapterOverview(
    val devices: List<DetectedUsbDevice>?,
    val selection: AdapterSelection,
    val resolution: AdapterResolution,
    val inUse: UsbId?,
    val helperConnected: Boolean,
) {
    companion object {
        /** The overview before anything is known: no helper yet, no scan, automatic selection, nothing in use. */
        val INITIAL: AdapterOverview =
            of(devices = null, selection = AdapterSelection.Automatic, inUse = null, helperConnected = false)

        /** An overview whose [resolution] is resolved from [selection] and [devices]. */
        fun of(
            devices: List<DetectedUsbDevice>?,
            selection: AdapterSelection,
            inUse: UsbId?,
            helperConnected: Boolean = true,
        ): AdapterOverview = AdapterOverview(devices, selection, resolveAdapter(selection, devices), inUse, helperConnected)
    }
}

/**
 * No adapter can be used for a bring-up: [resolution] says why, and names the device involved when there is one.
 *
 * Business logic: thrown by [UsbAdapterRepository.resolveForBringUp], so a Start or a Pairing that finds no usable
 * adapter fails with a message the user can act on ("plug in", "bind WinUSB", "pick another") instead of a radiod
 * error code.
 */
class AdapterUnavailableException(val resolution: AdapterResolution.Unavailable) : IllegalStateException(
    "no USB adapter can be used -> reason=${resolution.reason}" +
        (resolution.device?.let { " device=${it.id} access=${it.access}" } ?: ""),
) {
    /** Why no adapter can be used. */
    val reason: UnavailableReason get() = resolution.reason
}

/**
 * The USB adapters the platform sees and the one the radio will open.
 *
 * Business logic: the radio opens exactly one adapter, and which one is the user's choice ([AdapterSelection],
 * persisted) resolved against the devices the platform reports ([resolveAdapter]). This repository keeps that answer
 * current for the screen ([overview]) and gives the radio a fresh one right before each bring-up
 * ([resolveForBringUp]). Implementations: [RadiodUsbAdapterRepository] (desktop, radiod's LIST_DEVICES),
 * [DemoUsbAdapterRepository] (a fixed list, for the demo session and for Android until its USB enumeration exists) and
 * [FakeUsbAdapterRepository] (tests).
 *
 * Contract: [resolveForBringUp] and [radioActive] are called by the radio's owner (`RadioKartRadioAp`) only. Every
 * [resolveForBringUp] that returns is followed by one [radioActive] call: the adapter once the radio is up, null when
 * the bring-up failed or was abandoned; and [radioActive] with null again when the radio stops.
 */
interface UsbAdapterRepository {

    /** What is known now; emits the current value on collection. */
    val overview: StateFlow<AdapterOverview>

    /** Persists [selection] and resolves the adapter again at once, against the devices already known. */
    suspend fun setSelection(selection: AdapterSelection)

    /**
     * Scans the devices afresh and returns the adapter the radio must open.
     *
     * @throws AdapterUnavailableException when no adapter can be used.
     */
    suspend fun resolveForBringUp(): UsbId

    /**
     * Tells the repository which adapter the radio has open: [adapter] when a bring-up succeeded, null when one
     * failed or was abandoned and when the radio stops. Never throws.
     */
    fun radioActive(adapter: UsbId?)
}
