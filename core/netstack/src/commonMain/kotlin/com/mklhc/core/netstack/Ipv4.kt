package com.mklhc.core.netstack

import co.touchlab.kermit.Logger

/*
 * IPv4 header codec, Internet checksum and the receive-side fragment policy.
 *
 * Implemented from RFC 791 ("Internet Protocol") and RFC 1071 ("Computing the Internet Checksum").
 * Original code; no third-party source was consulted or copied. The header is version/IHL (1), type
 * of service (1), total length (2), identification (2), flags and fragment offset (2), TTL (1),
 * protocol (1), header checksum (2), source (4), destination (4), then up to 40 octets of options,
 * all big-endian. It travels in the link payload with EtherType 0x0800 (see [EtherType.IPV4]).
 */

/**
 * The 16-bit one's complement Internet checksum of RFC 1071, shared by the IPv4 header and the UDP
 * pseudo-header checksums.
 *
 * Business logic: the sum is taken over big-endian 16-bit words (an odd trailing octet is padded
 * with a zero octet), carries are folded back in, and the result is complemented. A block that
 * already carries its checksum therefore sums to zero, which is how receivers verify.
 */
object InternetChecksum {
    private const val WORD_MASK = 0xFFFF
    private const val WORD_BITS = 16

    /** The checksum (0..0xFFFF) of [length] octets of [data] starting at [offset]. */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int =
        finish(partialSum(data, offset, length))

    /**
     * Adds the 16-bit words of [length] octets of [data] at [offset] to [initial] without folding or
     * complementing, so a checksum can be built from several pieces (pseudo-header + segment). Every
     * piece except the last must have an even length.
     */
    fun partialSum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, initial: Long = 0L): Long {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range out of bounds: offset=$offset length=$length size=${data.size}"
        }
        var sum = initial
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += data.readU16(index)
            index += 2
        }
        if (index < end) sum += data.readU8(index) shl Byte.SIZE_BITS // odd trailing octet, zero padded
        return sum
    }

    /** Folds the carries of [sum] into 16 bits and complements it. */
    fun finish(sum: Long): Int {
        var folded = sum
        while (folded ushr WORD_BITS != 0L) {
            folded = (folded and WORD_MASK.toLong()) + (folded ushr WORD_BITS)
        }
        return folded.toInt().inv() and WORD_MASK
    }
}

/**
 * One IPv4 packet: header fields plus payload.
 *
 * Business logic: it is the network-layer unit between the link payload (EtherType 0x0800) and the
 * transport (UDP now, TCP in a later phase). [encode] fills in version, IHL, total length and the
 * header checksum; [parse] validates them. The "don't fragment" bit is carried and honoured: this
 * stack never fragments what it sends, and built packets set DF by default. Received fragments
 * (more-fragments set or a non-zero offset) are parsed but flagged by [isFragment]; they are never
 * reassembled (see [Ipv4Receiver] for the drop policy).
 *
 * @param source the sender's address.
 * @param destination the receiver's address.
 * @param protocol the IANA protocol number of the payload ([PROTOCOL_UDP], ...).
 * @param payload the transport-layer bytes (a copy is kept).
 * @param identification the 16-bit identification field.
 * @param dontFragment the DF flag.
 * @param moreFragments the MF flag.
 * @param fragmentOffset the 13-bit fragment offset, in 8-octet units.
 * @param ttl the time to live (0..255).
 * @param typeOfService the former type-of-service / DSCP+ECN octet.
 * @param options the raw header options (a multiple of four octets, at most 40; a copy is kept).
 */
