package com.mklhc.core.kart_protocol.video

import com.mklhc.core.kart_protocol.hexBytes

/**
 * Hand-built video datagrams for the LSP / FRAM tests.
 *
 * Business logic: every datagram is assembled from named spans so a test (and its reader) can see which
 * octet is which. The layout follows the reference reassembler's rules, with the length octet that sits
 * right before the "FRAM" tag set to 0x1f so that `rawStart = (framOffset - 1) + 0x1f + 4 = framOffset + 34`,
 * i.e. the H.264 bytes begin exactly [LspVideo.FRAM_SIZE_OVERHEAD] octets after the tag, which is what the
 * "FRAM size = H.264 length + 34" rule implies. Filler octets (0x11, 0x22, 0x33) contain no Annex-B start
 * code so they can never be mistaken for H.264.
 *
 * ```
 * start datagram, FRAM at 13 (47 octets before the H.264):
 *   [0..11]  0x11 filler   [12] length octet   [13..16] "FRAM"   [17..20] size u32 BE
 *   [21..46] 0x22 filler   [47..]  H.264 bytes   [last 8] trailer
 * start datagram, FRAM at 43 (77 octets before the H.264):
 *   [0..7]   0x33 header   [8..9] "L2"   [10..11] checksum u16 BE   [12..19] value u64 BE
 *   [20..41] 0x33 filler   [42] length octet   [43..46] "FRAM"   [47..50] size u32 BE
 *   [51..76] 0x22 filler   [77..]  H.264 bytes   [last 8] trailer
 * continuation datagram:
 *   [0..]    H.264 bytes   [last 8] trailer
 * ```
 */
internal object LspTestVectors {
    /** The "FRAM" tag as ASCII: 46 52 41 4d. */
    val FRAM_TAG: ByteArray = hexBytes("46 52 41 4d")

    /** The length octet in front of the tag that makes the H.264 start `framOffset + 34`. */
    const val LENGTH_OCTET = 0x1f

    /** Octets in front of the H.264 bytes of a start datagram with FRAM at 13. */
    const val PREFIX_AT_13 = 47

    /** Octets in front of the H.264 bytes of a start datagram with FRAM at 43. */
    const val PREFIX_AT_43 = 77

    /**
     * L2 value used by the FRAM-at-43 vectors: 0xfedcba9876543210.
     * Checksum derived by hand (and cross-checked with an independent script): hi15 = 0xfedc & 0x7fff =
     * 0x7edc; upper32 = 0xfedcba98; folded = 0x7edc ^ 0xfedcba98 = 0xfedcc444; tail32 = 0x76543210;
     * folded ^= tail32 >> 16 (0x7654) -> 0xfedcb210; folded ^= tail32 -> 0x88888000; folded ^= 0xaaaaaaaa
     * -> 0x22222aaa; low 16 bits = 0x2aaa.
     */
    val L2_VALUE: ByteArray = hexBytes("fe dc ba 98 76 54 32 10")
    const val L2_CHECKSUM = 0x2aaa

    /** A recognisable IDR start: 4-octet start code, NAL byte 0x65 (type 5), three payload octets. */
    val IDR_8: ByteArray = hexBytes("00 00 00 01 65 88 84 21")

    /** The H.264 size field a start datagram needs so that the frame's H.264 length is [h264Length]. */
    fun framSizeFor(h264Length: Int): Long = (h264Length + LspVideo.FRAM_SIZE_OVERHEAD).toLong()

    /** An 8-octet trailer: seven 0xee octets and a counter octet (counts up by 2 per packet). */
    fun trailer(counter: Int = 0x02): ByteArray = hexBytes("ee ee ee ee ee ee ee") + byteArrayOf(counter.toByte())

    /** [value] as four big-endian octets. */
    fun u32Be(value: Long): ByteArray = ByteArray(4) { (value ushr (8 * (3 - it))).toByte() }

    /** A start datagram whose "FRAM" tag is at offset 13 (no L2 block). */
    fun startAt13(
        framSize: Long,
        h264: ByteArray,
        lengthOctet: Int = LENGTH_OCTET,
        trailerCounter: Int = 0x02,
    ): ByteArray =
        ByteArray(12) { 0x11 } + byteArrayOf(lengthOctet.toByte()) + FRAM_TAG + u32Be(framSize) +
            ByteArray(26) { 0x22 } + h264 + trailer(trailerCounter)

    /** A start datagram whose "FRAM" tag is at offset 43, preceded by an L2 block with [l2Checksum]. */
    fun startAt43(
        framSize: Long,
        h264: ByteArray,
        lengthOctet: Int = LENGTH_OCTET,
        l2Checksum: Int = L2_CHECKSUM,
        trailerCounter: Int = 0x02,
    ): ByteArray =
        ByteArray(8) { 0x33 } + "L2".encodeToByteArray() +
            byteArrayOf((l2Checksum ushr 8).toByte(), l2Checksum.toByte()) + L2_VALUE +
            ByteArray(22) { 0x33 } + byteArrayOf(lengthOctet.toByte()) + FRAM_TAG + u32Be(framSize) +
            ByteArray(26) { 0x22 } + h264 + trailer(trailerCounter)

    /** A continuation datagram: the [h264] bytes followed by the trailer. */
    fun continuation(h264: ByteArray, trailerCounter: Int = 0x02): ByteArray = h264 + trailer(trailerCounter)
}
