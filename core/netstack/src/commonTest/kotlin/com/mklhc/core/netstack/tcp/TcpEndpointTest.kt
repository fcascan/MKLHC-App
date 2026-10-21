package com.mklhc.core.netstack.tcp

import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.Ipv4Address
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests of [TcpEndpoint]'s demultiplexing rules, driven by a hand-built peer ([Harness]) instead of a
 * second endpoint: the test feeds exact segments (a SYN from a chosen source port, a retransmitted SYN, a
 * RST) and reads the exact segments the endpoint answers with, all in virtual time.
 *
 * The endpoint under test is at [AddressPlan.AP] and listens on [LOCAL_PORT]; the peer is the single kart at
 * [AddressPlan.KART_LEASE], plus a second address ([otherPeer]) for the busy-port case.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent / advanceTimeBy drive the endpoint by hand
class TcpEndpointTest {

    private val kart = AddressPlan.KART_LEASE
    private val otherPeer = Ipv4Address.parse("169.254.98.67")

    /** One captured outbound segment and the peer address it was sent to. */
    private class Sent(val peer: Ipv4Address, val segment: TcpSegment)

    /** A [TcpLink] that records every segment the endpoint sends and lets the test inject inbound ones. */
    private class ScriptedLink(override val localAddress: Ipv4Address) : TcpLink {
        val sent = mutableListOf<Sent>()
        private val incoming = Channel<InboundSegment>(Channel.UNLIMITED)

        override suspend fun send(peer: Ipv4Address, segment: ByteArray) {
            sent += Sent(peer, checkNotNull(TcpSegment.parse(segment, localAddress, peer)) { "the endpoint sent a malformed segment" })
        }

        override val inbound: Flow<InboundSegment> get() = incoming.receiveAsFlow()

        fun deliver(peer: Ipv4Address, segment: TcpSegment) {
            incoming.trySend(InboundSegment(peer, segment.encode(peer, localAddress)))
        }
    }

    /** The endpoint under test, its listener on [LOCAL_PORT], and helpers to play the kart's side of a connection. */
    private inner class Harness(private val testScope: TestScope) {
        val link = ScriptedLink(AddressPlan.AP)
        val endpoint = TcpEndpoint(link, testScope.backgroundScope, TcpConfig(), Random(1))
        val listener: TcpListener = endpoint.listen(LOCAL_PORT)

        fun settle() = testScope.runCurrent()

        /** The segments sent to [peerPort] after the first [after] captured segments. */
        fun sentTo(peerPort: Int, after: Int = 0): List<TcpSegment> =
            link.sent.drop(after).map { it.segment }.filter { it.destinationPort == peerPort }

        fun synAckTo(peerPort: Int, after: Int = 0): TcpSegment? =
            sentTo(peerPort, after).lastOrNull { it.isSyn && it.isAck }

        /** The peer's opening SYN from [peerPort] with initial sequence number [peerIss]. */
        fun syn(peerPort: Int, peerIss: Long) =
            TcpSegment(peerPort, LOCAL_PORT, peerIss, 0, TcpSegment.SYN, PEER_WINDOW, maxSegmentSize = PEER_MSS)

        fun deliverSyn(peerPort: Int, peerIss: Long, from: Ipv4Address = kart) {
            link.deliver(from, syn(peerPort, peerIss))
            settle()
        }

        /** The handshake-completing ACK for [synAck], from the peer that opened with sequence number [peerIss]. */
        fun handshakeAck(peerPort: Int, peerIss: Long, synAck: TcpSegment) =
            TcpSegment(peerPort, LOCAL_PORT, peerIss + 1, synAck.sequenceNumber + 1, TcpSegment.ACK, PEER_WINDOW)

        fun deliverHandshakeAck(peerPort: Int, peerIss: Long, synAck: TcpSegment) {
            link.deliver(kart, handshakeAck(peerPort, peerIss, synAck))
            settle()
        }

        /** Completes a handshake from [peerPort] without accepting it, leaving the ESTABLISHED connection on the accept queue. */
        fun handshakeUnaccepted(peerPort: Int, peerIss: Long) {
            deliverSyn(peerPort, peerIss)
            deliverHandshakeAck(peerPort, peerIss, assertNotNull(synAckTo(peerPort), "the SYN is answered with a SYN-ACK"))
        }

        /** Plays a whole three-way handshake from [peerPort] and returns the accepted, ESTABLISHED connection. */
        suspend fun handshake(peerPort: Int, peerIss: Long): TcpConnection {
            handshakeUnaccepted(peerPort, peerIss)
            return listener.accept()
        }

        /** Fails unless no connection holds [port] any more: [TcpEndpoint.open] refuses a port that is still in use. */
        fun assertPortFree(port: Int = LOCAL_PORT) {
            endpoint.open(port, kart, PROBE_PORT)
        }
    }