class Ipv4Packet(
    val source: Ipv4Address,
    val destination: Ipv4Address,
    val protocol: Int,
    payload: ByteArray,
    val identification: Int = 0,
    val dontFragment: Boolean = true,
    val moreFragments: Boolean = false,
    val fragmentOffset: Int = 0,
    val ttl: Int = DEFAULT_TTL,
    val typeOfService: Int = 0,
    options: ByteArray = ByteArray(0),
) {
    private val payloadBytes: ByteArray = payload.copyOf()
    private val optionBytes: ByteArray = options.copyOf()

    init {
        require(optionBytes.size % WORD_BYTES == 0 && optionBytes.size <= MAX_OPTIONS_BYTES) {
            "options must be a multiple of 4 octets and at most $MAX_OPTIONS_BYTES: size=${optionBytes.size}"
        }
        require(fragmentOffset in 0..MAX_FRAGMENT_OFFSET) { "fragment offset must fit 13 bits: $fragmentOffset" }
    }

    /** The transport-layer bytes after the header, a copy. */
    val payload: ByteArray get() = payloadBytes.copyOf()

    /** The raw header options, a copy; empty for the common 20-octet header. */
    val options: ByteArray get() = optionBytes.copyOf()

    /** True when this packet is a fragment of a larger datagram (MF set or offset non-zero). */
    val isFragment: Boolean get() = moreFragments || fragmentOffset != 0

    /** Header length in octets (20 plus the options). */
    val headerLength: Int get() = MIN_HEADER_BYTES + optionBytes.size

    /**
     * The on-wire form with a freshly computed header checksum.
     *
     * @throws IllegalArgumentException if the packet would exceed the 65535-octet total length limit
     * or a field does not fit its width.
     */
    fun encode(): ByteArray {
        val totalLength = headerLength + payloadBytes.size
        require(totalLength <= MAX_TOTAL_LENGTH) { "IPv4 packet too large: totalLength=$totalLength" }
        require(identification in 0..MAX_U16 && ttl in 0..MAX_U8 && protocol in 0..MAX_U8 && typeOfService in 0..MAX_U8) {
            "header field out of range: id=$identification ttl=$ttl protocol=$protocol tos=$typeOfService"
        }
        val wire = ByteArray(totalLength)
        wire.writeU8(VERSION_IHL_OFFSET, (VERSION shl NIBBLE_BITS) or (headerLength / WORD_BYTES))
        wire.writeU8(TOS_OFFSET, typeOfService)
        wire.writeU16(TOTAL_LENGTH_OFFSET, totalLength)
        wire.writeU16(ID_OFFSET, identification)
        var flagsAndOffset = fragmentOffset
        if (dontFragment) flagsAndOffset = flagsAndOffset or FLAG_DF
        if (moreFragments) flagsAndOffset = flagsAndOffset or FLAG_MF
        wire.writeU16(FLAGS_OFFSET, flagsAndOffset)
        wire.writeU8(TTL_OFFSET, ttl)
        wire.writeU8(PROTOCOL_OFFSET, protocol)
        source.toByteArray().copyInto(wire, SOURCE_OFFSET)
        destination.toByteArray().copyInto(wire, DESTINATION_OFFSET)
        optionBytes.copyInto(wire, MIN_HEADER_BYTES)
        wire.writeU16(CHECKSUM_OFFSET, InternetChecksum.compute(wire, 0, headerLength))
        payloadBytes.copyInto(wire, headerLength)
        return wire
    }

    companion object {
        /** Protocol number of ICMP. */
        const val PROTOCOL_ICMP = 1

        /** Protocol number of TCP (carried by a later phase). */
        const val PROTOCOL_TCP = 6

        /** Protocol number of UDP. */
        const val PROTOCOL_UDP = 17

        /** The TTL used for packets this stack originates. */
        const val DEFAULT_TTL = 64

        private const val VERSION = 4
        private const val NIBBLE_BITS = 4
        private const val NIBBLE_MASK = 0x0F
        private const val WORD_BYTES = 4
        private const val MIN_HEADER_BYTES = 20
        private const val MAX_OPTIONS_BYTES = 40
        private const val MAX_TOTAL_LENGTH = 0xFFFF
        private const val MAX_U16 = 0xFFFF
        private const val MAX_U8 = 0xFF
        private const val MAX_FRAGMENT_OFFSET = 0x1FFF
        private const val FLAG_DF = 0x4000
        private const val FLAG_MF = 0x2000
        private const val VERSION_IHL_OFFSET = 0
        private const val TOS_OFFSET = 1
        private const val TOTAL_LENGTH_OFFSET = 2
        private const val ID_OFFSET = 4
        private const val FLAGS_OFFSET = 6
        private const val TTL_OFFSET = 8
        private const val PROTOCOL_OFFSET = 9
        private const val CHECKSUM_OFFSET = 10
        private const val SOURCE_OFFSET = 12
        private const val DESTINATION_OFFSET = 16

        /**
         * True when [bytes] holds a complete IPv4 header whose checksum verifies, that is, the
         * one's complement sum over the IHL-sized header is zero. Does not look at the payload.
         */
        fun hasValidHeaderChecksum(bytes: ByteArray): Boolean {
            if (bytes.size < MIN_HEADER_BYTES) return false
            val headerLength = (bytes.readU8(VERSION_IHL_OFFSET) and NIBBLE_MASK) * WORD_BYTES
            if (headerLength < MIN_HEADER_BYTES || headerLength > bytes.size) return false
            return InternetChecksum.compute(bytes, 0, headerLength) == 0
        }

        /**
         * Parses an IPv4 packet from the start of [bytes]; link padding beyond the total length is
         * trimmed. Returns null when the packet is shorter than its headers claim, is not version 4,
         * has an IHL below 5, a total length that is smaller than the header or larger than [bytes],
         * or a header checksum that does not verify. A fragment parses normally (see [isFragment]).
         */
        fun parse(bytes: ByteArray): Ipv4Packet? {
            if (bytes.size < MIN_HEADER_BYTES) return null
            val versionAndIhl = bytes.readU8(VERSION_IHL_OFFSET)
            if (versionAndIhl ushr NIBBLE_BITS != VERSION) return null
            val headerLength = (versionAndIhl and NIBBLE_MASK) * WORD_BYTES
            if (headerLength < MIN_HEADER_BYTES || headerLength > bytes.size) return null
            val totalLength = bytes.readU16(TOTAL_LENGTH_OFFSET)
            if (totalLength < headerLength || totalLength > bytes.size) return null
            if (InternetChecksum.compute(bytes, 0, headerLength) != 0) return null
            val flagsAndOffset = bytes.readU16(FLAGS_OFFSET)
            return Ipv4Packet(
                source = Ipv4Address.fromBytes(bytes, SOURCE_OFFSET),
                destination = Ipv4Address.fromBytes(bytes, DESTINATION_OFFSET),
                protocol = bytes.readU8(PROTOCOL_OFFSET),
                payload = bytes.copyOfRange(headerLength, totalLength),
                identification = bytes.readU16(ID_OFFSET),
                dontFragment = flagsAndOffset and FLAG_DF != 0,
                moreFragments = flagsAndOffset and FLAG_MF != 0,
                fragmentOffset = flagsAndOffset and MAX_FRAGMENT_OFFSET,
                ttl = bytes.readU8(TTL_OFFSET),
                typeOfService = bytes.readU8(TOS_OFFSET),
                options = bytes.copyOfRange(MIN_HEADER_BYTES, headerLength),
            )
        }
    }
}

