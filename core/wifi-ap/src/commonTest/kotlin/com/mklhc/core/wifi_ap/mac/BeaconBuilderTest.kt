package com.mklhc.core.wifi_ap.mac

import com.mklhc.core.wifi_ap.ap.ApTestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [BeaconBuilder]: the hidden-SSID LP2P beacon (element order, zeroed SSID, capability
 * with Privacy and no RSN, rates, LP2P vendor elements last) and the matching probe response.
 */
class BeaconBuilderTest {

    private val bssid = ApTestFixtures.BSSID
    private val station = ApTestFixtures.STATION_A
    private val ssid = ApTestFixtures.SSID

    // Two synthetic vendor elements with the LP2P OUI/type framing (the content is opaque to the builder).
    private val syntheticVendorElements = "dd050022aa0600" + "dd050022aa0601"

    private fun builder(
        vendorElements: ByteArray = syntheticVendorElements.hexToByteArray(),
        channel: Int = 6,
        beaconIntervalTu: Int = 100,
    ) = BeaconBuilder(
        bssid = bssid,
        ssid = ssid,
        channel = channel,
        beaconIntervalTu = beaconIntervalTu,
        vendorElements = vendorElements,
    )

    private fun parseBeacon(bytes: ByteArray): Pair<Ieee80211Frame, BeaconFields> {
        val frame = assertNotNull(Ieee80211Frame.parse(bytes))
        return frame to assertNotNull(frame.beaconFields())
    }

    @Test
    fun `beacon is built byte for byte`() {
        val beacon = builder().beacon()

        val expected =
            "8000" + // frame control: management, beacon
                "0000" + // duration
                "ffffffffffff" + // addr1 = broadcast
                "021122334455" + // addr2 = BSSID
                "021122334455" + // addr3 = BSSID
                "0000" + // sequence control
                "0000000000000000" + // timestamp (hardware overwrites)
                "6400" + // beacon interval 100 TU
                "1100" + // capability: ESS | Privacy
                "000a" + "00000000000000000000" + // SSID: real length 10, all zero (hidden)
                "0108" + "82848b96" + "0c121824" + // Supported Rates
                "030106" + // DS Parameter Set: channel 6
                "050400010000" + // TIM
                "2a0100" + // ERP
                "32043048606c" + // Extended Supported Rates
                syntheticVendorElements
        assertEquals(expected, beacon.toHexString())
    }

    @Test
    fun `beacon is a broadcast management beacon from the BSSID`() {
        val (frame, _) = parseBeacon(builder().beacon())

        assertTrue(frame.isManagement)
        assertTrue(frame.isBeacon)
        assertEquals(Ieee80211.SUBTYPE_BEACON, frame.frameControl.subtype)
        assertEquals(MacAddress.BROADCAST, frame.addr1)
        assertEquals(bssid, frame.addr2)
        assertEquals(bssid, frame.addr3)
    }

    @Test
    fun `beacon elements are in the required order`() {
        val (_, fields) = parseBeacon(builder().beacon())

        assertEquals(
            listOf(
                InformationElements.SSID,
                InformationElements.SUPPORTED_RATES,
                InformationElements.DS_PARAMETER_SET,
                InformationElements.TIM,
                InformationElements.ERP,
                InformationElements.EXTENDED_SUPPORTED_RATES,
                InformationElements.VENDOR_SPECIFIC,
                InformationElements.VENDOR_SPECIFIC,
            ),
            fields.elements.map { it.id },
        )
    }

    @Test
    fun `hidden SSID keeps the real length but every byte is zero`() {
        val (_, fields) = parseBeacon(builder().beacon())

        val ssidElement = assertNotNull(InformationElements.find(fields.elements, InformationElements.SSID))
        assertEquals(ssid.encodeToByteArray().size, ssidElement.value.size)
        assertTrue(ssidElement.value.all { it == 0.toByte() }, "SSID bytes must be zero in the beacon")
    }

    @Test
    fun `capability has ESS and Privacy and no RSN element is present`() {
        val (_, fields) = parseBeacon(builder().beacon())

        assertEquals(0x0011, fields.capabilityInfo)
        assertTrue(fields.capabilityInfo and BeaconBuilder.CAPABILITY_ESS != 0)
        assertTrue(fields.capabilityInfo and BeaconBuilder.CAPABILITY_PRIVACY != 0)
        assertNull(InformationElements.find(fields.elements, InformationElements.RSN))
        // Static CCMP: no WPA vendor IE either (00:50:f2 type 1); the only vendor IEs are LP2P.
        val vendorElements = fields.elements.filter { it.id == InformationElements.VENDOR_SPECIFIC }
        assertTrue(vendorElements.all { it.value.copyOf(4).toHexString() == "0022aa06" })
    }

