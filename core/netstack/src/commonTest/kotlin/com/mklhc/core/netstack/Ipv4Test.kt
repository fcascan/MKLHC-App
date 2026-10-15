package com.mklhc.core.netstack

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the IPv4 codec ([Ipv4Packet], [InternetChecksum], [Ipv4Receiver]) against RFC 791 and
 * the RFC 1071 checksum.
 *
 * Expected headers are written out by hand, field by field. The checksum values were computed
 * independently of this code (the 0xb861 header is the widely published worked example of the
 * RFC 1071 header checksum; the 0x1018 header was checked with a separate script).
 */
class Ipv4Test {

    private val ap = AddressPlan.AP
    private val kart = AddressPlan.KART_LEASE

    /** 20-byte header: ver/IHL 45, tos 0, len 33, id 0x1234, DF, ttl 64, UDP, checksum 0x1018, AP -> kart. */
    private val headerHex = "4500" + "0021" + "1234" + "4000" + "40" + "11" + "1018" + "a9fe6241" + "a9fe6242"
    private val payloadHex = "13881770000d788968656c6c6f"

    private fun samplePacket(
        moreFragments: Boolean = false,
        fragmentOffset: Int = 0,
        dontFragment: Boolean = true,
    ) = Ipv4Packet(
        source = ap,
        destination = kart,
        protocol = Ipv4Packet.PROTOCOL_UDP,
        payload = payloadHex.hexToByteArray(),
        identification = 0x1234,
        dontFragment = dontFragment,
        moreFragments = moreFragments,
        fragmentOffset = fragmentOffset,
        ttl = 64,
    )

    // ---- checksum -----------------------------------------------------------------------------

    @Test
    fun checksumMatchesThePublishedRfc1071HeaderExample() {
        val headerWithoutChecksum = "45000073000040004011" + "0000" + "c0a80001c0a800c7"

        assertEquals(0xb861, InternetChecksum.compute(headerWithoutChecksum.hexToByteArray()))
    }

    @Test
    fun checksumOverAHeaderThatCarriesItsOwnChecksumIsZero() {
        val header = "45000073000040004011" + "b861" + "c0a80001c0a800c7"

        assertEquals(0, InternetChecksum.compute(header.hexToByteArray()))
    }

    @Test
    fun checksumPadsAnOddTrailingByteWithZero() {
        // words 0x0102 and 0x0300 sum to 0x0402; the complement is 0xfbfd.
        assertEquals(0xfbfd, InternetChecksum.compute("010203".hexToByteArray()))
    }

    @Test
    fun checksumFoldsCarriesBackIntoTheSum() {
        // 0xffff + 0x0001 = 0x10000 -> folds to 0x0001 -> complement 0xfffe.
        assertEquals(0xfffe, InternetChecksum.compute("ffff0001".hexToByteArray()))
    }

    @Test
    fun checksumHonoursTheOffsetAndLength() {
        val bytes = "ee" + "010203" + "ee"

        assertEquals(0xfbfd, InternetChecksum.compute(bytes.hexToByteArray(), offset = 1, length = 3))
    }

    // ---- build / parse ------------------------------------------------------------------------

    @Test
    fun encodeWritesTheHeaderFieldByFieldWithAValidChecksum() {
        val wire = samplePacket().encode()

        assertEquals(headerHex + payloadHex, wire.toHexString())
        assertTrue(Ipv4Packet.hasValidHeaderChecksum(wire))
    }

    @Test
    fun encodeMatchesThePublishedRfc1071ExampleHeader() {
        val packet = Ipv4Packet(
            source = Ipv4Address.parse("192.168.0.1"),
            destination = Ipv4Address.parse("192.168.0.199"),
            protocol = Ipv4Packet.PROTOCOL_UDP,
            payload = ByteArray(95),
            identification = 0,
            dontFragment = true,
            ttl = 64,
        )

        assertEquals("45000073000040004011b861c0a80001c0a800c7", packet.encode().copyOf(20).toHexString())
    }

    @Test
    fun parseReadsEveryHeaderFieldBackFromTheWireForm() {
        val packet = assertNotNull(Ipv4Packet.parse((headerHex + payloadHex).hexToByteArray()))

        assertEquals(ap, packet.source)
        assertEquals(kart, packet.destination)
        assertEquals(Ipv4Packet.PROTOCOL_UDP, packet.protocol)
        assertEquals(0x1234, packet.identification)
        assertEquals(64, packet.ttl)
        assertEquals(0, packet.typeOfService)
        assertTrue(packet.dontFragment)
        assertFalse(packet.moreFragments)
        assertEquals(0, packet.fragmentOffset)
        assertFalse(packet.isFragment)
        assertContentEquals(payloadHex.hexToByteArray(), packet.payload)
    }

    @Test
    fun encodeThenParseRoundTripsIncludingHeaderOptions() {
        val original = Ipv4Packet(
            source = kart,
            destination = ap,
            protocol = Ipv4Packet.PROTOCOL_ICMP,
            payload = "0800f7ff00000000".hexToByteArray(),
            identification = 7,
            ttl = 32,
            typeOfService = 0x10,
            options = "01010101".hexToByteArray(), // four no-operation options -> IHL 6
        )

        val wire = original.encode()
        val parsed = assertNotNull(Ipv4Packet.parse(wire))

        assertEquals(0x46, wire[0].toInt() and 0xFF)
        assertEquals(24 + 8, wire.size)
        assertEquals(kart, parsed.source)
        assertEquals(ap, parsed.destination)
        assertEquals(Ipv4Packet.PROTOCOL_ICMP, parsed.protocol)
        assertEquals(0x10, parsed.typeOfService)
        assertContentEquals(original.options, parsed.options)
        assertContentEquals(original.payload, parsed.payload)
    }

