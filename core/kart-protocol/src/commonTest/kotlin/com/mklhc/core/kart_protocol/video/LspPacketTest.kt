package com.mklhc.core.kart_protocol.video

import com.mklhc.core.kart_protocol.hexBytes
import com.mklhc.core.kart_protocol.video.LspTestVectors.IDR_8
import com.mklhc.core.kart_protocol.video.LspTestVectors.PREFIX_AT_13
import com.mklhc.core.kart_protocol.video.LspTestVectors.PREFIX_AT_43
import com.mklhc.core.kart_protocol.video.LspTestVectors.framSizeFor
import com.mklhc.core.kart_protocol.video.LspTestVectors.startAt13
import com.mklhc.core.kart_protocol.video.LspTestVectors.startAt43
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Known-answer tests for the LSP packet helpers: [findAnnexbStart], [l2ChecksumOk] and [findFramStart].
 *
 * The vectors are hand-built from the reference rules. The L2 checksums were computed by hand from the
 * fold in the reference (and cross-checked with an independent script); each test says which value it
 * uses. The FRAM start datagrams come from [LspTestVectors], whose header comment gives the layout.
 */
class LspPacketTest {

    // --- findAnnexbStart --------------------------------------------------------------------------

    @Test
    fun `a 4 octet start code followed by each valid NAL type is found at its first octet`() {
        // NAL octets: 0x41 (type 1), 0x65 (5), 0x67 (7), 0x68 (8), 0x09 (9). The marker sits at index 2.
        for (nal in listOf(0x41, 0x65, 0x67, 0x68, 0x09)) {
            val buf = hexBytes("aa bb 00 00 00 01") + byteArrayOf(nal.toByte(), 0x11)

            assertEquals(2, findAnnexbStart(buf), "nal 0x${nal.toString(16)}")
        }
    }

    @Test
    fun `a 3 octet start code is found at its first octet`() {
        // 00 00 01 at index 1, NAL 0x41 (type 1).
        assertEquals(1, findAnnexbStart(hexBytes("aa 00 00 01 41 9a 00")))
    }

    @Test
    fun `only the low five bits of the NAL octet decide validity`() {
        // 0xe5 has the forbidden-zero and ref-idc bits set but 0xe5 & 0x1f = 5, so it is accepted.
        assertEquals(0, findAnnexbStart(hexBytes("00 00 00 01 e5 00")))
    }

    @Test
    fun `a start code followed by an unlisted NAL type is not a start`() {
        // 0x06 = SEI (6), 0x0a (10), 0x00 (0), 0x1f (31): none is in {1, 5, 7, 8, 9}.
        for (nal in listOf(0x06, 0x0a, 0x00, 0x1f)) {
            val buf4 = hexBytes("00 00 00 01") + byteArrayOf(nal.toByte(), 0x11)
            val buf3 = hexBytes("00 00 01") + byteArrayOf(nal.toByte(), 0x11)

            assertNull(findAnnexbStart(buf4), "4-octet start code, nal 0x${nal.toString(16)}")
            assertNull(findAnnexbStart(buf3), "3-octet start code, nal 0x${nal.toString(16)}")
        }
    }

    @Test
    fun `an invalid start code is skipped in favour of a later valid one`() {
        // SEI start code at 0 (invalid), then a 3-octet SPS start code at 7.
        assertEquals(7, findAnnexbStart(hexBytes("00 00 00 01 06 aa bb 00 00 01 67 42")))
    }

    @Test
    fun `the 4 octet marker is reported at its first zero not at the embedded 3 octet marker`() {
        // 00 00 00 01 contains 00 00 01 at +1; the earliest qualifying position is the 4-octet one.
        assertEquals(3, findAnnexbStart(hexBytes("aa bb cc 00 00 00 01 65 00")))
    }

