// Adapted from mk-live https://github.com/olus85/mk-live, src/mk_live/video_worker.py (MIT).
package com.mklhc.core.kart_protocol.video

/**
 * Where the H.264 of a video frame starts inside a datagram that opens that frame.
 *
 * Business logic: the kart marks the first datagram of every video frame with a "FRAM" record. This is
 * what [findFramStart] reads out of it so [LspVideoReassembler] knows how long the frame is and where its
 * H.264 bytes begin.
 *
 * @property expectedH264Len total H.264 octets of the frame across all its datagrams (the FRAM size minus
 *   [LspVideo.FRAM_SIZE_OVERHEAD]); always positive.
 * @property rawStart offset in the datagram of the first H.264 octet (an Annex-B start code).
 * @property framOffset offset in the datagram of the "FRAM" tag: [LspVideo.FRAM_OFFSET_PLAIN] or
 *   [LspVideo.FRAM_OFFSET_WITH_L2].
 */
data class FramStart(val expectedH264Len: Int, val rawStart: Int, val framOffset: Int)

/** Octets of an "L2" block that [l2ChecksumOk] needs to be present: tag, checksum, value and 2 more. */
private const val L2_BLOCK_SIZE = 14

/** Octets of the tag and the big-endian size that follow the length octet: "FRAM" + u32. */
private const val FRAM_HEADER_SIZE = 8

/** Bit set of [LspVideo.VALID_ANNEXB_NAL_TYPES], one bit per NAL type, for a cheap test per octet. */
private val validNalTypeMask: Int = LspVideo.VALID_ANNEXB_NAL_TYPES.fold(0) { mask, type -> mask or (1 shl type) }

/**
 * Finds the earliest Annex-B start of an H.264 NAL unit at or after [start].
 *
 * Business logic: a start is a 4-octet marker `00 00 00 01` or a 3-octet marker `00 00 01` whose next
 * octet is a NAL header with a type in [LspVideo.VALID_ANNEXB_NAL_TYPES] (only the low five bits of that
 * octet count). A marker that is followed by any other NAL type, or by nothing, is ignored, which keeps a
 * stray `00 00 01` inside the video header or trailer from being taken for a frame start. Both markers
 * are considered and the smallest qualifying position wins; a 4-octet marker is reported at its first
 * zero, not at the 3-octet marker embedded in it, unless the search itself begins inside the marker.
 *
 * Differences from the reference: a negative [start] is treated as 0 (the reference is only ever called
 * with non-negative offsets), and a marker at the very end of [buf] with no octet after it never matches.
 *
 * @param buf the bytes to scan; not modified.
 * @param start first offset to consider.
 * @return the offset of the first octet of the marker, or null if there is none.
 */
fun findAnnexbStart(buf: ByteArray, start: Int = 0): Int? {
    var i = if (start < 0) 0 else start
    while (i < buf.size) {
        val markerLength = startCodeLengthAt(buf, i)
        if (markerLength > 0) {
            val nalIndex = i + markerLength
            if (nalIndex < buf.size && (validNalTypeMask ushr (buf[nalIndex].toInt() and 0x1F)) and 1 == 1) {
                return i
            }
        }
        i++
    }
    return null
}

/**
 * The length of the Annex-B start code that begins at [i] (4 for `00 00 00 01`, 3 for `00 00 01`), or 0 if
 * none does. The two patterns cannot both begin at the same offset.
 */
private fun startCodeLengthAt(buf: ByteArray, i: Int): Int {
    if (i + 2 >= buf.size || buf[i] != ZERO || buf[i + 1] != ZERO) return 0
    if (buf[i + 2] == ONE) return 3
    if (buf[i + 2] == ZERO && i + 3 < buf.size && buf[i + 3] == ONE) return 4
    return 0
}

private const val ZERO: Byte = 0x00
private const val ONE: Byte = 0x01

/**
 * Checks the "L2" block that guards a FRAM tag at [LspVideo.FRAM_OFFSET_WITH_L2].
 *
 * Business logic: the block is the ASCII tag "L2" at [off], a big-endian 16-bit checksum at `off + 2`
 * and a big-endian 64-bit value at `off + 4`. The checksum is a fold of the value:
 * `folded = ((value >> 48) & 0x7fff) ^ ((value >> 32) & 0xffffffff)`, then
 * `folded ^= tail32 >> 16`, `folded ^= tail32`, `folded ^= 0xaaaaaaaa` with `tail32 = value & 0xffffffff`,
 * and the block is genuine when the checksum equals the low 16 bits of `folded`. It is what lets a FRAM
 * tag at the later offset be told from the same four letters appearing by chance in a payload.
 *
 * @param pkt the datagram; not modified.
 * @param off offset of the "L2" tag.
 * @return true if the block fits in [pkt], carries the tag and its checksum matches; false for a negative
 *   or out-of-range [off], a block that does not fit, a wrong tag, or a wrong checksum.
 */
