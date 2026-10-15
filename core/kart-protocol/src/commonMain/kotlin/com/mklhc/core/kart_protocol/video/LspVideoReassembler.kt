// Adapted from mk-live https://github.com/olus85/mk-live, src/mk_live/video_worker.py (MIT).
package com.mklhc.core.kart_protocol.video

import co.touchlab.kermit.Logger

/**
 * Rebuilds H.264 Annex-B video frames from the kart's UDP video datagrams.
 *
 * Business logic: the kart sends each video frame as one or more datagrams. The first datagram of a frame
 * carries a "FRAM" record (see [findFramStart]) that gives the frame's total H.264 length and where its
 * H.264 starts; if the frame does not fit one datagram the rest follows in continuation datagrams that
 * hold only H.264 bytes. Every datagram ends with an [LspVideo.PACKET_TRAILER]-octet trailer that is not
 * H.264 and is removed. A frame is complete when its H.264 length is reached; it is then handed out as
 * exactly that many octets (the trailers and any surplus are never part of it).
 *
 * Rules, in the order [feed] applies them to each datagram:
 * 1. A datagram shorter than the trailer is dropped (it cannot hold anything) and logged at debug level.
 * 2. A datagram for which [findFramStart] finds a record STARTS a frame, taking its bytes from `rawStart`
 *    up to the trailer.
 * 3. Any other datagram is a CONTINUATION: its bytes up to the trailer are appended to the frame in
 *    progress; with no frame in progress it is dropped and logged at debug level.
 * 4. When the frame reaches its H.264 length it is emitted and the state is cleared. Octets beyond the
 *    length (the datagram that completes a frame can carry more than is needed) are trimmed and counted
 *    in [bytesDropped].
 *
 * INCOMPLETE-FRAME POLICY: if a new frame starts while the previous one is still incomplete (a datagram of
 * it was lost or arrived late), the incomplete frame is DROPPED, never emitted as a partial frame; a
 * warning is logged, [framesDropped] is incremented and its buffered octets are added to [bytesDropped];
 * then the new frame begins fresh. The same applies to [reset]. A partial access unit would only corrupt
 * the decoder's picture, so the decoder is left to resynchronise on the next frame.
 *
 * Memory is bounded: the buffer of a frame is allocated at its announced H.264 length, which
 * [findFramStart] caps at [LspVideo.MAX_FRAM_PACKET_SIZE] minus [LspVideo.FRAM_SIZE_OVERHEAD], and nothing
 * is ever appended beyond it, so a malformed stream cannot grow memory without bound.
 *
 * Known limits (inherited from the reference): there is no sequence number, so a lost continuation in the
 * middle of a frame is not detected and the frame comes out the right length but corrupt; and a
 * continuation that happens to hold "FRAM" at the right offset would be taken for a new start.
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

    /** Frames emitted so far. */
    var framesEmitted: Int = 0
        private set

    /** Incomplete frames abandoned because a new frame started or [reset] was called. */
    var framesDropped: Int = 0
        private set

    /** Datagrams discarded whole: shorter than the trailer, or a continuation with no frame in progress. */
    var datagramsDropped: Int = 0
        private set

    /**
     * Octets discarded without reaching an emitted frame: the full size of each dropped datagram, the
     * buffered octets of each abandoned frame, and surplus octets trimmed off a completed frame. The
     * stripped trailers of accepted datagrams are not counted.
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
     * @param datagram a whole UDP payload as received (including its trailer); not modified or retained.
     * @return the frames completed by this datagram: empty, or one frame (an Annex-B access unit of exactly
     *   the announced H.264 length, an array the caller owns).
     */
    fun feed(datagram: ByteArray): List<ByteArray> {
        if (datagram.size < LspVideo.PACKET_TRAILER) {
            dropDatagram(datagram, "shorter than the trailer")
            return emptyList()
        }
        val payloadEnd = datagram.size - LspVideo.PACKET_TRAILER

        val start = findFramStart(datagram)
        if (start != null) {
            abandonFrameInProgress(superseded = true)
            frame = ByteArray(start.expectedH264Len)
            filled = 0
            append(datagram, start.rawStart, payloadEnd)
        } else {
            if (frame == null) {
                dropDatagram(datagram, "continuation with no frame in progress")
                return emptyList()
            }
            append(datagram, 0, payloadEnd)
        }
        return completedFrame()
    }

    /**
     * Abandons the frame in progress, if any, under the same policy as a new frame starting; the counters
     * are kept. Call it when the video stream restarts (new session, link loss).
     */
    fun reset() {
        abandonFrameInProgress(superseded = false)
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
     * @param superseded true when a new frame started (data was lost: logged as a warning), false for an
     *   explicit [reset] (expected: logged at debug level).
     */
    private fun abandonFrameInProgress(superseded: Boolean) {
        val abandoned = frame ?: return
        framesDropped++
        bytesDropped += filled
        if (superseded) {
            log.w { "abandonFrameInProgress(): incomplete frame dropped, a new frame started -> have=$filled expected=${abandoned.size}" }
        } else {
            log.d { "abandonFrameInProgress(): incomplete frame dropped on reset -> have=$filled expected=${abandoned.size}" }
        }
        frame = null
        filled = 0
    }

    private fun dropDatagram(datagram: ByteArray, reason: String) {
        datagramsDropped++
        bytesDropped += datagram.size
        log.d { "feed(): datagram dropped -> reason=$reason size=${datagram.size}" }
    }

    private companion object {
        private const val TAG = "LspVideoReassembler"
    }
}
