package com.mklhc.core.netstack

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the ARP codec and responder ([ArpPacket], [Arp]) against RFC 826.
 *
 * The on-wire forms are written out by hand, field by field, so a codec bug cannot hide behind a
 * symmetric encode/decode mistake.
 */
class ArpTest {

    private val ourMac = "021122334455".hexToByteArray()
    private val ourIp = AddressPlan.AP
    private val kartMac = "02aabbccdd01".hexToByteArray()
    private val kartIp = AddressPlan.KART_LEASE

    /** who-has 169.254.98.65 tell 169.254.98.66, as the kart would broadcast it. */
    private val whoHasOursHex =
        "0001" + "0800" + "06" + "04" + "0001" + // htype Ethernet, ptype IPv4, hlen 6, plen 4, oper request
            "02aabbccdd01" + "a9fe6242" + // sha, spa = kart
            "000000000000" + "a9fe6241" // tha unknown, tpa = our address

    @Test
    fun decodeReadsEveryFieldOfAWhoHasRequest() {
        val packet = assertNotNull(ArpPacket.decode(whoHasOursHex.hexToByteArray()))

        assertEquals(1, packet.hardwareType)
        assertEquals(0x0800, packet.protocolType)
        assertEquals(6, packet.hardwareLength)
        assertEquals(4, packet.protocolLength)
        assertEquals(Arp.OPERATION_REQUEST, packet.operation)
        assertContentEquals(kartMac, packet.senderMac)
        assertEquals(kartIp, packet.senderIp)
        assertContentEquals(ByteArray(6), packet.targetMac)
        assertEquals(ourIp, packet.targetIp)
    }

    @Test
    fun encodeProducesTheTwentyEightByteWireForm() {
        val packet = ArpPacket(
            operation = Arp.OPERATION_REQUEST,
            senderMac = kartMac,
            senderIp = kartIp,
            targetMac = ByteArray(6),
            targetIp = ourIp,
        )

        assertEquals(28, packet.encode().size)
        assertEquals(whoHasOursHex, packet.encode().toHexString())
    }

    @Test
    fun whoHasForOurAddressIsAnsweredWithAReplyAddressedToTheRequester() {
        val request = assertNotNull(ArpPacket.decode(whoHasOursHex.hexToByteArray()))

        val reply = assertNotNull(Arp.handle(request, ourMac, ourIp))

        assertEquals(Arp.OPERATION_REPLY, reply.operation)
        assertContentEquals(ourMac, reply.senderMac)
        assertEquals(ourIp, reply.senderIp)
        assertContentEquals(kartMac, reply.targetMac)
        assertEquals(kartIp, reply.targetIp)
        assertEquals(
            "0001" + "0800" + "06" + "04" + "0002" + "021122334455" + "a9fe6241" + "02aabbccdd01" + "a9fe6242",
            reply.encode().toHexString(),
        )
    }

    @Test
    fun whoHasForAnotherAddressIsIgnored() {
        val request = ArpPacket(
            operation = Arp.OPERATION_REQUEST,
            senderMac = kartMac,
            senderIp = kartIp,
            targetMac = ByteArray(6),
            targetIp = Ipv4Address.parse("169.254.98.99"),
        )

        assertNull(Arp.handle(request, ourMac, ourIp))
    }

    @Test
    fun anArpReplyIsNeverAnsweredEvenWhenItNamesOurAddress() {
        val reply = ArpPacket(
            operation = Arp.OPERATION_REPLY,
            senderMac = kartMac,
            senderIp = kartIp,
            targetMac = ourMac,
            targetIp = ourIp,
        )

        assertNull(Arp.handle(reply, ourMac, ourIp))
    }

    @Test
    fun decodeRoundTripsAnEncodedPacket() {
        val original = ArpPacket(
            operation = Arp.OPERATION_REPLY,
            senderMac = ourMac,
            senderIp = ourIp,
            targetMac = kartMac,
            targetIp = kartIp,
        )

        val decoded = assertNotNull(ArpPacket.decode(original.encode()))

        assertEquals(original, decoded)
    }

    @Test
    fun decodeRejectsTruncatedAndNonEthernetIpv4Packets() {
        val whoHas = whoHasOursHex.hexToByteArray()

        assertNull(ArpPacket.decode(whoHas.copyOf(27)), "27 bytes is one short of an ARP packet")
        assertNull(ArpPacket.decode(whoHasOursHex.replaceRange(0, 4, "0006").hexToByteArray()), "htype 6 is not Ethernet")
        assertNull(ArpPacket.decode(whoHasOursHex.replaceRange(4, 8, "86dd").hexToByteArray()), "ptype 0x86dd is not IPv4")
        assertNull(ArpPacket.decode(whoHasOursHex.replaceRange(8, 10, "08").hexToByteArray()), "hlen 8 is not 6")
    }

    @Test
    fun decodeIgnoresTrailingLinkPadding() {
        val padded = whoHasOursHex.hexToByteArray() + ByteArray(18)

        assertNotNull(ArpPacket.decode(padded))
    }
}