fun l2ChecksumOk(pkt: ByteArray, off: Int = LspVideo.L2_OFFSET): Boolean {
    if (off < 0 || off > pkt.size - L2_BLOCK_SIZE) return false
    if (pkt[off] != 'L'.code.toByte() || pkt[off + 1] != '2'.code.toByte()) return false

    val checksum = ((pkt[off + 2].toInt() and 0xFF) shl 8) or (pkt[off + 3].toInt() and 0xFF)
    var value = 0L
    for (k in 0 until 8) {
        value = (value shl 8) or (pkt[off + 4 + k].toLong() and 0xFF)
    }

    val tail32 = value and 0xFFFFFFFFL
    var folded = ((value ushr 48) and 0x7FFF) xor ((value ushr 32) and 0xFFFFFFFFL)
    folded = folded xor (tail32 ushr 16)
    folded = folded xor tail32
    folded = folded xor 0xAAAAAAAAL
    return checksum.toLong() == (folded and 0xFFFF)
}

/**
 * Looks for the "FRAM" record that opens a video frame in [pkt].
 *
 * Business logic: the tag is tried at [LspVideo.FRAM_OFFSET_PLAIN] and then at
 * [LspVideo.FRAM_OFFSET_WITH_L2]; a candidate at the later offset only counts if the "L2" block at
 * [LspVideo.L2_OFFSET] has a valid checksum. A candidate is accepted when its big-endian u32 size, which
 * follows the tag, is in `1..`[LspVideo.MAX_FRAM_PACKET_SIZE] and leaves a positive H.264 length once
 * [LspVideo.FRAM_SIZE_OVERHEAD] is subtracted; a rejected candidate falls through to the next offset.
 *
 * The H.264 start is taken from the length octet just before the tag (`rawStart = framOffset - 1 +
 * length + 4`, the octet read as unsigned) when that lands exactly on an Annex-B start code; otherwise
 * the first Annex-B start after the tag and size is used, and a candidate with no such start is rejected.
 *
 * @param pkt the datagram; not modified.
 * @return the frame start, or null if [pkt] does not open a frame (it is then a continuation).
 */
fun findFramStart(pkt: ByteArray): FramStart? {
    for (framOffset in FRAM_OFFSETS) {
        if (framOffset < 1 || framOffset + FRAM_HEADER_SIZE > pkt.size) continue
        if (!hasFramTag(pkt, framOffset)) continue
        if (framOffset == LspVideo.FRAM_OFFSET_WITH_L2 && !l2ChecksumOk(pkt, LspVideo.L2_OFFSET)) continue

        val base = framOffset - 1
        val packetSize = u32BigEndian(pkt, framOffset + 4)
        if (packetSize <= 0 || packetSize > LspVideo.MAX_FRAM_PACKET_SIZE) continue

        var rawStart = base + (pkt[base].toInt() and 0xFF) + 4
        if (rawStart >= pkt.size || findAnnexbStart(pkt, rawStart) != rawStart) {
            rawStart = findAnnexbStart(pkt, framOffset + FRAM_HEADER_SIZE) ?: continue
        }

        val expected = packetSize - LspVideo.FRAM_SIZE_OVERHEAD
        if (expected > 0) return FramStart(expected.toInt(), rawStart, framOffset)
    }
    return null
}

private val FRAM_OFFSETS = intArrayOf(LspVideo.FRAM_OFFSET_PLAIN, LspVideo.FRAM_OFFSET_WITH_L2)

private val FRAM_TAG = "FRAM".encodeToByteArray()

/** True if the ASCII text "FRAM" is at [offset] of [pkt] (the caller has checked it fits). */
private fun hasFramTag(pkt: ByteArray, offset: Int): Boolean {
    for (k in FRAM_TAG.indices) {
        if (pkt[offset + k] != FRAM_TAG[k]) return false
    }
    return true
}

/** The unsigned big-endian 32-bit value at [offset] as a [Long], so a size above 2^31 is not negative. */
private fun u32BigEndian(pkt: ByteArray, offset: Int): Long =
    ((pkt[offset].toLong() and 0xFF) shl 24) or
        ((pkt[offset + 1].toLong() and 0xFF) shl 16) or
        ((pkt[offset + 2].toLong() and 0xFF) shl 8) or
        (pkt[offset + 3].toLong() and 0xFF)
