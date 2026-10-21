package com.mklhc.core.kart_protocol.video

import com.mklhc.core.kart_protocol.hexBytes

/**
 * Hand-built video datagrams for the LSP / FRAM tests.
 *
 * Business logic: every datagram is assembled from named spans so a test (and its reader) can see which
 * octet is which. The layout is the one measured from the kart on 2026-10-21 (see [LspVideo]): an 8-octet
 * datagram header (`ec c1`, a 24-bit sequence number, group, count, index), then a 5-octet chunk header whose
 * last two octets carry the chunk type and length. The length octet right before the "FRAM" tag is 0x1f so
 * that `rawStart = (framOffset - 1) + 0x1f + 4 = framOffset + 34`, i.e. the H.264 bytes begin exactly
 * [LspVideo.FRAM_SIZE_OVERHEAD] octets after the tag. Filler octets (0x22, 0x33) contain no Annex-B start code
 * so they can never be mistaken for H.264, and the trailer is seven 0xee octets plus a counter, so a header or
 * trailer octet that leaks into an emitted frame is easy to spot.
 *
 * ```
 * datagram header, 8 octets, every datagram:
 *   [0..1] ec c1   [2..4] sequence u24 BE   [5] group   [6] datagrams in the group   [7] index in the group
 * start datagram, FRAM at 13 (47 octets before the H.264):
 *   [0..7] header   [8..11] 0a 00 <seq low> 80   [12] length octet   [13..16] "FRAM"   [17..20] size u32 BE
 *   [21..46] 0x22 filler   [47..]  H.264 bytes   [last 8] trailer
 * start datagram, FRAM at 43 (77 octets before the H.264):
 *   [0..7] header   [8..9] "L2"   [10..11] checksum u16 BE   [12..19] value u64 BE
 *   [20..41] 0x33 filler   [42] length octet   [43..46] "FRAM"   [47..50] size u32 BE
 *   [51..76] 0x22 filler   [77..]  H.264 bytes   [last 8] trailer
 * continuation datagram (chunk with a length, like the kart's 85 72):
 *   [0..7] header   [8..10] 0a 00 <seq low>   [11..12] 0x80 | (n - 1), 11 bits   [13..] the n H.264 octets
 *   then stale padding, if any   [last 8] trailer
 * last datagram of a frame (chunk without a length, like the kart's ba 00):
 *   [0..7] header   [8..10] 0a 00 <seq low>   [11..12] ba 00   [13..16] frame-end field
 *   [17..] H.264 then stale padding   [last 8] trailer
 * The FRAM size counts the FRAM record and the frame-end field: a frame of n H.264 octets has the size n + 38.
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

    /**
     * Stale octets after a short chunk, made up in the shape the dump showed there (parts of an old header and an
     * old AUD start code), not copied from it.
     */
    val STALE: ByteArray = hexBytes("ec c1 00 00 00 01 09 30")

    /** The FRAM size field of a frame whose H.264 is [h264Length] octets: it also counts the record and the field. */
    fun framSizeFor(h264Length: Int): Long =
        (h264Length + LspVideo.FRAM_SIZE_OVERHEAD + LspVideo.FRAME_END_FIELD).toLong()

    /** The 4-octet field after the header of a frame's last datagram (made-up value; the kart's steps per frame). */
    val FRAME_END_FIELD: ByteArray = hexBytes("fa fb fc fd")

    /** An 8-octet trailer: seven 0xee octets and a counter octet (counts up by 2 per packet). */
    fun trailer(counter: Int = 0x02): ByteArray = hexBytes("ee ee ee ee ee ee ee") + byteArrayOf(counter.toByte())

    /** [value] as four big-endian octets. */
    fun u32Be(value: Long): ByteArray = ByteArray(4) { (value ushr (8 * (3 - it))).toByte() }

    /** The 8-octet header every video datagram starts with. */
    fun header(seq: Int, group: Int = 1, count: Int = 3, index: Int = 0): ByteArray {
        require(seq in 0..LspVideo.SEQUENCE_MASK) { "the sequence number has 24 bits: seq=$seq" }
        return hexBytes("ec c1") +
            byteArrayOf((seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte()) +
            byteArrayOf(group.toByte(), count.toByte(), index.toByte())
    }

    /** A start datagram whose "FRAM" tag is at offset 13 (no L2 block). */
    fun startAt13(
        framSize: Long,
        h264: ByteArray,
        lengthOctet: Int = LENGTH_OCTET,
        trailerCounter: Int = 0x02,
        seq: Int = 0,
    ): ByteArray =
        header(seq) + byteArrayOf(0x0a, 0x00, seq.toByte(), 0x80.toByte(), lengthOctet.toByte()) + FRAM_TAG +
            u32Be(framSize) + ByteArray(26) { 0x22 } + h264 + trailer(trailerCounter)

    /** A start datagram whose "FRAM" tag is at offset 43, preceded by an L2 block with [l2Checksum]. */
    fun startAt43(
        framSize: Long,
        h264: ByteArray,
        lengthOctet: Int = LENGTH_OCTET,
        l2Checksum: Int = L2_CHECKSUM,
        trailerCounter: Int = 0x02,
        seq: Int = 0,
    ): ByteArray =
        header(seq) + "L2".encodeToByteArray() +
            byteArrayOf((l2Checksum ushr 8).toByte(), l2Checksum.toByte()) + L2_VALUE +
            ByteArray(22) { 0x33 } + byteArrayOf(lengthOctet.toByte()) + FRAM_TAG + u32Be(framSize) +
            ByteArray(26) { 0x22 } + h264 + trailer(trailerCounter)

    /**
     * A continuation whose chunk header gives its length: octets 11-12 hold `0x80 | (h264.size - 1)` over 11 bits,
     * as the kart's full continuations do (`85 72` for 1395 octets). [padding] follows the chunk before the trailer.
     */
    fun continuation(
        h264: ByteArray,
        seq: Int,
        padding: ByteArray = ByteArray(0),
        trailerCounter: Int = 0x02,
    ): ByteArray {
        val field = h264.size - 1
        require(field in 0..LENGTH_FIELD_MAX) { "a chunk length must be 1..${LENGTH_FIELD_MAX + 1} octets: size=${h264.size}" }
        return header(seq, index = 1) +
            byteArrayOf(0x0a, 0x00, seq.toByte(), (0x80 or (field ushr 8)).toByte(), field.toByte()) +
            h264 + padding + trailer(trailerCounter)
    }

    /**
     * The last datagram of a frame: its chunk header ([chunkType] then 00; the kart sends ba, b6 or be) gives no
     * length; the [FRAME_END_FIELD] follows, then everything up to the trailer is taken and the frame length cuts
     * [padding] off.
     */
    fun lastOfFrame(
        h264: ByteArray,
        seq: Int,
        padding: ByteArray = ByteArray(0),
        trailerCounter: Int = 0x02,
        chunkType: Int = 0xba,
    ): ByteArray =
        header(seq, index = 2) + byteArrayOf(0x0a, 0x00, seq.toByte(), chunkType.toByte(), 0x00) + FRAME_END_FIELD +
            h264 + padding + trailer(trailerCounter)

    private const val LENGTH_FIELD_MAX = 0x7FF
}
