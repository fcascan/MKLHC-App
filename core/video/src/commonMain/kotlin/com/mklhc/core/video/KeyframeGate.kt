package com.mklhc.core.video

/**
 * The SPS and PPS NAL units of a stream, each with its start code, as the decoder's codec-specific data.
 *
 * @property sps the sequence parameter set.
 * @property pps the picture parameter set.
 */
internal class ParameterSets(val sps: ByteArray, val pps: ByteArray)

/**
 * Decides, access unit by access unit, whether the decoder should be given the picture.
 *
 * Business logic: a hardware decoder can only begin at an IDR picture, and needs the stream's SPS and PPS to
 * configure itself. The kart's stream joins mid-flight, so the gate holds pictures back until it has seen an IDR
 * together with both parameter sets (in the IDR's own access unit or in an earlier one; the latest of each is
 * remembered). From that point every picture is passed on, and the first decision after a start (or a restart)
 * carries the parameter sets so the caller can configure the decoder with them.
 *
 * [lostSync] is how the caller reports that it could not keep up (no free input buffer, a decoder error): from
 * then on pictures are skipped again until the next IDR, because feeding the decoder the pictures that follow a
 * gap would show corrupt images, and a live view is better frozen for a moment than smeared. The remembered
 * parameter sets survive, so the restart needs only the next IDR.
 *
 * Not thread-safe: one decode loop owns it.
 */
internal class KeyframeGate {

    /** What to do with one access unit. */
    sealed interface Decision {
        /** Do not decode this picture: the stream has not (re)started yet. */
        data object Skip : Decision

        /**
         * Decode this picture.
         *
         * @property keyFrame whether it holds an IDR slice.
         * @property parameterSets set only on the picture that (re)starts the stream: the parameter sets to
         *   configure the decoder with when it is not already running; null on every later picture.
         */
        class Decode(val keyFrame: Boolean, val parameterSets: ParameterSets?) : Decision
    }

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var running = false

    /** Judges one Annex-B access unit. */
    fun decide(accessUnit: ByteArray): Decision {
        var keyFrame = false
        for (unit in AnnexB.scan(accessUnit)) {
            when (unit.type) {
                AnnexB.NAL_TYPE_SPS -> sps = accessUnit.copyOfRange(unit.start, unit.end)
                AnnexB.NAL_TYPE_PPS -> pps = accessUnit.copyOfRange(unit.start, unit.end)
                AnnexB.NAL_TYPE_IDR -> keyFrame = true
            }
        }
        if (running) return Decision.Decode(keyFrame, parameterSets = null)

        val knownSps = sps
        val knownPps = pps
        if (keyFrame && knownSps != null && knownPps != null) {
            running = true
            return Decision.Decode(keyFrame = true, parameterSets = ParameterSets(knownSps, knownPps))
        }
        return Decision.Skip
    }

    /** Reports that the decoder is no longer in step; pictures are skipped until the next IDR. */
    fun lostSync() {
        running = false
    }
}
