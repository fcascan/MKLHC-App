package com.mklhc.core.video

/*
 * Original code: a tiny H.264 bitstream writer for decoder tests, written from the syntax tables of ITU-T H.264
 * (sections 7.3.2.1 SPS, 7.3.2.2 PPS, 7.3.3 slice header, 7.3.4 slice data, 7.3.5 macroblock layer). No encoder and
 * no recorded video are involved: every picture is built from I_PCM macroblocks, which carry raw samples, so the
 * colour a decoder must produce is known exactly.
 */

/**
 * Builds small, valid Annex-B H.264 access units with a known picture, for testing a decoder without real video.
 *
 * Business logic: the stream is Baseline profile, CAVLC, one slice per picture, picture order count type 2 (output
 * order equals decoding order, so a low-delay decoder hands every picture out at once). [idr] gives an IDR access
 * unit (SPS, PPS, IDR slice) whose every macroblock is I_PCM filled with one YUV colour; [skippedP] gives a P picture
 * whose macroblocks are all skipped, so it decodes to a copy of the previous picture.
 *
 * @param widthInMbs the picture width in 16-pixel macroblocks.
 * @param heightInMbs the picture height in 16-pixel macroblocks.
 */
internal class SyntheticH264(private val widthInMbs: Int = 2, private val heightInMbs: Int = 2) {

    val width: Int get() = widthInMbs * MB_SIZE
    val height: Int get() = heightInMbs * MB_SIZE

    private val macroblocks: Int get() = widthInMbs * heightInMbs

    /** An IDR access unit (SPS, PPS and an IDR slice) of one flat colour given as 8-bit Y, Cb and Cr samples. */
    fun idr(y: Int, cb: Int, cr: Int, idrPicId: Int = 0): ByteArray = sps() + pps() + idrSlice(y, cb, cr, idrPicId)

    /** A P access unit with every macroblock skipped; [frameNum] counts the reference pictures since the IDR (1, 2, ...). */
    fun skippedP(frameNum: Int): ByteArray {
        val bits = BitWriter()
        bits.ue(0) // first_mb_in_slice
        bits.ue(SLICE_TYPE_P_ALL)
        bits.ue(0) // pic_parameter_set_id
        bits.u(frameNum and FRAME_NUM_MASK, FRAME_NUM_BITS)
        bits.u(0, 1) // num_ref_idx_active_override_flag
        bits.u(0, 1) // ref_pic_list_modification_flag_l0
        bits.u(0, 1) // adaptive_ref_pic_marking_mode_flag
        bits.se(0) // slice_qp_delta
        bits.ue(1) // disable_deblocking_filter_idc: off
        bits.ue(macroblocks) // mb_skip_run: the whole picture
        bits.trailing()
        return nal(NAL_HEADER_P, bits.bytes())
    }

    private fun sps(): ByteArray {
        val bits = BitWriter()
        bits.u(PROFILE_BASELINE, 8)
        bits.u(CONSTRAINTS_BASELINE, 8)
        bits.u(LEVEL_3_0, 8)
        bits.ue(0) // seq_parameter_set_id
        bits.ue(0) // log2_max_frame_num_minus4: frame_num has 4 bits
        bits.ue(2) // pic_order_cnt_type 2: output order is decoding order
        bits.ue(1) // max_num_ref_frames
        bits.u(0, 1) // gaps_in_frame_num_value_allowed_flag
        bits.ue(widthInMbs - 1)
        bits.ue(heightInMbs - 1)
        bits.u(1, 1) // frame_mbs_only_flag
        bits.u(1, 1) // direct_8x8_inference_flag
        bits.u(0, 1) // frame_cropping_flag
        bits.u(0, 1) // vui_parameters_present_flag
        bits.trailing()
        return nal(NAL_HEADER_SPS, bits.bytes())
    }