/**
 * The receive side of IPv4: validates inbound packets and applies the drop policy.
 *
 * Business logic: the kart link is a dead end that never needs fragmentation, so reassembly is out
 * of scope. A fragment (more-fragments set or a non-zero fragment offset) is counted in
 * [fragmentsDropped] and dropped with a warning; a malformed packet (truncated, wrong version, bad
 * header checksum) is counted in [malformedDropped] and dropped. Whole packets are returned as
 * parsed; address filtering (ours / broadcast) is left to the caller because a DHCP client's
 * packets arrive from 0.0.0.0 to 255.255.255.255.
 *
 * Not thread-safe: drive it from a single coroutine / thread.
 */
class Ipv4Receiver {
    private val log = Logger.withTag(TAG)

    /** How many fragments have been dropped so far. */
    var fragmentsDropped: Int = 0
        private set

    /** How many malformed packets have been dropped so far. */
    var malformedDropped: Int = 0
        private set

    /** Returns the whole packet in [bytes], or null after counting and logging why it was dropped. */
    fun receive(bytes: ByteArray): Ipv4Packet? {
        val packet = Ipv4Packet.parse(bytes)
        if (packet == null) {
            malformedDropped++
            log.w {
                "receive(): dropping malformed IPv4 packet -> size=${bytes.size} " +
                    "headerChecksumValid=${Ipv4Packet.hasValidHeaderChecksum(bytes)} malformedDropped=$malformedDropped"
            }
            return null
        }
        if (packet.isFragment) {
            fragmentsDropped++
            log.w {
                "receive(): dropping IPv4 fragment, reassembly is not supported -> id=${packet.identification} " +
                    "moreFragments=${packet.moreFragments} fragmentOffset=${packet.fragmentOffset} " +
                    "source=${packet.source} fragmentsDropped=$fragmentsDropped"
            }
            return null
        }
        return packet
    }

    private companion object {
        const val TAG = "Ipv4Receiver"
    }
}