    /**
     * A dispatcher whose tasks run only when the test calls [runAll], so a coroutine on it can be cancelled
     * after it was resumed but before it runs.
     */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    // ---- restarted peer: a fresh SYN from a new source port -----------------------------------

    @Test
    fun aRestartedPeerReplacesTheStaleEstablishedConnection() = runTest {
        val h = Harness(this)
        val stale = h.handshake(peerPort = STALE_PORT, peerIss = 1_000)
        assertEquals(TcpState.ESTABLISHED, stale.state.value)
        val pendingRead = backgroundScope.async { runCatching { stale.read(16) } }
        runCurrent()
        assertFalse(pendingRead.isCompleted, "the stale connection's reader is blocked")
        val mark = h.link.sent.size

        h.deliverSyn(peerPort = FRESH_PORT, peerIss = 9_000)

        assertEquals(TcpState.CLOSED, stale.state.value, "the stale connection ends at once")
        assertTrue(pendingRead.isCompleted, "the blocked reader is released promptly")
        assertIs<TcpResetException>(pendingRead.getCompleted().exceptionOrNull(), "the reader sees the reset exception")
        assertFailsWith<TcpResetException> { stale.read(16) }
        assertFailsWith<TcpResetException> { stale.write("x".encodeToByteArray()) }
        val staleRst = h.sentTo(STALE_PORT, mark).single()
        assertTrue(staleRst.isRst, "the old peer port is told the connection is gone")
        val synAck = assertNotNull(h.synAckTo(FRESH_PORT, mark), "the new SYN is answered with a SYN-ACK to its own source port")
        assertEquals(9_001L, synAck.acknowledgmentNumber)
        assertEquals(LOCAL_PORT, synAck.sourcePort)

        // The new connection completes its handshake and is the one the listener hands out and the port routes to.
        h.deliverHandshakeAck(FRESH_PORT, 9_000, synAck)
        val fresh = h.listener.accept()
        assertNotSame(stale, fresh)
        assertEquals(FRESH_PORT, fresh.peerPort)
        assertEquals(TcpState.ESTABLISHED, fresh.state.value)
        val hello = "hello".encodeToByteArray()
        h.link.deliver(
            kart,
            TcpSegment(FRESH_PORT, LOCAL_PORT, 9_001, synAck.sequenceNumber + 1, TcpSegment.PSH or TcpSegment.ACK, PEER_WINDOW, hello),
        )
        runCurrent()
        assertContentEquals(hello, fresh.read(16), "segments of the new four-tuple reach the new connection")
    }

    @Test
    fun theReplacedConnectionSendsNothingMoreAfterItsResetNotEvenARetransmission() = runTest {
        val h = Harness(this)
        val stale = h.handshake(peerPort = STALE_PORT, peerIss = 1_000)
        stale.write("unacknowledged".encodeToByteArray()) // leaves data on the retransmission queue
        runCurrent()
        val mark = h.link.sent.size

        h.deliverSyn(peerPort = FRESH_PORT, peerIss = 9_000)
        advanceTimeBy(60.seconds) // far past the retransmission and keepalive timers
        runCurrent()

        assertTrue(h.sentTo(STALE_PORT, mark).single().isRst, "only the RST goes to the old port, no retransmit or probe")
        assertEquals(0, stale.totalRetransmits, "the stale connection never retransmits again")
        assertEquals(0, stale.keepaliveProbesSent, "and never probes")
    }