    @Test
    fun `extra leading zeros before a start code do not move the answer past the 4 octet marker`() {
        // 00 00 00 00 01 65: the 4-octet marker (00 00 00 01) begins at index 1.
        assertEquals(1, findAnnexbStart(hexBytes("00 00 00 00 01 65")))
    }

    @Test
    fun `the earliest of a 3 octet and a later 4 octet marker wins`() {
        // 3-octet marker at 1, 4-octet marker at 7: earliest is 1.
        assertEquals(1, findAnnexbStart(hexBytes("aa 00 00 01 41 9a bb 00 00 00 01 65")))
    }

    @Test
    fun `the earliest of a 4 octet and a later 3 octet marker wins`() {
        // 4-octet marker at 2, 3-octet marker at 9: earliest is 2.
        assertEquals(2, findAnnexbStart(hexBytes("aa bb 00 00 00 01 67 11 22 00 00 01 41")))
    }

    @Test
    fun `the search honours the start offset`() {
        // Two 4-octet markers: [0..3] + NAL 0x67 at 4, and [6..9] + NAL 0x65 at 10.
        val buf = hexBytes("00 00 00 01 67 11 00 00 00 01 65 22")

        assertEquals(0, findAnnexbStart(buf))
        assertEquals(0, findAnnexbStart(buf, 0))
        assertEquals(6, findAnnexbStart(buf, 2), "from 2 the first marker is out of reach")
        assertEquals(6, findAnnexbStart(buf, 6))
        assertNull(findAnnexbStart(buf, 8), "from 8 only 00 01 65 22 is left, which holds no marker")
    }

    @Test
    fun `a search that begins inside a 4 octet marker finds the 3 octet marker embedded in it`() {
        // 00 00 00 01 67: scanning from index 1 sees 00 00 01 followed by 0x67 (type 7), a valid start, so
        // the answer is 1 and not 4 or beyond. The reference does the same (its find() starts at the offset).
        val buf = hexBytes("00 00 00 01 67 11 00 00 00 01 65 22")

        assertEquals(1, findAnnexbStart(buf, 1))
        assertEquals(7, findAnnexbStart(buf, 7), "the same effect on the second marker")
    }

    @Test
    fun `a marker with no NAL octet after it is not a start`() {
        assertNull(findAnnexbStart(hexBytes("aa bb 00 00 00 01")), "4-octet marker at the very end")
        assertNull(findAnnexbStart(hexBytes("aa bb 00 00 01")), "3-octet marker at the very end")
    }

    @Test
    fun `an empty buffer, a short buffer and a start past the end all return null`() {
        assertNull(findAnnexbStart(ByteArray(0)))
        assertNull(findAnnexbStart(hexBytes("00 00")))
        assertNull(findAnnexbStart(hexBytes("00 00 01 65"), 99))
    }

    @Test
    fun `a buffer with no start code returns null`() {
        assertNull(findAnnexbStart(hexBytes("11 22 33 44 55 66 77 88")))
    }

    // --- l2ChecksumOk -----------------------------------------------------------------------------

    /** "L2" + checksum 0x2aaa + value 0xfedcba9876543210 + 2 spare octets: 14 octets (see [LspTestVectors]). */
    private val l2Block = hexBytes("4c 32  2a aa  fe dc ba 98 76 54 32 10  00 00")

    @Test
    fun `a correct L2 block passes at the default offset 8`() {
        val pkt = hexBytes("33 33 33 33 33 33 33 33") + l2Block

        assertTrue(l2ChecksumOk(pkt))
        assertTrue(l2ChecksumOk(pkt, 8))
    }

    @Test
    fun `a correct L2 block passes at another offset`() {
        val pkt = hexBytes("01 02 03") + l2Block + hexBytes("99 99")

        assertTrue(l2ChecksumOk(pkt, 3))
        assertFalse(l2ChecksumOk(pkt, 8), "nothing valid at the default offset of this packet")
    }

