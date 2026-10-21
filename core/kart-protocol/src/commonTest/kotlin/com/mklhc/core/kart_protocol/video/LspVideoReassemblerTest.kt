package com.mklhc.core.kart_protocol.video

import co.touchlab.kermit.Severity
import com.mklhc.core.kart_protocol.CapturingLogWriter
import com.mklhc.core.kart_protocol.capturingLogger
import com.mklhc.core.kart_protocol.fuji.assertBytes
import com.mklhc.core.kart_protocol.hexBytes
import com.mklhc.core.kart_protocol.video.LspTestVectors.IDR_8
import com.mklhc.core.kart_protocol.video.LspTestVectors.STALE
import com.mklhc.core.kart_protocol.video.LspTestVectors.continuation
import com.mklhc.core.kart_protocol.video.LspTestVectors.framSizeFor
import com.mklhc.core.kart_protocol.video.LspTestVectors.lastOfFrame
import com.mklhc.core.kart_protocol.video.LspTestVectors.startAt13
import com.mklhc.core.kart_protocol.video.LspTestVectors.startAt43
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour tests for [LspVideoReassembler] over hand-built datagrams.
 *
 * Every vector is built from [LspTestVectors] in the layout measured from the kart: an 8-octet datagram header
 * with the sequence number, a 5-octet chunk header, then H.264, maybe stale padding, and the 8-octet trailer
 * (seven 0xee octets and a counter). The header starts with `ec c1` and the trailer with 0xee, so any header or
 * trailer octet that leaks into an emitted frame is easy to spot. The expected frames are the concatenation of
 * the H.264 parts, written out as literal hex below.
 *
 * Reference frame used by most tests (22 octets, `expected = 22`, so the FRAM size is 56), sequence 0, 1, 2:
 * ```
 * part 1  00 00 00 01 65 88 84 21   (8 octets, rides in the start datagram)
 * part 2  de ad be ef 01 02 03 04   (8 octets, first continuation)
 * part 3  05 06 07 08 09 0a         (6 octets, second continuation)
 * ```
 */
class LspVideoReassemblerTest {

    private val writer = CapturingLogWriter()
    private val reassembler = LspVideoReassembler(capturingLogger(writer, "LspVideoReassemblerTest"))

    private val part1 = IDR_8
    private val part2 = hexBytes("de ad be ef 01 02 03 04")
    private val part3 = hexBytes("05 06 07 08 09 0a")
    private val frame22 = hexBytes("00 00 00 01 65 88 84 21  de ad be ef 01 02 03 04  05 06 07 08 09 0a")

    // --- single datagram frames ------------------------------------------------------------------

    @Test
    fun `a frame that fits one datagram with FRAM at 13 is emitted at once without its trailer`() {
        // expected = 8, the start datagram carries all 8 H.264 octets, then the 8-octet trailer.
        val frames = reassembler.feed(startAt13(framSizeFor(8), IDR_8))

        assertEquals(1, frames.size)
        assertBytes(hexBytes("00 00 00 01 65 88 84 21"), frames.single(), "frame")
        assertEquals(1, reassembler.framesEmitted)
        assertFalse(reassembler.frameInProgress)
        assertEquals(0, reassembler.bufferedBytes)
        assertEquals(0, reassembler.datagramsDropped)
        assertEquals(0, reassembler.framesDropped)
        assertEquals(0L, reassembler.bytesDropped)
    }

    @Test
    fun `a frame that fits one datagram with FRAM at 43 is emitted at once`() {
        val frames = reassembler.feed(startAt43(framSizeFor(8), IDR_8))

        assertEquals(1, frames.size)
        assertBytes(hexBytes("00 00 00 01 65 88 84 21"), frames.single(), "frame")
    }