    private fun pps(): ByteArray {
        val bits = BitWriter()
        bits.ue(0) // pic_parameter_set_id
        bits.ue(0) // seq_parameter_set_id
        bits.u(0, 1) // entropy_coding_mode_flag: CAVLC
        bits.u(0, 1) // bottom_field_pic_order_in_frame_present_flag
        bits.ue(0) // num_slice_groups_minus1
        bits.ue(0) // num_ref_idx_l0_default_active_minus1
        bits.ue(0) // num_ref_idx_l1_default_active_minus1
        bits.u(0, 1) // weighted_pred_flag
        bits.u(0, 2) // weighted_bipred_idc
        bits.se(0) // pic_init_qp_minus26
        bits.se(0) // pic_init_qs_minus26
        bits.se(0) // chroma_qp_index_offset
        bits.u(1, 1) // deblocking_filter_control_present_flag
        bits.u(0, 1) // constrained_intra_pred_flag
        bits.u(0, 1) // redundant_pic_cnt_present_flag
        bits.trailing()
        return nal(NAL_HEADER_PPS, bits.bytes())
    }

    private fun idrSlice(y: Int, cb: Int, cr: Int, idrPicId: Int): ByteArray {
        val bits = BitWriter()
        bits.ue(0) // first_mb_in_slice
        bits.ue(SLICE_TYPE_I_ALL)
        bits.ue(0) // pic_parameter_set_id
        bits.u(0, FRAME_NUM_BITS) // frame_num
        bits.ue(idrPicId)
        bits.u(0, 1) // no_output_of_prior_pics_flag
        bits.u(0, 1) // long_term_reference_flag
        bits.se(0) // slice_qp_delta
        bits.ue(1) // disable_deblocking_filter_idc: off
        repeat(macroblocks) {
            bits.ue(MB_TYPE_I_PCM)
            bits.alignWithZeros() // pcm_alignment_zero_bit
            repeat(MB_SIZE * MB_SIZE) { bits.u(y, 8) }
            repeat(CHROMA_SAMPLES) { bits.u(cb, 8) }
            repeat(CHROMA_SAMPLES) { bits.u(cr, 8) }
        }
        bits.trailing()
        return nal(NAL_HEADER_IDR, bits.bytes())
    }

    /** A NAL unit with a 4-octet start code, its header octet and the RBSP with emulation prevention applied. */
    private fun nal(header: Int, rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(rbsp.size + 8)
        out += listOf(0, 0, 0, 1, header).map { it.toByte() }
        var zeros = 0
        for (octet in rbsp) {
            if (zeros >= 2 && (octet.toInt() and 0xFF) <= 3) {
                out += EMULATION_PREVENTION
                zeros = 0
            }
            out += octet
            zeros = if (octet.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Writes bits most significant first. */
    private class BitWriter {
        private val octets = ArrayList<Byte>()
        private var current = 0
        private var used = 0

        fun u(value: Int, bits: Int) {
            for (shift in bits - 1 downTo 0) bit((value ushr shift) and 1)
        }

        fun ue(value: Int) {
            val codeNum = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(codeNum)
            u(0, length - 1)
            u(codeNum, length)
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        fun alignWithZeros() {
            while (used != 0) bit(0)
        }

        /** rbsp_trailing_bits: a stop bit, then zeros to the octet boundary. */
        fun trailing() {
            bit(1)
            alignWithZeros()
        }

        fun bytes(): ByteArray {
            check(used == 0) { "not octet-aligned" }
            return octets.toByteArray()
        }

        private fun bit(value: Int) {
            current = (current shl 1) or value
            used++
            if (used == 8) {
                octets += current.toByte()
                current = 0
                used = 0
            }
        }
    }

    private companion object {
        const val MB_SIZE = 16
        const val CHROMA_SAMPLES = 64 // 8x8 per chroma plane in 4:2:0
        const val FRAME_NUM_BITS = 4
        const val FRAME_NUM_MASK = 0xF
        const val PROFILE_BASELINE = 66
        const val CONSTRAINTS_BASELINE = 0xC0 // constraint_set0 and constraint_set1
        const val LEVEL_3_0 = 30
        const val SLICE_TYPE_P_ALL = 5
        const val SLICE_TYPE_I_ALL = 7
        const val MB_TYPE_I_PCM = 25
        const val NAL_HEADER_SPS = 0x67 // nal_ref_idc 3, type 7
        const val NAL_HEADER_PPS = 0x68 // nal_ref_idc 3, type 8
        const val NAL_HEADER_IDR = 0x65 // nal_ref_idc 3, type 5
        const val NAL_HEADER_P = 0x41 // nal_ref_idc 2, type 1
        const val EMULATION_PREVENTION: Byte = 3
    }
}