    @Test
    fun theDontFragmentFlagIsHonouredInBothDirections() {
        val withDf = samplePacket(dontFragment = true).encode()
        val withoutDf = samplePacket(dontFragment = false).encode()

        assertEquals("4000", withDf.copyOfRange(6, 8).toHexString())
        assertEquals("0000", withoutDf.copyOfRange(6, 8).toHexString())
        assertTrue(assertNotNull(Ipv4Packet.parse(withDf)).dontFragment)
        assertFalse(assertNotNull(Ipv4Packet.parse(withoutDf)).dontFragment)
    }

    @Test
    fun parseTrimsLinkLayerPaddingBeyondTheTotalLength() {
        val padded = (headerHex + payloadHex).hexToByteArray() + ByteArray(10)

        val packet = assertNotNull(Ipv4Packet.parse(padded))

        assertContentEquals(payloadHex.hexToByteArray(), packet.payload)
    }

    @Test
    fun aCorruptedHeaderChecksumIsDetected() {
        val wire = (headerHex + payloadHex).hexToByteArray()
        wire[8] = (wire[8] + 1).toByte() // change the TTL without fixing the checksum

        assertFalse(Ipv4Packet.hasValidHeaderChecksum(wire))
        assertNull(Ipv4Packet.parse(wire))
    }

    @Test
    fun aCorruptedPayloadIsNotAHeaderChecksumError() {
        val wire = (headerHex + payloadHex).hexToByteArray()
        wire[wire.size - 1] = 0

        assertTrue(Ipv4Packet.hasValidHeaderChecksum(wire))
        assertNotNull(Ipv4Packet.parse(wire))
    }

    @Test
    fun parseRejectsMalformedHeaders() {
        val good = (headerHex + payloadHex).hexToByteArray()

        assertNull(Ipv4Packet.parse(good.copyOf(19)), "shorter than a minimal header")
        assertNull(Ipv4Packet.parse(good.copyOf(good.size - 1)), "total length larger than the bytes available")
        assertNull(Ipv4Packet.parse(withFixedChecksum(good.copyOf().also { it[0] = 0x65 })), "version 6")
        assertNull(Ipv4Packet.parse(withFixedChecksum(good.copyOf().also { it[0] = 0x44 })), "IHL 4 is below the minimum")
    }

    @Test
    fun encodeRejectsAPacketTooLargeForOneDatagram() {
        val tooBig = Ipv4Packet(ap, kart, Ipv4Packet.PROTOCOL_UDP, ByteArray(65_536 - 20 + 1))

        assertFailsWith<IllegalArgumentException> { tooBig.encode() }
    }

    // ---- fragments ----------------------------------------------------------------------------

    @Test
    fun parseReportsFragmentsButDoesNotReassembleThem() {
        val first = assertNotNull(Ipv4Packet.parse(samplePacket(moreFragments = true, dontFragment = false).encode()))
        val last = assertNotNull(Ipv4Packet.parse(samplePacket(fragmentOffset = 185, dontFragment = false).encode()))

        assertTrue(first.isFragment)
        assertTrue(first.moreFragments)
        assertTrue(last.isFragment)
        assertEquals(185, last.fragmentOffset)
    }

    @Test
    fun receiverDropsAndCountsFragments() {
        val receiver = Ipv4Receiver()

        assertNull(receiver.receive(samplePacket(moreFragments = true, dontFragment = false).encode()))
        assertEquals(1, receiver.fragmentsDropped)
        assertNull(receiver.receive(samplePacket(fragmentOffset = 185, dontFragment = false).encode()))
        assertEquals(2, receiver.fragmentsDropped)
    }

    @Test
    fun receiverDeliversWholePacketsWithoutCountingThem() {
        val receiver = Ipv4Receiver()

        val packet = assertNotNull(receiver.receive(samplePacket().encode()))

        assertEquals(kart, packet.destination)
        assertEquals(0, receiver.fragmentsDropped)
        assertEquals(0, receiver.malformedDropped)
    }

    @Test
    fun receiverCountsMalformedPacketsSeparatelyFromFragments() {
        val receiver = Ipv4Receiver()
        val corrupted = samplePacket().encode().also { it[8] = 1 }

        assertNull(receiver.receive(corrupted))
        assertNull(receiver.receive(ByteArray(5)))

        assertEquals(2, receiver.malformedDropped)
        assertEquals(0, receiver.fragmentsDropped)
    }

    /** Recomputes the header checksum of [wire] in place so only the field under test is wrong. */
    private fun withFixedChecksum(wire: ByteArray): ByteArray {
        val headerLength = ((wire[0].toInt() and 0x0F) * 4).coerceIn(20, wire.size)
        wire[10] = 0
        wire[11] = 0
        val checksum = InternetChecksum.compute(wire, 0, headerLength)
        wire[10] = (checksum shr 8).toByte()
        wire[11] = checksum.toByte()
        return wire
    }
}
