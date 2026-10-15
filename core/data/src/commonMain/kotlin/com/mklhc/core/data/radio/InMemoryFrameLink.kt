package com.mklhc.core.data.radio

import com.mklhc.core.netstack.FrameLink
import com.mklhc.core.netstack.LinkPayload
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * An in-memory [FrameLink] end, cross-connected to a peer with [pair].
 *
 * Business logic: what one end [send]s arrives on the other end's [inbound], through an unbounded
 * buffer, so frames sent before the receiver starts collecting are kept (the `FrameLink` contract) and
 * [send] never suspends. Every sent frame is also recorded in [sent] for assertions. It models the
 * link only, not the radio: there is no association gating, so a frame is delivered whether or not a
 * station is "associated". `inbound` has a single consumer; a frame goes to whichever collector takes
 * it first.
 */
class InMemoryFrameLink private constructor() : FrameLink {
    private val inboundChannel = Channel<LinkPayload>(Channel.UNLIMITED)
    private val sentFrames = ArrayList<LinkPayload>()
    private var peer: InMemoryFrameLink? = null

    override val inbound: Flow<LinkPayload> = inboundChannel.receiveAsFlow()

    /** Every frame this end has sent, in order. */
    val sent: List<LinkPayload> get() = sentFrames.toList()

    override suspend fun send(etherType: Int, payload: ByteArray) {
        val frame = LinkPayload(etherType, payload)
        sentFrames += frame
        peer?.inboundChannel?.trySend(frame)
    }

    companion object {
        /** Two ends wired back to back: the first's [send] arrives on the second's [inbound] and vice versa. */
        fun pair(): Pair<InMemoryFrameLink, InMemoryFrameLink> {
            val first = InMemoryFrameLink()
            val second = InMemoryFrameLink()
            first.peer = second
            second.peer = first
            return first to second
        }
    }
}
