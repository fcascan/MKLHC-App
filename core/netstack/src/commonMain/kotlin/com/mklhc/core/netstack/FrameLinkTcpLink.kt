package com.mklhc.core.netstack

import co.touchlab.kermit.Logger
import com.mklhc.core.netstack.tcp.InboundSegment
import com.mklhc.core.netstack.tcp.TcpLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.cancellation.CancellationException

/*
 * The TcpLink implementation that rides on the netstack node's FrameLink.
 *
 * Original code; it only adapts the TcpLink interface of this module to the node's IPv4 sender.
 */

/**
 * A [TcpLink] over the node's link: outbound segments are wrapped in IPv4 by the node, inbound ones
 * are handed over by the node's dispatcher.
 *
 * Business logic: [TcpEndpoint][com.mklhc.core.netstack.tcp.TcpEndpoint] only ever sees raw TCP
 * segments tagged with the peer's address. This adapter is the seam to the rest of the stack:
 * [send] hands the segment to [sendSegment] (the node wraps it in IPv4 with protocol 6 and sends it
 * through the [FrameLink]), and [deliver] is called by the node's receive loop for each IPv4 packet
 * of protocol 6 addressed to us, queueing it for the endpoint's receive loop via [inbound].
 *
 * A failing send is logged and swallowed, which makes it indistinguishable from a lost packet: TCP's
 * retransmission resends, and gives up with a reset when the link stays down. Letting the exception
 * escape would instead kill the connection's actor coroutine and leave its caller hanging.
 *
 * @property localAddress our IPv4 address, the pseudo-header source of every outbound segment.
 * @param sendSegment wraps and transmits one encoded TCP segment to a peer.
 */
internal class FrameLinkTcpLink(
    override val localAddress: Ipv4Address,
    private val sendSegment: suspend (peer: Ipv4Address, segment: ByteArray) -> Unit,
) : TcpLink {
    private val log = Logger.withTag(TAG)

    private val segments = Channel<InboundSegment>(Channel.UNLIMITED)

    override val inbound: Flow<InboundSegment> = segments.receiveAsFlow()

    override suspend fun send(peer: Ipv4Address, segment: ByteArray) {
        try {
            sendSegment(peer, segment)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) { "send(): link send failed, treating the segment as lost -> peer=$peer size=${segment.size} message=${failure.message}" }
        }
    }

    /** Queues the TCP [segment] that arrived from [peer] for the endpoint. */
    fun deliver(peer: Ipv4Address, segment: ByteArray) {
        segments.trySend(InboundSegment(peer, segment))
    }

    /** Ends [inbound], which stops the endpoint's receive loop. Idempotent. */
    fun close() {
        segments.close()
    }

    private companion object {
        const val TAG = "FrameLinkTcpLink"
    }
}
