package com.mklhc.core.netstack

/*
 * ARP (Address Resolution Protocol) codec and responder.
 *
 * Implemented from RFC 826 ("An Ethernet Address Resolution Protocol"). Original code; no
 * third-party source was consulted or copied. The 28-byte packet is hardware type (2), protocol
 * type (2), hardware length (1), protocol length (1), operation (2), sender hardware address (6),
 * sender protocol address (4), target hardware address (6), target protocol address (4), all
 * big-endian. It travels directly in the link payload with EtherType 0x0806 (see [EtherType.ARP]).
 */

/**
 * One ARP packet for Ethernet hardware addresses (6 octets) and IPv4 protocol addresses (4 octets).
 *
 * Business logic: before the kart can send us an IPv4 packet it asks "who has 169.254.98.65?";
 * [Arp.handle] turns that [Arp.OPERATION_REQUEST] into the matching reply. Instances are compared by
 * value, and the MAC arrays are copied on the way in and out so a packet stays immutable.
 *
 * Field names follow RFC 826: [senderMac] is `sha`, [senderIp] is `spa`, [targetMac] is `tha` and
 * [targetIp] is `tpa`; [operation] is `oper`.
 */
class ArpPacket(
    val operation: Int,
    senderMac: ByteArray,
    val senderIp: Ipv4Address,
    targetMac: ByteArray,
    val targetIp: Ipv4Address,
    val hardwareType: Int = HARDWARE_TYPE_ETHERNET,
    val protocolType: Int = EtherType.IPV4,
    val hardwareLength: Int = MAC_SIZE,
    val protocolLength: Int = Ipv4Address.SIZE,
) {
    private val senderMacBytes: ByteArray = senderMac.copyOf()
    private val targetMacBytes: ByteArray = targetMac.copyOf()

    init {
        require(senderMacBytes.size == MAC_SIZE) { "sender MAC must be $MAC_SIZE octets: size=${senderMacBytes.size}" }
        require(targetMacBytes.size == MAC_SIZE) { "target MAC must be $MAC_SIZE octets: size=${targetMacBytes.size}" }
    }

    /** Sender hardware address (`sha`), a copy of the six octets. */
    val senderMac: ByteArray get() = senderMacBytes.copyOf()

    /** Target hardware address (`tha`), a copy of the six octets; all zero in a who-has request. */
    val targetMac: ByteArray get() = targetMacBytes.copyOf()

    /** The 28-octet on-wire form. */
    fun encode(): ByteArray {
        val wire = ByteArray(WIRE_SIZE)
        wire.writeU16(HTYPE_OFFSET, hardwareType)
        wire.writeU16(PTYPE_OFFSET, protocolType)
        wire.writeU8(HLEN_OFFSET, hardwareLength)
        wire.writeU8(PLEN_OFFSET, protocolLength)
        wire.writeU16(OPER_OFFSET, operation)
        senderMacBytes.copyInto(wire, SHA_OFFSET)
        senderIp.toByteArray().copyInto(wire, SPA_OFFSET)
        targetMacBytes.copyInto(wire, THA_OFFSET)
        targetIp.toByteArray().copyInto(wire, TPA_OFFSET)
        return wire
    }

    override fun equals(other: Any?): Boolean =
        other is ArpPacket &&
            operation == other.operation &&
            hardwareType == other.hardwareType &&
            protocolType == other.protocolType &&
            hardwareLength == other.hardwareLength &&
            protocolLength == other.protocolLength &&
            senderIp == other.senderIp &&
            targetIp == other.targetIp &&
            senderMacBytes.contentEquals(other.senderMacBytes) &&
            targetMacBytes.contentEquals(other.targetMacBytes)

    override fun hashCode(): Int {
        var result = operation
        result = HASH_PRIME * result + senderIp.hashCode()
        result = HASH_PRIME * result + targetIp.hashCode()
        result = HASH_PRIME * result + senderMacBytes.contentHashCode()
        result = HASH_PRIME * result + targetMacBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ArpPacket(operation=$operation, senderMac=${senderMacBytes.macText()}, senderIp=$senderIp, " +
            "targetMac=${targetMacBytes.macText()}, targetIp=$targetIp)"

    companion object {
        /** Hardware type 1: Ethernet (also used for 802.11 data frames carrying LLC/SNAP). */
        const val HARDWARE_TYPE_ETHERNET = 1

        /** Size of an Ethernet/IPv4 ARP packet on the wire. */
        const val WIRE_SIZE = 28

        private const val MAC_SIZE = 6
        private const val HASH_PRIME = 31
        private const val HTYPE_OFFSET = 0
        private const val PTYPE_OFFSET = 2
        private const val HLEN_OFFSET = 4
        private const val PLEN_OFFSET = 5
        private const val OPER_OFFSET = 6
        private const val SHA_OFFSET = 8
        private const val SPA_OFFSET = 14
        private const val THA_OFFSET = 18
        private const val TPA_OFFSET = 24

        /**
         * Parses an Ethernet/IPv4 ARP packet from the start of [bytes]. Trailing link padding is
         * ignored. Returns null when the packet is shorter than [WIRE_SIZE] or is not
         * hardware type 1 / protocol type IPv4 / lengths 6 and 4, because this stack speaks nothing else.
         */
        fun decode(bytes: ByteArray): ArpPacket? {
            if (bytes.size < WIRE_SIZE) return null
            val hardwareType = bytes.readU16(HTYPE_OFFSET)
            val protocolType = bytes.readU16(PTYPE_OFFSET)
            val hardwareLength = bytes.readU8(HLEN_OFFSET)
            val protocolLength = bytes.readU8(PLEN_OFFSET)
            val supported = hardwareType == HARDWARE_TYPE_ETHERNET &&
                protocolType == EtherType.IPV4 &&
                hardwareLength == MAC_SIZE &&
                protocolLength == Ipv4Address.SIZE
            if (!supported) return null
            return ArpPacket(
                operation = bytes.readU16(OPER_OFFSET),
                senderMac = bytes.copyOfRange(SHA_OFFSET, SHA_OFFSET + MAC_SIZE),
                senderIp = Ipv4Address.fromBytes(bytes, SPA_OFFSET),
                targetMac = bytes.copyOfRange(THA_OFFSET, THA_OFFSET + MAC_SIZE),
                targetIp = Ipv4Address.fromBytes(bytes, TPA_OFFSET),
                hardwareType = hardwareType,
                protocolType = protocolType,
                hardwareLength = hardwareLength,
                protocolLength = protocolLength,
            )
        }
    }
}

/**
 * The ARP responder: answers "who has our IP address?" and nothing else.
 *
 * Business logic: the link is a dead end with one neighbour (the kart), so no ARP cache and no
 * outgoing requests are needed. The kart resolves our address (169.254.98.65) once; [handle] builds
 * the reply that carries our hardware address.
 */
object Arp {
    /** `oper` 1: a who-has request. */
    const val OPERATION_REQUEST = 1

    /** `oper` 2: an is-at reply. */
    const val OPERATION_REPLY = 2

    /**
     * Returns the reply to [request] when it is an Ethernet/IPv4 who-has for [ourIp], otherwise null
     * (a request for another host, or any reply, is never answered).
     *
     * The reply swaps the roles per RFC 826: sender = ([ourMac], [ourIp]), target = the requester's
     * hardware and protocol addresses.
     *
     * @param ourMac our six-octet hardware address.
     * @param ourIp the IPv4 address we answer for.
     */
    fun handle(request: ArpPacket, ourMac: ByteArray, ourIp: Ipv4Address): ArpPacket? {
        val isIpv4OverEthernet = request.hardwareType == ArpPacket.HARDWARE_TYPE_ETHERNET &&
            request.protocolType == EtherType.IPV4
        if (!isIpv4OverEthernet || request.operation != OPERATION_REQUEST || request.targetIp != ourIp) return null
        return ArpPacket(
            operation = OPERATION_REPLY,
            senderMac = ourMac,
            senderIp = ourIp,
            targetMac = request.senderMac,
            targetIp = request.senderIp,
        )
    }
}

/** Lower-case colon-separated hex of a hardware address, for logs and [ArpPacket.toString]. */
internal fun ByteArray.macText(): String =
    joinToString(":") { (it.toInt() and 0xFF).toString(HEX_RADIX).padStart(2, '0') }

private const val HEX_RADIX = 16
