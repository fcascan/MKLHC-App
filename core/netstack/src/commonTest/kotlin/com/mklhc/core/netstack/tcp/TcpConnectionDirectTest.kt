package com.mklhc.core.netstack.tcp

import com.mklhc.core.netstack.AddressPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lower-level tests that drive a single [TcpConnection] against a hand-built peer ([DirectPeer]).
 *
 * Two cooperating [TcpEndpoint]s cannot set an exact advertised window, a sequence number ahead of
 * rcvNxt, or a near-wraparound initial sequence number, so these behaviours (window-update recovery,
 * in-window RST acceptance, 32-bit serial arithmetic) are exercised here where the test feeds raw
 * segments and reads the raw segments the connection emits. Everything runs on the test scheduler, so
 * [runCurrent] drains the connection's immediate work without advancing the clock into its timers.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler / runCurrent / advanceTimeBy drive the actor by hand
class TcpConnectionDirectTest {

    private val peerAddress = AddressPlan.KART_LEASE
    private val localPort = 40000
    private val peerPort = 5201

    /**
     * Wraps one [TcpConnection] plus a hand-built remote peer: outbound segments are captured in
     * [outbound], and the test feeds inbound segments with [feed]. [idle] runs the connection's
     * current-time work (actor and immediate sends) without firing its future timers.
     */
    private inner class DirectPeer(
        private val testScope: TestScope,
        config: TcpConfig = TcpConfig(),
        private val localIss: Long = 1_000L,
        private val peerIss: Long = 5_000L,
    ) {
        val outbound = mutableListOf<TcpSegment>()

        val connection = TcpConnection(
            localPort = localPort,
            peer = peerAddress,
            peerPort = peerPort,
            initialSendSequence = localIss,
            config = config,
            scope = testScope.backgroundScope,
            send = { outbound += it },
            initialState = TcpState.CLOSED,
            onEstablished = null,
            onClosed = null,
        ).also { it.start() }

        /** The rcvNxt the connection holds once the handshake completes with no peer data delivered. */
        val connectionRcvNxt: Long get() = connection.add(peerIss, 1)

        private fun idle() = testScope.testScheduler.runCurrent()

        /** Launches [TcpConnection.connect] and returns its (eventual) result; the SYN is emitted by the time this returns. */
        fun beginActiveOpen(): Deferred<Result<Unit>> {
            val result = testScope.backgroundScope.async { runCatching { connection.connect() } }
            idle()
            return result
        }

        /** Completes the three-way handshake with the peer advertising [peerWindow]. */
        fun establishActiveOpen(peerWindow: Int = 0xFFFF): Deferred<Result<Unit>> {
            val result = beginActiveOpen()
            feed(synAck(peerWindow))
            idle()
            return result
        }

        fun feed(segment: TcpSegment) {
            connection.onSegment(segment)
            idle()
        }

        /** A sequence number [n] octets ahead of the connection's current rcvNxt (wrapping). */
        fun aheadOfRcvNxt(n: Int): Long = connection.add(connectionRcvNxt, n)

        fun synAck(window: Int, ack: Long = connection.add(localIss, 1)) = TcpSegment(
            sourcePort = peerPort,
            destinationPort = localPort,
            sequenceNumber = peerIss,
            acknowledgmentNumber = ack,
            flags = TcpSegment.SYN or TcpSegment.ACK,
            window = window,
            maxSegmentSize = 1460,
        )

        /** The pure ACK a peer still holding an old connection on this four-tuple answers our SYN with: it acknowledges [ack]. */
        fun challengeAck(ack: Long) = TcpSegment(
            sourcePort = peerPort,
            destinationPort = localPort,
            sequenceNumber = 424_242L,
            acknowledgmentNumber = ack,
            flags = TcpSegment.ACK,
            window = 0xFFFF,
        )

        /** A pure ACK that acknowledges only our SYN (no data) but advertises a new [window]. */
        fun windowUpdate(window: Int) = TcpSegment(
            sourcePort = peerPort,
            destinationPort = localPort,
            sequenceNumber = connectionRcvNxt,
            acknowledgmentNumber = connection.add(localIss, 1),
            flags = TcpSegment.ACK,
            window = window,
        )

        fun data(seq: Long, payload: ByteArray, window: Int = 0xFFFF) = TcpSegment(
            sourcePort = peerPort,
            destinationPort = localPort,
            sequenceNumber = seq,
            acknowledgmentNumber = connection.add(localIss, 1),
            flags = TcpSegment.PSH or TcpSegment.ACK,
            window = window,
            payload = payload,
        )

        fun reset(seq: Long) = TcpSegment(
            sourcePort = peerPort,
            destinationPort = localPort,
            sequenceNumber = seq,
            acknowledgmentNumber = connection.add(localIss, 1),
            flags = TcpSegment.RST or TcpSegment.ACK,
            window = 0,
        )

        fun synCount(): Int = outbound.count { it.isSyn }

        /** All application bytes the connection has emitted so far, in order. */
        fun emittedData(): ByteArray {
            val out = ArrayList<Byte>()
            for (segment in outbound) for (b in segment.payload) out.add(b)
            return out.toByteArray()
        }
    }

