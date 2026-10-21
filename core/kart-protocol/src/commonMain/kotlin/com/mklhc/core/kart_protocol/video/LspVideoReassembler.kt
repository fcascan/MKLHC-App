// Adapted from mk-live https://github.com/olus85/mk-live, src/mk_live/video_worker.py (MIT).
// The continuation header, the chunk length, the frame-end field and the sequence-number check are original: they
// follow the layout measured from the kart's stream on 2026-10-21 and checked with FFmpeg (see LspVideo), which the
// reference does not handle.
package com.mklhc.core.kart_protocol.video

import co.touchlab.kermit.Logger

/**
 * Rebuilds H.264 Annex-B video frames from the kart's UDP video datagrams.
 *
 * Business logic: the kart sends each video frame as one or more datagrams, each starting with a
 * [LspVideo.DATAGRAM_HEADER]-octet header (sequence number, group, chunk header) and ending with an
 * [LspVideo.PACKET_TRAILER]-octet trailer, neither of which is H.264. The first datagram of a frame carries a
 * "FRAM" record (see [findFramStart]) that gives the frame's H.264 length and where its H.264 starts; the rest
 * follows in continuation datagrams. A frame is complete when its H.264 length is reached; it is then handed out
 * as exactly that many octets.
 *
 * Rules, in the order [feed] applies them to each datagram:
 * 1. A datagram shorter than its header and trailer is dropped (it cannot hold anything) and logged at debug
 *    level; it does not count for the sequence. (Every datagram the kart was seen to send is 1416 octets; should
 *    it ever send a short one, the datagram after it is then taken for a loss.)
 * 2. The sequence number (octets 2-4) must follow the previous datagram's, wrapping at 2^24. When it does not
 *    (a datagram was lost, or came twice) and a frame is in progress, that frame is dropped with a warning, since
 *    it can no longer come out right.
 * 3. A datagram for which [findFramStart] finds a record STARTS a frame, taking its bytes from `rawStart` up to
 *    the trailer.
 * 4. Any other datagram is a CONTINUATION. When its chunk header gives a length ([LspVideo.CHUNK_WITH_LENGTH]) the
 *    H.264 is the `length + 1` octets right after the header, and whatever follows it before the trailer is stale
 *    padding, not counted anywhere; otherwise (the frame's last datagram) the [LspVideo.FRAME_END_FIELD] comes
 *    first and the H.264 runs from after it to the trailer. With no frame in progress the datagram is dropped and
 *    logged at debug level.
 * 5. When the frame reaches its H.264 length it is emitted and the state is cleared. Octets beyond the length
 *    (the last datagram of a frame is padded with stale octets) are trimmed and counted in [bytesDropped]. A
 *    frame can reach its length before its last datagram: the kart sometimes ends a sized chunk with the next
 *    frame's start code (`00 00 01 09`), past the frame's length; that last datagram then arrives with no frame
 *    in progress and is dropped.
 *
 * INCOMPLETE-FRAME POLICY: if a new frame starts while the previous one is still incomplete, or a datagram of it
 * was lost (rule 2), the incomplete frame is DROPPED, never emitted as a partial frame; a warning is logged,
 * [framesDropped] is incremented and its buffered octets are added to [bytesDropped]; continuations that still
 * belong to it are then dropped as having no frame in progress, and the next frame begins fresh. [reset] drops
 * the frame the same way (logged at debug level) and forgets the sequence. A partial access unit would only
 * corrupt the decoder's picture, so the decoder is left to resynchronise on the next frame.
 *
 * Memory is bounded: the buffer of a frame is allocated at its announced H.264 length, which [findFramStart]
 * caps at [LspVideo.MAX_FRAM_PACKET_SIZE] minus [LspVideo.FRAM_SIZE_OVERHEAD] and [LspVideo.FRAME_END_FIELD], and
 * nothing is ever appended beyond it, so a malformed stream cannot grow memory without bound.
 *
 * Known limit: a continuation that happens to hold "FRAM" at the right offset would be taken for a new start.
 * Checked against a 2,185-frame capture: FFmpeg decodes every frame rebuilt by these rules without an error.
 *
 * Pure byte processing: not suspending, no I/O, and not thread-safe (one coroutine feeds it). The counters
 * are exposed for tests and diagnostics.
 *
 * @param log the logger; replaceable so tests can capture what is logged.
 */
