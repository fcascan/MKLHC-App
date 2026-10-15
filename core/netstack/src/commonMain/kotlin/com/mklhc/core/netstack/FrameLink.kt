package com.mklhc.core.netstack

import kotlinx.coroutines.flow.Flow

/**
 * EtherType values of the network-layer protocols this stack speaks (IEEE 802 / IANA registry).
 *
 * Business logic: the link layer strips the 802.11 data frame and its LLC/SNAP header and hands the
 * stack the bare network-layer payload together with the EtherType that SNAP carried; the stack
 * demultiplexes on it.
 */
object EtherType {
    /** IPv4 (RFC 791). */
    const val IPV4 = 0x0800

    /** ARP (RFC 826). */
    const val ARP = 0x0806
}

/**
 * One network-layer payload as it crosses the link boundary: the bytes after 802.11 + LLC/SNAP,
 * tagged with the EtherType from the SNAP header. Compared by value (the payload array by content).
 */
class LinkPayload(val etherType: Int, payload: ByteArray) {
    private val payloadBytes: ByteArray = payload.copyOf()

    /** The network-layer bytes (an ARP packet or an IPv4 packet), a copy. */
    val payload: ByteArray get() = payloadBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LinkPayload && etherType == other.etherType && payloadBytes.contentEquals(other.payloadBytes)

    override fun hashCode(): Int = HASH_PRIME * etherType + payloadBytes.contentHashCode()

    override fun toString(): String = "LinkPayload(etherType=0x${etherType.toString(HEX_RADIX)}, size=${payloadBytes.size})"

    private companion object {
        const val HASH_PRIME = 31
        const val HEX_RADIX = 16
    }
}

/**
 * The boundary this module expects from the link layer.
 *
 * Business logic: :core:wifi-ap owns 802.11 and LLC/SNAP (it adds them on send and strips them on
 * receive, encrypting and decrypting CCMP on the way); :core:netstack only sees network-layer
 * payloads keyed by EtherType. Keeping this interface in netstack means netstack has no dependency
 * on wifi-ap; wifi-ap (or the integration layer) provides the implementation later.
 *
 * Addressing: the access point admits exactly one station, so neither direction carries a link
 * address. [send] is delivered to the associated station and [inbound] only carries what it sent.
 */
interface FrameLink {
    /**
     * Transmits [payload] (an ARP or IPv4 packet) to the associated station with SNAP EtherType
     * [etherType] (see [EtherType]). Suspends until the link accepts the frame.
     */
    suspend fun send(etherType: Int, payload: ByteArray)

    /**
     * The network-layer payloads received from the station, in arrival order.
     *
     * CONTRACT for implementations (the :core:wifi-ap link in particular): `inbound` MUST be a
     * buffered cold flow, for example a `Channel.receiveAsFlow()`, or a `SharedFlow` with adequate
     * `replay` / `extraBufferCapacity` and a suspending or buffering overflow policy. A replay-less
     * hot `SharedFlow` drops link frames emitted before `NetstackNode.start()` subscribes (and any
     * emitted before `start()` is called), which silently breaks association and DHCP.
     */
    val inbound: Flow<LinkPayload>
}
