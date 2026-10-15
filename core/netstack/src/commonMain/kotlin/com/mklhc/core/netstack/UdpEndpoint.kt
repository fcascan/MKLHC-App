package com.mklhc.core.netstack

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.concurrent.Volatile

/*
 * Application-facing UDP endpoints of the netstack node.
 *
 * Original code, implemented over the UDP codec of this module (RFC 768).
 */

/**
 * One UDP datagram as it arrived at a bound [UdpEndpoint].
 *
 * @property sourceIp the IPv4 address of the sender (the kart).
 * @property sourcePort the sender's UDP port, where a reply goes.
 * @param payload the application bytes (a copy is kept).
 */
class UdpInbound(val sourceIp: Ipv4Address, val sourcePort: Int, payload: ByteArray) {
    private val payloadBytes: ByteArray = payload.copyOf()

    /** The application bytes, a copy. */
    val payload: ByteArray get() = payloadBytes.copyOf()

    override fun toString(): String = "UdpInbound(sourceIp=$sourceIp, sourcePort=$sourcePort, size=${payloadBytes.size})"
}

/**
 * A bound UDP port: receives the datagrams addressed to it and sends datagrams from it.
 *
 * Business logic: the app (teleop, telemetry, video later) talks to the kart over UDP through one of
 * these. The node routes each inbound datagram to the endpoint bound to its destination port;
 * [send] wraps the payload in UDP and IPv4 from the node's address and hands it to the link.
 *
 * Loss policy: UDP is best effort and the consumers want the freshest data, so the receive queue is
 * bounded and drops the OLDEST datagram when the consumer falls behind (see
 * [NetstackNode.bindUdp]). Nothing blocks the node's receive loop.
 *
 * Obtain one from [NetstackNode.bindUdp]; do not instantiate directly. Like the node, not
 * thread-safe: drive it from the node's single-threaded scope.
 *
 * @property localPort the bound UDP port: the destination port of what arrives, the source port of what is sent.
 */
class UdpEndpoint internal constructor(
    val localPort: Int,
    queueCapacity: Int,
    private val transmit: suspend (destinationIp: Ipv4Address, sourcePort: Int, destinationPort: Int, payload: ByteArray) -> Unit,
    private val onClosed: (UdpEndpoint) -> Unit,
) {
    private val queue = Channel<UdpInbound>(capacity = queueCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var closed = false

    /**
     * The received datagrams in arrival order, as a flow over the same queue as [receive]: each
     * datagram goes to exactly one consumer, and the flow completes when the endpoint is closed.
     */
    val inbound: Flow<UdpInbound> = queue.receiveAsFlow()

    /**
     * Suspends until a datagram arrives and returns it.
     *
     * @throws kotlinx.coroutines.channels.ClosedReceiveChannelException once the endpoint is closed
     * (by [close] or by stopping the node) and the queue is drained.
     */
    suspend fun receive(): UdpInbound = queue.receive()

    /**
     * Sends [payload] as a UDP datagram from [localPort] to [destinationIp]:[destinationPort],
     * wrapped in IPv4 from the node's address. Suspends until the link accepts the frame.
     *
     * @throws IllegalStateException if the endpoint is closed.
     * @throws IllegalArgumentException if the datagram does not fit one IPv4 packet.
     * Failures of the link are not swallowed: UDP has no recovery of its own, so the caller sees them.
     */
    suspend fun send(destinationIp: Ipv4Address, destinationPort: Int, payload: ByteArray) {
        check(!closed) { "UDP endpoint on port $localPort is closed" }
        transmit(destinationIp, localPort, destinationPort, payload)
    }

    /**
     * Frees the port and ends the receive side: [receive] fails and [inbound] completes. Idempotent,
     * and safe to race with itself: the node's teardown may call it from another thread.
     */
    fun close() {
        if (closed) return
        closed = true
        queue.close()
        onClosed(this)
    }

    /** Queues [datagram] for the consumer, dropping the oldest queued one when the queue is full. Called by the node. */
    internal fun deliver(datagram: UdpInbound) {
        queue.trySend(datagram)
    }
}