    @Test
    fun `a start datagram with a wrong length octet still yields the H264 from the fallback start`() {
        // Length octet 0x05 -> the direct rawStart (21) is filler, so the first Annex-B start after the
        // FRAM header (47) is used; the 0x22 filler must not leak into the frame.
        val frames = reassembler.feed(startAt13(framSizeFor(8), IDR_8, lengthOctet = 0x05))

        assertEquals(1, frames.size)
        assertBytes(IDR_8, frames.single(), "frame")
    }

    // --- multi datagram frames -------------------------------------------------------------------

    @Test
    fun `a frame spanning three datagrams is emitted when the last one arrives`() {
        // FRAM size 60 = 22 + 34 + 4. Start carries part 1 (8), then 8 and 6 octets in continuations.
        val start = startAt13(60L, part1, trailerCounter = 0x02, seq = 0)
        val cont1 = continuation(part2, seq = 1, trailerCounter = 0x04)
        val cont2 = continuation(part3, seq = 2, trailerCounter = 0x06)

        val afterStart = reassembler.feed(start)
        assertTrue(afterStart.isEmpty(), "nothing is emitted until the frame is complete")
        assertTrue(reassembler.frameInProgress)
        assertEquals(22, reassembler.expectedBytes)
        assertEquals(8, reassembler.bufferedBytes)

        val afterCont1 = reassembler.feed(cont1)
        assertTrue(afterCont1.isEmpty())
        assertEquals(16, reassembler.bufferedBytes, "only the chunk counts, not the 13-octet header")

        val afterCont2 = reassembler.feed(cont2)
        assertEquals(1, afterCont2.size)
        assertBytes(frame22, afterCont2.single(), "reassembled frame")
        assertEquals(22, afterCont2.single().size, "exactly the expected length")
        assertFalse(afterCont2.single().contains(0xEE.toByte()), "no trailer octet leaked in")
        assertFalse(afterCont2.single().contains(0xEC.toByte()), "no header octet leaked in")
        assertFalse(reassembler.frameInProgress)
        assertEquals(1, reassembler.framesEmitted)
        assertEquals(0L, reassembler.bytesDropped)
    }

    @Test
    fun `a frame spanning datagrams that starts with FRAM at 43 reassembles the same way`() {
        val frames = listOf(
            startAt43(framSizeFor(22), part1, seq = 0),
            continuation(part2, seq = 1),
            continuation(part3, seq = 2),
        ).flatMap { reassembler.feed(it) }

        assertEquals(1, frames.size)
        assertBytes(frame22, frames.single(), "reassembled frame")
    }

    @Test
    fun `a full size continuation carries the 1395 octets its 85 72 chunk header announces`() {
        // The kart's usual continuation: 1416 octets, chunk header 85 72 (0x572 + 1 = 1395), trailer.
        val big = ByteArray(1395) { 0x5a }
        val cont = continuation(big, seq = 1)
        assertEquals(1416, cont.size, "the size every datagram of the kart has")
        assertBytes(hexBytes("85 72"), cont.copyOfRange(11, 13), "chunk header")
        reassembler.feed(startAt13(framSizeFor(8 + 1395), part1, seq = 0))

        val frames = reassembler.feed(cont)

        assertEquals(1, frames.size)
        assertBytes(part1 + big, frames.single(), "frame")
        assertEquals(0L, reassembler.bytesDropped)
    }

    @Test
    fun `a continuation's chunk length leaves out the stale octets after it`() {
        // The kart sometimes fills a datagram with a shorter chunk; what follows it is stale buffer content.
        val frames = listOf(
            startAt13(framSizeFor(22), part1, seq = 0),
            continuation(part2, seq = 1, padding = STALE),
            continuation(part3, seq = 2),
        ).flatMap { reassembler.feed(it) }

        assertEquals(1, frames.size)
        assertBytes(frame22, frames.single(), "the stale octets never reach the frame")
        assertEquals(0L, reassembler.bytesDropped, "octets outside the chunk are not frame surplus")
    }

