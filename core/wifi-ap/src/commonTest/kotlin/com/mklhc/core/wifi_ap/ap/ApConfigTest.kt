package com.mklhc.core.wifi_ap.ap

import com.mklhc.core.wifi_ap.lp2p.Lp2pNetwork
import com.mklhc.core.wifi_ap.mac.MacAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tests for the validation [ApConfig] applies to the BSS parameters. */
class ApConfigTest {

    private val network = Lp2pNetwork(ccmpKey = ByteArray(16) { 0x01 }, vendorElements = "dd050022aa0600".hexToByteArray())

    private fun config(
        bssid: MacAddress = ApTestFixtures.BSSID,
        ssid: String = ApTestFixtures.SSID,
        channel: Int = 6,
        beaconIntervalTu: Int = 100,
        network: Lp2pNetwork = this.network,
    ) = ApConfig(bssid, ssid, channel, beaconIntervalTu, network)

    @Test
    fun `accepts channels 1 6 and 11 and the default beacon interval is 100 TU`() {
        for (channel in listOf(1, 6, 11)) {
            assertEquals(channel, config(channel = channel).channel)
        }
        assertEquals(100, ApConfig(ApTestFixtures.BSSID, ApTestFixtures.SSID, 6, network = network).beaconIntervalTu)
    }

    @Test
    fun `rejects channels outside the non-overlapping 2_4 GHz set`() {
        for (channel in listOf(0, 2, 5, 7, 12, 13, 14)) {
            assertFailsWith<IllegalArgumentException>("channel $channel") { config(channel = channel) }
        }
    }

    @Test
    fun `rejects a multicast BSSID an empty or oversized SSID and a bad beacon interval`() {
        assertFailsWith<IllegalArgumentException> { config(bssid = MacAddress.BROADCAST) }
        assertFailsWith<IllegalArgumentException> { config(bssid = MacAddress.parse("01:00:5e:00:00:01")) }
        assertFailsWith<IllegalArgumentException> { config(ssid = "") }
        assertFailsWith<IllegalArgumentException> { config(ssid = "x".repeat(33)) }
        assertFailsWith<IllegalArgumentException> { config(beaconIntervalTu = 0) }
        assertFailsWith<IllegalArgumentException> { config(beaconIntervalTu = 65536) }
    }

    @Test
    fun `rejects a CCMP key that is not 128 bits`() {
        assertFailsWith<IllegalArgumentException> { config(network = Lp2pNetwork(ByteArray(15), network.vendorElements)) }
    }

    @Test
    fun `counts the SSID length in UTF-8 octets`() {
        // U+00E9 encodes to two UTF-8 octets.
        val twoOctetChar = 0xE9.toChar().toString()
        // 17 two-octet characters = 34 octets, over the 32 octet limit although only 17 characters.
        assertFailsWith<IllegalArgumentException> { config(ssid = twoOctetChar.repeat(17)) }
        assertEquals(32, config(ssid = twoOctetChar.repeat(16)).ssid.encodeToByteArray().size)
    }
}