class LspVideoReassembler(
    private val log: Logger = Logger.withTag(TAG),
) {

    /** The H.264 buffer of the frame in progress, sized to its expected length; null when idle. */
    private var frame: ByteArray? = null

    /** Octets of [frame] filled so far. */
    private var filled: Int = 0

    /** The sequence number of the last datagram fed, or null before the first one and after [reset]. */
    private var lastSequence: Int? = null

    /** Frames emitted so far. */
    var framesEmitted: Int = 0
        private set

    /** Incomplete frames abandoned because a new frame started, a datagram was lost, or [reset] was called. */
    var framesDropped: Int = 0
        private set

    /**
     * Datagrams discarded whole: shorter than their header and trailer, or a continuation with no frame in
     * progress.
     */
    var datagramsDropped: Int = 0
        private set

    /**
     * Octets discarded without reaching an emitted frame: the full size of each dropped datagram, the
     * buffered octets of each abandoned frame, and surplus octets trimmed off a completed frame. The headers,
     * frame-end fields and trailers of accepted datagrams, and the stale octets after a chunk of known length, are
     * not counted.
     */
    var bytesDropped: Long = 0
        private set

    /** True while a frame has started and is not yet complete. */
    val frameInProgress: Boolean get() = frame != null

    /** H.264 octets collected for the frame in progress; 0 when idle. */
    val bufferedBytes: Int get() = if (frame != null) filled else 0

    /** H.264 length of the frame in progress; 0 when idle. */
    val expectedBytes: Int get() = frame?.size ?: 0

    /**
     * Processes one video datagram.
     *
     * @param datagram a whole UDP payload as received (including its header and trailer); not modified or
     *   retained.
     * @return the frames completed by this datagram: empty, or one frame (an Annex-B access unit of exactly
     *   the announced H.264 length, an array the caller owns).
     */
    fun feed(datagram: ByteArray): List<ByteArray> {
        if (datagram.size < MIN_DATAGRAM) {
            dropDatagram(datagram, "shorter than its header and trailer")
            return emptyList()
        }
        val payloadEnd = datagram.size - LspVideo.PACKET_TRAILER
        checkSequence(sequenceOf(datagram))

        val start = findFramStart(datagram)
        if (start != null) {
            abandonFrameInProgress("a new frame started", warning = true)
            frame = ByteArray(start.expectedH264Len)
            filled = 0
            append(datagram, start.rawStart, payloadEnd)
        } else {
            if (frame == null) {
                dropDatagram(datagram, "continuation with no frame in progress")
                return emptyList()
            }
            appendContinuation(datagram, payloadEnd)
        }
        return completedFrame()
    }

    /**
     * Abandons the frame in progress, if any, under the same policy as a new frame starting, and forgets the
     * sequence number; the counters are kept. Call it when the video stream restarts (new session, link loss).
     */
    fun reset() {
        abandonFrameInProgress("reset", warning = false)
        lastSequence = null
    }

    /** Records [sequence] and drops the frame in progress when it does not follow the previous datagram's. */
    private fun checkSequence(sequence: Int) {
        val previous = lastSequence
        lastSequence = sequence
        if (previous == null || frame == null) return
        val next = (previous + 1) and LspVideo.SEQUENCE_MASK
        if (sequence != next) {
            abandonFrameInProgress("a datagram was lost", warning = true, detail = " expectedSeq=$next gotSeq=$sequence")
        }
    }

    /**
     * Appends a continuation's H.264: the sized chunk right after the header (never past the trailer) when the chunk
     * header gives a length, otherwise (the frame's last datagram) everything from after the frame-end field to the
     * trailer.
     */
    private fun appendContinuation(datagram: ByteArray, payloadEnd: Int) {
        val field = u16BigEndian(datagram, LspVideo.CHUNK_FIELD_OFFSET)
        if (((field ushr Byte.SIZE_BITS) and LspVideo.CHUNK_TYPE_MASK) == LspVideo.CHUNK_WITH_LENGTH) {
            val length = (field and LspVideo.CHUNK_LENGTH_MASK) + 1
            append(datagram, LspVideo.DATAGRAM_HEADER, minOf(LspVideo.DATAGRAM_HEADER + length, payloadEnd))
        } else {
            append(datagram, LspVideo.DATAGRAM_HEADER + LspVideo.FRAME_END_FIELD, payloadEnd)
        }
    }

    /** Copies `datagram[from, to)` into the frame buffer, trimming what does not fit. */
    private fun append(datagram: ByteArray, from: Int, to: Int) {
        val target = frame ?: return
        val available = to - from
        if (available <= 0) return

        val room = target.size - filled
        val take = if (available < room) available else room
        datagram.copyInto(target, filled, from, from + take)
        filled += take

        val surplus = available - take
        if (surplus > 0) {
            bytesDropped += surplus
            log.d { "append(): surplus octets trimmed -> surplus=$surplus expected=${target.size}" }
        }
    }

    /** Emits the frame in progress if it is complete and clears the state; otherwise returns nothing. */
    private fun completedFrame(): List<ByteArray> {
        val done = frame ?: return emptyList()
        if (filled < done.size) return emptyList()

        frame = null
        filled = 0
        framesEmitted++
        return listOf(done)
    }

    /**
     * Drops the frame in progress, if any, per the incomplete-frame policy.
     *
     * @param reason why, for the log.
     * @param warning true when data was lost (logged as a warning), false for an explicit [reset] (expected:
     *   logged at debug level).
     * @param detail extra `key=value` pairs for the log line, each with a leading space.
     */
    private fun abandonFrameInProgress(reason: String, warning: Boolean, detail: String = "") {
        val abandoned = frame ?: return
        framesDropped++
        bytesDropped += filled
        if (warning) {
            log.w { "abandonFrameInProgress(): incomplete frame dropped, $reason -> have=$filled expected=${abandoned.size}$detail" }
        } else {
            log.d { "abandonFrameInProgress(): incomplete frame dropped on $reason -> have=$filled expected=${abandoned.size}$detail" }
        }
        frame = null
        filled = 0
    }

    private fun dropDatagram(datagram: ByteArray, reason: String) {
        datagramsDropped++
        bytesDropped += datagram.size
        log.d { "feed(): datagram dropped -> reason=$reason size=${datagram.size}" }
    }

    private fun sequenceOf(datagram: ByteArray): Int =
        ((datagram[LspVideo.SEQUENCE_OFFSET].toInt() and 0xFF) shl 16) or
            u16BigEndian(datagram, LspVideo.SEQUENCE_OFFSET + 1)

    private fun u16BigEndian(datagram: ByteArray, offset: Int): Int =
        ((datagram[offset].toInt() and 0xFF) shl Byte.SIZE_BITS) or (datagram[offset + 1].toInt() and 0xFF)

    private companion object {
        private const val TAG = "LspVideoReassembler"

        /** The smallest datagram that can carry anything: its header and its trailer. */
        private const val MIN_DATAGRAM = LspVideo.DATAGRAM_HEADER + LspVideo.PACKET_TRAILER
    }
}
