package com.mklhc.core.netstack

/*
 * Big-endian ("network byte order") accessors shared by the ARP, IPv4, UDP and DHCP codecs.
 *
 * Business logic: every protocol in this module (RFC 826, RFC 791, RFC 768, RFC 2131) transmits
 * multi-octet fields most significant octet first. Kotlin bytes are signed, so each read masks to
 * an unsigned value; these helpers keep that detail out of the codecs. Original code.
 */

private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

/** The unsigned octet at [index] (0..255). */
internal fun ByteArray.readU8(index: Int): Int = this[index].toInt() and BYTE_MASK

/** The unsigned big-endian 16-bit value at [index] (0..65535). */
internal fun ByteArray.readU16(index: Int): Int = (readU8(index) shl BITS_PER_BYTE) or readU8(index + 1)

/** The big-endian 32-bit value at [index]; values above 0x7fffffff come back negative (bit pattern preserved). */
internal fun ByteArray.readI32(index: Int): Int = (readU16(index) shl (2 * BITS_PER_BYTE)) or readU16(index + 2)

/** Stores the low 8 bits of [value] at [index]. */
internal fun ByteArray.writeU8(index: Int, value: Int) {
    this[index] = value.toByte()
}

/** Stores the low 16 bits of [value] big-endian at [index]. */
internal fun ByteArray.writeU16(index: Int, value: Int) {
    writeU8(index, value shr BITS_PER_BYTE)
    writeU8(index + 1, value)
}

/** Stores the 32 bits of [value] big-endian at [index]. */
internal fun ByteArray.writeI32(index: Int, value: Int) {
    writeU16(index, value ushr (2 * BITS_PER_BYTE))
    writeU16(index + 2, value)
}
