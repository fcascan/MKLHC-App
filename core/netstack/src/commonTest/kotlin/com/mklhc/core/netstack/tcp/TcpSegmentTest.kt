package com.mklhc.core.netstack.tcp

import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.Ipv4Address
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the TCP header + options codec ([TcpSegment]) against RFC 9293 / RFC 793, including the
 * IPv4 pseudo-header checksum (RFC 1071).
 *
 * The two reference wire forms and their checksums (0x7ef0, 0x52ce) were computed independently of
 * this code with a separate script, then written out here by hand, so a codec bug cannot hide behind
 * a symmetric encode/decode mistake.
 */
class TcpSegmentTest {

    private val ap = AddressPlan.AP
    private val kart = AddressPlan.KART_LEASE

    /** SYN+ACK carrying the MSS option: 5201 -> 40000, seq 0x01020304, ack 0x05060708, win 0x4000, MSS 1460. */
    private val synAckHex = "1451" + "9c40" + "01020304" + "05060708" + "6012" + "4000" + "7ef0" + "0000" + "020405b4"

    /** A PSH+ACK data segment "hello": 40000 -> 5201, seq 0x05060708, ack 0x01020305, win 0x4000. */
    private val dataHex = "9c40" + "1451" + "05060708" + "01020305" + "5018" + "4000" + "52ce" + "0000" + "68656c6c6f"

    private fun synAck() = TcpSegment(
        sourcePort = 5201,
        destinationPort = 40000,
        sequenceNumber = 0x01020304L,
        acknowledgmentNumber = 0x05060708L,
        flags = TcpSegment.SYN or TcpSegment.ACK,
        window = 0x4000,
        maxSegmentSize = 1460,
    )

    private fun data() = TcpSegment(
        sourcePort = 40000,
        destinationPort = 5201,
        sequenceNumber = 0x05060708L,
        acknowledgmentNumber = 0x01020305L,
        flags = TcpSegment.PSH or TcpSegment.ACK,
        window = 0x4000,
        payload = "hello".encodeToByteArray(),
    )

    // ---- encode -------------------------------------------------------------------------------

    @Test
    fun encodeWritesTheSynAckHeaderWithTheMssOptionAndChecksum() {
        assertEquals(synAckHex, synAck().encode(ap, kart).toHexString())
    }

    @Test
    fun encodeWritesADataSegmentWithThePseudoHeaderChecksum() {
        assertEquals(dataHex, data().encode(ap, kart).toHexString())
    }

    @Test
    fun aSegmentWithoutAnMssOptionHasTheMinimalTwentyByteHeader() {
        val wire = data().encode(ap, kart)

        val dataOffsetWords = (wire[12].toInt() ushr 4) and 0x0F
        assertEquals(5, dataOffsetWords, "data offset is 5 words (20 octets) with no options")
        assertEquals(20 + "hello".length, wire.size)
    }

    // ---- parse --------------------------------------------------------------------------------

    @Test
    fun parseReadsEveryHeaderFieldAndTheMssOption() {
        val segment = assertNotNull(TcpSegment.parse(synAckHex.hexToByteArray(), ap, kart))

        assertEquals(5201, segment.sourcePort)
        assertEquals(40000, segment.destinationPort)
        assertEquals(0x01020304L, segment.sequenceNumber)
        assertEquals(0x05060708L, segment.acknowledgmentNumber)
        assertTrue(segment.isSyn)
        assertTrue(segment.isAck)
        assertFalse(segment.isFin)
        assertFalse(segment.isRst)
        assertEquals(0x4000, segment.window)
        assertEquals(1460, segment.maxSegmentSize)
        assertEquals(0, segment.payload.size)
    }

    @Test
    fun parseReadsADataSegmentPayload() {
        val segment = assertNotNull(TcpSegment.parse(dataHex.hexToByteArray(), kart, ap))

        assertTrue(segment.isPsh)
        assertTrue(segment.isAck)
        assertNull(segment.maxSegmentSize)
        assertContentEquals("hello".encodeToByteArray(), segment.payload)
    }

