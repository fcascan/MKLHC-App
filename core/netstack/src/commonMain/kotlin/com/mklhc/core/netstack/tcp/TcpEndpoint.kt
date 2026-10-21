package com.mklhc.core.netstack.tcp

import co.touchlab.kermit.Logger
import com.mklhc.core.netstack.Ipv4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.random.Random

/*
 * The local TCP endpoint: encodes and sends segments, and demultiplexes inbound segments to the
 * right connection or listener.
 *
 * Implemented from RFC 9293. Original code; no third-party source was consulted or copied.
 */

/**
 * One raw TCP segment as it arrives from a peer, before parsing.
 *
 * @property peer the IPv4 source address of the segment (the pseudo-header source for its checksum).
 * @param bytes the encoded TCP segment (header, options and payload); a copy is kept.
 */
class InboundSegment(val peer: Ipv4Address, bytes: ByteArray) {
    private val raw: ByteArray = bytes.copyOf()

    /** The encoded TCP segment, a copy. */
    val bytes: ByteArray get() = raw.copyOf()
}

/**
 * The transport boundary the endpoint drives: encoded TCP segments out to a peer, and the stream of
 * encoded segments coming in.
 *
 * Business logic: this keeps the TCP layer transport-agnostic. A later FrameLink-based transport
 * wraps each outbound segment in an IPv4 packet from [localAddress] to the peer and unwraps inbound
 * ones, but [TcpEndpoint] only ever sees raw TCP-over-IP. Tests substitute a fake, lossy link.
 */
interface TcpLink {
    /** Our local IPv4 address: the pseudo-header source for outbound checksums, the destination for inbound ones. */
    val localAddress: Ipv4Address

    /** Transmits an encoded TCP [segment] to [peer]. Suspends until the link accepts it. */
    suspend fun send(peer: Ipv4Address, segment: ByteArray)

    /** The encoded TCP segments received from peers, in arrival order. */
    val inbound: Flow<InboundSegment>
}

/**
 * The local TCP endpoint for one IPv4 address: it ties the [link] to the per-connection state
 * machines.
 *
 * Business logic: a single receive loop collects [TcpLink.inbound], validates each segment's
 * checksum, and demultiplexes by local port (one connection per port, because the link has a single
 * peer). A SYN to a listening port opens a passive connection; a segment to a port with no
 * connection or listener earns a RST. Active opens go through [open]. Everything runs on the injected
 * [scope], so tests drive the whole endpoint with virtual time.
 *
 * Restarted peer: the kart can reset or lose power without sending a FIN or RST, then reconnect from a
 * new source port while the old connection still holds the local port (it only dies when
 * retransmission or keepalive gives up, several seconds later). Because the link has exactly one peer,
 * a fresh SYN (SYN without ACK) to a port that has a listener, from the same peer address as the
 * existing connection but another source port, can only be that peer starting over. The endpoint then
 * ends the old connection as a received RST would (its readers and writers fail at once with
 * [TcpResetException], its timers stop, and a RST is sent to the old source port so a peer that did
 * not restart learns of it) and opens a new passive connection for the SYN. That is safe here because
 * no second client can legitimately hold the port, and the only wrongly-replaced case, a stray or
 * spoofed SYN from the peer's own address, costs one reconnect. Any other segment from the peer's address
 * on a source port the held connection does not use (the replaced four-tuple included, or anything aimed
 * at an active-open port) has no connection and earns a RST, as at a closed port. The remaining rules are
 * unchanged: segments of the same four-tuple go to the existing connection, a fresh SYN for a connection
 * in TIME_WAIT reopens it, a segment from another peer address to a held port is dropped as busy, and a
 * port with no listener (an active open) is never replaced.
 *
 * Port bookkeeping: a connection holds its local port from [open] or the opening SYN until it terminates,
 * whatever ends it (a close or abort, even before [TcpConnection.connect], a reset, a timeout, or a
 * replacement); its close callback frees the port only while the table still maps the port to it. Closing a
 * listener resets the handshake still in progress on its port and every connection it had queued but nobody
 * accepted (see [TcpListener]), so none of them keeps the port or retransmits to a peer nobody serves.
 *
 * Same four-tuple: a fresh SYN from the existing connection's own source port is not replaced, it is
 * routed to that connection. While it is synchronized (SYN_RECEIVED, ESTABLISHED and the closing
 * states) the connection answers it with a pure ACK of its current receive position and keeps its
 * state (the RFC 5961 challenge ACK of RFC 9293 section 3.10.7.4). A peer that restarted onto the same
 * source port finds that ACK unacceptable in SYN_SENT and answers with a RST, which tears the stale
 * connection down, and its next SYN retransmission opens the new one; a peer that ignores the ACK and
 * keeps resending its SYN is not recovered until it gives up or the old connection ends by timeout. A
 * retransmitted SYN in SYN_RECEIVED gets the same pure ACK and the connection stays; its own SYN-ACK is
 * resent by the retransmission timer.
 *
 * Not thread-safe: drive [listen] / [open] and the receive loop from a single coroutine / scope, as
 * with the rest of this module.
 *
 * @param link the transport boundary.
 * @param scope the coroutine scope the receive loop, connections and their timers run on.
 * @param config the TCP tuning parameters shared by the connections this endpoint creates.
 * @param issRandom the source of initial sequence numbers (seedable for deterministic tests).
 */