    @Test
    fun aRestartedPeerReplacesAStaleConnectionStillInSynReceived() = runTest {
        val h = Harness(this)
        h.deliverSyn(peerPort = STALE_PORT, peerIss = 1_000) // half-open: its SYN-ACK was never acknowledged
        val staleSynAck = assertNotNull(h.synAckTo(STALE_PORT))
        val mark = h.link.sent.size

        h.deliverSyn(peerPort = FRESH_PORT, peerIss = 9_000)
        advanceTimeBy(5.seconds) // the stale SYN-ACK's retransmission timer must not keep running
        runCurrent()

        val staleSegments = h.sentTo(STALE_PORT, mark)
        assertEquals(1, staleSegments.size, "the stale half-open connection sends only a RST and then nothing")
        assertTrue(staleSegments.single().isRst)
        assertEquals(staleSynAck.sequenceNumber + 1, staleSegments.single().sequenceNumber, "the RST carries the stale connection's next sequence number")
        val synAck = assertNotNull(h.synAckTo(FRESH_PORT, mark), "the new SYN is answered with a SYN-ACK")
        h.deliverHandshakeAck(FRESH_PORT, 9_000, synAck)

        val accepted = h.listener.accept()
        assertEquals(FRESH_PORT, accepted.peerPort, "the first connection handed out is the new one; the half-open one never was")
        assertEquals(TcpState.ESTABLISHED, accepted.state.value)
        val second = backgroundScope.async { h.listener.accept() }
        runCurrent()
        assertFalse(second.isCompleted, "no connection was queued for the stale half-open one")
    }

    @Test
    fun aRestartedPeerIsAcceptedWhileTheOldConnectionSitsInTimeWait() = runTest {
        val h = Harness(this)
        val old = h.handshake(peerPort = STALE_PORT, peerIss = 1_000)
        old.close() // we close first, so this side ends in TIME_WAIT
        runCurrent()
        val fin = h.sentTo(STALE_PORT).last { it.isFin }
        h.link.deliver(kart, TcpSegment(STALE_PORT, LOCAL_PORT, 1_001, fin.sequenceNumber + 1, TcpSegment.FIN or TcpSegment.ACK, PEER_WINDOW))
        runCurrent()
        assertEquals(TcpState.TIME_WAIT, old.state.value)
        val mark = h.link.sent.size

        h.deliverSyn(peerPort = FRESH_PORT, peerIss = 9_000)

        assertEquals(TcpState.CLOSED, old.state.value, "the TIME_WAIT connection is discarded")
        assertTrue(h.sentTo(STALE_PORT, mark).isEmpty(), "a cleanly closed connection needs no RST")
        assertNotNull(h.synAckTo(FRESH_PORT, mark), "the new SYN is answered at once instead of being dropped as a busy port")
    }

    @Test
    fun aRetransmissionAlreadyQueuedWhenThePeerRestartsSendsNothingToTheOldPort() = runTest {
        val h = Harness(this)
        val stale = h.handshake(peerPort = STALE_PORT, peerIss = 1_000)
        stale.write("unacknowledged".encodeToByteArray()) // arms the retransmission timer
        runCurrent()
        val mark = h.link.sent.size

        // Move the clock to the instant the timer is due without running it, then let the restarting SYN arrive in
        // that same instant. The timer's expiry is older in the scheduler's queue, so it queues RtoFired on the stale
        // connection first, and the endpoint replaces the connection before its actor reaches that event.
        advanceTimeBy(TcpConfig().initialRto)
        assertEquals(0, stale.totalRetransmits, "the timer is due but has not fired yet")
        h.link.deliver(kart, h.syn(peerPort = FRESH_PORT, peerIss = 9_000))
        runCurrent()

        assertTrue(h.sentTo(STALE_PORT, mark).single().isRst, "only the RST goes to the old port, not the queued retransmission")
        assertEquals(0, stale.totalRetransmits, "the queued expiry is dropped, not acted on")
        advanceTimeBy(60.seconds) // and the dropped expiry did not re-arm the timer
        runCurrent()
        assertEquals(1, h.sentTo(STALE_PORT, mark).size, "nothing more ever reaches the old port")
        assertNotNull(h.synAckTo(FRESH_PORT, mark), "the new SYN is answered")
    }

