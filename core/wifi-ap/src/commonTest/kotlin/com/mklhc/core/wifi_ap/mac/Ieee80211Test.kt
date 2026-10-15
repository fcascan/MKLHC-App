package com.mklhc.core.wifi_ap.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pure IEEE 802.11 frame codec ([Ieee80211], [Ieee80211Frame], [FrameControl],
 * [InformationElements]).
 *
 * Expected frames are written out by hand, field by field, from the "IEEE 802.11 frame formats"
 * (little-endian multi-byte header fields), so a codec bug cannot hide behind a symmetric
 * encode/decode mistake.
 */
class Ieee80211Test {

    private val station = MacAddress.parse("02:aa:bb:cc:dd:01")
    private val bssid = MacAddress.parse("02:11:22:33:44:55")
    private val sourceAddress = MacAddress.parse("02:de:ad:be:ef:01")

    private val ssidText = "mklhc_ABCD"
    private val ssidElementHex = "000a" + "6d6b6c68635f41424344" // id 0, len 10, "mklhc_ABCD"
    private val ratesElementHex = "0108" + "82848b96" + "0c121824" // id 1, 1* 2* 5.5* 11* 6 9 12 18
    private val dsElementHex = "030106" // channel 6

    // ---- FrameControl -----------------------------------------------------------------------

    private class FcCase(
        val byte0: Int,
        val byte1: Int,
        val type: Int,
        val subtype: Int,
        val toDs: Boolean = false,
        val fromDs: Boolean = false,
        val retry: Boolean = false,
        val protectedFrame: Boolean = false,
        val powerManagement: Boolean = false,
    )

    @Test
    fun `frame control decodes type subtype and flag bits from known bytes`() {
        val cases = listOf(
            FcCase(0x80, 0x00, type = 0, subtype = 8), // beacon
            FcCase(0x40, 0x00, type = 0, subtype = 4), // probe request
            FcCase(0xB0, 0x00, type = 0, subtype = 11), // authentication
            FcCase(0x08, 0x01, type = 2, subtype = 0, toDs = true), // data, to DS
            FcCase(0x08, 0x42, type = 2, subtype = 0, fromDs = true, protectedFrame = true), // protected from-DS
            FcCase(0x88, 0x09, type = 2, subtype = 8, toDs = true, retry = true), // QoS data, retry
            FcCase(0x08, 0x48, type = 2, subtype = 0, retry = true, protectedFrame = true), // CCMP test frame
            FcCase(0x48, 0x11, type = 2, subtype = 4, toDs = true, powerManagement = true), // null, PM
            FcCase(0xC8, 0x01, type = 2, subtype = 12, toDs = true), // QoS null
        )

        for (case in cases) {
            val label = "fc=${case.byte0.toString(16)} ${case.byte1.toString(16)}"
            val fc = FrameControl.fromBytes(byteArrayOf(case.byte0.toByte(), case.byte1.toByte()))
            assertEquals(0, fc.protocolVersion, label)
            assertEquals(case.type, fc.type, "$label type")
            assertEquals(case.subtype, fc.subtype, "$label subtype")
            assertEquals(case.toDs, fc.toDs, "$label toDs")
            assertEquals(case.fromDs, fc.fromDs, "$label fromDs")
            assertEquals(case.retry, fc.retry, "$label retry")
            assertEquals(case.protectedFrame, fc.protectedFrame, "$label protected")
            assertEquals(case.powerManagement, fc.powerManagement, "$label powerManagement")
        }
    }

