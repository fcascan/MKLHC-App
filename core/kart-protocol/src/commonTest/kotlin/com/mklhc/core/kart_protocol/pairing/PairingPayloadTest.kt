package com.mklhc.core.kart_protocol.pairing

import com.mklhc.core.kart_protocol.fuji.assertBytes
import com.mklhc.core.kart_protocol.hexBytes
import com.mklhc.core.kart_protocol.zeros
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Known-answer tests for [encodePairingPayload].
 *
 * The vectors are hand-built from the pairing QR payload layout of the community protocol wiki (Fuji
 * kart protocol), 0x3E octets: [0x00..0x0F] seed, [0x10..0x2F] SSID in UTF-8 zero-padded to 0x20,
 * [0x30..0x31] channel u16 little-endian (0 = the current channel), [0x32..0x3D] zero padding.
 */
class PairingPayloadTest {

    private val seed = hexBytes("00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f")

    @Test
    fun `a fixed seed, ssid and channel encode to the exact 62 octets`() {
        // "kart-pair-01" in ASCII is 6b 61 72 74 2d 70 61 69 72 2d 30 31 (12 octets, 20 zeros of padding);
        // channel 11 is 0x000b, stored low octet first as 0b 00; then 12 zero octets of padding.
        val expected = hexBytes(
            "00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f " +
                "6b 61 72 74 2d 70 61 69  72 2d 30 31 ${zeros(20)} " +
                "0b 00 " +
                zeros(12),
        )

        val payload = encodePairingPayload(seed, "kart-pair-01", channel = 11)

        assertEquals(62, expected.size, "the hand-built vector is 0x3E octets")
        assertBytes(expected, payload, "pairing payload")
    }

    @Test
    fun `the payload size constant is 0x3E`() {
        assertEquals(0x3E, PAIRING_PAYLOAD_SIZE)
        assertEquals(PAIRING_PAYLOAD_SIZE, encodePairingPayload(seed, "x").size)
    }

    @Test
    fun `the channel defaults to zero meaning the current channel`() {
        val payload = encodePairingPayload(seed, "kart-pair-01")

        assertBytes(hexBytes("00 00"), payload.copyOfRange(0x30, 0x32), "channel field")
    }

    @Test
    fun `the channel is a little-endian u16`() {
        val payload = encodePairingPayload(seed, "a", channel = 0x1234)

        assertBytes(hexBytes("34 12"), payload.copyOfRange(0x30, 0x32), "channel 0x1234")
    }

    @Test
    fun `the largest channel is 0xffff`() {
        val payload = encodePairingPayload(seed, "a", channel = 0xFFFF)

        assertBytes(hexBytes("ff ff"), payload.copyOfRange(0x30, 0x32), "channel 0xffff")
    }

    @Test
    fun `an empty ssid leaves the whole ssid field zero`() {
        val payload = encodePairingPayload(seed, "")

        assertBytes(hexBytes(zeros(0x20)), payload.copyOfRange(0x10, 0x30), "ssid field")
    }

    @Test
    fun `an ssid of exactly 32 octets fills the field`() {
        val ssid = "0123456789abcdef0123456789abcdef"

        val payload = encodePairingPayload(seed, ssid)

        assertEquals(ssid, payload.copyOfRange(0x10, 0x30).decodeToString())
        assertBytes(hexBytes("00 00"), payload.copyOfRange(0x30, 0x32), "channel after a full ssid")
    }

    @Test
    fun `the ssid is measured and written in UTF-8 octets`() {
        // U+00E9 is c3 a9 in UTF-8: 16 of them fill the 32-octet field, 17 characters (34 octets) do not.
        val full = "\u00E9".repeat(16)

        val payload = encodePairingPayload(seed, full)

        assertBytes(
            hexBytes("c3 a9 ".repeat(16)),
            payload.copyOfRange(0x10, 0x30),
            "sixteen two-octet characters",
        )
        assertFailsWith<IllegalArgumentException> { encodePairingPayload(seed, "\u00E9".repeat(17)) }
    }

    @Test
    fun `the padding after the channel is zero`() {
        val payload = encodePairingPayload(seed, "kart-pair-01", channel = 0xFFFF)

        assertBytes(hexBytes(zeros(12)), payload.copyOfRange(0x32, 0x3E), "trailing padding")
    }

    @Test
    fun `a seed that is not 16 octets is rejected`() {
        assertFailsWith<IllegalArgumentException> { encodePairingPayload(ByteArray(15), "x") }
        assertFailsWith<IllegalArgumentException> { encodePairingPayload(ByteArray(17), "x") }
        assertFailsWith<IllegalArgumentException> { encodePairingPayload(ByteArray(0), "x") }
    }

    @Test
    fun `an ssid longer than 32 octets is rejected`() {
        assertFailsWith<IllegalArgumentException> { encodePairingPayload(seed, "0123456789abcdef0123456789abcdef0") }
    }

    @Test
    fun `a channel outside the u16 range is rejected`() {
        assertFailsWith<IllegalArgumentException> { encodePairingPayload(seed, "x", channel = -1) }
        assertFailsWith<IllegalArgumentException> { encodePairingPayload(seed, "x", channel = 0x10000) }
    }
}
