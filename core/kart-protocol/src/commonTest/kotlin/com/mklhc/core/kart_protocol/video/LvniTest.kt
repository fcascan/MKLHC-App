package com.mklhc.core.kart_protocol.video

import com.mklhc.core.kart_protocol.fuji.assertBytes
import com.mklhc.core.kart_protocol.hexBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * Known-answer tests for [Lvni.encode].
 *
 * The reference builds the keepalive as `b'LVNI' + struct.pack('<QQI', previous, current, flags)`: the ASCII
 * tag 4c 56 4e 49, then a little-endian u64, a little-endian u64 and a little-endian u32, 24 octets in all.
 * Each vector below is that expression evaluated by hand (and cross-checked with an independent script):
 * a multi-octet value is written least significant octet first.
 */
class LvniTest {

    @Test
    fun `the tag is the ASCII text LVNI`() {
        val tag = Lvni.encode(0, 0, 0).copyOfRange(0, 4)

        assertEquals("LVNI", tag.decodeToString())
        assertBytes(hexBytes("4c 56 4e 49"), tag, "tag octets")
    }

    @Test
    fun `all zero ids and flags give the tag and 20 zero octets`() {
        assertBytes(
            hexBytes("4c 56 4e 49  00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00  00 00 00 00"),
            Lvni.encode(previousFrameId = 0, currentFrameId = 0, flags = 0),
            "LVNI 0 0 0",
        )
    }

    @Test
    fun `small values are little endian`() {
        // previous = 1, current = 2, flags = 3.
        assertBytes(
            hexBytes("4c 56 4e 49  01 00 00 00 00 00 00 00  02 00 00 00 00 00 00 00  03 00 00 00"),
            Lvni.encode(previousFrameId = 1, currentFrameId = 2, flags = 3),
            "LVNI 1 2 3",
        )
    }

    @Test
    fun `every octet of the ids and flags lands at its little endian position`() {
        // previous = 0x0102030405060708 -> 08 07 06 05 04 03 02 01
        // current  = 0x1112131415161718 -> 18 17 16 15 14 13 12 11
        // flags    = 0x0a0b0c0d         -> 0d 0c 0b 0a
        assertBytes(
            hexBytes("4c 56 4e 49  08 07 06 05 04 03 02 01  18 17 16 15 14 13 12 11  0d 0c 0b 0a"),
            Lvni.encode(
                previousFrameId = 0x0102030405060708L,
                currentFrameId = 0x1112131415161718L,
                flags = 0x0A0B0C0D,
            ),
            "LVNI distinct octets",
        )
    }

    @Test
    fun `ids and flags are the raw unsigned bit patterns`() {
        // previous = 2^64 - 1 (as a Long: -1), current = 2^63 (Long.MIN_VALUE), flags = 2^32 - 1 (Int -1).
        assertBytes(
            hexBytes("4c 56 4e 49  ff ff ff ff ff ff ff ff  00 00 00 00 00 00 00 80  ff ff ff ff"),
            Lvni.encode(previousFrameId = -1L, currentFrameId = Long.MIN_VALUE, flags = -1),
            "LVNI max values",
        )
    }

    @Test
    fun `the packet is 24 octets`() {
        assertEquals(24, Lvni.PACKET_SIZE)
        assertEquals(Lvni.PACKET_SIZE, Lvni.encode(7, 8, 9).size)
    }

    @Test
    fun `the keepalive is sent once per second`() {
        assertEquals(1_000L, Lvni.KEEPALIVE_INTERVAL_MS)
    }

    @Test
    fun `each call returns a fresh array`() {
        val first = Lvni.encode(1, 2, 3)
        val second = Lvni.encode(1, 2, 3)

        assertNotSame(first, second)
        assertBytes(first, second, "same content")
        first[0] = 0x00
        assertBytes(hexBytes("4c 56 4e 49"), second.copyOfRange(0, 4), "mutating one result does not affect another")
    }
}