    // ---- Fix 1: a window update re-drives a stalled sender ------------------------------------

    @Test
    fun aPureWindowUpdateReDrivesASenderStalledByAZeroWindow() = runTest {
        val peer = DirectPeer(this)
        peer.establishActiveOpen(peerWindow = 0) // zero window: the sender cannot send any data

        val payload = "the deferred payload".encodeToByteArray()
        backgroundScope.launch { runCatching { peer.connection.write(payload) } }
        runCurrent()
        assertTrue(peer.emittedData().isEmpty(), "a zero window stalls the sender")

        // A pure window-update ACK: it acknowledges nothing new but opens the window.
        peer.feed(peer.windowUpdate(window = 16 * 1024))

        assertContentEquals(payload, peer.emittedData(), "the window-update ACK re-drives the stalled sender")
    }

    // ---- Fix 4: a RST within the receive window tears the connection down ---------------------

    @Test
    fun aRstAheadOfRcvNxtButWithinTheWindowTearsDownTheConnection() = runTest {
        val peer = DirectPeer(this)
        peer.establishActiveOpen()

        val readError = CompletableDeferred<Throwable>()
        backgroundScope.launch {
            try {
                peer.connection.read(16)
            } catch (e: Throwable) {
                readError.complete(e)
            }
        }
        runCurrent()

        // A RST a little ahead of rcvNxt, still well inside the advertised 16 KiB window.
        peer.feed(peer.reset(seq = peer.aheadOfRcvNxt(100)))

        assertEquals(TcpState.CLOSED, peer.connection.state.value, "an in-window RST is accepted")
        assertTrue(readError.isCompleted, "the reset surfaces to the blocked reader")
        assertTrue(readError.getCompleted() is TcpResetException, "the reader sees a reset")
    }

    // ---- 32-bit sequence wraparound ----------------------------------------------------------

    @Test
    fun serialNumberArithmeticWrapsAtThe32BitBoundary() = runTest {
        val c = DirectPeer(this).connection
        val max = 0xFFFFFFFFL

        assertEquals(max, c.add(max - 1, 1), "add stays just below the wrap")
        assertEquals(0L, c.add(max, 1), "add wraps 2^32 - 1 to 0")
        assertEquals(3L, c.add(max - 2, 6), "add carries across the wrap")

        assertTrue(c.seqLt(max - 1, 2), "a seq just below the wrap precedes a small seq just past it")
        assertFalse(c.seqLt(2, max - 1), "and not the other way round")

        assertTrue(c.seqLeq(max, max), "seqLeq is reflexive")
        assertTrue(c.seqLeq(max, 3), "seqLeq holds across the wrap")
        assertFalse(c.seqLeq(3, max), "and rejects the reverse across the wrap")
    }

