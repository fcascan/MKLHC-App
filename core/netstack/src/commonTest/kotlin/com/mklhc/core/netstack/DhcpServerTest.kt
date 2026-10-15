package com.mklhc.core.netstack

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the single-lease DHCPv4 server ([DhcpServer]) and its BOOTP/DHCP codec ([DhcpMessage])
 * against RFC 2131 / RFC 2132.
 *
 * Requests are assembled byte by byte from the RFC 2131 figure 1 layout (236 fixed octets, the
 * magic cookie, then options) rather than through [DhcpMessage.encode], and the OFFER/ACK option
 * block is compared against hand-written octets, so a codec bug cannot hide behind a symmetric
 * encode/decode mistake.
 */
class DhcpServerTest {

    private val kartMacHex = "02aabbccdd01"
    private val otherMacHex = "02aabbccdd99"
    private val xidHex = "3903f326"
    private val magicCookieHex = "63825363"

    /** Builds a client -> server BOOTP/DHCP payload from raw fields; every argument is a hex string. */
    private fun messageHex(
        optionsHex: String,
        op: String = "01",
        macHex: String = kartMacHex,
        ciaddrHex: String = "00000000",
        flagsHex: String = "8000",
        cookieHex: String = magicCookieHex,
    ): ByteArray = (
        op + "01" + "06" + "00" + // op, htype Ethernet, hlen 6, hops 0
            xidHex + "0000" + flagsHex + // xid, secs, flags
            ciaddrHex + "00000000" + "00000000" + "00000000" + // ciaddr, yiaddr, siaddr, giaddr
            macHex + "00".repeat(10) + // chaddr (16 octets)
            "00".repeat(64) + "00".repeat(128) + // sname, file
            cookieHex + optionsHex
        ).hexToByteArray()

    /** A realistic client DISCOVER: type 1, client id, a requested address and a parameter request list. */
    private fun discover(macHex: String = kartMacHex): ByteArray = messageHex(
        macHex = macHex,
        optionsHex = "350101" + "3d0701" + macHex + "3204a9fe6242" + "3703010306" + "ff",
    )

    /** A SELECTING-state client REQUEST for the offered address, naming our server. */
    private fun request(macHex: String = kartMacHex, requestedHex: String = "a9fe6242", serverHex: String = "a9fe6241"): ByteArray =
        messageHex(
            macHex = macHex,
            optionsHex = "350103" + "3204" + requestedHex + "3604" + serverHex + "3d0701" + macHex + "ff",
        )

    private fun parse(bytes: ByteArray): DhcpMessage = assertNotNull(DhcpMessage.parse(bytes))

    // ---- parsing ------------------------------------------------------------------------------

    @Test
    fun aDiscoverIsParsedIntoItsHeaderFieldsAndOptions() {
        val message = parse(discover())

        assertEquals(DhcpMessage.OP_REQUEST, message.op)
        assertEquals(1, message.hardwareType)
        assertEquals(6, message.hardwareLength)
        assertEquals(0x3903f326, message.xid)
        assertEquals(0x8000, message.flags)
        assertTrue(message.isBroadcast)
        assertEquals(kartMacHex, message.clientMac.toHexString())
        assertEquals(DhcpMessageType.DISCOVER, message.messageType)
        assertEquals(AddressPlan.KART_LEASE, message.requestedIp)
        assertEquals(Ipv4Address.ANY, message.clientIp)
        assertEquals("010306", message.options.getValue(DhcpOption.PARAMETER_REQUEST_LIST).toHexString())
    }

    @Test
    fun parseSkipsPadOptionsAndStopsAtEnd() {
        val message = parse(messageHex("00" + "00" + "350103" + "00" + "ff" + "3204a9fe6299"))

        assertEquals(DhcpMessageType.REQUEST, message.messageType)
        assertNull(message.requestedIp, "options after the end marker must be ignored")
    }

    @Test
    fun parseRejectsAWrongMagicCookieATruncatedOptionAndAShortPayload() {
        assertNull(DhcpMessage.parse(messageHex("350101" + "ff", cookieHex = "63825364")))
        assertNull(DhcpMessage.parse(messageHex("350101" + "3204a9fe")), "option 50 claims 4 octets but only 2 follow")
        assertNull(DhcpMessage.parse(discover().copyOf(239)), "shorter than the fixed part plus the cookie")
    }

    @Test
    fun parseRejectsAHardwareAddressLongerThanTheChaddrField() {
        val bytes = discover()
        bytes[2] = 17

        assertNull(DhcpMessage.parse(bytes))
    }

