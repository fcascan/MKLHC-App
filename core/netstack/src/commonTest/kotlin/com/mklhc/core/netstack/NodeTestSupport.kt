package com.mklhc.core.netstack

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * An in-memory [FrameLink] for the node tests.
 *
 * Business logic: [push] plays the part of the station sending a frame to the access point (it shows
 * up on [inbound]), and everything the node transmits is captured in [sent]. A pair made with [pair]
 * is cross-connected, so what one side sends the other side receives; that lets two nodes talk to
 * each other through the real dispatch code. [sendFailure] makes [send] throw, to stand in for a
 * link that has gone away.
 */
class FakeFrameLink : FrameLink {
    private val inboundChannel = Channel<LinkPayload>(Channel.UNLIMITED)
    private val sentFrames = ArrayList<LinkPayload>()
    private var peer: FakeFrameLink? = null

    /** When non-null, [send] throws it instead of transmitting. */
    var sendFailure: Throwable? = null

    override val inbound: Flow<LinkPayload> = inboundChannel.receiveAsFlow()

    /** Every frame the node under test has transmitted, in order. */
    val sent: List<LinkPayload> get() = sentFrames.toList()

    override suspend fun send(etherType: Int, payload: ByteArray) {
        sendFailure?.let { throw it }
        val frame = LinkPayload(etherType, payload)
        sentFrames += frame
        peer?.inboundChannel?.trySend(frame)
    }

    /** Delivers a frame to the node as if the station had sent it. */
    fun push(etherType: Int, payload: ByteArray) {
        inboundChannel.trySend(LinkPayload(etherType, payload))
    }

    /** Forgets the captured frames, so a test can assert on what a later step sends. */
    fun clearSent() {
        sentFrames.clear()
    }

    companion object {
        /** Two links wired back to back: the first's [send] arrives on the second's [inbound] and vice versa. */
        fun pair(): Pair<FakeFrameLink, FakeFrameLink> {
            val first = FakeFrameLink()
            val second = FakeFrameLink()
            first.peer = second
            second.peer = first
            return first to second
        }
    }
}

/** A complete IPv4 packet carrying a UDP datagram, as the station would put it on the link. */
internal fun udpOverIpv4(
    sourceIp: Ipv4Address,
    sourcePort: Int,
    destinationIp: Ipv4Address,
    destinationPort: Int,
    payload: ByteArray,
): ByteArray = Ipv4Packet(
    source = sourceIp,
    destination = destinationIp,
    protocol = Ipv4Packet.PROTOCOL_UDP,
    payload = UdpDatagram(sourcePort, destinationPort, payload).encode(sourceIp, destinationIp),
).encode()

/** This captured frame as an IPv4 packet; fails the test when it is not an IPv4 frame that parses. */
internal fun LinkPayload.ipv4Packet(): Ipv4Packet {
    assertEquals(EtherType.IPV4, etherType, "expected an IPv4 frame")
    return assertNotNull(Ipv4Packet.parse(payload), "the frame must hold a valid IPv4 packet")
}

/** This captured frame as an IPv4 packet carrying UDP, with the UDP datagram checked against its pseudo-header. */
internal fun LinkPayload.udpDatagram(): Pair<Ipv4Packet, UdpDatagram> {
    val packet = ipv4Packet()
    assertEquals(Ipv4Packet.PROTOCOL_UDP, packet.protocol, "expected a UDP packet")
    val datagram = assertNotNull(
        UdpDatagram.parse(packet.payload, packet.source, packet.destination),
        "the packet must hold a valid UDP datagram",
    )
    return packet to datagram
}
