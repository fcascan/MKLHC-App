package com.mklhc.core.netstack.tcp

import com.mklhc.core.netstack.Ipv4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration

/**
 * A fake two-node link joining two [TcpEndpoint]s for tests, with configurable loss, reordering and
 * delay, all driven by the injected [scope]'s virtual time.
 *
 * Business logic: each direction is a channel. [send] first decides loss (so a drop is immediate and
 * deterministic for a given seed), then schedules delivery after a latency that may include an extra
 * delay on alternate segments to force reordering. Because every timer runs on the test scheduler,
 * loss recovery and reordering play out instantly under `runTest`.
 *
 * @param scope the test scope (virtual time) delivery timers run on.
 * @param addressA one endpoint's address.
 * @param addressB the other endpoint's address.
 * @param seed seeds the loss decisions so a test is reproducible.
 */
class FakeNetwork(
    private val scope: CoroutineScope,
    val addressA: Ipv4Address,
    val addressB: Ipv4Address,
    seed: Long = 1L,
) {
    /** Probability in `0.0..1.0` that a transmitted segment is dropped. */
    var lossRate: Double = 0.0

    /** Base one-way latency added to every delivered segment. */
    var latency: Duration = Duration.ZERO

    /** When positive, every other segment is held this much longer, forcing out-of-order arrival. */
    var reorderDelay: Duration = Duration.ZERO

    /** How many segments have been dropped so far. */
    var dropped: Int = 0
        private set

    /** How many segments have been delivered so far. */
    var delivered: Int = 0
        private set

    private val random = Random(seed)
    private var sendCounter = 0L
    private val inboundA = Channel<InboundSegment>(Channel.UNLIMITED)
    private val inboundB = Channel<InboundSegment>(Channel.UNLIMITED)

    /** The [TcpLink] for the endpoint at [addressA]. */
    val linkA: TcpLink = Node(addressA, inboundA) { bytes -> deliver(addressA, inboundB, bytes) }

    /** The [TcpLink] for the endpoint at [addressB]. */
    val linkB: TcpLink = Node(addressB, inboundB) { bytes -> deliver(addressB, inboundA, bytes) }

    private fun deliver(from: Ipv4Address, target: Channel<InboundSegment>, bytes: ByteArray) {
        if (lossRate > 0.0 && random.nextDouble() < lossRate) {
            dropped++
            return
        }
        val extra = if (reorderDelay > Duration.ZERO && sendCounter++ % 2L == 0L) reorderDelay else Duration.ZERO
        val total = latency + extra
        if (total <= Duration.ZERO) {
            target.trySend(InboundSegment(from, bytes))
            delivered++
        } else {
            scope.launch {
                delay(total)
                target.trySend(InboundSegment(from, bytes))
                delivered++
            }
        }
    }

    private class Node(
        override val localAddress: Ipv4Address,
        private val inboundChannel: Channel<InboundSegment>,
        private val onSend: (ByteArray) -> Unit,
    ) : TcpLink {
        override suspend fun send(peer: Ipv4Address, segment: ByteArray) = onSend(segment)
        override val inbound: Flow<InboundSegment> get() = inboundChannel.receiveAsFlow()
    }
}
