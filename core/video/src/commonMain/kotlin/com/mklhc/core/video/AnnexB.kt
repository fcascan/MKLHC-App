package com.mklhc.core.video

/**
 * One NAL unit located inside an Annex-B byte stream.
 *
 * @property type the NAL unit type, the low five bits of its header octet (5 is an IDR slice, 7 the SPS, 8 the PPS).
 * @property start index of the first octet of the unit's start code (three or four octets).
 * @property end index one past the last octet of the unit, with any trailing zero padding excluded.
 */
internal data class NalUnit(val type: Int, val start: Int, val end: Int)

/**
 * Locates the NAL units of an H.264 Annex-B access unit.
 *
 * Business logic: the decoder must be started at an IDR picture and configured with the stream's SPS and PPS,
 * so the video layer has to see inside each access unit just far enough to know which NAL units it holds and
 * where each one starts and ends. This reads only start codes (`00 00 01`, optionally preceded by one more zero
 * octet) and the type bits of each NAL header; it never parses a payload. A NAL unit's last octet is never zero
 * (the bitstream ends in a stop bit), so zero octets between the end of a unit and the next start code are
 * padding and are left out of the unit. Octets before the first start code, and a start code with no header octet
 * after it, belong to no unit. Data that is not Annex-B yields no units.
 */
internal object AnnexB {

    /** NAL unit type of a coded slice of an IDR picture. */
    const val NAL_TYPE_IDR = 5

    /** NAL unit type of a sequence parameter set. */
    const val NAL_TYPE_SPS = 7

    /** NAL unit type of a picture parameter set. */
    const val NAL_TYPE_PPS = 8

    private const val NAL_TYPE_MASK = 0x1F
    private const val START_CODE_SIZE = 3

    /** Returns every NAL unit in [data], in stream order. */
    fun scan(data: ByteArray): List<NalUnit> {
        // Positions of the "00 00 01" triplets that have a NAL header octet after them.
        val triplets = ArrayList<Int>()
        var i = 0
        while (i + START_CODE_SIZE < data.size) {
            if (data[i + 2] > 1) {
                // The third octet is neither 0 nor 1, so no start code can begin at i, i + 1 or i + 2.
                i += START_CODE_SIZE
            } else if (data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE) {
                triplets += i
                i += START_CODE_SIZE
            } else {
                i++
            }
        }
        if (triplets.isEmpty()) return emptyList()

        // A four-octet start code has one extra leading zero; it belongs to the unit that follows it.
        val starts = IntArray(triplets.size) { k ->
            val triplet = triplets[k]
            val floor = if (k == 0) 0 else triplets[k - 1] + START_CODE_SIZE + 1
            if (triplet - 1 >= floor && data[triplet - 1] == ZERO) triplet - 1 else triplet
        }

        return List(triplets.size) { k ->
            val headerIndex = triplets[k] + START_CODE_SIZE
            var end = if (k + 1 < triplets.size) starts[k + 1] else data.size
            while (end > headerIndex + 1 && data[end - 1] == ZERO) end--
            NalUnit(type = data[headerIndex].toInt() and NAL_TYPE_MASK, start = starts[k], end = end)
        }
    }

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}
