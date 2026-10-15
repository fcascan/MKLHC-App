package com.mklhc.core.radio

import kotlinx.coroutines.flow.Flow

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
     * [RadioMessage.Log] and [RadioMessage.Error].
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
}
