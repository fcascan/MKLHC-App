package com.mklhc.core.netstack.tcp

import com.mklhc.core.netstack.InternetChecksum
import com.mklhc.core.netstack.Ipv4Address
import com.mklhc.core.netstack.Ipv4Packet
import com.mklhc.core.netstack.readU16
import com.mklhc.core.netstack.readU8
import com.mklhc.core.netstack.writeI32
import com.mklhc.core.netstack.writeU16
import com.mklhc.core.netstack.writeU8

/*
 * TCP segment codec: header, flags, the MSS option and the IPv4 pseudo-header checksum.
 *
 * Implemented from RFC 9293 ("Transmission Control Protocol", which obsoletes RFC 793) and the
 * checksum arithmetic of RFC 1071. Original code; no third-party source was consulted or copied. The
 * header is source port (2), destination port (2), sequence number (4), acknowledgment number (4),
 * data offset + reserved + flags (2), window (2), checksum (2) and urgent pointer (2), all
 * big-endian, followed by up to 40 octets of options padded to a 32-bit boundary, then the payload.
 * It rides in an IPv4 packet with protocol number 6 (see [Ipv4Packet.PROTOCOL_TCP]).
 *
 * Only the MSS option (kind 2) is produced; on receive it is parsed and every other option is
 * skipped (NOP is kind 1, end-of-list is kind 0). Unlike UDP, the TCP checksum is mandatory and a
 * computed value of zero is transmitted unchanged.
 */

/**
 * One TCP segment: the header fields, the optional maximum segment size, and the payload.
 *
 * Business logic: this is the transport unit of the kart's reliable RCD channels. [encode] fills in
 * the data offset and the checksum over the IPv4 pseudo-header; [parse] validates the data offset
 * and, when the caller supplies the IPv4 addresses, the checksum. Sequence and acknowledgment
 * numbers are 32-bit unsigned values held in a [Long] in `0..0xFFFFFFFF`; the connection layer does
 * the modular ("serial number") arithmetic on them.
 *
 * @param sourcePort the sender's 16-bit port.
 * @param destinationPort the receiver's 16-bit port.
 * @param sequenceNumber the 32-bit sequence number (0..0xFFFFFFFF).
 * @param acknowledgmentNumber the 32-bit acknowledgment number, meaningful when [ACK] is set.
 * @param flags the control bits, a bitmask of [FIN], [SYN], [RST], [PSH], [ACK] and [URG].
 * @param window the advertised receive window in octets (0..65535).
 * @param payload the application bytes (a copy is kept).
 * @param maxSegmentSize the MSS option value to emit (only on a SYN), or null for no option.
 * @param urgentPointer the urgent pointer, meaningful when [URG] is set.
 * @param checksum the checksum field as parsed from the wire; ignored by [encode], which recomputes it.
 */
class TcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    val flags: Int,
    val window: Int,
    payload: ByteArray = ByteArray(0),
    val maxSegmentSize: Int? = null,
    val urgentPointer: Int = 0,
    val checksum: Int = 0,
) {
    private val payloadBytes: ByteArray = payload.copyOf()

    init {
        require(sourcePort in 0..MAX_U16 && destinationPort in 0..MAX_U16) {
            "ports must fit 16 bits: sourcePort=$sourcePort destinationPort=$destinationPort"
        }
        require(sequenceNumber in 0..MAX_U32 && acknowledgmentNumber in 0..MAX_U32) {
            "sequence numbers must fit 32 bits: seq=$sequenceNumber ack=$acknowledgmentNumber"
        }
        require(window in 0..MAX_U16) { "window must fit 16 bits: window=$window" }
        require(maxSegmentSize == null || maxSegmentSize in 0..MAX_U16) { "MSS must fit 16 bits: maxSegmentSize=$maxSegmentSize" }
    }

    /** The application bytes, a copy. */
    val payload: ByteArray get() = payloadBytes.copyOf()

    /** True when the FIN control bit is set. */
    val isFin: Boolean get() = flags and FIN != 0

    /** True when the SYN control bit is set. */
    val isSyn: Boolean get() = flags and SYN != 0

    /** True when the RST control bit is set. */
    val isRst: Boolean get() = flags and RST != 0

    /** True when the PSH control bit is set. */
    val isPsh: Boolean get() = flags and PSH != 0

    /** True when the ACK control bit is set. */
    val isAck: Boolean get() = flags and ACK != 0

    /** True when the URG control bit is set. */
    val isUrg: Boolean get() = flags and URG != 0

    /**
     * The length this segment occupies in sequence space: the payload size plus one for a SYN and one
     * for a FIN (RFC 9293 section 3.4). This is how far [acknowledgmentNumber] advances for it.
     */
    val sequenceLength: Int get() = payloadBytes.size + (if (isSyn) 1 else 0) + (if (isFin) 1 else 0)

    private val optionBytes: ByteArray
        get() = if (maxSegmentSize == null) {
            ByteArray(0)
        } else {
            byteArrayOf(OPTION_MSS.toByte(), OPTION_MSS_LENGTH.toByte(), (maxSegmentSize ushr Byte.SIZE_BITS).toByte(), maxSegmentSize.toByte())
        }

    /**
     * The on-wire form: header, options and payload, with a freshly computed checksum over the IPv4
     * pseudo-header built from [source] and [destination].
     */
    fun encode(source: Ipv4Address, destination: Ipv4Address): ByteArray {
        val options = optionBytes
        val headerLength = MIN_HEADER_BYTES + options.size
        val wire = ByteArray(headerLength + payloadBytes.size)
        wire.writeU16(SOURCE_PORT_OFFSET, sourcePort)
        wire.writeU16(DESTINATION_PORT_OFFSET, destinationPort)
        wire.writeI32(SEQUENCE_OFFSET, sequenceNumber.toInt())
        wire.writeI32(ACK_OFFSET, acknowledgmentNumber.toInt())
        wire.writeU8(DATA_OFFSET_OFFSET, (headerLength / WORD_BYTES) shl DATA_OFFSET_SHIFT)
        wire.writeU8(FLAGS_OFFSET, flags and ALL_FLAGS)
        wire.writeU16(WINDOW_OFFSET, window)
        wire.writeU16(URGENT_OFFSET, urgentPointer)
        options.copyInto(wire, MIN_HEADER_BYTES)
        payloadBytes.copyInto(wire, headerLength)
        wire.writeU16(CHECKSUM_OFFSET, checksumFor(source, destination, wire))
        return wire
    }

    companion object {
        /** FIN control bit: no more data from the sender (RFC 9293). */
        const val FIN = 0x01

        /** SYN control bit: synchronize sequence numbers. */
        const val SYN = 0x02

        /** RST control bit: reset the connection. */
        const val RST = 0x04

        /** PSH control bit: push buffered data to the application. */
        const val PSH = 0x08

        /** ACK control bit: the acknowledgment number is significant. */
        const val ACK = 0x10

        /** URG control bit: the urgent pointer is significant. */
        const val URG = 0x20

        private const val ALL_FLAGS = 0x3F
        private const val MAX_U16 = 0xFFFF
        private const val MAX_U32 = 0xFFFFFFFFL
        private const val MIN_HEADER_BYTES = 20
        private const val MAX_HEADER_BYTES = 60
        private const val WORD_BYTES = 4
        private const val MIN_DATA_OFFSET_WORDS = 5
        private const val DATA_OFFSET_SHIFT = 4
        private const val DATA_OFFSET_MASK = 0xF0
        private const val PSEUDO_HEADER_BYTES = 12
        private const val OPTION_END = 0
        private const val OPTION_NOP = 1
        private const val OPTION_MSS = 2
        private const val OPTION_MSS_LENGTH = 4
        private const val MIN_OPTION_LENGTH = 2
        private const val SOURCE_PORT_OFFSET = 0
        private const val DESTINATION_PORT_OFFSET = 2
        private const val SEQUENCE_OFFSET = 4
        private const val ACK_OFFSET = 8
        private const val DATA_OFFSET_OFFSET = 12
        private const val FLAGS_OFFSET = 13
        private const val WINDOW_OFFSET = 14
        private const val CHECKSUM_OFFSET = 16
        private const val URGENT_OFFSET = 18

        /**
         * The TCP checksum of [segment] (header with the checksum field set to zero, then options and
         * payload) over the IPv4 pseudo-header built from [source] and [destination]: source (4),
         * destination (4), a zero octet, protocol 6 and the TCP length (2). A computed value of 0 is
         * transmitted unchanged, as RFC 9293 requires.
         */
        fun checksumFor(source: Ipv4Address, destination: Ipv4Address, segment: ByteArray): Int =
            InternetChecksum.finish(
                InternetChecksum.partialSum(segment, initial = pseudoHeaderSum(source, destination, segment.size)),
            )

        /**
         * True when the checksum field of [segment] verifies against the pseudo-header from [source]
         * and [destination]: the one's complement sum over the pseudo-header and the whole segment,
         * field included, is all ones.
         */
        fun hasValidChecksum(segment: ByteArray, source: Ipv4Address, destination: Ipv4Address): Boolean {
            if (segment.size < MIN_HEADER_BYTES) return false
            val sum = InternetChecksum.partialSum(segment, initial = pseudoHeaderSum(source, destination, segment.size))
            return InternetChecksum.finish(sum) == 0
        }

        /**
         * Parses a TCP segment from the start of [bytes]. Returns null when the buffer is shorter than
         * the 20-octet header or than the data offset it declares, when the data offset is below five
         * 32-bit words, or when [source] and [destination] are given and the checksum does not verify.
         * Unknown options are skipped; only the MSS option is interpreted. Without both addresses the
         * checksum check is skipped.
         */
        fun parse(bytes: ByteArray, source: Ipv4Address? = null, destination: Ipv4Address? = null): TcpSegment? {
            if (bytes.size < MIN_HEADER_BYTES) return null
            val dataOffsetWords = (bytes.readU8(DATA_OFFSET_OFFSET) and DATA_OFFSET_MASK) ushr DATA_OFFSET_SHIFT
            if (dataOffsetWords < MIN_DATA_OFFSET_WORDS) return null
            val headerLength = dataOffsetWords * WORD_BYTES
            if (headerLength > bytes.size || headerLength > MAX_HEADER_BYTES) return null
            if (source != null && destination != null && !hasValidChecksum(bytes, source, destination)) return null
            return TcpSegment(
                sourcePort = bytes.readU16(SOURCE_PORT_OFFSET),
                destinationPort = bytes.readU16(DESTINATION_PORT_OFFSET),
                sequenceNumber = bytes.readU16(SEQUENCE_OFFSET).toLong() shl (2 * Byte.SIZE_BITS) or bytes.readU16(SEQUENCE_OFFSET + 2).toLong(),
                acknowledgmentNumber = bytes.readU16(ACK_OFFSET).toLong() shl (2 * Byte.SIZE_BITS) or bytes.readU16(ACK_OFFSET + 2).toLong(),
                flags = bytes.readU8(FLAGS_OFFSET) and ALL_FLAGS,
                window = bytes.readU16(WINDOW_OFFSET),
                payload = bytes.copyOfRange(headerLength, bytes.size),
                maxSegmentSize = parseMss(bytes, headerLength),
                urgentPointer = bytes.readU16(URGENT_OFFSET),
                checksum = bytes.readU16(CHECKSUM_OFFSET),
            )
        }

        /** The MSS option value in the option area of [bytes] (octets [MIN_HEADER_BYTES], [headerLength]), or null. */
        private fun parseMss(bytes: ByteArray, headerLength: Int): Int? {
            var index = MIN_HEADER_BYTES
            while (index < headerLength) {
                when (val kind = bytes.readU8(index)) {
                    OPTION_END -> return null
                    OPTION_NOP -> index++
                    else -> {
                        if (index + 1 >= headerLength) return null
                        val length = bytes.readU8(index + 1)
                        if (length < MIN_OPTION_LENGTH || index + length > headerLength) return null
                        if (kind == OPTION_MSS && length == OPTION_MSS_LENGTH) return bytes.readU16(index + 2)
                        index += length
                    }
                }
            }
            return null
        }

        /** The unfolded sum of the IPv4 pseudo-header words for a TCP segment of [tcpLength] octets. */
        private fun pseudoHeaderSum(source: Ipv4Address, destination: Ipv4Address, tcpLength: Int): Long {
            val pseudo = ByteArray(PSEUDO_HEADER_BYTES)
            source.toByteArray().copyInto(pseudo, 0)
            destination.toByteArray().copyInto(pseudo, Ipv4Address.SIZE)
            pseudo.writeU8(2 * Ipv4Address.SIZE + 1, Ipv4Packet.PROTOCOL_TCP)
            pseudo.writeU16(2 * Ipv4Address.SIZE + 2, tcpLength)
            return InternetChecksum.partialSum(pseudo)
        }
    }
}