    // ---- DISCOVER -> OFFER --------------------------------------------------------------------

    @Test
    fun aDiscoverIsAnsweredWithAnOfferOfTheSingleLease() {
        val server = DhcpServer()

        val offer = parse(assertNotNull(server.handlePayload(discover())))

        assertEquals(DhcpMessage.OP_REPLY, offer.op)
        assertEquals(1, offer.hardwareType)
        assertEquals(6, offer.hardwareLength)
        assertEquals(0x3903f326, offer.xid)
        assertEquals(0x8000, offer.flags, "the broadcast flag is echoed so the caller knows how to address the reply")
        assertEquals(Ipv4Address.ANY, offer.clientIp)
        assertEquals(AddressPlan.KART_LEASE, offer.yourIp)
        assertEquals(Ipv4Address.ANY, offer.serverIp)
        assertEquals(Ipv4Address.ANY, offer.gatewayIp)
        assertEquals(kartMacHex, offer.clientMac.toHexString())
        assertEquals(DhcpMessageType.OFFER, offer.messageType)
        assertEquals(AddressPlan.AP, offer.serverIdentifier)
        assertEquals(AddressPlan.NETMASK, offer.subnetMask)
        assertEquals(86_400L, offer.leaseSeconds)
    }

    @Test
    fun theOfferCarriesExactlyTheMirroredOptionSetAndNoRouterOrDns() {
        val server = DhcpServer()

        val wire = assertNotNull(server.handlePayload(discover()))
        val offer = parse(wire)

        val optionsHex = "350102" + "3604a9fe6241" + "330400015180" + "0104ffffffc0" + "ff"
        assertEquals(optionsHex, wire.copyOfRange(240, 240 + optionsHex.length / 2).toHexString())
        assertEquals(setOf(53, 54, 51, 1), offer.options.keys)
        assertFalse(DhcpOption.ROUTER in offer.options, "no router option: the kart link is a dead end")
        assertFalse(DhcpOption.DNS_SERVERS in offer.options, "no DNS option: the kart link is a dead end")
    }

    @Test
    fun theReplyIsPaddedToTheBootpMinimumOf300Octets() {
        val wire = assertNotNull(DhcpServer().handlePayload(discover()))

        assertTrue(wire.size >= 300, "BOOTP replies are at least 300 octets, got ${wire.size}")
        assertEquals(magicCookieHex, wire.copyOfRange(236, 240).toHexString())
    }

    @Test
    fun theFixedFieldsOfTheOfferAreWrittenAtTheRfc2131Offsets() {
        val wire = assertNotNull(DhcpServer().handlePayload(discover()))

        assertEquals("02" + "01" + "06" + "00", wire.copyOfRange(0, 4).toHexString())
        assertEquals(xidHex, wire.copyOfRange(4, 8).toHexString())
        assertEquals("00000000", wire.copyOfRange(12, 16).toHexString(), "ciaddr")
        assertEquals("a9fe6242", wire.copyOfRange(16, 20).toHexString(), "yiaddr")
        assertEquals("00000000", wire.copyOfRange(20, 24).toHexString(), "siaddr")
        assertEquals("00000000", wire.copyOfRange(24, 28).toHexString(), "giaddr")
        assertEquals(kartMacHex + "00".repeat(10), wire.copyOfRange(28, 44).toHexString(), "chaddr")
    }

    // ---- REQUEST -> ACK -----------------------------------------------------------------------

    @Test
    fun aRequestForTheOfferedAddressIsAnsweredWithAnAck() {
        val server = DhcpServer()

        val ack = parse(assertNotNull(server.handlePayload(request())))

        assertEquals(DhcpMessage.OP_REPLY, ack.op)
        assertEquals(0x3903f326, ack.xid)
        assertEquals(AddressPlan.KART_LEASE, ack.yourIp)
        assertEquals(DhcpMessageType.ACK, ack.messageType)
        assertEquals(AddressPlan.AP, ack.serverIdentifier)
        assertEquals(AddressPlan.NETMASK, ack.subnetMask)
        assertEquals(86_400L, ack.leaseSeconds)
        assertEquals(setOf(53, 54, 51, 1), ack.options.keys)
    }

    @Test
    fun aRenewingRequestThatCarriesTheAddressInCiaddrIsAcked() {
        val renewing = messageHex(ciaddrHex = "a9fe6242", flagsHex = "0000", optionsHex = "350103" + "ff")

        val ack = parse(assertNotNull(DhcpServer().handlePayload(renewing)))

        assertEquals(DhcpMessageType.ACK, ack.messageType)
        assertEquals(AddressPlan.KART_LEASE, ack.yourIp)
        assertEquals(0, ack.flags)
    }