class TcpEndpoint(
    private val link: TcpLink,
    private val scope: CoroutineScope,
    private val config: TcpConfig = TcpConfig(),
    private val issRandom: Random = Random.Default,
) {
    private val log = Logger.withTag(TAG)

    /** This endpoint's local IPv4 address. */
    val localAddress: Ipv4Address = link.localAddress

    private val connections = HashMap<Int, TcpConnection>()
    private val listeners = HashMap<Int, TcpListener>()

    init {
        scope.launch {
            link.inbound.collect { dispatch(it) }
        }
    }

    /** Binds [localPort] for passive opens and returns its accept queue. */
    fun listen(localPort: Int): TcpListener {
        require(localPort in MIN_PORT..MAX_PORT) { "port must be 1..$MAX_PORT: localPort=$localPort" }
        require(listeners[localPort] == null) { "already listening on port $localPort" }
        val listener = TcpListener(localPort, ::onListenerClosed)
        listeners[localPort] = listener
        log.i { "listen(): listening -> localPort=$localPort" }
        return listener
    }

    /**
     * Unbinds [listener]'s port and resets the passive connection still handshaking there (LISTEN or
     * SYN_RECEIVED): nobody would accept it, and left alone it would retransmit its SYN-ACK and hold the port
     * until it gave up. The listener itself resets the connections it had queued. A no-op unless [listener] is
     * the one bound to its port.
     */
    private fun onListenerClosed(listener: TcpListener) {
        val localPort = listener.localPort
        if (listeners[localPort] !== listener) return
        listeners.remove(localPort)
        val handshaking = connections[localPort]?.takeIf {
            it.isPassive && (it.state.value == TcpState.LISTEN || it.state.value == TcpState.SYN_RECEIVED)
        }
        log.i { "onListenerClosed(): stopped listening -> localPort=$localPort resetsHandshake=${handshaking != null}" }
        handshaking?.abortSoon()
    }

    /**
     * Hands an established passive connection to [listener]'s accept queue, or resets it when the listener has
     * closed meanwhile (the handshake completed after the close), so it neither lingers unaccepted nor holds the port.
     */
    private fun deliverToListener(listener: TcpListener, connection: TcpConnection) {
        if (listener.onAccepted(connection)) return
        log.i { "deliverToListener(): the listener closed before the handshake completed, resetting -> localPort=${connection.localPort} peer=${connection.peer}:${connection.peerPort}" }
        connection.abortSoon()
    }

    /** Frees [connection]'s local port when it terminates, unless the table already maps that port to another connection. */
    private fun onConnectionClosed(connection: TcpConnection) {
        if (connections[connection.localPort] === connection) connections.remove(connection.localPort)
    }

    /**
     * Creates an active-open connection from [localPort] to [peer]:[peerPort]. Call
     * [TcpConnection.connect] to perform the handshake.
     */
    fun open(localPort: Int, peer: Ipv4Address, peerPort: Int): TcpConnection {
        require(localPort in MIN_PORT..MAX_PORT) { "port must be 1..$MAX_PORT: localPort=$localPort" }
        require(connections[localPort] == null) { "local port $localPort is already in use" }
        val connection = newConnection(localPort, peer, peerPort, TcpState.CLOSED, onEstablished = null)
        connections[localPort] = connection
        connection.start()
        return connection
    }

    private fun newConnection(
        localPort: Int,
        peer: Ipv4Address,
        peerPort: Int,
        initialState: TcpState,
        onEstablished: ((TcpConnection) -> Unit)?,
    ): TcpConnection = TcpConnection(
        localPort = localPort,
        peer = peer,
        peerPort = peerPort,
        initialSendSequence = issRandom.nextInt().toLong() and MASK_32,
        config = config,
        scope = scope,
        send = { segment -> link.send(peer, segment.encode(localAddress, peer)) },
        initialState = initialState,
        onEstablished = onEstablished,
        onClosed = ::onConnectionClosed,
    )

    private suspend fun dispatch(inbound: InboundSegment) {
        val segment = TcpSegment.parse(inbound.bytes, inbound.peer, localAddress)
        if (segment == null) {
            log.w { "dispatch(): dropping a segment with a bad checksum or short header -> peer=${inbound.peer}" }
            return
        }
        val existing = connections[segment.destinationPort]
        if (existing != null) {
            val samePeerAddress = existing.peer == inbound.peer
            val fromSamePeer = samePeerAddress && existing.peerPort == segment.sourcePort
            val isFreshSyn = segment.isSyn && !segment.isAck
            val hasListener = listeners.containsKey(segment.destinationPort)
            val canReopen = existing.state.value == TcpState.TIME_WAIT && hasListener
            val peerRestarted = isFreshSyn && hasListener && samePeerAddress && !fromSamePeer
            if (isFreshSyn && fromSamePeer && canReopen) {
                // RFC 9293 section 3.10.7.4: a new SYN for a connection in TIME_WAIT is a new
                // incarnation. Drop the old TCB and let the listener open a fresh passive connection
                // (the kart reconnects frequently), rather than routing the SYN into TIME_WAIT where
                // it is merely re-acknowledged. Its close callback frees the port. Falls through to the
                // listener path below.
                log.i {
                    "dispatch(): a new SYN reopens a TIME_WAIT connection -> " +
                        "localPort=${segment.destinationPort} peer=${inbound.peer}:${segment.sourcePort}"
                }
                existing.discardQuietly()
            } else if (peerRestarted) {
                // The link has a single peer, so a fresh SYN from its address on a new source port can only
                // be that peer starting over (a reset or power cycle that sent no FIN or RST): the old
                // connection is stale. End it as a received RST would (its close callback frees the port) and
                // let the listener open the new one, instead of dropping the SYN as a busy port until the old
                // connection times out. Falls through to the listener path below.
                log.i {
                    "dispatch(): a new SYN from a restarted peer replaces the stale connection -> " +
                        "localPort=${segment.destinationPort} peer=${inbound.peer}:${segment.sourcePort} " +
                        "stalePeerPort=${existing.peerPort} staleState=${existing.state.value}"
                }
                existing.resetByPeerRestart()
            } else if (fromSamePeer) {
                existing.onSegment(segment)
                return
            } else if (samePeerAddress) {
                // The peer's address on a source port the held connection does not use: most often the
                // four-tuple a restart just replaced, still in use by a peer that did not restart, or anything
                // aimed at an active-open port. No connection matches it, so it earns a RST as at a closed
                // port (RFC 9293 section 3.10.7.1), unless it is itself a RST.
                log.d {
                    "dispatch(): no connection for this source port of the peer -> " +
                        "localPort=${segment.destinationPort} peer=${inbound.peer}:${segment.sourcePort} " +
                        "heldPeerPort=${existing.peerPort} isRst=${segment.isRst}"
                }
                if (!segment.isRst) sendRstTo(inbound.peer, segment)
                return
            } else {
                log.w {
                    "dispatch(): dropping a segment for a busy port from another peer -> " +
                        "localPort=${segment.destinationPort} peer=${inbound.peer}:${segment.sourcePort}"
                }
                return
            }
        }
        val listener = listeners[segment.destinationPort]
        if (listener != null && segment.isSyn && !segment.isAck) {
            val connection = newConnection(
                segment.destinationPort,
                inbound.peer,
                segment.sourcePort,
                TcpState.LISTEN,
                onEstablished = { established -> deliverToListener(listener, established) },
            )
            connections[segment.destinationPort] = connection
            connection.start()
            connection.onSegment(segment)
            return
        }
        if (!segment.isRst) sendRstTo(inbound.peer, segment)
    }

    /** Answers a segment to a closed port with a RST, so the peer stops retrying (RFC 9293 section 3.10.7.1). */
    private suspend fun sendRstTo(peer: Ipv4Address, segment: TcpSegment) {
        val rst = if (segment.isAck) {
            TcpSegment(segment.destinationPort, segment.sourcePort, segment.acknowledgmentNumber, 0, TcpSegment.RST, 0)
        } else {
            val ack = (segment.sequenceNumber + segment.sequenceLength) and MASK_32
            TcpSegment(segment.destinationPort, segment.sourcePort, 0, ack, TcpSegment.RST or TcpSegment.ACK, 0)
        }
        log.d { "sendRstTo(): no connection for port ${segment.destinationPort}, sending RST -> peer=$peer:${segment.sourcePort}" }
        link.send(peer, rst.encode(localAddress, peer))
    }

    private companion object {
        const val TAG = "TcpEndpoint"
        const val MIN_PORT = 1
        const val MAX_PORT = 0xFFFF
        const val MASK_32 = 0xFFFFFFFFL
    }
}
