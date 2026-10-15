package com.mklhc.core.wifi_ap.ap

import com.mklhc.core.wifi_ap.lp2p.Lp2pNetwork
import com.mklhc.core.wifi_ap.mac.MacAddress

/**
 * Immutable description of the one BSS the access point serves.
 *
 * Business logic: the AP advertises exactly one network at a time (the pairing network or the game
 * network, see the design's pairing/game switch). Everything the beacon, the probe/association
 * responses and the data plane need is held here: the [bssid], the real [ssid] (hidden in the
 * beacon, revealed in probe responses), the 2.4 GHz [channel] (1, 6 or 11, the non-overlapping
 * channels of OpenKart's hostapd `hw_mode=g` setup), the beacon interval and the LP2P [network]
 * (the static CCMP key and the two LP2P vendor elements). Switching networks is done by building a
 * new [ApConfig] and a new [ApStateMachine]; nothing here is mutable.
 *
 * @property bssid locally administered unicast BSSID (devourer's hardware ACK engine needs a unicast BSSID).
 * @property ssid the real SSID, 1..32 UTF-8 octets.
 * @property channel 1, 6 or 11.
 * @property beaconIntervalTu beacon interval in time units (1 TU = 1024 microseconds); 100 TU by default.
 * @property network the LP2P network: 16-byte static CCMP key and the vendor elements for the beacon.
 */
class ApConfig(
    val bssid: MacAddress,
    val ssid: String,
    val channel: Int,
    val beaconIntervalTu: Int = DEFAULT_BEACON_INTERVAL_TU,
    val network: Lp2pNetwork,
) {

    init {
        require(!bssid.isMulticast) { "BSSID must be a unicast address: $bssid" }
        val ssidOctets = ssid.encodeToByteArray().size
        require(ssidOctets in 1..MAX_SSID_BYTES) { "SSID must be 1..$MAX_SSID_BYTES UTF-8 octets, got $ssidOctets" }
        require(channel in ALLOWED_CHANNELS) { "channel must be one of $ALLOWED_CHANNELS: $channel" }
        require(beaconIntervalTu in 1..MAX_BEACON_INTERVAL_TU) { "beacon interval must be 1..$MAX_BEACON_INTERVAL_TU TU" }
        require(network.ccmpKey.size == CCMP_KEY_BYTES) { "CCMP key must be $CCMP_KEY_BYTES bytes, got ${network.ccmpKey.size}" }
    }

    companion object {
        /** Default beacon interval; tunable during the hardware spike. */
        const val DEFAULT_BEACON_INTERVAL_TU = 100

        /** The non-overlapping 2.4 GHz channels the AP may use. */
        val ALLOWED_CHANNELS: List<Int> = listOf(1, 6, 11)

        private const val MAX_SSID_BYTES = 32
        private const val MAX_BEACON_INTERVAL_TU = 0xFFFF
        private const val CCMP_KEY_BYTES = 16
    }
}
