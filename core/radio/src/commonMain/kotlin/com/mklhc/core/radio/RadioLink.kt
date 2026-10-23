package com.mklhc.core.radio

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-side handle on the `radiod` radio helper: the seam between app logic and the raw 802.11 radio.
 *
 * Business logic: radiod is a dumb radio that owns the USB adapter. A [RadioLink] sends it commands
 * (open the device, bring the radio up, set the MAC identity, run the hardware beacon, transmit raw
 * frames) and exposes what radiod reports (every received frame plus readiness, counters, logs and
 * errors). All 802.11 management logic lives above this interface, which keeps it replaceable.
 * Implementations: [FakeRadioLink] (in-memory test double), `DesktopRadioLink` (Unix domain socket, desktop),
 * and a `LocalSocket`-backed link on Android (Phase 2).
 *
 * Contract:
 * - Every `suspend` command returns once the command has been handed to the transport, not when
 *   radiod has finished executing it. Results arrive asynchronously on [events] (for example
 *   [RadioMessage.Ready] after [openDevice] and [init], or [RadioMessage.Error] on failure).
 * - Commands fail (throw) when the link is not connected to radiod.
 * - [rxFrames] and [events] are hot flows with no replay: collect them before issuing the commands
 *   whose answers you need, or the answer can be missed. A slow collector may miss the oldest items.
 */
interface RadioLink {
    /** Every 802.11 frame received by the radio, in arrival order (FCS already stripped). */
    val rxFrames: Flow<RadioFrame>

    /**
     * Notifications from radiod other than frames: [RadioMessage.Ready], [RadioMessage.Status],
     * [RadioMessage.Log], [RadioMessage.Error] and [RadioMessage.DeviceList].
     */
    val events: Flow<RadioMessage>

    /**
     * Asks radiod to open the USB adapter identified by [vid] and [pid] (desktop form). The Android
     * form, which hands over a file descriptor instead, is added by the Android link in Phase 2.
     */
    suspend fun openDevice(vid: Int, pid: Int)

    /** Brings the radio up on [channel] with [bandwidthMhz] and the TX power index [txPowerIndex]. */
    suspend fun init(channel: Int, bandwidthMhz: Int, txPowerIndex: Int)

    /** Sets the BSSID / own MAC (6 bytes, locally administered unicast) used by the hardware ACK engine. */
    suspend fun setIdentity(mac: ByteArray)

    /** Starts the hardware beacon with the given interval in time units (1 TU = 1024 us) and full beacon frame. */
    suspend fun startBeacon(intervalTu: Int, beacon: ByteArray)

    /** Replaces the running hardware beacon with a new full beacon frame. */
    suspend fun updateBeacon(beacon: ByteArray)

    /** Stops the hardware beacon. */
    suspend fun stopBeacon()

    /**
     * Transmits one raw 802.11 [frame] at [rate] (for example `"6M"`) with a hardware [retryLimit]
     * and radiod-defined TX [flags].
     */
    suspend fun txFrame(rate: String, retryLimit: Int, flags: Int, frame: ByteArray)

    /** Asks radiod to stop the radio and exit. */
    suspend fun shutdown()

    /**
     * Asks radiod which USB devices it sees. The answer, one [RadioMessage.DeviceList] echoing [requestId], arrives on
     * [events]; [requestDeviceList] picks the id, sends this and waits for that answer. Allowed at any time, before or
     * after [openDevice].
     */
    suspend fun listDevices(requestId: UInt)
}

/** The last LIST_DEVICES request id handed out; shared by every link, u32 and wrapping like the wire field. */
private val lastDeviceListRequestId = MutableStateFlow(0u)

/**
 * Sends [RadioLink.listDevices] with a new request id and returns the devices of the [RadioMessage.DeviceList] that
 * echoes it, or null when none arrives within [timeoutMs].
 *
 * It subscribes to [RadioLink.events] before sending, so a reply that comes back at once is not missed. A DEVICE_LIST
 * with another id (a late answer to an earlier request that timed out, or another caller's) is ignored. Throws like
 * any command when the link is not connected.
 */
suspend fun RadioLink.requestDeviceList(timeoutMs: Long): List<UsbDeviceEntry>? = coroutineScope {
    val requestId = lastDeviceListRequestId.updateAndGet { it + 1u }
    val reply = async(start = CoroutineStart.UNDISPATCHED) {
        events.filterIsInstance<RadioMessage.DeviceList>().first { it.requestId == requestId }
    }
    try {
        listDevices(requestId)
        withTimeoutOrNull(timeoutMs) { reply.await().devices }
    } finally {
        reply.cancel()
    }
}
