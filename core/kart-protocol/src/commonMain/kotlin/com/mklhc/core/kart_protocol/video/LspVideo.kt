// Adapted from mk-live https://github.com/olus85/mk-live, src/mk_live/video_worker.py (MIT).
package com.mklhc.core.kart_protocol.video

/**
 * Constants of the kart's UDP video stream ("LSP" datagrams carrying "FRAM" records).
 *
 * Business logic: the kart streams H.264 video as UDP datagrams to the app's video port. A datagram
 * that opens a video frame carries a "FRAM" record whose big-endian size field covers the 32-octet FRAM
 * header plus two meta octets, followed by the first part of the H.264 access unit; a frame larger than
 * one datagram continues in header-less datagrams that carry only H.264 bytes. Every datagram, start or
 * continuation, ends with an 8-octet trailer that is not H.264 and has to be removed.
 *
 * The values are the ones the reference implementation uses; the FRAM offsets, the trailer length and
 * the size overhead are confirmed only by that reference and still have to be checked against hardware
 * captures (see [LspVideoReassembler]).
 */
object LspVideo {
    /**
     * Octets at the end of every video datagram that are not H.264. The last trailer octet counts up by 2
     * per packet; that is informational only and nothing here relies on it.
     */
    const val PACKET_TRAILER = 8

    /**
     * Octets the FRAM size field counts on top of the H.264 bytes: the 32-octet FRAM header plus 2 meta
     * octets. The H.264 length of a frame is therefore `framSize - FRAM_SIZE_OVERHEAD`.
     */
    const val FRAM_SIZE_OVERHEAD = 34

    /** Largest FRAM size accepted; a bigger value is treated as "not a FRAM header". Bounds frame memory. */
    const val MAX_FRAM_PACKET_SIZE = 2 * 1024 * 1024

    /** Offset of the "FRAM" tag in a start datagram that has no leading "L2" block. */
    const val FRAM_OFFSET_PLAIN = 13

    /** Offset of the "FRAM" tag in a start datagram that is preceded by an "L2" checksum block. */
    const val FRAM_OFFSET_WITH_L2 = 43

    /** Offset of the "L2" checksum block that guards a FRAM tag at [FRAM_OFFSET_WITH_L2]. */
    const val L2_OFFSET = 8

    /**
     * NAL unit types that count as a genuine Annex-B start: 1 (non-IDR slice), 5 (IDR slice), 7 (SPS),
     * 8 (PPS) and 9 (access unit delimiter). A start code followed by any other type is ignored.
     */
    val VALID_ANNEXB_NAL_TYPES: Set<Int> = setOf(1, 5, 7, 8, 9)
}
