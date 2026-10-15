package com.mklhc.core.netstack

/*
 * UDP datagram codec with the IPv4 pseudo-header checksum.
 *
 * Implemented from RFC 768 ("User Datagram Protocol"), with the pseudo-header and checksum
 * arithmetic of RFC 1071. Original code; no third-party source was consulted or copied. The header is
 * source port (2), destination port (2), length (2) and checksum (2), all big-endian, followed by
 * the payload; the length covers the header and the payload. It rides in an IPv4 packet with
 * protocol number 17 (see [Ipv4Packet.PROTOCOL_UDP]).
 */

/**
 * One UDP datagram: ports and payload.
 *
 * Business logic: it is the transport used by DHCP (client port 68, server port 67) and, later, by
 * the kart's control and video-side channels. [encode] fills in the length and the checksum over the
 * IPv4 pseudo-header; [parse] validates the length and, when the caller supplies the IPv4 addresses
 * and the sender did not disable it, the checksum. A transmitted checksum of 0 means "no checksum"
 * and is always accepted (RFC 768); a computed checksum of 0 is sent as 0xFFFF.
 *
 * @param sourcePort the sender's 16-bit port.
 * @param destinationPort the receiver's 16-bit port.
 * @param payload the application bytes (a copy is kept).
 * @param checksum the checksum field as it was on the wire when parsed (0 = sender disabled it);
 * ignored by [encode], which always recomputes it.
 */
class UdpDatagram(
    val sourcePort: Int,
    val destinationPort: Int,
    payload: ByteArray,
    val checksum: Int = 0,
) {
    private val payloadBytes: ByteArray = payload.copyOf()

    init {
        require(sourcePort in 0..MAX_PORT && destinationPort in 0..MAX_PORT) {
            "ports must fit 16 bits: sourcePort=$sourcePort destinationPort=$destinationPort"
        }
    }

    /** The application bytes, a copy. */
    val payload: ByteArray get() = payloadBytes.copyOf()

    /**
     * The on-wire form: header plus payload.
     *
     * @param source the IPv4 source address that will carry it (part of the pseudo-header).
     * @param destination the IPv4 destination address that will carry it (part of the pseudo-header).
     * @param computeChecksum false leaves the checksum field 0 (checksum disabled); true (default)
     * computes it over the pseudo-header.
     * @throws IllegalArgumentException if the datagram would not fit the 16-bit length field.
     */
    fun encode(source: Ipv4Address, destination: Ipv4Address, computeChecksum: Boolean = true): ByteArray {
        val length = HEADER_BYTES + payloadBytes.size
        require(length <= MAX_LENGTH) { "UDP datagram too large: length=$length" }
        val wire = ByteArray(length)
        wire.writeU16(SOURCE_PORT_OFFSET, sourcePort)
        wire.writeU16(DESTINATION_PORT_OFFSET, destinationPort)
        wire.writeU16(LENGTH_OFFSET, length)
        payloadBytes.copyInto(wire, HEADER_BYTES)
        if (computeChecksum) wire.writeU16(CHECKSUM_OFFSET, checksumFor(source, destination, wire))
        return wire
    }

    companion object {
        private const val HEADER_BYTES = 8
        private const val MAX_PORT = 0xFFFF
        private const val MAX_LENGTH = 0xFFFF
        private const val SOURCE_PORT_OFFSET = 0
        private const val DESTINATION_PORT_OFFSET = 2
        private const val LENGTH_OFFSET = 4
        private const val CHECKSUM_OFFSET = 6
        private const val ALL_ONES = 0xFFFF
        private const val PSEUDO_HEADER_BYTES = 12

        /**
         * The UDP checksum of [segment] (header with the checksum field set to zero, then the
         * payload) over the IPv4 pseudo-header built from [source] and [destination]; the pseudo-header
         * is source (4), destination (4), a zero octet, protocol 17 and the UDP length (2). A
         * computed value of 0 is returned as 0xFFFF, because 0 on the wire means "disabled".
         */
        fun checksumFor(source: Ipv4Address, destination: Ipv4Address, segment: ByteArray): Int {
            val computed = InternetChecksum.finish(
                InternetChecksum.partialSum(segment, initial = pseudoHeaderSum(source, destination, segment.size)),
            )
            return if (computed == 0) ALL_ONES else computed
        }

        /**
         * True when the checksum field of [segment] verifies against the pseudo-header from
         * [source] and [destination]: the one's complement sum over the pseudo-header and the whole
         * datagram, field included, is all ones. A segment whose field is 0 is NOT valid here; a
         * caller that accepts "disabled" checks the field itself ([parse] does).
         */
        fun hasValidChecksum(segment: ByteArray, source: Ipv4Address, destination: Ipv4Address): Boolean {
            val sum = InternetChecksum.partialSum(segment, initial = pseudoHeaderSum(source, destination, segment.size))
            return InternetChecksum.finish(sum) == 0
        }

        /**
         * Parses a UDP datagram from the start of [bytes]; IPv4 padding beyond the UDP length is
         * trimmed. Returns null when the datagram is shorter than the header, its length field is
         * below 8 or larger than [bytes], or the checksum is present, [source] and [destination] were
         * given, and the checksum does not verify. A checksum field of 0 is always accepted. Without
         * both addresses the (optional) checksum check is skipped.
         */
        fun parse(bytes: ByteArray, source: Ipv4Address? = null, destination: Ipv4Address? = null): UdpDatagram? {
            if (bytes.size < HEADER_BYTES) return null
            val length = bytes.readU16(LENGTH_OFFSET)
            if (length < HEADER_BYTES || length > bytes.size) return null
            val wireChecksum = bytes.readU16(CHECKSUM_OFFSET)
            if (wireChecksum != 0 && source != null && destination != null) {
                if (!hasValidChecksum(bytes.copyOf(length), source, destination)) return null
            }
            return UdpDatagram(
                sourcePort = bytes.readU16(SOURCE_PORT_OFFSET),
                destinationPort = bytes.readU16(DESTINATION_PORT_OFFSET),
                payload = bytes.copyOfRange(HEADER_BYTES, length),
                checksum = wireChecksum,
            )
        }

        /** The unfolded sum of the IPv4 pseudo-header words for a UDP segment of [udpLength] octets. */
        private fun pseudoHeaderSum(source: Ipv4Address, destination: Ipv4Address, udpLength: Int): Long {
            val pseudo = ByteArray(PSEUDO_HEADER_BYTES)
            source.toByteArray().copyInto(pseudo, 0)
            destination.toByteArray().copyInto(pseudo, Ipv4Address.SIZE)
            pseudo.writeU8(2 * Ipv4Address.SIZE + 1, Ipv4Packet.PROTOCOL_UDP)
            pseudo.writeU16(2 * Ipv4Address.SIZE + 2, udpLength)
            return InternetChecksum.partialSum(pseudo)
        }
    }
}