    @Test
    fun `timestamp is zero and beacon interval is honoured`() {
        val (_, fields) = parseBeacon(builder(beaconIntervalTu = 200).beacon())

        assertEquals(0L, fields.timestamp)
        assertEquals(200, fields.beaconIntervalTu)
    }

    @Test
    fun `rates follow the 802_11g set with the 802_11b rates marked basic`() {
        val (_, fields) = parseBeacon(builder().beacon())

        val supported = assertNotNull(InformationElements.find(fields.elements, InformationElements.SUPPORTED_RATES))
        val extended = assertNotNull(InformationElements.find(fields.elements, InformationElements.EXTENDED_SUPPORTED_RATES))
        // 1*, 2*, 5.5*, 11* (basic, bit 7 set) then 6, 9, 12, 18 Mbit/s.
        assertEquals("82848b96" + "0c121824", supported.value.toHexString())
        // 24, 36, 48, 54 Mbit/s.
        assertEquals("3048606c", extended.value.toHexString())
    }

    @Test
    fun `DS parameter set TIM and ERP carry the channel and static values`() {
        val (_, fields) = parseBeacon(builder(channel = 11).beacon())

        assertEquals("0b", assertNotNull(InformationElements.find(fields.elements, InformationElements.DS_PARAMETER_SET)).value.toHexString())
        assertEquals("00010000", assertNotNull(InformationElements.find(fields.elements, InformationElements.TIM)).value.toHexString())
        assertEquals("00", assertNotNull(InformationElements.find(fields.elements, InformationElements.ERP)).value.toHexString())
    }

    @Test
    fun `the two LP2P vendor elements are the last bytes of the beacon`() {
        val network = ApTestFixtures.network()
        val beacon = builder(vendorElements = network.vendorElements).beacon()

        assertEquals(
            network.vendorElements.toHexString(),
            beacon.copyOfRange(beacon.size - network.vendorElements.size, beacon.size).toHexString(),
        )
        val (_, fields) = parseBeacon(beacon)
        val vendors = fields.elements.takeLast(2)
        assertEquals(listOf(0xDD, 0xDD), vendors.map { it.id })
        assertEquals("0022aa0600", vendors[0].value.copyOf(5).toHexString())
        assertEquals("0022aa0601", vendors[1].value.copyOf(5).toHexString())
    }

    @Test
    fun `probe response mirrors the beacon elements but carries the real SSID`() {
        val builder = builder(vendorElements = ApTestFixtures.network().vendorElements)

        val response = builder.probeResponse(station)

        val frame = assertNotNull(Ieee80211Frame.parse(response))
        assertTrue(frame.isProbeResponse)
        assertEquals(station, frame.addr1)
        assertEquals(bssid, frame.addr2)
        assertEquals(bssid, frame.addr3)
        val fields = assertNotNull(frame.beaconFields())
        assertEquals(0x0011, fields.capabilityInfo)
        assertEquals(100, fields.beaconIntervalTu)
        val ssidElement = assertNotNull(InformationElements.find(fields.elements, InformationElements.SSID))
        assertEquals(ssid, ssidElement.value.decodeToString())
        // Same elements and order as the beacon, minus the TIM (a TIM is not part of a probe response).
        assertEquals(
            listOf(0, 1, 3, 42, 50, 221, 221),
            fields.elements.map { it.id },
        )
        // The vendor elements are byte-identical to the beacon's and last.
        val vendorBytes = builder.beacon().let { it.copyOfRange(it.size - builder.vendorElements.size, it.size) }
        assertEquals(vendorBytes.toHexString(), response.copyOfRange(response.size - vendorBytes.size, response.size).toHexString())
    }

    @Test
    fun `probe response takes an optional TSF timestamp and sequence number`() {
        val response = builder().probeResponse(station, timestamp = 0x1122334455667788L, sequenceNumber = 7)

        val frame = assertNotNull(Ieee80211Frame.parse(response))
        assertEquals(7, frame.sequenceControl.sequenceNumber)
        assertEquals(0x1122334455667788L, assertNotNull(frame.beaconFields()).timestamp)
    }

    @Test
    fun `rate elements are the rates carried in the association response`() {
        val rates = builder().rateElements()

        assertEquals("0108" + "82848b96" + "0c121824" + "32043048606c", rates.toHexString())
    }

    @Test
    fun `builder validates its arguments`() {
        assertFailsWith<IllegalArgumentException> { builder(channel = 0) }
        assertFailsWith<IllegalArgumentException> { builder(channel = 15) }
        assertFailsWith<IllegalArgumentException> { builder(beaconIntervalTu = 0) }
        assertFailsWith<IllegalArgumentException> {
            BeaconBuilder(bssid, "", 6, 100, ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            BeaconBuilder(bssid, "x".repeat(33), 6, 100, ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            BeaconBuilder(MacAddress.BROADCAST, ssid, 6, 100, ByteArray(0))
        }
    }
}