    @Test
    fun `a second known value passes - 0123456789abcdef gives checksum aaaa`() {
        // value 0x0123456789abcdef: hi15 = 0x0123; upper32 = 0x01234567; folded = 0x01234444; tail32 =
        // 0x89abcdef; ^ (tail32 >> 16 = 0x89ab) = 0x0123cdef; ^ tail32 = 0x88880000; ^ 0xaaaaaaaa =
        // 0x2222aaaa; low 16 = 0xaaaa.
        val pkt = hexBytes("4c 32 aa aa 01 23 45 67 89 ab cd ef 00 00")

        assertTrue(l2ChecksumOk(pkt, 0))
    }

    @Test
    fun `a third known value passes - 1f4652414d000000 gives checksum aaad`() {
        // value 0x1f4652414d000000: hi15 = 0x1f46; upper32 = 0x1f465241; folded = 0x1f465241 ^ 0x1f46 =
        // 0x1f464d07 (0x5241 ^ 0x1f46 = 0x4d07); tail32 = 0x4d000000; ^ (tail32 >> 16 = 0x4d00) ->
        // 0x1f460007; ^ tail32 -> 0x52460007; ^ 0xaaaaaaaa -> 0xf8ecaaad; low 16 = 0xaaad.
        val pkt = hexBytes("4c 32 aa ad 1f 46 52 41 4d 00 00 00 00 00")

        assertTrue(l2ChecksumOk(pkt, 0))
    }

    @Test
    fun `a flipped checksum bit fails`() {
        for (bit in 0 until 16) {
            val pkt = hexBytes("33 33 33 33 33 33 33 33") + l2Block
            val flipped = 0x2aaa xor (1 shl bit)
            pkt[10] = (flipped ushr 8).toByte()
            pkt[11] = flipped.toByte()

            assertFalse(l2ChecksumOk(pkt), "checksum bit $bit flipped")
        }
    }

    @Test
    fun `a corrupted value octet fails`() {
        for (i in 0 until 8) {
            val pkt = hexBytes("33 33 33 33 33 33 33 33") + l2Block
            pkt[12 + i] = (pkt[12 + i].toInt() xor 0x01).toByte()

            assertFalse(l2ChecksumOk(pkt), "value octet $i flipped")
        }
    }

    @Test
    fun `bit 63 of the value does not take part in the checksum but bit 62 does`() {
        // Reference value 0xfedcba9876543210 has checksum 0x2aaa. Clearing bit 63 gives 0x7edcba98...:
        // the 0x7fff mask already drops bit 63 from the (value >> 48) term, and in the (value >> 32) term
        // it only flips bit 31 of "folded", above the low 16 bits that are compared. So the same
        // checksum still verifies. Clearing bit 62 instead (0xbedc...) changes bit 14 of the masked
        // (value >> 48) term, which lands in the low 16 bits, so the old checksum no longer verifies.
        val bit63Cleared = hexBytes("4c 32 2a aa 7e dc ba 98 76 54 32 10 00 00")
        val bit62Cleared = hexBytes("4c 32 2a aa be dc ba 98 76 54 32 10 00 00")

        assertTrue(l2ChecksumOk(bit63Cleared, 0))
        assertFalse(l2ChecksumOk(bit62Cleared, 0))
    }

    @Test
    fun `an all zero value has checksum aaaa`() {
        assertTrue(l2ChecksumOk(hexBytes("4c 32 aa aa 00 00 00 00 00 00 00 00 00 00"), 0))
        assertFalse(l2ChecksumOk(hexBytes("4c 32 00 00 00 00 00 00 00 00 00 00 00 00"), 0))
    }

    @Test
    fun `a packet too short for the 14 octet block fails`() {
        val full = hexBytes("33 33 33 33 33 33 33 33") + l2Block // 22 octets, off + 14 = 22

        assertTrue(l2ChecksumOk(full))
        assertFalse(l2ChecksumOk(full.copyOf(21)), "one octet short")
        assertFalse(l2ChecksumOk(full.copyOf(8)), "only the leading header")
        assertFalse(l2ChecksumOk(ByteArray(0)))
    }

