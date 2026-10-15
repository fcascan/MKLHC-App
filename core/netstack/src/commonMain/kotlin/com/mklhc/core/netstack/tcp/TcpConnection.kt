package com.mklhc.core.netstack.tcp

import co.touchlab.kermit.Logger
import com.mklhc.core.netstack.Ipv4Address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/*
 * The per-connection TCP state machine, send/receive buffers, retransmission and timers.
 *
 * Implemented from RFC 9293 ("Transmission Control Protocol", obsoleting RFC 793). Original code; no
 * third-party source was consulted or copied. The keepalive parameters mirror OpenKart's socket
 * options (see [TcpConfig]).
 *
 * This is a minimal, single-peer TCP: one connection per local port, a fixed receive window, and
 * in-order delivery only -- out-of-order segments are dropped and recovered by the sender's
 * retransmission, which keeps the receiver simple at the cost of some throughput on a lossy link.
 */

/**
 * One TCP connection: the full state machine plus a reliable, in-order byte stream over a
 * retransmission queue with cumulative acknowledgments.
 *
 * Business logic: the connection is a single-threaded actor. All mutable state lives in one
 * coroutine that drains an event channel fed by (a) inbound segments ([onSegment]), (b) timer
 * expiries scheduled on the injected [scope] with [delay] (so tests drive them with virtual time),
 * and (c) the application via [connect], [write], [read] and [close]. Nothing else touches the
 * state, so no locks are needed.
 *
 * Reliability: outbound data is cut into segments of at most [TcpConfig.mss] octets (Nagle is
 * disabled, so data is sent promptly) and kept on a retransmission queue until the cumulative ACK
 * covers them. A single retransmission timer, starting at [TcpConfig.initialRto] and doubling on
 * each firing, resends the whole queue (go-back-N); after [TcpConfig.maxRetransmits] resends without
 * progress the connection is reset. Keepalive probes guard an idle connection (see [TcpConfig]).
 *
 * Flow control: the advertised window ([TcpConfig.receiveWindow], a fixed 16 KiB) is NOT
 * backpressure-enforced on receive. The inbound [recv] buffer is unbounded, so a slow [read] cannot
 * throttle a fast peer; delivered data is queued without limit. This is deliberate for the kart's
 * low-rate RCD control channel (small, bursty messages), and is unsuitable for bulk transfer. A
 * zero-window persist-probe timer is likewise not implemented; the sender is re-driven by the peer's
 * window-update ACKs instead (see [onSegmentSynchronized]).
 *
 * Construct through a [TcpEndpoint]; do not instantiate directly.
 *
 * @property localPort the local TCP port.
 * @property peer the remote IPv4 address (single peer).
 * @property peerPort the remote TCP port.
 */