    @Test
    fun theByteStreamSurvivesAnInitialSequenceNumberNearTheWraparound() = runTest {
        val peer = DirectPeer(this, localIss = 0xFFFFFFFEL, peerIss = 0xFFFFFFFDL)
        peer.establishActiveOpen()

        // rcvNxt is now 0xFFFFFFFE; sixteen octets carry it across 2^32 mid-segment.
        val fromPeer = Random(99).nextBytes(16)
        peer.feed(peer.data(seq = peer.connectionRcvNxt, payload = fromPeer))

        val got = peer.connection.read(16)
        assertContentEquals(fromPeer, got, "data spanning the sequence-number wrap arrives intact")
    }

    // ---- SYN retransmission and exhaustion ---------------------------------------------------

    @Test
    fun connectRetransmitsTheSynWhenTheSynAckIsLost() = runTest {
        val peer = DirectPeer(this)
        val result = peer.beginActiveOpen()
        assertEquals(1, peer.synCount(), "the initial SYN is sent")

        // Drop the SYN-ACK: the retransmission timer (initial RTO ~200 ms) fires.
        advanceTimeBy(201.milliseconds)
        runCurrent()
        assertTrue(peer.synCount() >= 2, "the SYN is retransmitted after the RTO")

        // The SYN-ACK finally arrives; the handshake completes on the retransmitted SYN.
        peer.feed(peer.synAck(window = 0xFFFF))
        assertEquals(TcpState.ESTABLISHED, peer.connection.state.value)
        assertTrue(result.isCompleted && result.getCompleted().isSuccess, "connect() returned")
    }

    // ---- An unacceptable ACK in SYN_SENT is answered with a RST ------------------------------

    /**
     * A peer that still holds an old connection on the same four-tuple (our RST for it was lost) answers our SYN
     * with a challenge ACK of that old connection. RFC 9293 section 3.10.7.3: we reset it with `<SEQ=SEG.ACK><CTL=RST>`,
     * which clears the peer's stale connection, and the next SYN retransmission opens the new one.
     */
    @Test
    fun aChallengeAckInSynSentIsResetAndTheRetransmittedSynThenConnects() = runTest {
        val peer = DirectPeer(this)
        val result = peer.beginActiveOpen()
        val staleAck = 777_777L

        peer.feed(peer.challengeAck(ack = staleAck))

        val rst = peer.outbound.single { it.isRst }
        assertEquals(staleAck, rst.sequenceNumber, "the RST carries the unacceptable ACK as its sequence number")
        assertEquals(TcpSegment.RST, rst.flags, "the RST carries no ACK")
        assertEquals(TcpState.SYN_SENT, peer.connection.state.value, "the connection keeps waiting for its SYN-ACK")

        advanceTimeBy(201.milliseconds)
        runCurrent()
        assertTrue(peer.synCount() >= 2, "the SYN is retransmitted after the RTO")

        peer.feed(peer.synAck(window = 0xFFFF))
        assertEquals(TcpState.ESTABLISHED, peer.connection.state.value)
        assertTrue(result.isCompleted && result.getCompleted().isSuccess, "connect() returned")
    }

    @Test
    fun aSynAckWithAnUnacceptableAckInSynSentIsReset() = runTest {
        val peer = DirectPeer(this)
        peer.beginActiveOpen()
        val wrongAck = 123_456L

        peer.feed(peer.synAck(window = 0xFFFF, ack = wrongAck))

        val rst = peer.outbound.single { it.isRst }
        assertEquals(wrongAck, rst.sequenceNumber, "the RST carries the unacceptable ACK as its sequence number")
        assertEquals(TcpState.SYN_SENT, peer.connection.state.value, "the connection keeps waiting for its SYN-ACK")
    }

    @Test
    fun connectFailsWithAResetWhenTheSynIsNeverAnswered() = runTest {
        val peer = DirectPeer(this)
        val result = peer.beginActiveOpen()

        // SYN_SENT has no keepalive; advance the clock past the RTO backoff (cumulative ~12.6 s) to
        // exhaustion. advanceUntilIdle() would not fire the background-scope RTO timer on its own.
        advanceTimeBy(15.seconds)
        runCurrent()

        assertEquals(TcpState.CLOSED, peer.connection.state.value)
        assertTrue(result.isCompleted, "connect() returned")
        assertTrue(
            result.getCompleted().exceptionOrNull() is TcpResetException,
            "connect() fails with a reset after SYN exhaustion",
        )
    }
}