    @Test
    fun `the last datagram of a group carries H264 up to its trailer and the frame length cuts the padding`() {
        // Its chunk header (ba 00) gives no length: the H.264 runs to the trailer, then stale padding.
        val frames = listOf(
            startAt13(framSizeFor(22), part1, seq = 0),
            continuation(part2, seq = 1),
            lastOfFrame(part3, seq = 2, padding = STALE),
        ).flatMap { reassembler.feed(it) }

        assertEquals(1, frames.size)
        assertBytes(frame22, frames.single(), "frame")
        assertEquals(STALE.size.toLong(), reassembler.bytesDropped, "the padding is the surplus")
    }

    @Test
    fun `the frame-end field of the last datagram never reaches the frame`() {
        val frames = listOf(
            startAt13(framSizeFor(22), part1, seq = 0),
            continuation(part2, seq = 1),
            lastOfFrame(part3, seq = 2),
        ).flatMap { reassembler.feed(it) }

        assertBytes(frame22, frames.single(), "frame")
        assertEquals(0L, reassembler.bytesDropped, "the field is skipped, not taken in and trimmed as surplus")
    }

    @Test
    fun `the FRAM size counts the record and the frame-end field, so the frame is 38 octets shorter`() {
        reassembler.feed(startAt13(64L, part1, seq = 0))

        assertEquals(26, reassembler.expectedBytes)
    }

