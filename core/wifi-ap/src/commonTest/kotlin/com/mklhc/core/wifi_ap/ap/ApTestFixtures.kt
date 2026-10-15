package com.mklhc.core.wifi_ap.ap

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.wifi_ap.lp2p.Lp2pBeacon
import com.mklhc.core.wifi_ap.lp2p.Lp2pNetwork
import com.mklhc.core.wifi_ap.mac.MacAddress

/**
 * Shared constants and builders for the AP-side tests: one BSSID, two stations, a real LP2P network
 * (the two vendor IEs plus the CCMP key, produced by [Lp2pBeacon] from fixed inputs) and an
 * [ApConfig] around them.
 */
internal object ApTestFixtures {

    val BSSID: MacAddress = MacAddress.parse("02:11:22:33:44:55")
    val STATION_A: MacAddress = MacAddress.parse("02:aa:bb:cc:dd:01")
    val STATION_B: MacAddress = MacAddress.parse("02:aa:bb:cc:dd:02")
    const val SSID = "mklhc_ABCD"

    /** A real LP2P network built from fixed psk / nonces / random draws (deterministic). */
    fun network(): Lp2pNetwork = Lp2pBeacon(CryptoPrimitives()).encryptNetwork(
        psk = ByteArray(32) { 0x11 },
        plainNonce = ByteArray(16) { 0x22 },
        encryptedNonce = ByteArray(16) { 0x33 },
        randomness = { count -> ByteArray(count) { 0x44 } },
    )

    fun config(channel: Int = 6, beaconIntervalTu: Int = 100): ApConfig = ApConfig(
        bssid = BSSID,
        ssid = SSID,
        channel = channel,
        beaconIntervalTu = beaconIntervalTu,
        network = network(),
    )
}