    @Test
    fun `a negative or out of range offset fails`() {
        val pkt = hexBytes("33 33 33 33 33 33 33 33") + l2Block

        assertFalse(l2ChecksumOk(pkt, -1))
        assertFalse(l2ChecksumOk(pkt, 9), "the block would run past the end")
        assertFalse(l2ChecksumOk(pkt, 1000))
    }

    @Test
    fun `a wrong L2 tag fails even with a matching checksum`() {
        for (tag in listOf("4c 33", "6c 32", "32 4c", "00 00", "4c 00")) {
            val pkt = hexBytes("33 33 33 33 33 33 33 33  $tag  2a aa  fe dc ba 98 76 54 32 10  00 00")

            assertFalse(l2ChecksumOk(pkt), "tag $tag")
        }
    }

    // --- findFramStart ----------------------------------------------------------------------------

    @Test
    fun `a literal FRAM at offset 13 without L2 is a start`() {
        // 47 octets before the H.264: [0..11] 0x11, [12] 0x1f, [13..16] "FRAM", [17..20] size 0x2a (42 =
        // 8 H.264 octets + 34), [21..46] 0x22. Then the 8 H.264 octets and the 8-octet trailer.
        val pkt = hexBytes(
            """
            11 11 11 11 11 11 11 11 11 11 11 11
            1f
            46 52 41 4d
            00 00 00 2a
            ${"22 ".repeat(26)}
            00 00 00 01 65 88 84 21
            ee ee ee ee ee ee ee 02
            """,
        )

        assertEquals(PREFIX_AT_13 + 8 + 8, pkt.size)
        assertEquals(FramStart(expectedH264Len = 8, rawStart = 47, framOffset = 13), findFramStart(pkt))
    }

    @Test
    fun `a literal FRAM at offset 43 with a valid L2 block is a start`() {
        // [0..7] 0x33, [8..9] "L2", [10..11] checksum 0x2aaa, [12..19] value 0xfedcba9876543210, [20..41]
        // 0x33, [42] 0x1f, [43..46] "FRAM", [47..50] size 0x2a, [51..76] 0x22, then H.264 + trailer.
        val pkt = hexBytes(
            """
            33 33 33 33 33 33 33 33
            4c 32
            2a aa
            fe dc ba 98 76 54 32 10
            ${"33 ".repeat(22)}
            1f
            46 52 41 4d
            00 00 00 2a
            ${"22 ".repeat(26)}
            00 00 00 01 65 88 84 21
            ee ee ee ee ee ee ee 02
            """,
        )

        assertEquals(PREFIX_AT_43 + 8 + 8, pkt.size)
        assertEquals(FramStart(expectedH264Len = 8, rawStart = 77, framOffset = 43), findFramStart(pkt))
    }

    @Test
    fun `the builder reproduces the literal vectors`() {
        // Guards the helper the other tests rely on: 63 and 93 octets, tag at the stated offsets.
        val at13 = startAt13(framSizeFor(8), IDR_8)
        val at43 = startAt43(framSizeFor(8), IDR_8)

        assertEquals(63, at13.size)
        assertEquals(93, at43.size)
        assertEquals(FramStart(8, 47, 13), findFramStart(at13))
        assertEquals(FramStart(8, 77, 43), findFramStart(at43))
    }

    @Test
    fun `a FRAM at 43 with a corrupt L2 checksum is rejected`() {
        val pkt = startAt43(framSizeFor(8), IDR_8, l2Checksum = 0x2aab)

        assertNull(findFramStart(pkt))
    }