    @Test
    fun `frame control of builds the on-wire bytes little-endian`() {
        assertEquals("8000", FrameControl.of(Ieee80211.TYPE_MANAGEMENT, Ieee80211.SUBTYPE_BEACON).toBytes().toHexString())
        assertEquals(
            "0842",
            FrameControl.of(Ieee80211.TYPE_DATA, 0, fromDs = true, protectedFrame = true).toBytes().toHexString(),
        )
        assertEquals(
            "8809",
            FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_QOS_DATA, toDs = true, retry = true)
                .toBytes().toHexString(),
        )
    }

    @Test
    fun `sequence control splits a 12 bit sequence number and a 4 bit fragment number`() {
        val sequenceControl = SequenceControl.fromBytes(byteArrayOf(0xA3.toByte(), 0x01))

        assertEquals(0x1A, sequenceControl.sequenceNumber)
        assertEquals(3, sequenceControl.fragmentNumber)
        assertEquals("a301", SequenceControl.of(0x1A, 3).toBytes().toHexString())
        // The sequence number wraps modulo 4096.
        assertEquals(1, SequenceControl.of(4097, 0).sequenceNumber)
    }

    @Test
    fun `mac address parses prints and compares by value`() {
        val parsed = MacAddress.parse("02:AA:bb:cc:dd:01")

        assertEquals("02:aa:bb:cc:dd:01", parsed.toString())
        assertEquals(parsed, MacAddress.fromBytes(byteArrayOf(0x02, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0x01)))
        assertEquals(parsed.hashCode(), station.hashCode())
        assertTrue(MacAddress.BROADCAST.isBroadcast)
        assertTrue(MacAddress.BROADCAST.isMulticast)
        assertFalse(parsed.isMulticast)
        assertFailsWith<IllegalArgumentException> { MacAddress.parse("02:aa:bb") }
    }

    // ---- Parsing ----------------------------------------------------------------------------

    private val probeRequestHex =
        "4000" + // frame control: management, probe request
            "0000" + // duration
            "ffffffffffff" + // addr1 (DA) broadcast
            "02aabbccdd01" + // addr2 (SA) station
            "ffffffffffff" + // addr3 (BSSID) wildcard
            "1000" + // sequence control: seq 1, fragment 0
            ssidElementHex + ratesElementHex

    @Test
    fun `parse decodes a probe request into a typed model`() {
        val frame = assertNotNull(Ieee80211Frame.parse(probeRequestHex.hexToByteArray()))

        assertTrue(frame.isManagement)
        assertTrue(frame.isProbeRequest)
        assertFalse(frame.isAuth)
        assertFalse(frame.isAssocRequest)
        assertFalse(frame.isDataFrame)
        assertFalse(frame.toDs)
        assertFalse(frame.fromDs)
        assertFalse(frame.retry)
        assertFalse(frame.isProtected)
        assertEquals(MacAddress.BROADCAST, frame.addr1)
        assertEquals(station, frame.addr2)
        assertEquals(MacAddress.BROADCAST, frame.addr3)
        assertEquals(1, frame.sequenceControl.sequenceNumber)
        assertEquals(0, frame.sequenceControl.fragmentNumber)
        assertEquals(24, frame.headerLength)
        assertEquals(ssidElementHex + ratesElementHex, frame.body.toHexString())

        val elements = frame.probeRequestElements()
        assertEquals(listOf(InformationElements.SSID, InformationElements.SUPPORTED_RATES), elements.map { it.id })
        assertEquals(ssidText, elements.first().value.decodeToString())
    }

    @Test
    fun `parse then toBytes reproduces the received probe request byte for byte`() {
        val original = probeRequestHex.hexToByteArray()

        val rebuilt = assertNotNull(Ieee80211Frame.parse(original)).toBytes()

        assertEquals(original.toHexString(), rebuilt.toHexString())
    }

    @Test
    fun `parse decodes a QoS data frame header with its QoS control field`() {
        val hex =
            "8801" + // QoS data, to DS
                "0000" +
                "021122334455" + // addr1 = BSSID
                "02aabbccdd01" + // addr2 = station
                "ffffffffffff" + // addr3 = DA
                "a001" + // seq 26, fragment 0
                "0500" + // QoS control: TID 5
                "aaaa03000000" + "0806" + "0102" // LLC/SNAP (ARP) + 2 payload bytes
        val frame = assertNotNull(Ieee80211Frame.parse(hex.hexToByteArray()))

        assertTrue(frame.isDataFrame)
        assertTrue(frame.isQosData)
        assertFalse(frame.isNullData)
        assertTrue(frame.toDs)
        assertEquals(26, frame.headerLength)
        assertEquals(5, frame.qosControl)
        assertEquals(26, frame.sequenceControl.sequenceNumber)
        assertEquals("aaaa030000000806" + "0102", frame.body.toHexString())
        assertEquals(hex, frame.toBytes().toHexString())
    }

    @Test
    fun `parse identifies null and QoS null data frames`() {
        val nullFrame = "4801" + "0000" + "021122334455" + "02aabbccdd01" + "021122334455" + "0000"
        val qosNull = "c801" + "0000" + "021122334455" + "02aabbccdd01" + "021122334455" + "0000" + "0000"

        assertTrue(assertNotNull(Ieee80211Frame.parse(nullFrame.hexToByteArray())).isNullData)
        val parsedQosNull = assertNotNull(Ieee80211Frame.parse(qosNull.hexToByteArray()))
        assertTrue(parsedQosNull.isNullData)
        assertTrue(parsedQosNull.isQosData)
    }

    @Test
    fun `parse returns null for truncated control and wrong version frames`() {
        // Truncated management header.
        assertNull(Ieee80211Frame.parse(probeRequestHex.hexToByteArray().copyOf(20)))
        // ACK control frame (type 1): not a management or data frame.
        assertNull(Ieee80211Frame.parse("d4000000".hexToByteArray() + station.toByteArray()))
        // Protocol version 1.
        val badVersion = probeRequestHex.hexToByteArray()
        badVersion[0] = (badVersion[0].toInt() or 0x01).toByte()
        assertNull(Ieee80211Frame.parse(badVersion))
        // QoS data frame cut short before its QoS control field.
        assertNull(Ieee80211Frame.parse(("8801" + "0000" + "021122334455" + "02aabbccdd01" + "ffffffffffff" + "0000").hexToByteArray()))
        assertNull(Ieee80211Frame.parse(ByteArray(0)))
    }

    @Test
    fun `parse reports the retry and to-DS from-DS bits`() {
        // 0x08 0x0a: data frame, fromDS (0x02) + retry (0x08).
        val frame = assertNotNull(
            Ieee80211Frame.parse(("080a" + "0000" + "02aabbccdd01" + "021122334455" + "021122334455" + "0000").hexToByteArray()),
        )
        assertTrue(frame.fromDs)
        assertFalse(frame.toDs)
        assertTrue(frame.retry)
    }

    @Test
    fun `parse decodes authentication association and reason fields`() {
        val auth = Ieee80211.managementFrame(
            Ieee80211.SUBTYPE_AUTH, bssid, station, bssid,
            "000001000000".hexToByteArray(), // open system, seq 1, status 0
        )
        val authFields = assertNotNull(assertNotNull(Ieee80211Frame.parse(auth)).authentication())
        assertEquals(0, authFields.algorithm)
        assertEquals(1, authFields.transactionSequence)
        assertEquals(0, authFields.statusCode)

        val assoc = Ieee80211.managementFrame(
            Ieee80211.SUBTYPE_ASSOC_REQUEST, bssid, station, bssid,
            ("2100" + "0a00" + ssidElementHex + ratesElementHex).hexToByteArray(),
        )
        val assocParsed = assertNotNull(Ieee80211Frame.parse(assoc))
        assertTrue(assocParsed.isAssocRequest)
        val assocFields = assertNotNull(assocParsed.associationRequest())
        assertEquals(0x0021, assocFields.capabilityInfo)
        assertEquals(10, assocFields.listenInterval)
        assertNull(assocFields.currentAp)
        assertEquals(ssidText, assocFields.elements.first().value.decodeToString())

        val reassoc = Ieee80211.managementFrame(
            Ieee80211.SUBTYPE_REASSOC_REQUEST, bssid, station, bssid,
            ("2100" + "0a00" + "02deadbeef01" + ssidElementHex).hexToByteArray(),
        )
        val reassocParsed = assertNotNull(Ieee80211Frame.parse(reassoc))
        assertTrue(reassocParsed.isReassocRequest)
        val reassocFields = assertNotNull(reassocParsed.associationRequest())
        assertEquals(MacAddress.parse("02:de:ad:be:ef:01"), reassocFields.currentAp)
        assertEquals(1, reassocFields.elements.size)

        val deauth = assertNotNull(Ieee80211Frame.parse(Ieee80211.deauthentication(bssid, station, 6)))
        assertTrue(deauth.isDeauth)
        assertEquals(6, deauth.reasonCode())
        val disassoc = assertNotNull(Ieee80211Frame.parse(Ieee80211.disassociation(bssid, station, 8)))
        assertTrue(disassoc.isDisassoc)
        assertEquals(8, disassoc.reasonCode())
    }

    // ---- Building management frames ---------------------------------------------------------

    @Test
    fun `probe response is built byte for byte`() {
        val elements = (ssidElementHex + ratesElementHex + dsElementHex).hexToByteArray()

        val frame = Ieee80211.probeResponse(
            station = station,
            bssid = bssid,
            timestamp = 0L,
            beaconIntervalTu = 100,
            capabilityInfo = 0x0011,
            elements = elements,
            sequenceNumber = 2,
        )

        val expected =
            "5000" + // frame control: management, probe response
                "0000" + // duration
                "02aabbccdd01" + // addr1 = station
                "021122334455" + // addr2 = BSSID
                "021122334455" + // addr3 = BSSID
                "2000" + // sequence control: seq 2
                "0000000000000000" + // timestamp
                "6400" + // beacon interval 100 TU
                "1100" + // capability: ESS + Privacy
                ssidElementHex + ratesElementHex + dsElementHex
        assertEquals(expected, frame.toHexString())
    }

    @Test
    fun `probe response round trips through parse`() {
        val elements = (ssidElementHex + ratesElementHex + dsElementHex).hexToByteArray()
        val frame = Ieee80211.probeResponse(station, bssid, 0x0102030405060708L, 100, 0x0011, elements, sequenceNumber = 9)

        val parsed = assertNotNull(Ieee80211Frame.parse(frame))
        assertTrue(parsed.isProbeResponse)
        assertEquals(station, parsed.addr1)
        assertEquals(bssid, parsed.addr2)
        assertEquals(bssid, parsed.addr3)
        assertEquals(9, parsed.sequenceControl.sequenceNumber)
        val fields = assertNotNull(parsed.beaconFields())
        assertEquals(0x0102030405060708L, fields.timestamp)
        assertEquals(100, fields.beaconIntervalTu)
        assertEquals(0x0011, fields.capabilityInfo)
        assertEquals(listOf(0, 1, 3), fields.elements.map { it.id })
    }

    @Test
    fun `authentication response is built byte for byte`() {
        val frame = Ieee80211.authenticationResponse(
            station = station,
            bssid = bssid,
            algorithm = 0,
            transactionSequence = 2,
            statusCode = 0,
            sequenceNumber = 3,
        )

        val expected =
            "b000" + "0000" + "02aabbccdd01" + "021122334455" + "021122334455" + "3000" +
                "0000" + // algorithm: open system
                "0200" + // transaction sequence 2
                "0000" // status: success
        assertEquals(expected, frame.toHexString())
    }

    @Test
    fun `association response carries the AID with the two high bits set`() {
        val rates = (ratesElementHex).hexToByteArray()

        val frame = Ieee80211.associationResponse(
            station = station,
            bssid = bssid,
            capabilityInfo = 0x0011,
            statusCode = 0,
            aid = 1,
            elements = rates,
            sequenceNumber = 4,
        )

        val expected =
            "1000" + "0000" + "02aabbccdd01" + "021122334455" + "021122334455" + "4000" +
                "1100" + // capability
                "0000" + // status: success
                "01c0" + // AID 1 | 0xC000
                ratesElementHex
        assertEquals(expected, frame.toHexString())

        val fields = assertNotNull(assertNotNull(Ieee80211Frame.parse(frame)).associationResponseFields())
        assertEquals(1, fields.aid)
        assertEquals(0, fields.statusCode)
        assertEquals(0x0011, fields.capabilityInfo)
    }

    @Test
    fun `reassociation response uses the reassociation response subtype`() {
        val frame = Ieee80211.associationResponse(station, bssid, 0x0011, 0, 1, ByteArray(0), reassociation = true)

        assertEquals("3000", frame.copyOf(2).toHexString())
        assertTrue(assertNotNull(Ieee80211Frame.parse(frame)).isReassocResponse)
    }

    @Test
    fun `rejecting association response carries the status code`() {
        val frame = Ieee80211.associationResponse(station, bssid, 0x0011, StatusCodes.AP_FULL, 0, ByteArray(0))

        val fields = assertNotNull(assertNotNull(Ieee80211Frame.parse(frame)).associationResponseFields())
        assertEquals(17, fields.statusCode)
    }

    @Test
    fun `deauthentication and disassociation are built byte for byte`() {
        val deauth = Ieee80211.deauthentication(station, bssid, reasonCode = 6, sequenceNumber = 5)
        val disassoc = Ieee80211.disassociation(station, bssid, reasonCode = 8, sequenceNumber = 6)

        assertEquals(
            "c000" + "0000" + "02aabbccdd01" + "021122334455" + "021122334455" + "5000" + "0600",
            deauth.toHexString(),
        )
        assertEquals(
            "a000" + "0000" + "02aabbccdd01" + "021122334455" + "021122334455" + "6000" + "0800",
            disassoc.toHexString(),
        )
    }

    @Test
    fun `sequence number is masked to 12 bits when building`() {
        val frame = Ieee80211.deauthentication(station, bssid, 1, sequenceNumber = 4097)

        assertEquals(1, assertNotNull(Ieee80211Frame.parse(frame)).sequenceControl.sequenceNumber)
    }

    // ---- Building data frames ---------------------------------------------------------------

    @Test
    fun `from-DS data frame is built byte for byte with LLC SNAP`() {
        val body = LlcSnap.wrap(etherType = 0x0806, payload = "aabb".hexToByteArray())

        val frame = Ieee80211.fromDsData(
            station = station,
            bssid = bssid,
            source = sourceAddress,
            body = body,
            sequenceNumber = 5,
        )

        val expected =
            "0802" + // data, from DS
                "0000" +
                "02aabbccdd01" + // addr1 = DA (station)
                "021122334455" + // addr2 = TA (BSSID)
                "02deadbeef01" + // addr3 = SA
                "5000" +
                "aaaa03000000" + "0806" + "aabb"
        assertEquals(expected, frame.toHexString())

        val parsed = assertNotNull(Ieee80211Frame.parse(frame))
        assertTrue(parsed.isDataFrame)
        assertTrue(parsed.fromDs)
        assertFalse(parsed.toDs)
        assertFalse(parsed.isProtected)
        val snap = assertNotNull(LlcSnap.unwrap(parsed.body))
        assertEquals(0x0806, snap.etherType)
        assertEquals("aabb", snap.payload.toHexString())
    }

    @Test
    fun `from-DS data header sets the Protected bit for CCMP`() {
        val header = Ieee80211.fromDsDataHeader(station, bssid, sourceAddress, protectedFrame = true, sequenceNumber = 7)

        assertEquals(24, header.size)
        assertEquals("0842", header.copyOf(2).toHexString())
        assertEquals("02aabbccdd01" + "021122334455" + "02deadbeef01", header.copyOfRange(4, 22).toHexString())
        assertEquals("7000", header.copyOfRange(22, 24).toHexString())
    }

    @Test
    fun `llc snap rejects a body that is not SNAP`() {
        assertNull(LlcSnap.unwrap("0102030405060708".hexToByteArray()))
        assertNull(LlcSnap.unwrap("aaaa0300".hexToByteArray()))
        assertEquals("aaaa030000000800" + "010203", LlcSnap.wrap(0x0800, "010203".hexToByteArray()).toHexString())
    }

    // ---- Information elements ---------------------------------------------------------------

    @Test
    fun `information element build and parse round trip`() {
        val vendor = "0022aa0600".hexToByteArray()
        val bytes = listOf(
            InformationElements.ssid(ssidText.encodeToByteArray()),
            InformationElements.supportedRates("82848b96".hexToByteArray()),
            InformationElements.dsParameterSet(6),
            InformationElements.tim(dtimCount = 0, dtimPeriod = 1, bitmapControl = 0, partialVirtualBitmap = byteArrayOf(0)),
            InformationElements.erp(0x00),
            InformationElements.extendedSupportedRates("3048606c".hexToByteArray()),
            InformationElements.build(InformationElements.VENDOR_SPECIFIC, vendor),
        )

        assertEquals(
            ssidElementHex + "010482848b96" + dsElementHex + "050400010000" + "2a0100" + "32043048606c" + "dd050022aa0600",
            bytes.joinToString("") { it.toHexString() },
        )

        val parsed = InformationElements.parse(bytes.fold(ByteArray(0)) { acc, element -> acc + element })
        assertEquals(listOf(0, 1, 3, 5, 42, 50, 221), parsed.map { it.id })
        assertEquals(ssidText, parsed[0].value.decodeToString())
        assertEquals("82848b96", parsed[1].value.toHexString())
        assertEquals("06", parsed[2].value.toHexString())
        assertEquals("00010000", parsed[3].value.toHexString())
        assertEquals("00", parsed[4].value.toHexString())
        assertEquals("3048606c", parsed[5].value.toHexString())
        assertEquals("0022aa0600", parsed[6].value.toHexString())
        assertEquals(parsed[2], InformationElements.find(parsed, InformationElements.DS_PARAMETER_SET))
        assertNull(InformationElements.find(parsed, InformationElements.RSN))
        assertEquals(bytes[0].toHexString(), parsed[0].encode().toHexString())
    }

    @Test
    fun `information element parse keeps what precedes a truncated element`() {
        val bytes = ("030106" + "0505" + "0001").hexToByteArray() // DS ok, then TIM claims 5 octets but 2 follow

        val parsed = InformationElements.parse(bytes)

        assertEquals(listOf(3), parsed.map { it.id })
    }

    @Test
    fun `information element accepts a 255 octet value and a zero length value`() {
        val full = InformationElements.build(0xDD, ByteArray(255) { 0x55 })
        val empty = InformationElements.build(InformationElements.SSID, ByteArray(0))

        assertEquals(257, full.size)
        assertEquals("0000", empty.toHexString())
        assertEquals(255, InformationElements.parse(full).single().value.size)
        assertEquals(0, InformationElements.parse(empty).single().value.size)
        assertFailsWith<IllegalArgumentException> { InformationElements.build(0xDD, ByteArray(256)) }
    }

    @Test
    fun `information element builders validate their arguments`() {
        assertFailsWith<IllegalArgumentException> { InformationElements.ssid(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { InformationElements.dsParameterSet(0) }
        assertFailsWith<IllegalArgumentException> { InformationElements.dsParameterSet(15) }
    }
}