    @Test
    fun aStaleConnectionStillInListenIsReplacedWithoutAMeaninglessReset() = runTest {
        val h = Harness(this)

        // Two SYNs from different source ports arrive back to back: the second is dispatched before the first
        // connection's actor has processed its SYN, so that connection is still in LISTEN when it is replaced.
        h.link.deliver(kart, h.syn(peerPort = STALE_PORT, peerIss = 1_000))
        h.link.deliver(kart, h.syn(peerPort = FRESH_PORT, peerIss = 9_000))
        runCurrent()

        assertTrue(h.sentTo(STALE_PORT).isEmpty(), "no RST with sequence 0 and no ACK, and no SYN-ACK for the replaced SYN")
        val synAck = assertNotNull(h.synAckTo(FRESH_PORT), "the second SYN is answered")
        h.deliverHandshakeAck(FRESH_PORT, 9_000, synAck)
        assertEquals(FRESH_PORT, h.listener.accept().peerPort)
    }

    @Test
    fun aSegmentOnTheReplacedFourTupleIsAnsweredWithAReset() = runTest {
        val h = Harness(this)
        h.handshake(peerPort = STALE_PORT, peerIss = 1_000)
        h.deliverSyn(peerPort = FRESH_PORT, peerIss = 9_000) // replaces the connection from STALE_PORT
        val synAck = assertNotNull(h.synAckTo(FRESH_PORT))
        val mark = h.link.sent.size

        // A peer that did not restart keeps using the old four-tuple.
        val late = "late".encodeToByteArray()
        h.link.deliver(kart, TcpSegment(STALE_PORT, LOCAL_PORT, 1_001, LATE_ACK, TcpSegment.PSH or TcpSegment.ACK, PEER_WINDOW, late))
        runCurrent()

        val rst = h.sentTo(STALE_PORT, mark).single()
        assertTrue(rst.isRst, "no connection matches the old four-tuple any more, so it earns a RST")
        assertEquals(LATE_ACK, rst.sequenceNumber, "a RST answering an ACK carries that acknowledgment number as its sequence")
        assertTrue(h.sentTo(FRESH_PORT, mark).isEmpty(), "the new connection is not disturbed")

        // A RST on that four-tuple is never answered.
        h.link.deliver(kart, TcpSegment(STALE_PORT, LOCAL_PORT, 1_001, 0, TcpSegment.RST, 0))
        runCurrent()
        assertEquals(mark + 1, h.link.sent.size)

        h.deliverHandshakeAck(FRESH_PORT, 9_000, synAck)
        assertEquals(TcpState.ESTABLISHED, h.listener.accept().state.value, "the new connection completes as usual")
    }

    // ---- port bookkeeping: a connection that never connected ----------------------------------

    @Test
    fun abortingAConnectionThatNeverConnectedFreesItsPortWithoutAReset() = runTest {
        val h = Harness(this)
        val never = h.endpoint.open(ACTIVE_PORT, kart, LOCAL_PORT)
        val pendingRead = backgroundScope.async { runCatching { never.read(16) } }
        runCurrent()

        never.abort()
        runCurrent()

        assertEquals(TcpState.CLOSED, never.state.value)
        assertTrue(h.link.sent.isEmpty(), "nothing was ever sent, so there is no peer to reset")
        assertTrue(pendingRead.isCompleted, "a pending read is released")
        assertIs<TcpResetException>(pendingRead.getCompleted().exceptionOrNull(), "and fails as after any abort")
        assertFailsWith<TcpResetException> { never.connect() }

        val again = h.endpoint.open(ACTIVE_PORT, kart, LOCAL_PORT) // would throw "already in use" if the port leaked
        backgroundScope.launch { runCatching { again.connect() } }
        runCurrent()
        assertTrue(h.link.sent.single().segment.isSyn, "the reopened port connects normally")
    }