    @Test
    fun aRequestForAnyOtherAddressIsRefusedWithANak() {
        val nak = parse(assertNotNull(DhcpServer().handlePayload(request(requestedHex = "a9fe6299"))))

        assertEquals(DhcpMessageType.NAK, nak.messageType)
        assertEquals(Ipv4Address.ANY, nak.yourIp)
        assertEquals(AddressPlan.AP, nak.serverIdentifier)
        assertEquals(0x3903f326, nak.xid)
    }

    @Test
    fun aRequestThatChoseAnotherServerIsIgnored() {
        val chosenElsewhere = request(serverHex = "c0a80101")

        assertNull(DhcpServer().handlePayload(chosenElsewhere))
    }

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    fun ourOwnOfferParsesBackAndReEncodesToTheSameBytes() {
        val wire = assertNotNull(DhcpServer().handlePayload(discover()))

        val reparsed = parse(wire)

        assertContentEquals(wire, reparsed.encode())
        assertEquals(DhcpMessageType.OFFER, reparsed.messageType)
        assertEquals(AddressPlan.KART_LEASE, reparsed.yourIp)
    }

    // ---- the single lease ---------------------------------------------------------------------

    @Test
    fun theLeaseIsBoundToTheClientThatWasAcked() {
        val server = DhcpServer()
        assertNull(server.boundClientMac)

        server.handlePayload(discover())
        assertNull(server.boundClientMac, "an offer alone binds nothing")

        server.handlePayload(request())
        assertEquals(kartMacHex, assertNotNull(server.boundClientMac).toHexString())
    }

    @Test
    fun aDifferentClientTakesOverTheLeaseBecauseTheApAdmitsOneStationAtATime() {
        val server = DhcpServer()
        server.handlePayload(request())

        val offer = parse(assertNotNull(server.handlePayload(discover(macHex = otherMacHex))))
        assertEquals(otherMacHex, offer.clientMac.toHexString())
        assertEquals(AddressPlan.KART_LEASE, offer.yourIp)

        server.handlePayload(request(macHex = otherMacHex))
        assertEquals(otherMacHex, assertNotNull(server.boundClientMac).toHexString())
    }

    @Test
    fun aReleaseFromTheBoundClientFreesTheLeaseAndAnyoneElsesIsIgnored() {
        val server = DhcpServer()
        server.handlePayload(request())

        val foreignRelease = messageHex(macHex = otherMacHex, optionsHex = "350107" + "ff")
        assertNull(server.handlePayload(foreignRelease))
        assertNotNull(server.boundClientMac)

        val ownRelease = messageHex(optionsHex = "350107" + "ff")
        assertNull(server.handlePayload(ownRelease), "a release is never answered")
        assertNull(server.boundClientMac)
    }

    @Test
    fun resetForgetsTheBoundClient() {
        val server = DhcpServer()
        server.handlePayload(request())

        server.reset()

        assertNull(server.boundClientMac)
    }

    // ---- things the server must not answer ----------------------------------------------------

    @Test
    fun messagesThatAreNotClientRequestsAreIgnored() {
        val server = DhcpServer()

        assertNull(server.handlePayload(messageHex(op = "02", optionsHex = "350101" + "ff")), "a BOOTREPLY")
        assertNull(server.handlePayload(messageHex(optionsHex = "ff")), "no message type option")
        assertNull(server.handlePayload(messageHex(optionsHex = "350104" + "ff")), "DECLINE")
        assertNull(server.handlePayload(messageHex(optionsHex = "350108" + "ff")), "INFORM")
        assertNull(server.handlePayload(ByteArray(10)), "not a DHCP payload at all")
    }

    @Test
    fun theWellKnownPortsAndDefaultLeaseTimeAreExposedForTheCaller() {
        assertEquals(67, DhcpServer.SERVER_PORT)
        assertEquals(68, DhcpServer.CLIENT_PORT)
        assertEquals(86_400, DhcpServer.DEFAULT_LEASE_SECONDS)
    }

    @Test
    fun aCustomLeaseTimeIsAdvertisedInOption51() {
        val server = DhcpServer(leaseSeconds = 3_600)

        val offer = parse(assertNotNull(server.handlePayload(discover())))

        assertEquals(3_600L, offer.leaseSeconds)
    }
}