class TcpConnection internal constructor(
    val localPort: Int,
    val peer: Ipv4Address,
    val peerPort: Int,
    private val initialSendSequence: Long,
    private val config: TcpConfig,
    private val scope: CoroutineScope,
    private val send: suspend (TcpSegment) -> Unit,
    initialState: TcpState,
    private val onEstablished: ((TcpConnection) -> Unit)?,
    private val onClosed: (() -> Unit)?,
) {
    private val log = Logger.withTag(TAG)

    private val _state = MutableStateFlow(initialState)

    /** The current connection state, observable by the application and by tests. */
    val state: StateFlow<TcpState> get() = _state.asStateFlow()

    private val _totalRetransmits = MutableStateFlow(0)

    /** How many segments have been retransmitted so far (evidence that the lossy-link tests exercised recovery). */
    val totalRetransmits: Int get() = _totalRetransmits.value

    private val _keepaliveProbesSent = MutableStateFlow(0)

    /** How many keepalive probes have been sent on the current idle period. */
    val keepaliveProbesSent: Int get() = _keepaliveProbesSent.value

    // Transmission control block (RFC 9293 section 3.3.1).
    private var sndUna: Long = 0
    private var sndNxt: Long = 0
    private var iss: Long = 0
    private var rcvNxt: Long = 0
    private var irs: Long = 0
    private var sndWnd: Int = 0
    private var sendMss: Int = config.mss
    private var ackValid: Boolean = false
    private var finSeq: Long = -1

    private val pending = ArrayDeque<Byte>()
    private val retxQueue = ArrayDeque<OutSegment>()
    private var finRequested = false

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val recv = Channel<ByteArray>(Channel.UNLIMITED)
    private var readLeftover = ByteArray(0)

    private var connectResult: CompletableDeferred<Unit>? = null

    private var rtoJob: Job? = null
    private var keepaliveJob: Job? = null
    private var timeWaitJob: Job? = null
    private var currentRto: Duration = config.initialRto
    private var consecutiveRetransmits = 0
    private var flushing = false

    // Terminal bookkeeping (Fix 2): once the actor reaches terminal CLOSED it closes [events], so the
    // API methods must no longer blindly send to it. [terminated] is the fast path and the closed
    // channel is the authoritative signal; [terminalCause] distinguishes a reset from a normal close.
    private var terminated = false
    private var terminalCause: TcpResetException? = null

    /** Starts the actor coroutine; the endpoint calls this once, right after construction. */
    internal fun start() {
        scope.launch {
            for (event in events) process(event)
        }
    }

    /** Feeds an inbound segment to the actor (called by the endpoint's demultiplexer). */
    internal fun onSegment(segment: TcpSegment) {
        events.trySend(Inbound(segment))
    }

    /**
     * Actively opens the connection: sends a SYN and suspends until the connection is [TcpState.ESTABLISHED].
     *
     * @throws TcpResetException if the open fails (reset, or retransmission gave up), or if the
     * connection has already been terminally reset.
     * @throws TcpException if the connection is already terminally closed by a normal close.
     */
    suspend fun connect() {
        val result = CompletableDeferred<Unit>()
        if (!offerEvent(DoConnect(result))) throw terminalException()
        result.await()
    }

    /**
     * Writes [bytes] to the connection; they are queued and sent promptly (Nagle disabled). Returns
     * once the bytes are accepted into the send buffer.
     *
     * @throws TcpResetException if the connection has been terminally reset.
     * @throws TcpException if the connection cannot accept data in its current state, or is terminally closed.
     */
    suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val result = CompletableDeferred<Unit>()
        if (!offerEvent(DoWrite(bytes.copyOf(), result))) throw terminalException()
        result.await()
    }

    /**
     * Reads up to [max] octets from the in-order byte stream, suspending until some arrive.
     *
     * Single-reader: call this from one coroutine at a time. Concurrent readers race on the shared
     * leftover buffer and the inbound channel and are not supported. The inbound buffer is unbounded
     * and the advertised window is not backpressure-enforced (see the class note), so a slow reader
     * does not throttle the peer; this suits the low-rate RCD control channel, not bulk transfer.
     *
     * @return the bytes read, or an empty array at the clean end of the stream (the peer's FIN).
     * @throws TcpResetException if the connection was reset before the stream ended.
     */
    suspend fun read(max: Int): ByteArray {
        require(max > 0) { "max must be positive: max=$max" }
        if (readLeftover.isEmpty()) {
            val next = recv.receiveCatching()
            if (next.isClosed) {
                val cause = next.exceptionOrNull() ?: return ByteArray(0)
                throw cause
            }
            readLeftover = next.getOrThrow()
        }
        val take = minOf(max, readLeftover.size)
        val out = readLeftover.copyOfRange(0, take)
        readLeftover = readLeftover.copyOfRange(take, readLeftover.size)
        return out
    }

    /**
     * Closes the sending half gracefully (sends a FIN) and returns once the close is initiated. The
     * state machine completes the four-way handshake in the background; observe [state] for the rest.
     * Idempotent: a no-op that returns normally once the connection is already terminally closed.
     */
    suspend fun close() {
        val result = CompletableDeferred<Unit>()
        if (!offerEvent(DoClose(result))) return // already terminally closed: idempotent no-op
        result.await()
    }

    /**
     * Aborts the connection: sends a RST, moves to [TcpState.CLOSED] at once, and surfaces a
     * [TcpResetException] to any pending or future [read]. Returns once the abort is done.
     * Idempotent: a no-op that returns normally once the connection is already terminally closed.
     */
    suspend fun abort() {
        val result = CompletableDeferred<Unit>()
        if (!offerEvent(DoAbort(result))) return // already terminally closed: idempotent no-op
        result.await()
    }

    /**
     * Sends [event] to the actor, returning false when the connection has terminated (its event
     * channel is closed). The flag is only a fast path; the closed channel is the authoritative
     * signal, so the actual send is guarded against the close/send race.
     */
    private suspend fun offerEvent(event: Event): Boolean {
        if (terminated) return false
        return try {
            events.send(event)
            true
        } catch (closed: ClosedSendChannelException) {
            false
        }
    }

    /** The exception the API throws on a terminally closed connection: the reset cause, else a plain close. */
    private fun terminalException(): TcpException = terminalCause ?: TcpException("connection closed")

    // ---- actor --------------------------------------------------------------------------------

    private suspend fun process(event: Event) {
        when (event) {
            is Inbound -> onInbound(event.segment)
            is DoConnect -> onConnect(event.result)
            is DoWrite -> onWrite(event.data, event.result)
            is DoClose -> onClose(event.result)
            is DoAbort -> onAbort(event.result)
            RtoFired -> onRtoFired()
            KeepaliveProbe -> onKeepaliveProbe()
            TimeWaitElapsed -> enterClosed(null)
        }
    }

    private suspend fun onConnect(result: CompletableDeferred<Unit>) {
        if (_state.value != TcpState.CLOSED) {
            result.completeExceptionally(TcpException("connect() is only valid on a CLOSED connection, was ${_state.value}"))
            return
        }
        connectResult = result
        iss = initialSendSequence
        sndUna = iss
        sndNxt = iss
        log.i { "onConnect(): active open -> localPort=$localPort peer=$peer:$peerPort iss=$iss" }
        sendControl(TcpSegment.SYN, config.mss)
        setState(TcpState.SYN_SENT)
    }

    private suspend fun onWrite(data: ByteArray, result: CompletableDeferred<Unit>) {
        if (_state.value != TcpState.ESTABLISHED && _state.value != TcpState.CLOSE_WAIT) {
            result.completeExceptionally(TcpException("write() is not valid in state ${_state.value}"))
            return
        }
        for (b in data) pending.addLast(b)
        flush()
        result.complete(Unit)
    }

    private suspend fun onClose(result: CompletableDeferred<Unit>) {
        when (_state.value) {
            TcpState.ESTABLISHED -> {
                finRequested = true
                setState(TcpState.FIN_WAIT_1)
                flush()
            }
            TcpState.CLOSE_WAIT -> {
                finRequested = true
                setState(TcpState.LAST_ACK)
                flush()
            }
            TcpState.LISTEN, TcpState.SYN_SENT, TcpState.SYN_RECEIVED -> abortWithReset(null)
            else -> { /* already closing or closed */ }
        }
        result.complete(Unit)
    }

    private suspend fun onAbort(result: CompletableDeferred<Unit>) {
        if (_state.value != TcpState.CLOSED) abortWithReset(null)
        result.complete(Unit)
    }

    // ---- inbound segment processing -----------------------------------------------------------

    private suspend fun onInbound(segment: TcpSegment) {
        if (segment.isRst) {
            handleRst(segment)
            return
        }
        when (_state.value) {
            TcpState.LISTEN -> onSegmentListen(segment)
            TcpState.SYN_SENT -> onSegmentSynSent(segment)
            TcpState.CLOSED -> { /* ignore */ }
            else -> onSegmentSynchronized(segment)
        }
        if (_state.value == TcpState.ESTABLISHED) restartKeepalive()
    }

    private fun handleRst(segment: TcpSegment) {
        val acceptable = if (_state.value == TcpState.SYN_SENT) {
            segment.isAck && segment.acknowledgmentNumber == add(iss, 1)
        } else {
            // Accept a RST anywhere in the receive window, not only exactly at rcvNxt (RFC 9293
            // section 3.10.7.4): a reset carrying a sequence ahead of rcvNxt but within the window
            // still refers to this connection and must tear it down.
            val seq = segment.sequenceNumber
            ackValid && seqLeq(rcvNxt, seq) && seqLt(seq, add(rcvNxt, config.receiveWindow))
        }
        if (!acceptable) {
            log.d { "handleRst(): ignoring an out-of-window RST -> state=${_state.value} seq=${segment.sequenceNumber} rcvNxt=$rcvNxt" }
            return
        }
        log.e { "handleRst(): connection reset by peer -> state=${_state.value} localPort=$localPort peer=$peer:$peerPort" }
        abortLocally(TcpResetException("connection reset by peer"))
    }

    private suspend fun onSegmentListen(segment: TcpSegment) {
        if (!segment.isSyn || segment.isAck) {
            log.d { "onSegmentListen(): ignoring a non-SYN segment in LISTEN -> flags=${segment.flags}" }
            return
        }
        irs = segment.sequenceNumber
        rcvNxt = add(irs, 1)
        ackValid = true
        applyPeerMss(segment)
        sndWnd = segment.window
        iss = initialSendSequence
        sndUna = iss
        sndNxt = iss
        log.i { "onSegmentListen(): passive open, answering SYN -> peer=$peer:$peerPort irs=$irs iss=$iss" }
        sendControl(TcpSegment.SYN, config.mss)
        setState(TcpState.SYN_RECEIVED)
    }

    private suspend fun onSegmentSynSent(segment: TcpSegment) {
        if (!segment.isSyn) return
        if (segment.isAck && !(seqLt(sndUna, segment.acknowledgmentNumber) && seqLeq(segment.acknowledgmentNumber, sndNxt))) {
            log.d { "onSegmentSynSent(): SYN with an unacceptable ACK -> ack=${segment.acknowledgmentNumber} sndUna=$sndUna sndNxt=$sndNxt" }
            return
        }
        irs = segment.sequenceNumber
        rcvNxt = add(irs, 1)
        ackValid = true
        applyPeerMss(segment)
        sndWnd = segment.window
        if (segment.isAck) {
            processAck(segment)
            setState(TcpState.ESTABLISHED)
            sendPureAck()
            onEstablishedReached()
        } else {
            log.i { "onSegmentSynSent(): simultaneous open -> moving to SYN_RECEIVED" }
            setState(TcpState.SYN_RECEIVED)
            for (out in retxQueue) transmit(out)
        }
    }

    private suspend fun onSegmentSynchronized(segment: TcpSegment) {
        val windowGrew = segment.window > sndWnd
        sndWnd = segment.window
        if (segment.isAck) processAck(segment)

        if (_state.value == TcpState.SYN_RECEIVED && seqLt(iss, sndUna)) {
            setState(TcpState.ESTABLISHED)
            onEstablishedReached()
        }

        // A pure window-update ACK (the window grew but acked nothing new) must re-drive a sender that
        // stalled on a small or zero window: processAck() only flushes when the ack advances. This is
        // the stand-in for a zero-window persist-probe timer, which is a known omission (see the class
        // note). flush() guards against re-entrancy, so a redundant call here is harmless.
        if (windowGrew && pending.isNotEmpty() &&
            (_state.value == TcpState.ESTABLISHED || _state.value == TcpState.CLOSE_WAIT)
        ) {
            flush()
        }

        val occupies = segment.payload.isNotEmpty() || segment.isFin || segment.isSyn
        if (occupies) {
            if (segment.sequenceNumber == rcvNxt && !segment.isSyn) {
                if (segment.payload.isNotEmpty()) {
                    deliverData(segment.payload)
                    rcvNxt = add(rcvNxt, segment.payload.size)
                }
                if (segment.isFin) {
                    rcvNxt = add(rcvNxt, 1)
                    onPeerFin()
                }
                sendPureAck()
            } else {
                // Out-of-order, old, or a retransmitted SYN: drop any data and re-acknowledge rcvNxt.
                sendPureAck()
            }
        } else if (ackValid && seqLt(segment.sequenceNumber, rcvNxt)) {
            // An old, data-less segment: a keepalive (or window) probe. Re-acknowledge so the peer
            // learns the connection is alive (RFC 9293 section 3.8.4).
            sendPureAck()
        }

        checkCloseCompletion()

        if (_state.value == TcpState.TIME_WAIT) restartTimeWait()
    }

    private fun onPeerFin() {
        closeReceiveStream(null)
        log.i { "onPeerFin(): peer closed its half -> state=${_state.value} localPort=$localPort" }
        when (_state.value) {
            TcpState.ESTABLISHED -> setState(TcpState.CLOSE_WAIT)
            TcpState.FIN_WAIT_1 -> if (ourFinAcked()) enterTimeWait() else setState(TcpState.CLOSING)
            TcpState.FIN_WAIT_2 -> enterTimeWait()
            else -> { /* already closing; a duplicate FIN is re-acked by the caller */ }
        }
    }

    private fun checkCloseCompletion() {
        when (_state.value) {
            TcpState.FIN_WAIT_1 -> if (ourFinAcked()) setState(TcpState.FIN_WAIT_2)
            TcpState.CLOSING -> if (ourFinAcked()) enterTimeWait()
            TcpState.LAST_ACK -> if (ourFinAcked()) enterClosed(null)
            else -> { /* no ack-driven transition */ }
        }
    }

    private fun ourFinAcked(): Boolean = finSeq >= 0 && seqLt(finSeq, sndUna)

    // ---- acknowledgment processing ------------------------------------------------------------

    private suspend fun processAck(segment: TcpSegment) {
        val ack = segment.acknowledgmentNumber
        if (seqLt(sndUna, ack) && seqLeq(ack, sndNxt)) {
            removeAcked(ack)
            sndUna = ack
            onAckProgress()
        }
    }

    private fun removeAcked(ack: Long) {
        while (retxQueue.isNotEmpty()) {
            val head = retxQueue.first()
            val end = add(head.seq, head.sequenceLength)
            if (seqLeq(end, ack)) {
                retxQueue.removeFirst()
            } else if (seqLt(head.seq, ack) && head.data.isNotEmpty()) {
                val acked = seqDistance(head.seq, ack)
                retxQueue[0] = OutSegment(add(head.seq, acked), head.flags, head.data.copyOfRange(acked, head.data.size), null)
                break
            } else {
                break
            }
        }
    }

    private suspend fun onAckProgress() {
        consecutiveRetransmits = 0
        currentRto = config.initialRto
        if (retxQueue.isEmpty()) stopRto() else restartRto(currentRto)
        flush()
    }

    // ---- sending ------------------------------------------------------------------------------

    private suspend fun flush() {
        if (flushing) return // re-entrancy guard: a window-update flush must not nest inside an ack-driven one
        flushing = true
        try {
            while (pending.isNotEmpty()) {
                val outstanding = seqDistance(sndUna, sndNxt)
                val room = sndWnd - outstanding
                if (room <= 0) break
                val count = minOf(sendMss, pending.size, room)
                if (count <= 0) break
                val chunk = ByteArray(count) { pending.removeFirst() }
                val out = OutSegment(sndNxt, TcpSegment.PSH, chunk, null)
                retxQueue.addLast(out)
                sndNxt = add(sndNxt, count)
                transmit(out)
                ensureRtoRunning()
            }
            if (finRequested && finSeq < 0 && pending.isEmpty()) {
                val out = OutSegment(sndNxt, TcpSegment.FIN, EMPTY, null)
                retxQueue.addLast(out)
                finSeq = sndNxt
                sndNxt = add(sndNxt, 1)
                transmit(out)
                ensureRtoRunning()
                log.i { "flush(): sent FIN -> localPort=$localPort finSeq=$finSeq" }
            }
        } finally {
            flushing = false
        }
    }

    private suspend fun sendControl(flag: Int, mss: Int?) {
        val out = OutSegment(sndNxt, flag, EMPTY, mss)
        retxQueue.addLast(out)
        sndNxt = add(sndNxt, out.sequenceLength)
        transmit(out)
        ensureRtoRunning()
    }

    private suspend fun transmit(out: OutSegment) {
        val flags = if (ackValid) out.flags or TcpSegment.ACK else out.flags
        val ack = if (ackValid) rcvNxt else 0L
        send(TcpSegment(localPort, peerPort, out.seq, ack, flags, config.receiveWindow, out.data, out.mss))
    }

    private suspend fun sendPureAck() {
        if (!ackValid) return
        send(TcpSegment(localPort, peerPort, sndNxt, rcvNxt, TcpSegment.ACK, config.receiveWindow))
    }

    private suspend fun sendKeepaliveProbe() {
        send(TcpSegment(localPort, peerPort, add(sndNxt, -1), rcvNxt, TcpSegment.ACK, config.receiveWindow))
    }

    private suspend fun sendRst() {
        val flags = if (ackValid) TcpSegment.RST or TcpSegment.ACK else TcpSegment.RST
        send(TcpSegment(localPort, peerPort, sndNxt, rcvNxt, flags, 0))
    }

    // ---- lifecycle transitions ----------------------------------------------------------------

    private fun onEstablishedReached() {
        log.i { "onEstablishedReached(): connection established -> localPort=$localPort peer=$peer:$peerPort" }
        startKeepalive()
        connectResult?.complete(Unit)
        connectResult = null
        onEstablished?.invoke(this)
    }

    private fun enterTimeWait() {
        stopRto()
        stopKeepalive()
        setState(TcpState.TIME_WAIT)
        startTimeWait()
        log.i { "enterTimeWait(): entering TIME_WAIT -> localPort=$localPort duration=${config.timeWait}" }
    }

    private suspend fun abortWithReset(cause: TcpResetException?) {
        log.e { "abortWithReset(): resetting the connection -> state=${_state.value} localPort=$localPort cause=${cause?.message}" }
        sendRst()
        abortLocally(cause ?: TcpResetException("connection reset"))
    }

    private fun abortLocally(cause: TcpResetException) {
        enterClosed(cause)
    }

    private fun enterClosed(cause: TcpResetException?) {
        if (terminated) return
        terminated = true
        terminalCause = cause
        setState(TcpState.CLOSED)
        stopRto()
        stopKeepalive()
        stopTimeWait()
        if (cause != null) {
            connectResult?.completeExceptionally(cause)
            closeReceiveStream(cause)
        } else {
            closeReceiveStream(null)
        }
        connectResult = null
        onClosed?.invoke()
        events.close()
    }

    /**
     * Tears this TCB down with no RST and no [onClosed] callback, for a connection in [TcpState.TIME_WAIT]
     * that a new incarnation replaces (RFC 9293 section 3.10.7.4 reopen). The [TcpEndpoint] removes it
     * from its table and opens a fresh passive connection for the reopening SYN; it must not route that
     * SYN here, where TIME_WAIT would merely re-acknowledge it.
     */
    internal fun discardQuietly() {
        if (terminated) return
        terminated = true
        setState(TcpState.CLOSED)
        stopRto()
        stopKeepalive()
        stopTimeWait()
        closeReceiveStream(null)
        events.close()
    }

    private fun deliverData(data: ByteArray) {
        recv.trySend(data.copyOf())
    }

    private fun closeReceiveStream(cause: Throwable?) {
        recv.close(cause)
    }

    private fun applyPeerMss(segment: TcpSegment) {
        segment.maxSegmentSize?.let { sendMss = minOf(config.mss, it) }
    }

    // ---- timers (scheduled on the injected scope, so virtual time drives them) -----------------

    private fun ensureRtoRunning() {
        if (retxQueue.isNotEmpty() && (rtoJob == null || rtoJob?.isActive != true)) {
            restartRto(currentRto)
        }
    }

    private fun restartRto(rto: Duration) {
        rtoJob?.cancel()
        rtoJob = scope.launch {
            delay(rto)
            if (isActive) events.trySend(RtoFired)
        }
    }

    private fun stopRto() {
        rtoJob?.cancel()
        rtoJob = null
    }

    private suspend fun onRtoFired() {
        if (retxQueue.isEmpty()) {
            rtoJob = null
            return
        }
        if (consecutiveRetransmits >= config.maxRetransmits) {
            log.e { "onRtoFired(): giving up after ${config.maxRetransmits} retransmits -> localPort=$localPort peer=$peer:$peerPort" }
            abortWithReset(TcpResetException("retransmission timeout after ${config.maxRetransmits} attempts"))
            return
        }
        consecutiveRetransmits++
        for (out in retxQueue) {
            transmit(out)
            _totalRetransmits.value = _totalRetransmits.value + 1
        }
        currentRto = minOf(currentRto * 2, config.maxRto)
        log.w { "onRtoFired(): retransmitting ${retxQueue.size} segment(s) -> attempt=$consecutiveRetransmits nextRto=$currentRto" }
        restartRto(currentRto)
    }

    private fun startKeepalive() {
        stopKeepalive()
        _keepaliveProbesSent.value = 0
        keepaliveJob = scope.launch {
            delay(config.keepaliveIdle)
            while (isActive) {
                events.trySend(KeepaliveProbe)
                delay(config.keepaliveInterval)
            }
        }
    }

    private fun restartKeepalive() = startKeepalive()

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private suspend fun onKeepaliveProbe() {
        if (_state.value != TcpState.ESTABLISHED) return
        if (_keepaliveProbesSent.value >= config.keepaliveCount) {
            log.e { "onKeepaliveProbe(): keepalive unanswered after ${config.keepaliveCount} probes, resetting -> localPort=$localPort" }
            abortWithReset(TcpResetException("keepalive timeout after ${config.keepaliveCount} probes"))
            return
        }
        _keepaliveProbesSent.value = _keepaliveProbesSent.value + 1
        log.d { "onKeepaliveProbe(): sending keepalive probe -> probe=${_keepaliveProbesSent.value} localPort=$localPort" }
        sendKeepaliveProbe()
    }

    private fun startTimeWait() {
        stopTimeWait()
        timeWaitJob = scope.launch {
            delay(config.timeWait)
            if (isActive) events.trySend(TimeWaitElapsed)
        }
    }

    private fun restartTimeWait() = startTimeWait()

    private fun stopTimeWait() {
        timeWaitJob?.cancel()
        timeWaitJob = null
    }

    private fun setState(next: TcpState) {
        val previous = _state.value
        if (previous == next) return
        if (previous == TcpState.ESTABLISHED && next != TcpState.ESTABLISHED) stopKeepalive()
        _state.value = next
    }

    // ---- sequence-number (serial) arithmetic, RFC 1982 ----------------------------------------

    // internal (not private) so the wraparound tests can exercise the serial arithmetic directly.
    internal fun add(a: Long, n: Int): Long = (a + n) and MASK_32

    internal fun seqLt(a: Long, b: Long): Boolean {
        val diff = (b - a) and MASK_32
        return diff != 0L && diff < HALF_32
    }

    internal fun seqLeq(a: Long, b: Long): Boolean = a == b || seqLt(a, b)

    private fun seqDistance(from: Long, to: Long): Int = ((to - from) and MASK_32).toInt()

    /** One segment held on the retransmission queue: its sequence number, control flags and data. */
    private class OutSegment(val seq: Long, val flags: Int, val data: ByteArray, val mss: Int?) {
        val sequenceLength: Int get() = data.size + (if (flags and TcpSegment.SYN != 0) 1 else 0) + (if (flags and TcpSegment.FIN != 0) 1 else 0)
    }

    private sealed interface Event
    private class Inbound(val segment: TcpSegment) : Event
    private class DoConnect(val result: CompletableDeferred<Unit>) : Event
    private class DoWrite(val data: ByteArray, val result: CompletableDeferred<Unit>) : Event
    private class DoClose(val result: CompletableDeferred<Unit>) : Event
    private class DoAbort(val result: CompletableDeferred<Unit>) : Event
    private object RtoFired : Event
    private object KeepaliveProbe : Event
    private object TimeWaitElapsed : Event

    private companion object {
        const val TAG = "TcpConnection"
        const val MASK_32 = 0xFFFFFFFFL
        const val HALF_32 = 0x80000000L
        val EMPTY = ByteArray(0)
    }
}