    @Test
    fun closingAConnectionThatNeverConnectedEndsItsStreamAndFreesItsPort() = runTest {
        val h = Harness(this)
        val never = h.endpoint.open(ACTIVE_PORT, kart, LOCAL_PORT)
        val pendingRead = backgroundScope.async { runCatching { never.read(16) } }
        runCurrent()

        never.close()
        runCurrent()

        assertEquals(TcpState.CLOSED, never.state.value)
        assertTrue(h.link.sent.isEmpty(), "nothing is sent")
        assertTrue(pendingRead.isCompleted, "a pending read is released")
        assertEquals(0, pendingRead.getCompleted().getOrThrow().size, "with the end of the stream")
        val error = assertFailsWith<TcpException> { never.write("x".encodeToByteArray()) }
        assertFalse(error is TcpResetException, "a close is not a reset")
        h.assertPortFree(ACTIVE_PORT)
    }

    @Test
    fun aConnectQueuedBehindAnAbortFailsInsteadOfReopeningTheConnection() = runTest {
        val h = Harness(this)
        val never = h.endpoint.open(ACTIVE_PORT, kart, LOCAL_PORT)

        // Both calls reach the actor's queue before it runs: the abort first, then the connect.
        backgroundScope.launch { never.abort() }
        val connecting = backgroundScope.async { runCatching { never.connect() } }
        runCurrent()

        assertTrue(connecting.isCompleted)
        assertIs<TcpResetException>(connecting.getCompleted().exceptionOrNull(), "the connect fails with the abort's exception")
        assertEquals(TcpState.CLOSED, never.state.value, "the aborted connection stays closed")
        assertTrue(h.link.sent.isEmpty(), "no SYN goes out")
        h.assertPortFree(ACTIVE_PORT)
    }

    // ---- listener edges: no orphaned connection -----------------------------------------------

    @Test
    fun closingTheListenerResetsAConnectionNobodyAcceptedYet() = runTest {
        val h = Harness(this)
        h.handshakeUnaccepted(peerPort = STALE_PORT, peerIss = 1_000)
        val mark = h.link.sent.size

        h.listener.close()
        runCurrent()

        assertTrue(h.sentTo(STALE_PORT, mark).single().isRst, "the queued connection is reset")
        assertFailsWith<ClosedReceiveChannelException> { h.listener.accept() }
        h.assertPortFree()
    }

    @Test
    fun closingTheListenerResetsAHandshakeStillInProgress() = runTest {
        val h = Harness(this)
        h.deliverSyn(peerPort = STALE_PORT, peerIss = 1_000) // SYN_RECEIVED, its SYN-ACK unacknowledged
        val mark = h.link.sent.size

        h.listener.close()
        runCurrent()
        advanceTimeBy(15.seconds) // past every SYN-ACK retransmission
        runCurrent()

        assertTrue(h.sentTo(STALE_PORT, mark).single().isRst, "a RST and no further SYN-ACK")
        h.assertPortFree()
    }

    @Test
    fun aHandshakeThatCompletesAfterTheListenerClosedIsReset() = runTest {
        val h = Harness(this)
        h.deliverSyn(peerPort = STALE_PORT, peerIss = 1_000)
        val synAck = assertNotNull(h.synAckTo(STALE_PORT))
        val mark = h.link.sent.size

        // The completing ACK is routed to the connection before the listener closes, and the connection only
        // processes it afterwards: it reaches ESTABLISHED with nobody left to accept it.
        h.link.deliver(kart, h.handshakeAck(STALE_PORT, 1_000, synAck))
        backgroundScope.launch { h.listener.close() }
        runCurrent()

        assertTrue(h.sentTo(STALE_PORT, mark).single().isRst, "the connection is reset once, not dropped silently")
        assertFailsWith<ClosedReceiveChannelException> { h.listener.accept() }
        h.assertPortFree()
    }