    @Test
    fun aSegmentSurvivesAnEncodeParseRoundTrip() {
        val encoded = synAck().encode(ap, kart)

        val parsed = assertNotNull(TcpSegment.parse(encoded, ap, kart))

        assertContentEquals(encoded, parsed.encode(ap, kart))
        assertEquals(1460, parsed.maxSegmentSize)
    }

    // ---- checksum -----------------------------------------------------------------------------

    @Test
    fun theChecksumOverThePseudoHeaderAndTheSegmentValidates() {
        assertTrue(TcpSegment.hasValidChecksum(synAckHex.hexToByteArray(), ap, kart))
        assertTrue(TcpSegment.hasValidChecksum(dataHex.hexToByteArray(), kart, ap))
    }

    @Test
    fun aChecksumComputedForOtherAddressesFailsValidation() {
        val wrongDestination = Ipv4Address.parse("169.254.98.67")

        assertFalse(TcpSegment.hasValidChecksum(synAckHex.hexToByteArray(), ap, wrongDestination))
        assertNull(TcpSegment.parse(synAckHex.hexToByteArray(), ap, wrongDestination))
    }

    @Test
    fun parseRejectsASegmentWhosePayloadWasCorrupted() {
        val wire = dataHex.hexToByteArray()
        wire[wire.size - 1] = 'x'.code.toByte()

        assertFalse(TcpSegment.hasValidChecksum(wire, kart, ap))
        assertNull(TcpSegment.parse(wire, kart, ap))
    }

    @Test
    fun parseWithoutAddressesSkipsTheOptionalChecksumCheck() {
        val wire = dataHex.hexToByteArray()
        wire[wire.size - 1] = 'x'.code.toByte()

        assertNotNull(TcpSegment.parse(wire))
    }

    // ---- malformed buffers --------------------------------------------------------------------

    @Test
    fun parseRejectsBuffersShorterThanAHeaderOrThanTheirDataOffset() {
        assertNull(TcpSegment.parse(dataHex.hexToByteArray().copyOf(19)), "shorter than the 20-octet header")
        // data offset claims 15 words (60 octets) but only 20 are present.
        val lyingOffset = dataHex.hexToByteArray()
        lyingOffset[12] = (0x0F shl 4).toByte()
        assertNull(TcpSegment.parse(lyingOffset), "data offset larger than the buffer")
        // data offset below the minimum of 5 words.
        val tooSmall = dataHex.hexToByteArray()
        tooSmall[12] = (0x04 shl 4).toByte()
        assertNull(TcpSegment.parse(tooSmall), "data offset below 5 words")
    }

    // ---- options ------------------------------------------------------------------------------

    @Test
    fun parseSkipsNopAndUnknownOptionsAndStopsAtEndOfList() {
        // doff = 7 words (28-octet header): window-scale (unknown, kind 3), MSS, then end-of-list + pad.
        val handCrafted = "04d2" + "162e" + "00000000" + "00000000" + "7010" + "4000" + "0000" + "0000" +
            "030307" + "0204" + "05b4" + "00" + "6869"

        val segment = assertNotNull(TcpSegment.parse(handCrafted.hexToByteArray()))

        assertEquals(1460, segment.maxSegmentSize, "the MSS option is found past an unknown option")
        assertTrue(segment.isAck)
        assertContentEquals("hi".encodeToByteArray(), segment.payload)
    }

    // ---- flags --------------------------------------------------------------------------------

    @Test
    fun theFlagHelpersMatchTheFlagBits() {
        val rst = TcpSegment(1, 2, 0L, 0L, flags = TcpSegment.RST or TcpSegment.ACK, window = 0)

        assertTrue(rst.isRst)
        assertTrue(rst.isAck)
        assertFalse(rst.isSyn)
        assertFalse(rst.isFin)
        assertFalse(rst.isPsh)
    }
}
