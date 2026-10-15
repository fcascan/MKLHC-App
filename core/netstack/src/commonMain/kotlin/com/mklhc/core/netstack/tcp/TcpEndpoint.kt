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
        val listener = TcpListener(localPort) { listeners.remove(localPort) }
        listeners[localPort] = listener
        log.i { "listen(): listening -> localPort=$localPort" }
        return listener
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
        onClosed = { connections.remove(localPort) },
    )

    private suspend fun dispatch(inbound: InboundSegment) {
        val segment = TcpSegment.parse(inbound.bytes, inbound.peer, localAddress)
        if (segment == null) {
            log.w { "dispatch(): dropping a segment with a bad checksum or short header -> peer=${inbound.peer}" }
            return
        }
        val existing = connections[segment.destinationPort]
        if (existing != null) {
            val fromSamePeer = existing.peer == inbound.peer && existing.peerPort == segment.sourcePort
            val isFreshSyn = segment.isSyn && !segment.isAck
            val canReopen = existing.state.value == TcpState.TIME_WAIT && listeners.containsKey(segment.destinationPort)
            if (isFreshSyn && fromSamePeer && canReopen) {
                // RFC 9293 section 3.10.7.4: a new SYN for a connection in TIME_WAIT is a new
                // incarnation. Drop the old TCB and let the listener open a fresh passive connection
                // (the kart reconnects frequently), rather than routing the SYN into TIME_WAIT where
                // it is merely re-acknowledged. Falls through to the listener path below.
                log.i {
                    "dispatch(): a new SYN reopens a TIME_WAIT connection -> " +
                        "localPort=${segment.destinationPort} peer=${inbound.peer}:${segment.sourcePort}"
                }
                existing.discardQuietly()
                connections.remove(segment.destinationPort)
            } else if (fromSamePeer) {
                existing.onSegment(segment)
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
                onEstablished = { established -> listener.onAccepted(established) },
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
        log.d { "sendRstTo(): no connection for port ${segment.destinationPort}, sending RST -> peer=$peer" }
        link.send(peer, rst.encode(localAddress, peer))
    }

    private companion object {
        const val TAG = "TcpEndpoint"
        const val MIN_PORT = 1
        const val MAX_PORT = 0xFFFF
        const val MASK_32 = 0xFFFFFFFFL
    }
}