    @Test
    fun `a last datagram that leaves the frame short keeps it open until the next start drops it`() {
        // Not seen in the capture (every frame ends exactly in its last datagram); pinned so a change is noticed.
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))
        reassembler.feed(lastOfFrame(part2, seq = 1))
        assertTrue(reassembler.frameInProgress, "16 of 22 octets: still open")

        val frames = reassembler.feed(startAt13(framSizeFor(8), IDR_8, seq = 2))

        assertBytes(IDR_8, frames.single(), "the new frame")
        assertEquals(1, reassembler.framesDropped)
    }

    @Test
    fun `a sized chunk that ends with the next frame's start code closes the frame before it`() {
        // The kart's quirk on about 0.6% of frames: the frame's last sized chunk carries 00 00 01 09 past the
        // frame's length, and the frame's last datagram then holds nothing of the frame.
        val frames = listOf(
            startAt13(framSizeFor(22), part1, seq = 0),
            continuation(part2, seq = 1),
            continuation(part3 + hexBytes("00 00 01 09"), seq = 2),
        ).flatMap { reassembler.feed(it) }

        assertBytes(frame22, frames.single(), "the start code is cut off as surplus")
        assertEquals(4L, reassembler.bytesDropped)

        assertTrue(reassembler.feed(lastOfFrame(hexBytes("01 7d 40"), seq = 3)).isEmpty())
        assertEquals(1, reassembler.datagramsDropped, "the frame's last datagram has no frame to join")
        assertFalse(writer.logged(Severity.Warn), "an expected orphan, not a loss")
    }

    @Test
    fun `a FRAM whose size leaves no H264 is not a start and is dropped`() {
        // 38 = the FRAM record (34) and the frame-end field (4): no H.264 at all.
        val frames = reassembler.feed(startAt13(38L, IDR_8))

        assertTrue(frames.isEmpty())
        assertFalse(reassembler.frameInProgress)
        assertEquals(1, reassembler.datagramsDropped)
    }

    @Test
    fun `every chunk type that is not 0x80 runs to the trailer`() {
        // The kart's other last-of-group types, and a made-up 0x88 whose low bits would read as a length.
        for (chunkType in listOf(0xb6, 0xbe, 0x88)) {
            val reassembler = LspVideoReassembler(capturingLogger(CapturingLogWriter(), "chunkType"))
            val frames = listOf(
                startAt13(framSizeFor(22), part1, seq = 0),
                continuation(part2, seq = 1),
                lastOfFrame(part3, seq = 2, padding = STALE, chunkType = chunkType),
            ).flatMap { reassembler.feed(it) }

            assertBytes(frame22, frames.single(), "frame with chunk type $chunkType")
        }
    }

    @Test
    fun `a frame whose length ends exactly on a datagram boundary is emitted by that datagram`() {
        // expected = 16 = 8 (start) + 8 (continuation): no overage, nothing dropped.
        val first = reassembler.feed(startAt13(framSizeFor(16), part1, seq = 0))
        val second = reassembler.feed(continuation(part2, seq = 1))

        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        assertBytes(part1 + part2, second.single(), "frame")
        assertEquals(0L, reassembler.bytesDropped)
    }

    @Test
    fun `octets beyond the expected length are trimmed and counted as dropped`() {
        // expected = 10: the start carries 8, the continuation carries 6 (2 needed, 4 are overage).
        reassembler.feed(startAt13(framSizeFor(10), part1, seq = 0))

        val frames = reassembler.feed(continuation(hexBytes("de ad be ef 01 02"), seq = 1))

        assertEquals(1, frames.size)
        assertBytes(hexBytes("00 00 00 01 65 88 84 21 de ad"), frames.single(), "frame trimmed to expected")
        assertEquals(10, frames.single().size)
        assertEquals(4L, reassembler.bytesDropped)
        assertFalse(reassembler.frameInProgress)
    }

    @Test
    fun `a huge continuation cannot grow the frame beyond the expected length`() {
        // expected = 20 with 8 already buffered; a 1 MiB last-of-group datagram yields exactly 20 octets.
        reassembler.feed(startAt13(framSizeFor(20), part1, seq = 0))
        val huge = ByteArray(1024 * 1024) { 0x66 }

        val frames = reassembler.feed(lastOfFrame(huge, seq = 1))

        assertEquals(1, frames.size)
        assertEquals(20, frames.single().size)
        assertEquals((1024 * 1024 - 12).toLong(), reassembler.bytesDropped)
    }

    @Test
    fun `a chunk length that runs past the trailer is cut at the trailer`() {
        // A chunk header announcing 8 octets over a datagram that holds only 6 before its trailer.
        val cont = continuation(part2, seq = 1)
        val truncated = cont.copyOfRange(0, 13 + 6) + LspTestVectors.trailer()
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))

        reassembler.feed(truncated)

        assertEquals(14, reassembler.bufferedBytes, "only the 6 octets before the trailer")
    }

    @Test
    fun `a start datagram that carries more than the frame needs is trimmed to the expected length`() {
        // expected = 4 but the start datagram carries 8 H.264 octets before the trailer.
        val frames = reassembler.feed(startAt13(framSizeFor(4), IDR_8))

        assertEquals(1, frames.size)
        assertBytes(hexBytes("00 00 00 01"), frames.single(), "frame")
        assertEquals(4L, reassembler.bytesDropped)
    }

    @Test
    fun `a start datagram with no H264 before its trailer opens an empty frame`() {
        // 51 octets: 47 prefix + 00 00 01 65 at 47..50. The datagram's own payload ends at 51 - 8 = 43,
        // before rawStart (47), so it contributes nothing; the frame is opened and the continuation fills it.
        val start = startAt13(framSizeFor(8), ByteArray(0), seq = 0).copyOf(47) + hexBytes("00 00 01 65")

        assertTrue(reassembler.feed(start).isEmpty())
        assertTrue(reassembler.frameInProgress)
        assertEquals(0, reassembler.bufferedBytes)
        assertEquals(8, reassembler.expectedBytes)

        val frames = reassembler.feed(continuation(IDR_8, seq = 1))

        assertEquals(1, frames.size)
        assertBytes(IDR_8, frames.single(), "frame")
    }

    @Test
    fun `a datagram with only its headers and trailer adds nothing to the frame in progress`() {
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))
        val empty = lastOfFrame(ByteArray(0), seq = 1)
        assertEquals(LspVideo.DATAGRAM_HEADER + LspVideo.FRAME_END_FIELD + LspVideo.PACKET_TRAILER, empty.size)

        val frames = reassembler.feed(empty)

        assertTrue(frames.isEmpty())
        assertEquals(8, reassembler.bufferedBytes)
        assertTrue(reassembler.frameInProgress)
        assertEquals(0, reassembler.datagramsDropped)
    }

    @Test
    fun `back to back frames are emitted in order`() {
        val a = hexBytes("00 00 00 01 67 42 00 1f")
        val b = hexBytes("00 00 00 01 68 ce 3c 80")
        val c = hexBytes("00 00 00 01 65 88 84 21")

        val frames = listOf(
            startAt13(framSizeFor(8), a, seq = 0),
            startAt43(framSizeFor(8), b, seq = 1),
            startAt13(framSizeFor(8), c, seq = 2),
        ).flatMap { reassembler.feed(it) }

        assertEquals(3, frames.size)
        assertBytes(a, frames[0], "frame 0")
        assertBytes(b, frames[1], "frame 1")
        assertBytes(c, frames[2], "frame 2")
        assertEquals(3, reassembler.framesEmitted)
    }

    // --- the sequence number ---------------------------------------------------------------------

    @Test
    fun `a lost datagram drops the frame in progress with a warning`() {
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))

        // Sequence 1 never arrives: the frame cannot be completed correctly.
        val frames = reassembler.feed(continuation(part2, seq = 2))

        assertTrue(frames.isEmpty())
        assertFalse(reassembler.frameInProgress)
        assertEquals(1, reassembler.framesDropped)
        assertEquals(1, reassembler.datagramsDropped, "the continuation after the gap has no frame to join")
        assertEquals(8L + continuation(part2, seq = 2).size, reassembler.bytesDropped)
        assertTrue(writer.logged(Severity.Warn), "a lost datagram is a warning")
    }

    @Test
    fun `after a lost datagram the next frame start reassembles normally`() {
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))
        reassembler.feed(continuation(part2, seq = 2)) // sequence 1 lost
        reassembler.feed(continuation(part3, seq = 3)) // still the lost frame's: dropped

        val frames = listOf(
            startAt13(framSizeFor(22), part1, seq = 4),
            continuation(part2, seq = 5),
            continuation(part3, seq = 6),
        ).flatMap { reassembler.feed(it) }

        assertEquals(1, frames.size)
        assertBytes(frame22, frames.single(), "frame")
        assertEquals(2, reassembler.datagramsDropped)
    }

    @Test
    fun `a new frame start after a gap mid frame drops the old frame once and opens the new one`() {
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))

        // Sequence 1 is lost and the next datagram opens a frame of its own.
        val frames = reassembler.feed(startAt13(framSizeFor(30), IDR_8, seq = 2))

        assertTrue(frames.isEmpty())
        assertEquals(1, reassembler.framesDropped, "dropped once, for the loss, not again for the new start")
        assertTrue(reassembler.frameInProgress)
        assertEquals(30, reassembler.expectedBytes)
        assertEquals(8, reassembler.bufferedBytes)
        assertEquals(1, writer.entries.count { it.severity == Severity.Warn }, "a single warning")
    }

    @Test
    fun `a start datagram after a gap opens its frame without a lost datagram warning`() {
        // The gap ends a frame that was already complete: nothing is lost that mattered.
        reassembler.feed(startAt13(framSizeFor(8), IDR_8, seq = 0))

        val frames = reassembler.feed(startAt13(framSizeFor(8), IDR_8, seq = 7))

        assertEquals(1, frames.size)
        assertEquals(0, reassembler.framesDropped)
        assertFalse(writer.logged(Severity.Warn))
    }

    @Test
    fun `the sequence number wraps from 0xffffff to 0 without counting as a loss`() {
        val frames = listOf(
            startAt13(framSizeFor(22), part1, seq = 0xFFFFFF),
            continuation(part2, seq = 0),
            continuation(part3, seq = 1),
        ).flatMap { reassembler.feed(it) }

        assertEquals(1, frames.size)
        assertBytes(frame22, frames.single(), "frame")
        assertEquals(0, reassembler.framesDropped)
    }

    @Test
    fun `a repeated sequence number counts as a loss`() {
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 5))

        reassembler.feed(continuation(part2, seq = 5))

        assertEquals(1, reassembler.framesDropped)
        assertFalse(reassembler.frameInProgress)
    }

    // --- datagrams the reassembler must reject ---------------------------------------------------

    @Test
    fun `a continuation with no frame in progress is dropped and logged`() {
        val cont = continuation(part2, seq = 1)

        val frames = reassembler.feed(cont)

        assertTrue(frames.isEmpty())
        assertFalse(reassembler.frameInProgress)
        assertEquals(1, reassembler.datagramsDropped)
        assertEquals(cont.size.toLong(), reassembler.bytesDropped, "the whole datagram counts as dropped")
        assertTrue(writer.logged(Severity.Debug), "the drop is logged at debug level")
    }

    @Test
    fun `a continuation after a completed frame is dropped`() {
        reassembler.feed(startAt13(framSizeFor(8), IDR_8, seq = 0))

        val frames = reassembler.feed(continuation(part2, seq = 1))

        assertTrue(frames.isEmpty())
        assertEquals(1, reassembler.framesEmitted)
        assertEquals(1, reassembler.datagramsDropped)
    }

    @Test
    fun `a datagram shorter than its headers and trailer is dropped and logged`() {
        val minimum = LspVideo.DATAGRAM_HEADER + LspVideo.PACKET_TRAILER
        for (size in 0 until minimum) {
            assertTrue(reassembler.feed(ByteArray(size) { 0x55 }).isEmpty(), "size $size")
        }

        assertEquals(minimum, reassembler.datagramsDropped)
        assertEquals((0 until minimum).sum().toLong(), reassembler.bytesDropped)
        assertFalse(reassembler.frameInProgress)
        assertTrue(writer.logged(Severity.Debug))
    }

    @Test
    fun `a too short datagram does not disturb the frame in progress or its sequence`() {
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))
        reassembler.feed(ByteArray(3) { 0x55 })
        reassembler.feed(continuation(part2, seq = 1))

        val frames = reassembler.feed(continuation(part3, seq = 2))

        assertEquals(1, frames.size)
        assertBytes(frame22, frames.single(), "frame")
        assertEquals(1, reassembler.datagramsDropped)
    }

    @Test
    fun `a FRAM with an oversize packet size is not a start and is dropped as an orphan`() {
        // 2 MiB + 1 is over the cap, so findFramStart rejects it and, with nothing in progress, the
        // datagram is an orphan continuation: nothing is allocated and the frame is not opened.
        val frames = reassembler.feed(startAt13(2L * 1024 * 1024 + 1, IDR_8))

        assertTrue(frames.isEmpty())
        assertFalse(reassembler.frameInProgress)
        assertEquals(1, reassembler.datagramsDropped)
    }

    @Test
    fun `the largest accepted frame is held in a buffer of exactly its expected size`() {
        // 2 MiB packet size -> expected = 2 MiB - 34 - 4; the first datagram only fills 8 octets of it.
        assertTrue(reassembler.feed(startAt13(2L * 1024 * 1024, IDR_8)).isEmpty())

        assertTrue(reassembler.frameInProgress)
        assertEquals(2 * 1024 * 1024 - 34 - 4, reassembler.expectedBytes)
        assertEquals(8, reassembler.bufferedBytes)
    }

    // --- incomplete-frame policy -----------------------------------------------------------------

    @Test
    fun `a new FRAM arriving mid frame drops the incomplete frame with a warning and starts fresh`() {
        val a = startAt13(framSizeFor(22), part1, seq = 0) // would need 14 more octets
        val bFrame = hexBytes("00 00 00 01 67 42 00 1f")

        assertTrue(reassembler.feed(a).isEmpty())
        val frames = reassembler.feed(startAt43(framSizeFor(8), bFrame, seq = 1))

        assertEquals(1, frames.size, "only the new frame comes out")
        assertBytes(bFrame, frames.single(), "new frame")
        assertEquals(1, reassembler.framesEmitted)
        assertEquals(1, reassembler.framesDropped)
        assertEquals(8L, reassembler.bytesDropped, "the 8 buffered octets of the abandoned frame")
        assertTrue(writer.logged(Severity.Warn), "abandoning a frame is a warning")
        assertFalse(reassembler.frameInProgress)
    }

    @Test
    fun `after an abandoned frame the continuations belong to the new frame`() {
        val bFrame = hexBytes("00 00 00 01 68 ce 3c 80 11 22 33 44") // 12 octets: 8 + 4

        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0)) // frame A, abandoned below
        assertTrue(reassembler.feed(startAt13(framSizeFor(12), bFrame.copyOfRange(0, 8), seq = 1)).isEmpty())
        val frames = reassembler.feed(continuation(bFrame.copyOfRange(8, 12), seq = 2))

        assertEquals(1, frames.size)
        assertBytes(bFrame, frames.single(), "frame B only; none of frame A's octets")
        assertEquals(1, reassembler.framesDropped)
        assertEquals(1, reassembler.framesEmitted)
    }

    @Test
    fun `a new FRAM that is itself incomplete replaces the frame in progress`() {
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 0))

        val frames = reassembler.feed(startAt13(framSizeFor(30), IDR_8, seq = 1))

        assertTrue(frames.isEmpty())
        assertTrue(reassembler.frameInProgress)
        assertEquals(30, reassembler.expectedBytes)
        assertEquals(8, reassembler.bufferedBytes)
        assertEquals(1, reassembler.framesDropped)
    }

    @Test
    fun `reset abandons the frame in progress and keeps the counters`() {
        reassembler.feed(startAt13(framSizeFor(8), IDR_8, seq = 0)) // one frame emitted
        reassembler.feed(startAt13(framSizeFor(22), part1, seq = 1)) // one frame in progress

        reassembler.reset()

        assertFalse(reassembler.frameInProgress)
        assertEquals(0, reassembler.bufferedBytes)
        assertEquals(1, reassembler.framesEmitted)
        assertEquals(1, reassembler.framesDropped)
        assertEquals(8L, reassembler.bytesDropped)
        assertTrue(reassembler.feed(continuation(part2, seq = 2)).isEmpty(), "the abandoned frame cannot be resumed")
    }

    @Test
    fun `reset forgets the sequence so a restarted stream is not a loss`() {
        reassembler.feed(startAt13(framSizeFor(8), IDR_8, seq = 40))
        reassembler.reset()

        val frames = listOf(
            startAt13(framSizeFor(22), part1, seq = 0),
            continuation(part2, seq = 1),
            continuation(part3, seq = 2),
        ).flatMap { reassembler.feed(it) }

        assertEquals(1, frames.size)
        assertEquals(0, reassembler.framesDropped)
    }

    @Test
    fun `reset with nothing in progress changes nothing`() {
        reassembler.reset()

        assertEquals(0, reassembler.framesDropped)
        assertEquals(0L, reassembler.bytesDropped)
    }

    // --- hygiene ---------------------------------------------------------------------------------

    @Test
    fun `feed does not modify the datagram it is given`() {
        val start = startAt13(framSizeFor(8), IDR_8)
        val before = start.copyOf()

        reassembler.feed(start)

        assertBytes(before, start, "input datagram")
    }

    @Test
    fun `an emitted frame is independent of the datagram it came from`() {
        val start = startAt13(framSizeFor(8), IDR_8)

        val frame = reassembler.feed(start).single()
        start.fill(0x00)

        assertBytes(IDR_8, frame, "frame unaffected by later changes to the datagram")
    }

    @Test
    fun `a well formed frame logs nothing at warning or above`() {
        listOf(startAt13(framSizeFor(22), part1, seq = 0), continuation(part2, seq = 1), continuation(part3, seq = 2))
            .forEach { reassembler.feed(it) }

        assertFalse(writer.logged(Severity.Warn))
        assertFalse(writer.logged(Severity.Error))
    }
}
