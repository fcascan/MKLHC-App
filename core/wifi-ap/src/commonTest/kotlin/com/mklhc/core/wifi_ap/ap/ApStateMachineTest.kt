package com.mklhc.core.wifi_ap.ap

import com.mklhc.core.wifi_ap.ap.ApTestFixtures.BSSID
import com.mklhc.core.wifi_ap.ap.ApTestFixtures.SSID
import com.mklhc.core.wifi_ap.ap.ApTestFixtures.STATION_A
import com.mklhc.core.wifi_ap.ap.ApTestFixtures.STATION_B
import com.mklhc.core.wifi_ap.crypto.Ccmp
import com.mklhc.core.wifi_ap.crypto.CcmpException
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.wifi_ap.mac.BeaconBuilder
import com.mklhc.core.wifi_ap.mac.FrameControl
import com.mklhc.core.wifi_ap.mac.Ieee80211
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.mac.ReasonCodes
import com.mklhc.core.wifi_ap.mac.SequenceControl
import com.mklhc.core.wifi_ap.mac.StatusCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the single-station [ApStateMachine]: it is driven with hand-built station frames (the
 * codec's generic [Ieee80211.managementFrame] builder with a hand-written body) and its output
 * frames are parsed back and checked for subtype, addresses and status.
 */
class ApStateMachineTest {

    private val primitives = CryptoPrimitives()
    private val config = ApTestFixtures.config()
    private val ap = ApStateMachine(config, primitives)

    // ---- Station-side frame builders --------------------------------------------------------

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun rateElement() = InformationElements.supportedRates("82848b96".hexToByteArray())

    private fun probeRequest(
        sender: MacAddress,
        ssid: String?,
        addr1: MacAddress = MacAddress.BROADCAST,
        addr3: MacAddress = MacAddress.BROADCAST,
    ): ByteArray {
        val ssidElement = if (ssid == null) ByteArray(0) else InformationElements.ssid(ssid.encodeToByteArray())
        return Ieee80211.managementFrame(
            Ieee80211.SUBTYPE_PROBE_REQUEST, addr1, sender, addr3, ssidElement + rateElement(),
        )
    }

    private fun authRequest(
        sender: MacAddress,
        algorithm: Int = 0,
        transaction: Int = 1,
        to: MacAddress = BSSID,
    ): ByteArray = Ieee80211.managementFrame(
        Ieee80211.SUBTYPE_AUTH, to, sender, to, le16(algorithm) + le16(transaction) + le16(0),
    )

    private fun assocRequest(sender: MacAddress, reassociation: Boolean = false): ByteArray {
        val ssidElement = InformationElements.ssid(SSID.encodeToByteArray())
        return if (reassociation) {
            Ieee80211.managementFrame(
                Ieee80211.SUBTYPE_REASSOC_REQUEST, BSSID, sender, BSSID,
                le16(0x0011) + le16(10) + BSSID.toByteArray() + ssidElement + rateElement(),
            )
        } else {
            Ieee80211.managementFrame(
                Ieee80211.SUBTYPE_ASSOC_REQUEST, BSSID, sender, BSSID,
                le16(0x0011) + le16(10) + ssidElement + rateElement(),
            )
        }
    }

    private fun deauthFrom(sender: MacAddress, reason: Int = ReasonCodes.DEAUTH_LEAVING) = Ieee80211.managementFrame(
        Ieee80211.SUBTYPE_DEAUTH, BSSID, sender, BSSID, le16(reason),
    )

    private fun disassocFrom(sender: MacAddress, reason: Int = ReasonCodes.DISASSOC_LEAVING) = Ieee80211.managementFrame(
        Ieee80211.SUBTYPE_DISASSOC, BSSID, sender, BSSID, le16(reason),
    )

    /** Asserts exactly one frame was returned and parses it. */
    private fun single(frames: List<ByteArray>): Ieee80211Frame {
        assertEquals(1, frames.size, "expected exactly one response frame")
        return assertNotNull(Ieee80211Frame.parse(frames[0]), "response must parse as an 802.11 frame")
    }

    private fun associate(station: MacAddress) {
        single(ap.onFrame(authRequest(station)))
        single(ap.onFrame(assocRequest(station)))
    }

    // ---- Probe ------------------------------------------------------------------------------

    @Test
    fun `starts in Beaconing with no station`() {
        assertEquals(ApState.Beaconing, ap.state)
        assertNull(ap.station)
        assertNull(ap.aid)
    }

    @Test
    fun `directed probe request gets a probe response carrying the real SSID`() {
        val response = single(ap.onFrame(probeRequest(STATION_A, SSID)))

        assertTrue(response.isProbeResponse)
        assertEquals(STATION_A, response.addr1)
        assertEquals(BSSID, response.addr2)
        assertEquals(BSSID, response.addr3)
        val fields = assertNotNull(response.beaconFields())
        val ssidElement = assertNotNull(InformationElements.find(fields.elements, InformationElements.SSID))
        assertEquals(SSID, ssidElement.value.decodeToString())
        assertEquals(ApState.Beaconing, ap.state)
    }

    @Test
    fun `probe request addressed to the BSSID with the right SSID is answered`() {
        val response = single(ap.onFrame(probeRequest(STATION_A, SSID, addr1 = BSSID, addr3 = BSSID)))

        assertTrue(response.isProbeResponse)
    }

    @Test
    fun `wildcard probe request is ignored because the AP is hidden`() {
        assertEquals(0, ap.onFrame(probeRequest(STATION_A, "")).size)
        assertEquals(0, ap.onFrame(probeRequest(STATION_A, null)).size)
    }

    @Test
    fun `probe request for a different SSID or a different BSSID is ignored`() {
        assertEquals(0, ap.onFrame(probeRequest(STATION_A, "other_network")).size)
        assertEquals(0, ap.onFrame(probeRequest(STATION_A, SSID, addr1 = STATION_B, addr3 = STATION_B)).size)
    }

    // ---- Authentication and association -----------------------------------------------------

    @Test
    fun `open system authentication gets a success response`() {
        val response = single(ap.onFrame(authRequest(STATION_A)))

        assertTrue(response.isAuth)
        assertEquals(STATION_A, response.addr1)
        assertEquals(BSSID, response.addr2)
        assertEquals(BSSID, response.addr3)
        val fields = assertNotNull(response.authentication())
        assertEquals(0, fields.algorithm)
        assertEquals(2, fields.transactionSequence)
        assertEquals(StatusCodes.SUCCESS, fields.statusCode)
        assertEquals(ApState.Beaconing, ap.state, "authenticated but not yet associated")
    }

    @Test
    fun `probe then authentication then association ends Associated with AID 1`() {
        val probe = single(ap.onFrame(probeRequest(STATION_A, SSID)))
        assertTrue(probe.isProbeResponse)

        val auth = single(ap.onFrame(authRequest(STATION_A)))
        assertTrue(auth.isAuth)
        assertEquals(StatusCodes.SUCCESS, assertNotNull(auth.authentication()).statusCode)
        assertEquals(ApState.Beaconing, ap.state)

        val assoc = single(ap.onFrame(assocRequest(STATION_A)))
        assertTrue(assoc.isAssocResponse)
        assertEquals(STATION_A, assoc.addr1)
        assertEquals(BSSID, assoc.addr2)
        assertEquals(BSSID, assoc.addr3)
        val fields = assertNotNull(assoc.associationResponseFields())
        assertEquals(StatusCodes.SUCCESS, fields.statusCode)
        assertEquals(1, fields.aid)
        assertEquals(BeaconBuilder.CAPABILITY_ESS or BeaconBuilder.CAPABILITY_PRIVACY, fields.capabilityInfo)
        // The rate elements are the AP's (Supported Rates + Extended Supported Rates).
        assertEquals(listOf(1, 50), fields.elements.map { it.id })

        assertEquals(ApState.Associated, ap.state)
        assertEquals(STATION_A, ap.station)
        assertEquals(1, ap.aid)
    }

    @Test
    fun `reassociation request gets a reassociation response`() {
        single(ap.onFrame(authRequest(STATION_A)))

        val response = single(ap.onFrame(assocRequest(STATION_A, reassociation = true)))

        assertTrue(response.isReassocResponse)
        assertEquals(StatusCodes.SUCCESS, assertNotNull(response.associationResponseFields()).statusCode)
        assertEquals(ApState.Associated, ap.state)
    }

    @Test
    fun `association request from a station that did not authenticate is answered with a deauthentication`() {
        val response = single(ap.onFrame(assocRequest(STATION_A)))

        assertTrue(response.isDeauth)
        assertEquals(STATION_A, response.addr1)
        assertEquals(ReasonCodes.CLASS2_FRAME_FROM_NONAUTH_STA, response.reasonCode())
        assertEquals(ApState.Beaconing, ap.state)
    }

    @Test
    fun `unsupported authentication algorithm and unexpected transaction are refused`() {
        val sharedKey = single(ap.onFrame(authRequest(STATION_A, algorithm = 1, transaction = 1)))
        val sharedFields = assertNotNull(sharedKey.authentication())
        assertEquals(1, sharedFields.algorithm)
        assertEquals(2, sharedFields.transactionSequence)
        assertEquals(StatusCodes.UNSUPPORTED_AUTH_ALGORITHM, sharedFields.statusCode)

        val badSequence = single(ap.onFrame(authRequest(STATION_A, algorithm = 0, transaction = 3)))
        assertEquals(StatusCodes.UNKNOWN_AUTH_TRANSACTION, assertNotNull(badSequence.authentication()).statusCode)

        // Neither attempt authenticated the station.
        assertTrue(single(ap.onFrame(assocRequest(STATION_A))).isDeauth)
    }

    @Test
    fun `management frames for another BSSID or truncated garbage are ignored`() {
        val otherBssid = MacAddress.parse("02:99:99:99:99:99")

        assertEquals(0, ap.onFrame(authRequest(STATION_A, to = otherBssid)).size)
        assertEquals(0, ap.onFrame(ByteArray(0)).size)
        assertEquals(0, ap.onFrame(ByteArray(10) { 0x40 }).size)
        // A data frame is not a management frame: the state machine does not answer it.
        val data = Ieee80211.fromDsData(STATION_A, BSSID, BSSID, ByteArray(4))
        assertEquals(0, ap.onFrame(data).size)
        assertEquals(ApState.Beaconing, ap.state)
    }

    // ---- Single-station policy --------------------------------------------------------------

    @Test
    fun `a second station is rejected with AP full and the first is not evicted`() {
        associate(STATION_A)
        assertEquals(ApState.Associated, ap.state)

        val auth = single(ap.onFrame(authRequest(STATION_B)))
        assertTrue(auth.isAuth)
        assertEquals(STATION_B, auth.addr1)
        assertEquals(StatusCodes.AP_FULL, assertNotNull(auth.authentication()).statusCode)

        val assoc = single(ap.onFrame(assocRequest(STATION_B)))
        assertTrue(assoc.isAssocResponse)
        assertEquals(STATION_B, assoc.addr1)
        assertEquals(StatusCodes.AP_FULL, assertNotNull(assoc.associationResponseFields()).statusCode)

        assertEquals(ApState.Associated, ap.state)
        assertEquals(STATION_A, ap.station)
        assertEquals(1, ap.aid)
    }

    @Test
    fun `a second station reassociation is also rejected with a reassociation response`() {
        associate(STATION_A)

        val response = single(ap.onFrame(assocRequest(STATION_B, reassociation = true)))

        assertTrue(response.isReassocResponse)
        assertEquals(StatusCodes.AP_FULL, assertNotNull(response.associationResponseFields()).statusCode)
        assertEquals(STATION_A, ap.station)
    }

    @Test
    fun `a deauthentication from another station does not drop the associated one`() {
        associate(STATION_A)

        assertEquals(0, ap.onFrame(deauthFrom(STATION_B)).size)
        assertEquals(0, ap.onFrame(disassocFrom(STATION_B)).size)

        assertEquals(ApState.Associated, ap.state)
        assertEquals(STATION_A, ap.station)
    }

    @Test
    fun `deauthentication from the station returns to Beaconing and frees the slot`() {
        associate(STATION_A)

        val responses = ap.onFrame(deauthFrom(STATION_A))

        assertEquals(0, responses.size, "the radio ACKs a deauthentication; nothing to transmit")
        assertEquals(ApState.Beaconing, ap.state)
        assertNull(ap.station)
        // The slot is free: another station can now join.
        associate(STATION_B)
        assertEquals(STATION_B, ap.station)
    }

    @Test
    fun `disassociation from the station returns to Beaconing`() {
        associate(STATION_A)

        assertEquals(0, ap.onFrame(disassocFrom(STATION_A)).size)

        assertEquals(ApState.Beaconing, ap.state)
        assertNull(ap.station)
        // The station was dropped entirely: it must authenticate again.
        assertTrue(single(ap.onFrame(assocRequest(STATION_A))).isDeauth)
    }

    @Test
    fun `an associated station that authenticates again is moved back to the authenticated state`() {
        associate(STATION_A)

        val auth = single(ap.onFrame(authRequest(STATION_A)))

        assertEquals(StatusCodes.SUCCESS, assertNotNull(auth.authentication()).statusCode)
        assertEquals(ApState.Beaconing, ap.state)
        assertTrue(single(ap.onFrame(assocRequest(STATION_A))).isAssocResponse)
        assertEquals(ApState.Associated, ap.state)
    }

    @Test
    fun `an associated station that associates again keeps its AID`() {
        associate(STATION_A)

        val response = single(ap.onFrame(assocRequest(STATION_A)))

        assertEquals(StatusCodes.SUCCESS, assertNotNull(response.associationResponseFields()).statusCode)
        assertEquals(1, assertNotNull(response.associationResponseFields()).aid)
        assertEquals(ApState.Associated, ap.state)
    }

    @Test
    fun `response frames use incrementing sequence numbers`() {
        val first = single(ap.onFrame(probeRequest(STATION_A, SSID))).sequenceControl.sequenceNumber
        val second = single(ap.onFrame(authRequest(STATION_A))).sequenceControl.sequenceNumber
        val third = single(ap.onFrame(assocRequest(STATION_A))).sequenceControl.sequenceNumber

        assertEquals((first + 1) % 4096, second)
        assertEquals((second + 1) % 4096, third)
    }

    // ---- Dropping a station -----------------------------------------------------------------

    @Test
    fun `dropStation returns a deauthentication for inactivity and forgets the station entirely`() {
        associate(STATION_A)
        val seen = ccmp.encrypt(config.network.ccmpKey, toDsHeader(STATION_A, MacAddress.BROADCAST), payload, pn(5))
        ap.decryptData(seen)

        val deauth = assertNotNull(Ieee80211Frame.parse(assertNotNull(ap.dropStation(ReasonCodes.DISASSOC_INACTIVITY))))

        assertTrue(deauth.isDeauth)
        assertEquals(STATION_A, deauth.addr1)
        assertEquals(BSSID, deauth.addr2)
        assertEquals(BSSID, deauth.addr3)
        assertEquals(4, deauth.reasonCode())
        assertEquals(ApState.Beaconing, ap.state)
        assertNull(ap.station)
        assertNull(ap.aid)
        // The replay window starts afresh: a packet number already seen is accepted again.
        assertEquals(payload.toHexString(), ap.decryptData(seen).toHexString())
        // No longer authenticated either: an association request is answered as from an unknown station.
        val refused = single(ap.onFrame(assocRequest(STATION_A)))
        assertTrue(refused.isDeauth)
        assertEquals(ReasonCodes.CLASS2_FRAME_FROM_NONAUTH_STA, refused.reasonCode())
        assertEquals(ApState.Beaconing, ap.state)
        // Authenticating and associating again works as usual.
        associate(STATION_A)
        assertEquals(ApState.Associated, ap.state)
        assertEquals(STATION_A, ap.station)
    }

    @Test
    fun `dropStation returns null and changes nothing when no station is associated`() {
        assertNull(ap.dropStation(ReasonCodes.DISASSOC_INACTIVITY))

        // Authenticated but not associated: nothing to drop, and the station can still associate.
        single(ap.onFrame(authRequest(STATION_A)))
        assertNull(ap.dropStation(ReasonCodes.DISASSOC_INACTIVITY))
        assertTrue(single(ap.onFrame(assocRequest(STATION_A))).isAssocResponse)
        assertEquals(ApState.Associated, ap.state)
    }

    // ---- Data from a station that is not associated -----------------------------------------

    /** A null data frame from [sender] to [to], to-DS unless told otherwise; 4-address when both DS bits are set. */
    private fun nullData(
        sender: MacAddress,
        to: MacAddress = BSSID,
        toDs: Boolean = true,
        fromDs: Boolean = false,
    ): Ieee80211Frame = Ieee80211Frame(
        frameControl = FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_NULL, toDs = toDs, fromDs = fromDs),
        addr1 = to,
        addr2 = sender,
        addr3 = to,
        addr4 = if (toDs && fromDs) sender else null,
    )

    /** Asserts [frame] is a reason 7 deauthentication from our BSSID to [station] and returns its sequence number. */
    private fun assertClass3Deauthentication(station: MacAddress, frame: ByteArray?): Int {
        val deauth = assertNotNull(Ieee80211Frame.parse(assertNotNull(frame, "a deauthentication is expected")))
        assertTrue(deauth.isDeauth)
        assertEquals(station, deauth.addr1)
        assertEquals(BSSID, deauth.addr2)
        assertEquals(BSSID, deauth.addr3)
        assertEquals(ReasonCodes.CLASS3_FRAME_FROM_NONASSOC_STA, deauth.reasonCode())
        return deauth.sequenceControl.sequenceNumber
    }

    @Test
    fun `data from a station that is not associated is answered with a reason 7 deauthentication`() {
        val probeSequence = single(ap.onFrame(probeRequest(STATION_A, SSID))).sequenceControl.sequenceNumber
        val protectedData = assertNotNull(
            Ieee80211Frame.parse(ccmp.encrypt(config.network.ccmpKey, toDsHeader(STATION_A, MacAddress.BROADCAST), payload, pn(1))),
        )

        val first = assertClass3Deauthentication(STATION_A, ap.rejectUnassociatedData(protectedData))
        val second = assertClass3Deauthentication(STATION_A, ap.rejectUnassociatedData(nullData(STATION_A)))

        // The answers take their numbers from the counter every transmitted frame shares.
        assertEquals((probeSequence + 1) % 4096, first)
        assertEquals((first + 1) % 4096, second)
        assertEquals(ApState.Beaconing, ap.state)
    }

    @Test
    fun `a station dropped for inactivity that keeps sending data is told again with reason 7 and can join again`() {
        associate(STATION_A)
        assertNotNull(ap.dropStation(ReasonCodes.DISASSOC_INACTIVITY))

        assertClass3Deauthentication(STATION_A, ap.rejectUnassociatedData(nullData(STATION_A)))

        associate(STATION_A)
        assertEquals(STATION_A, ap.station)
        assertNull(ap.rejectUnassociatedData(nullData(STATION_A)), "associated again: its data needs no answer")
    }

    @Test
    fun `a reason 7 answer ends the authentication of a station that had only authenticated`() {
        single(ap.onFrame(authRequest(STATION_A)))

        assertClass3Deauthentication(STATION_A, ap.rejectUnassociatedData(nullData(STATION_A)))

        // It was told it is deauthenticated, so the machine forgets it too: it must authenticate before associating.
        val refused = single(ap.onFrame(assocRequest(STATION_A)))
        assertTrue(refused.isDeauth)
        assertEquals(ReasonCodes.CLASS2_FRAME_FROM_NONAUTH_STA, refused.reasonCode())
        associate(STATION_A)
        assertEquals(ApState.Associated, ap.state)
    }

    @Test
    fun `the associated station, other networks, 4-address frames and group senders get no reason 7 answer`() {
        associate(STATION_A)
        val otherBssid = MacAddress.parse("02:99:99:99:99:99")
        val groupSender = MacAddress.parse("03:00:00:00:00:01")
        val unanswered = mapOf(
            "the associated station's data" to nullData(STATION_A),
            "data to another BSSID" to nullData(STATION_B, to = otherBssid),
            "data that is not to-DS" to nullData(STATION_B, toDs = false),
            "another access point's from-DS data" to nullData(otherBssid, to = STATION_B, toDs = false, fromDs = true),
            "a 4-address frame" to nullData(STATION_B, fromDs = true),
            "a group sender" to nullData(groupSender),
            "a management frame" to assertNotNull(Ieee80211Frame.parse(authRequest(STATION_B))),
        )

        for ((what, frame) in unanswered) assertNull(ap.rejectUnassociatedData(frame), what)

        assertEquals(STATION_A, ap.station)
        // Another station's to-DS data to our BSSID is answered (it is not the associated one), and changes nothing here.
        assertClass3Deauthentication(STATION_B, ap.rejectUnassociatedData(nullData(STATION_B)))
        assertEquals(ApState.Associated, ap.state)
        assertEquals(STATION_A, ap.station)
    }

    // ---- Beacon -----------------------------------------------------------------------------

    @Test
    fun `beacon is the hidden SSID beacon built from the config`() {
        val beacon = ap.beacon()

        val expected = BeaconBuilder(
            bssid = config.bssid,
            ssid = config.ssid,
            channel = config.channel,
            beaconIntervalTu = config.beaconIntervalTu,
            vendorElements = config.network.vendorElements,
        ).beacon()
        assertEquals(expected.toHexString(), beacon.toHexString())

        val frame = assertNotNull(Ieee80211Frame.parse(beacon))
        assertTrue(frame.isBeacon)
        val ssid = assertNotNull(InformationElements.find(assertNotNull(frame.beaconFields()).elements, InformationElements.SSID))
        assertEquals(SSID.length, ssid.value.size)
        assertTrue(ssid.value.all { it == 0.toByte() })
    }

    // ---- Data plane -------------------------------------------------------------------------

    private val ccmp = Ccmp(primitives)

    private fun pn(value: Long): ByteArray = ByteArray(6) { index -> (value shr ((5 - index) * 8)).toByte() }

    private fun toDsHeader(sender: MacAddress, destination: MacAddress, sequenceNumber: Int = 0): ByteArray =
        Ieee80211Frame(
            frameControl = FrameControl.of(Ieee80211.TYPE_DATA, 0, toDs = true, protectedFrame = true),
            addr1 = BSSID,
            addr2 = sender,
            addr3 = destination,
            sequenceControl = SequenceControl.of(sequenceNumber, 0),
        ).toBytes()

    private val payload = LlcSnap.wrap(0x0800, "45000014deadbeef".hexToByteArray())

    @Test
    fun `encryptData builds a protected from-DS MPDU that decrypts with the config key`() {
        val source = MacAddress.parse("02:de:ad:be:ef:01")

        val mpdu = ap.encryptData(destination = STATION_A, source = source, body = payload)

        val frame = assertNotNull(Ieee80211Frame.parse(mpdu))
        assertTrue(frame.isDataFrame)
        assertTrue(frame.fromDs)
        assertTrue(!frame.toDs)
        assertTrue(frame.isProtected)
        assertEquals(STATION_A, frame.addr1)
        assertEquals(BSSID, frame.addr2)
        assertEquals(source, frame.addr3)
        // header(24) + CCMP header(8) + body + MIC(8)
        assertEquals(24 + 8 + payload.size + 8, mpdu.size)
        val recovered = Ccmp(primitives).decrypt(config.network.ccmpKey, mpdu)
        assertEquals(payload.toHexString(), recovered.toHexString())
    }

    @Test
    fun `encryptData uses key id 0 and a monotonically increasing packet number`() {
        val first = ap.encryptData(STATION_A, BSSID, payload)
        val second = ap.encryptData(STATION_A, BSSID, payload)

        // CCMP header follows the 24-octet header: PN0, PN1, rsvd, keyId|ExtIV, PN2..PN5.
        assertEquals("010000" + "20", first.copyOfRange(24, 28).toHexString())
        assertEquals("020000" + "20", second.copyOfRange(24, 28).toHexString())
        // Sequence numbers advance too.
        assertEquals(
            (assertNotNull(Ieee80211Frame.parse(first)).sequenceControl.sequenceNumber + 1) % 4096,
            assertNotNull(Ieee80211Frame.parse(second)).sequenceControl.sequenceNumber,
        )
    }

    @Test
    fun `decryptData recovers the body of a station frame encrypted with the config key`() {
        associate(STATION_A)
        val header = toDsHeader(STATION_A, MacAddress.BROADCAST, sequenceNumber = 12)
        val mpdu = ccmp.encrypt(config.network.ccmpKey, header, payload, pn(1))

        val recovered = ap.decryptData(mpdu)

        assertEquals(payload.toHexString(), recovered.toHexString())
    }

    @Test
    fun `decryptData accepts key id 1 as well as key id 0`() {
        val header = toDsHeader(STATION_A, MacAddress.BROADCAST)

        val withKeyId0 = ccmp.encrypt(config.network.ccmpKey, header, payload, pn(1), keyId = 0)
        val withKeyId1 = ccmp.encrypt(config.network.ccmpKey, header, payload, pn(2), keyId = 1)

        assertEquals(payload.toHexString(), ap.decryptData(withKeyId0).toHexString())
        assertEquals(payload.toHexString(), ap.decryptData(withKeyId1).toHexString())
    }

    @Test
    fun `decryptData enforces the replay window and the MIC`() {
        val header = toDsHeader(STATION_A, MacAddress.BROADCAST)
        val mpdu = ccmp.encrypt(config.network.ccmpKey, header, payload, pn(7))
        ap.decryptData(mpdu)

        assertFailsWith<CcmpException> { ap.decryptData(mpdu) }

        val tampered = ccmp.encrypt(config.network.ccmpKey, header, payload, pn(8))
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<CcmpException> { ap.decryptData(tampered) }
    }

    @Test
    fun `decryptData rejects frames that are not protected data frames`() {
        val clear = Ieee80211.fromDsData(STATION_A, BSSID, BSSID, payload)
        val management = authRequest(STATION_A)

        assertFailsWith<CcmpException> { ap.decryptData(clear) }
        assertFailsWith<CcmpException> { ap.decryptData(management) }
        assertFailsWith<CcmpException> { ap.decryptData(ByteArray(5)) }
    }

    @Test
    fun `a new association starts a fresh replay window`() {
        associate(STATION_A)
        val header = toDsHeader(STATION_A, MacAddress.BROADCAST)
        val mpdu = ccmp.encrypt(config.network.ccmpKey, header, payload, pn(5))
        ap.decryptData(mpdu)
        assertFailsWith<CcmpException> { ap.decryptData(mpdu) }

        ap.onFrame(deauthFrom(STATION_A))
        associate(STATION_A)

        // The station restarts its packet numbers after re-associating.
        assertEquals(payload.toHexString(), ap.decryptData(mpdu).toHexString())
    }
}