    @Test
    fun aCancelledAcceptThatAlreadyTookAConnectionResetsItAndFreesThePort() = runTest {
        val h = Harness(this)
        // The accepting coroutine runs on a dispatcher drained by hand, so it can be cancelled after the listener
        // handed it the connection but before it resumed with it.
        val acceptor = ManualDispatcher()
        val accepting = backgroundScope.launch(acceptor) { h.listener.accept() }
        acceptor.runAll() // suspended in accept()
        h.handshakeUnaccepted(peerPort = FRESH_PORT, peerIss = 9_000) // the connection goes straight to the waiting accept()
        val mark = h.link.sent.size

        accepting.cancel()
        acceptor.runAll() // resumes into the cancellation: the connection it took is reported undelivered
        runCurrent()

        assertTrue(accepting.isCancelled)
        assertTrue(h.sentTo(FRESH_PORT, mark).single().isRst, "the connection nobody received is reset, not leaked")
        h.assertPortFree()
    }

    @Test
    fun acceptSkipsAConnectionThatWasResetWhileQueued() = runTest {
        val h = Harness(this)
        h.handshakeUnaccepted(peerPort = STALE_PORT, peerIss = 1_000) // queued, never accepted

        h.deliverSyn(peerPort = FRESH_PORT, peerIss = 9_000) // the peer restarted: the queued connection is reset
        h.deliverHandshakeAck(FRESH_PORT, 9_000, assertNotNull(h.synAckTo(FRESH_PORT)))

        val accepted = h.listener.accept()
        assertEquals(FRESH_PORT, accepted.peerPort, "the reset connection is skipped")
        assertEquals(TcpState.ESTABLISHED, accepted.state.value)
        val second = backgroundScope.async { h.listener.accept() }
        runCurrent()
        assertFalse(second.isCompleted, "nothing else is queued")
    }

    // ---- rules that stay as they were ---------------------------------------------------------

    @Test
    fun aSynFromAnotherPeerAddressIsStillDroppedAsABusyPort() = runTest {
        val h = Harness(this)
        val held = h.handshake(peerPort = STALE_PORT, peerIss = 1_000)
        val mark = h.link.sent.size
        val next = backgroundScope.async { h.listener.accept() }
        runCurrent()

        h.deliverSyn(peerPort = FRESH_PORT, peerIss = 9_000, from = otherPeer)

        assertEquals(mark, h.link.sent.size, "the SYN earns neither a SYN-ACK nor a RST")
        assertEquals(TcpState.ESTABLISHED, held.state.value, "the held connection is untouched")
        assertFalse(next.isCompleted, "no connection is opened for the other peer address")
    }

    @Test
    fun aRetransmittedSynOnTheSameFourTupleInSynReceivedStaysWithTheExistingConnection() = runTest {
        val h = Harness(this)
        h.deliverSyn(peerPort = STALE_PORT, peerIss = 1_000)
        val synAck = assertNotNull(h.synAckTo(STALE_PORT))
        val mark = h.link.sent.size

        h.deliverSyn(peerPort = STALE_PORT, peerIss = 1_000) // the very same SYN again

        val replies = h.sentTo(STALE_PORT, mark)
        assertEquals(1, replies.size, "the existing connection answers once")
        assertTrue(replies.single().isAck && !replies.single().isSyn && !replies.single().isRst, "with a pure ACK, not a reset")
        assertEquals(1_001L, replies.single().acknowledgmentNumber)
        assertEquals(mark + 1, h.link.sent.size, "nothing else goes out: no RST, no second SYN-ACK to another port")

        h.deliverHandshakeAck(STALE_PORT, 1_000, synAck)
        val accepted = h.listener.accept()
        assertEquals(STALE_PORT, accepted.peerPort, "the original connection completes its handshake")
        assertEquals(TcpState.ESTABLISHED, accepted.state.value)
        val second = backgroundScope.async { h.listener.accept() }
        runCurrent()
        assertFalse(second.isCompleted, "the retransmitted SYN did not open a second connection")
    }

