// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

/**
 * One RCD wire message: a fixed 16-octet header followed by an optional payload.
 *
 * Business logic: the header is the big-endian C struct `>HHIIBxxx` -- service (u16), command (u16),
 * data size (u32), status (u32), flags (u8), then three padding octets. The only flag bit defined is
 * bit 0, [isResponse]; the server sets it on every reply so a peer can tell requests from responses
 * on the shared stream. The payload is at most [MAX_DATA_SIZE] octets; the framing transport (see
 * RcdTransport) rejects anything larger rather than buffering an unbounded message.
 *
 * This is an immutable value: [RcdServer] builds a reply with [copy], it never mutates a received
 * message in place.
 */
data class RcdMsg(
    val service: Int,
    val command: Int,
    val status: Int,
    val isResponse: Boolean,
    val data: ByteArray,
) {

    /** Encodes this message to its wire form: the 16-octet header followed by [data]. */
    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + data.size)
        writeU16(out, 0, service)
        writeU16(out, 2, command)
        writeU32(out, 4, data.size)
        writeU32(out, 8, status)
        out[12] = (if (isResponse) 1 else 0).toByte()
        // Octets 13..15 are the struct's `xxx` padding and stay zero.
        data.copyInto(out, HEADER_SIZE)
        return out
    }

    // A data class over a ByteArray would compare the array by reference; compare the payload by
    // content so two messages with equal bytes are equal (the golden tests rely on this).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RcdMsg) return false
        return service == other.service &&
            command == other.command &&
            status == other.status &&
            isResponse == other.isResponse &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = service
        result = 31 * result + command
        result = 31 * result + status
        result = 31 * result + isResponse.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        /** The fixed header length in octets (`>HHIIBxxx`). */
        const val HEADER_SIZE = 16

        /** The largest payload the protocol accepts, in octets. */
        const val MAX_DATA_SIZE = 0x1000

        /**
         * The total length of the message whose header starts [buf], or [HEADER_SIZE] when [buf] is
         * too short to even hold a header. Reads the declared data size from the header; it does not
         * require the payload to be present (used to drive incremental framing).
         *
         * The declared size is a wire u32 but [readU32] returns a signed [Int]: a value with the high
         * bit set (>= 0x80000000) reads back negative. Such a length is malformed -- it can only come
         * from a corrupt or hostile peer -- so it is rejected here as a protocol error rather than
         * returned as a nonsensical (overflowing or negative) total, which downstream would turn into a
         * [NegativeArraySizeException].
         *
         * @throws RcdProtocolException if the declared data size has its high bit set (reads negative).
         */
        fun size(buf: ByteArray): Int {
            if (buf.size < HEADER_SIZE) return HEADER_SIZE
            val declared = readU32(buf, 4)
            if (declared < 0) {
                throw RcdProtocolException("declared data size reads negative ($declared); malformed header")
            }
            return HEADER_SIZE + declared
        }

        /**
         * Decodes a complete message from [buf]. [buf] must hold at least [size] octets.
         *
         * @throws IllegalArgumentException if [buf] is shorter than the message it declares.
         * @throws RcdProtocolException if the declared data size is malformed (see [size]).
         */
        fun decode(buf: ByteArray): RcdMsg {
            val total = size(buf) // rejects a malformed (negative) declared length before it is used below
            require(buf.size >= total) { "buffer too short: have ${buf.size} octets, need $total" }
            val service = readU16(buf, 0)
            val command = readU16(buf, 2)
            val dataSize = readU32(buf, 4)
            val status = readU32(buf, 8)
            val flags = buf[12].toInt() and 0xFF
            val data = buf.copyOfRange(HEADER_SIZE, HEADER_SIZE + dataSize)
            return RcdMsg(service, command, status, isResponse = (flags and 1) != 0, data)
        }

        private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value ushr 8).toByte()
            buf[offset + 1] = value.toByte()
        }

        private fun writeU32(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value ushr 24).toByte()
            buf[offset + 1] = (value ushr 16).toByte()
            buf[offset + 2] = (value ushr 8).toByte()
            buf[offset + 3] = value.toByte()
        }

        private fun readU16(buf: ByteArray, offset: Int): Int =
            ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

        private fun readU32(buf: ByteArray, offset: Int): Int =
            ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
    }
}