    @Test
    fun `a FRAM at 43 with no L2 tag is rejected`() {
        val pkt = startAt43(framSizeFor(8), IDR_8)
        pkt[8] = 'X'.code.toByte() // the tag now reads "X2", so the L2 guard fails

        assertNull(findFramStart(pkt))
    }

    @Test
    fun `a FRAM at 13 needs no L2 block`() {
        // The 0x11 filler where an L2 block would be is not one, yet the FRAM at 13 is accepted.
        val pkt = startAt13(framSizeFor(8), IDR_8)

        assertFalse(l2ChecksumOk(pkt, 8), "there is no L2 block in this datagram")
        assertEquals(13, findFramStart(pkt)?.framOffset)
    }

    @Test
    fun `an invalid FRAM at 13 falls through to a valid FRAM at 43`() {
        // The L2 value 0x1f4652414d000000 (checksum 0xaaad, derived in the L2 tests above) at [12..19]
        // happens to spell a FRAM tag at [13..16] with size octets 00 00 00 at [17..19]; the 0x00 at
        // [20] completes a size of 0, which is rejected, so the scan moves on. The real FRAM follows at
        // 43 (length octet 0x1f at 42) with size 42, so the answer is the one at 43.
        val pkt = hexBytes("33 33 33 33 33 33 33 33") + // [0..7]
            hexBytes("4c 32 aa ad") + // [8..11] "L2" + checksum
            hexBytes("1f 46 52 41 4d 00 00 00") + // [12..19] L2 value
            byteArrayOf(0x00) + ByteArray(21) { 0x33 } + // [20..41]
            byteArrayOf(0x1f) + // [42] length octet
            LspTestVectors.FRAM_TAG + LspTestVectors.u32Be(framSizeFor(8)) + // [43..50]
            ByteArray(26) { 0x22 } + IDR_8 + LspTestVectors.trailer()

        assertEquals(93, pkt.size)
        assertEquals(FramStart(8, 77, 43), findFramStart(pkt))

        assertEquals(FramStart(8, 77, 43), findFramStart(pkt))
    }

    @Test
    fun `the packet size must be positive and at most 2 MiB`() {
        val sizes = mapOf<Long, Boolean>(
            0L to false, // not positive
            1L to false, // expected = 1 - 34 < 0
            34L to false, // expected = 0
            35L to true, // expected = 1, the smallest accepted
            42L to true,
            (2L * 1024 * 1024) to true, // the cap itself is accepted
            (2L * 1024 * 1024 + 1) to false, // one over the cap
            0x7fffffffL to false,
            0x80000000L to false, // would be negative as a signed int
            0xffffffffL to false,
        )

        for ((size, accepted) in sizes) {
            val pkt = startAt13(size, IDR_8)
            val start = findFramStart(pkt)

            if (accepted) {
                assertEquals(
                    FramStart(expectedH264Len = (size - 34).toInt(), rawStart = 47, framOffset = 13),
                    start,
                    "size $size",
                )
            } else {
                assertNull(start, "size $size")
            }
        }
    }

    @Test
    fun `the 2 MiB cap also applies to a FRAM at 43`() {
        assertNull(findFramStart(startAt43(2L * 1024 * 1024 + 1, IDR_8)))
        assertEquals(
            2 * 1024 * 1024 - 34,
            findFramStart(startAt43(2L * 1024 * 1024, IDR_8))?.expectedH264Len,
        )
    }

    @Test
    fun `a length octet that lands on an Annex-B start is used as is`() {
        // Put a second start code (SPS 0x67) at 47 and the length octet at 0x1f -> rawStart 47 directly.
        val pkt = startAt13(framSizeFor(8), hexBytes("00 00 00 01 67 11 22 33"))

        assertEquals(FramStart(8, 47, 13), findFramStart(pkt))
    }

