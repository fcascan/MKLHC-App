// Adapted from mk-live https://github.com/olus85/mk-live, src/mk_live/video_worker.py (MIT).
// The datagram header constants (DATAGRAM_HEADER and below) are original: measured from the kart's stream on
// 2026-10-21 with the desktop app's raw video dump, not taken from the reference.
package com.mklhc.core.kart_protocol.video

/**
 * Constants of the kart's UDP video stream ("LSP" datagrams carrying "FRAM" records).
 *
 * Business logic: the kart streams H.264 video as UDP datagrams to the app's video port, every one of them
 * 1416 octets. Each datagram starts with a 13-octet header and ends with an 8-octet trailer, neither of which is
 * H.264:
 *
 * ```
 * 0..1    ec c1
 * 2..4    sequence number, 24 bits big-endian, +1 per datagram (wraps)
 * 5       group number, +1 per group; 6 datagrams in the group (up to 14); 7 index in the group
 * 8..12   chunk header; octets 11-12 hold the chunk type (top 5 bits) and an 11-bit length
 * ```
 *
 * A datagram that opens a video frame carries a "FRAM" record right after the header, whose chunk header reads
 * `80 1f`; a keyframe's first datagram instead has an "L2" block at octet 8, in place of the chunk header, and its
 * FRAM record at 43. The FRAM record (32-octet FRAM header plus two meta octets) is followed by the first part of
 * the H.264 access unit. The rest follows in continuation datagrams: with chunk type [CHUNK_WITH_LENGTH] (`85 72`
 * on a full one) the chunk holds `length + 1` H.264 octets right after the header; the frame's last datagram has
 * another type (`ba 00`, `b6 00`, `be 00`) and no length: a [FRAME_END_FIELD]-octet field follows its header (it
 * steps by about 4168 per frame, a timestamp of some kind), then its H.264 runs to the trailer, and the frame's
 * length cuts off the stale octets the kart pads it with. A keyframe spans several groups, the earlier ones
 * ending on a sized chunk, so only the frame's last datagram has that type. The FRAM size counts the record and
 * the field, so a frame's H.264 length is `FRAM size - FRAM_SIZE_OVERHEAD - FRAME_END_FIELD`.
 *
 * Provenance of the values: the FRAM offsets, the FRAM record size and the trailer come from the reference; the
 * header, the chunk layout and the frame-end field were measured on a 2,185-frame capture (every frame ends with
 * exactly one such datagram), and FFmpeg decodes every frame rebuilt this way without an error (2026-10-21).
 */
object LspVideo {
    /**
     * Octets at the end of every video datagram that are not H.264. The last trailer octet counts up by 2
     * per packet; that is informational only and nothing here relies on it.
     */
    const val PACKET_TRAILER = 8

    /**
     * Octets of the FRAM record the FRAM size field counts: the 32-octet FRAM header plus 2 meta octets. The size
     * also counts the [FRAME_END_FIELD], so the frame's H.264 length is `framSize - FRAM_SIZE_OVERHEAD -
     * FRAME_END_FIELD`.
     */
    const val FRAM_SIZE_OVERHEAD = 34

    /**
     * Octets of the field right after the header of a frame's last datagram (a chunk that is not
     * [CHUNK_WITH_LENGTH]); not H.264, but counted by the FRAM size.
     */
    const val FRAME_END_FIELD = 4

    /** Largest FRAM size accepted; a bigger value is treated as "not a FRAM header". Bounds frame memory. */
    const val MAX_FRAM_PACKET_SIZE = 2 * 1024 * 1024

    /** Offset of the "FRAM" tag in a start datagram that has no leading "L2" block. */
    const val FRAM_OFFSET_PLAIN = 13

    /** Offset of the "FRAM" tag in a start datagram that is preceded by an "L2" checksum block. */
    const val FRAM_OFFSET_WITH_L2 = 43

    /** Offset of the "L2" checksum block that guards a FRAM tag at [FRAM_OFFSET_WITH_L2]. */
    const val L2_OFFSET = 8

    /** Octets of the header every video datagram starts with: 8 octets of datagram header, 5 of chunk header. */
    const val DATAGRAM_HEADER = 13

    /** Offset of the 24-bit big-endian sequence number in the datagram header. */
    const val SEQUENCE_OFFSET = 2

    /** The sequence number's range: it wraps from this value to 0. */
    const val SEQUENCE_MASK = 0xFF_FFFF

    /** Offset of the chunk header's type-and-length field (two octets, big-endian). */
    const val CHUNK_FIELD_OFFSET = 11

    /** The top 5 bits of [CHUNK_FIELD_OFFSET]'s first octet: the chunk type. */
    const val CHUNK_TYPE_MASK = 0xF8

    /** Chunk type of a chunk that gives its length (`80 1f` before a FRAM record, `85 72` on a continuation). */
    const val CHUNK_WITH_LENGTH = 0x80

    /** The chunk length bits of the two-octet field; a chunk holds `length + 1` octets. */
    const val CHUNK_LENGTH_MASK = 0x7FF

    /**
     * NAL unit types that count as a genuine Annex-B start: 1 (non-IDR slice), 5 (IDR slice), 7 (SPS),
     * 8 (PPS) and 9 (access unit delimiter). A start code followed by any other type is ignored.
     */
    val VALID_ANNEXB_NAL_TYPES: Set<Int> = setOf(1, 5, 7, 8, 9)
}