    @Test
    fun aFreshSynOnTheSameFourTupleWhileEstablishedGetsAChallengeAckAndTheConnectionStays() = runTest {
        val h = Harness(this)
        val existing = h.handshake(peerPort = STALE_PORT, peerIss = 1_000)
        val mark = h.link.sent.size

        h.deliverSyn(peerPort = STALE_PORT, peerIss = 7_000) // a new incarnation reusing its source port

        val reply = h.sentTo(STALE_PORT, mark).single()
        assertTrue(reply.isAck && !reply.isSyn && !reply.isRst, "a pure (challenge) ACK, not a reset")
        assertEquals(1_001L, reply.acknowledgmentNumber, "it acknowledges the old receive position, not the new SYN")
        assertEquals(TcpState.ESTABLISHED, existing.state.value, "the connection keeps its state")

        // A peer that restarted answers the unacceptable ACK with a RST (sequence = the ACK number): the
        // stale connection ends, and the peer's next SYN opens the new one. The pair does not wedge.
        h.link.deliver(kart, TcpSegment(STALE_PORT, LOCAL_PORT, reply.acknowledgmentNumber, 0, TcpSegment.RST, 0))
        runCurrent()
        assertEquals(TcpState.CLOSED, existing.state.value)
        assertFailsWith<TcpResetException> { existing.read(16) }

        h.deliverSyn(peerPort = STALE_PORT, peerIss = 7_000)
        val synAck = assertNotNull(h.synAckTo(STALE_PORT, mark + 1))
        h.deliverHandshakeAck(STALE_PORT, 7_000, synAck)
        val reopened = h.listener.accept()
        assertNotSame(existing, reopened)
        assertEquals(TcpState.ESTABLISHED, reopened.state.value)
    }

    @Test
    fun anActiveOpenPortWithNoListenerAnswersASynFromANewSourcePortWithAResetAndStays() = runTest {
        val h = Harness(this)
        val active = h.endpoint.open(ACTIVE_PORT, kart, LOCAL_PORT)
        val connecting = backgroundScope.async { runCatching { active.connect() } }
        runCurrent()
        val syn = h.sentTo(LOCAL_PORT).single { it.isSyn && !it.isAck }
        h.link.deliver(
            kart,
            TcpSegment(LOCAL_PORT, ACTIVE_PORT, 3_000, syn.sequenceNumber + 1, TcpSegment.SYN or TcpSegment.ACK, PEER_WINDOW, maxSegmentSize = PEER_MSS),
        )
        runCurrent()
        assertTrue(connecting.isCompleted && connecting.getCompleted().isSuccess, "the active open completes")
        assertEquals(TcpState.ESTABLISHED, active.state.value)
        val mark = h.link.sent.size

        // The same peer address, another source port, aimed at the active-open port (which has no listener).
        h.link.deliver(kart, TcpSegment(NEW_SOURCE_PORT, ACTIVE_PORT, 9_000, 0, TcpSegment.SYN, PEER_WINDOW, maxSegmentSize = PEER_MSS))
        runCurrent()

        val reply = h.link.sent.drop(mark).single()
        assertEquals(NEW_SOURCE_PORT, reply.segment.destinationPort)
        assertTrue(reply.segment.isRst && !reply.segment.isSyn, "no connection matches that source port, so a RST, never a SYN-ACK")
        assertEquals(9_001L, reply.segment.acknowledgmentNumber, "the RST acknowledges the SYN it refuses")
        assertEquals(TcpState.ESTABLISHED, active.state.value, "the active-open connection is not replaced")
    }

    private companion object {
        const val LOCAL_PORT = 5201
        const val STALE_PORT = 40000
        const val FRESH_PORT = 40001
        const val ACTIVE_PORT = 41000
        const val NEW_SOURCE_PORT = 5300
        const val PROBE_PORT = 5400
        const val PEER_WINDOW = 0xFFFF
        const val PEER_MSS = 1460
        const val LATE_ACK = 777L
    }
}