    @Test
    fun `a length octet pointing off a start code falls back to the first start after the FRAM header`() {
        // Length octet 0x05 -> rawStart = 12 + 5 + 4 = 21, which is 0x22 filler, not a start code. The
        // fallback scans from framOffset + 8 = 21 and finds the real H.264 start at 47.
        val pkt = startAt13(framSizeFor(8), IDR_8, lengthOctet = 0x05)

        assertEquals(FramStart(8, 47, 13), findFramStart(pkt))
    }

    @Test
    fun `a length octet pointing past the datagram falls back to the first start after the FRAM header`() {
        // 0xff -> rawStart = 12 + 255 + 4 = 271, beyond the 63-octet datagram.
        val pkt = startAt13(framSizeFor(8), IDR_8, lengthOctet = 0xff)

        assertEquals(FramStart(8, 47, 13), findFramStart(pkt))
    }

    @Test
    fun `the length octet is read as unsigned`() {
        // Length octet 0xe0 = 224 -> rawStart = 12 + 224 + 4 = 240. Build a 260-octet datagram with a start
        // code exactly at 240 and an earlier decoy at 100. Read as signed (-32) the octet would give a
        // negative rawStart that is not on a start code, so the fallback would return the decoy at 100.
        val pkt = ByteArray(260) { 0x22 }
        LspTestVectors.FRAM_TAG.copyInto(pkt, 13)
        pkt[12] = 0xe0.toByte()
        LspTestVectors.u32Be(framSizeFor(8)).copyInto(pkt, 17)
        hexBytes("00 00 00 01 67 11").copyInto(pkt, 100)
        IDR_8.copyInto(pkt, 240)

        assertEquals(FramStart(8, 240, 13), findFramStart(pkt))
    }

    @Test
    fun `a FRAM with no Annex-B start anywhere after it is rejected`() {
        // All filler after the header: the length octet cannot land on a start and the fallback finds none.
        val pkt = startAt13(framSizeFor(8), ByteArray(8) { 0x44 })

        assertNull(findFramStart(pkt))
    }

    @Test
    fun `a datagram without a FRAM tag is not a start`() {
        assertNull(findFramStart(LspTestVectors.continuation(IDR_8 + ByteArray(60) { 0x55 })))
        assertNull(findFramStart(ByteArray(0)))
        assertNull(findFramStart(ByteArray(100) { 0x55 }))
    }

    @Test
    fun `a near miss of the FRAM tag is not a start`() {
        for (tag in listOf("46 52 41 4e", "66 72 61 6d", "46 52 41 00", "4d 41 52 46")) {
            val pkt = startAt13(framSizeFor(8), IDR_8)
            hexBytes(tag).copyInto(pkt, 13)

            assertNull(findFramStart(pkt), "tag $tag")
        }
    }

    @Test
    fun `a datagram too short to hold the tag and size at 13 is not a start`() {
        // framOffset + 8 = 21 > size for 20 octets or fewer.
        val pkt = startAt13(framSizeFor(8), IDR_8).copyOf(20)

        assertNull(findFramStart(pkt))
        assertNull(findFramStart(ByteArray(5)))
    }

    @Test
    fun `a FRAM at 13 that fits exactly but has no H264 after it is rejected`() {
        // 21 octets: the tag and size fit (13 + 8 = 21) but there is nothing after them.
        val pkt = startAt13(framSizeFor(8), IDR_8).copyOf(21)

        assertNull(findFramStart(pkt))
    }

    @Test
    fun `the expected H264 length is the FRAM size minus 34`() {
        for (h264Len in listOf(1, 8, 1188, 65_000)) {
            val pkt = startAt13(framSizeFor(h264Len), IDR_8)

            assertEquals(h264Len, findFramStart(pkt)?.expectedH264Len, "h264 length $h264Len")
        }
    }

    @Test
    fun `findFramStart does not modify the datagram`() {
        val pkt = startAt43(framSizeFor(8), IDR_8)
        val before = pkt.copyOf()

        findFramStart(pkt)

        assertTrue(before.contentEquals(pkt))
    }
}
