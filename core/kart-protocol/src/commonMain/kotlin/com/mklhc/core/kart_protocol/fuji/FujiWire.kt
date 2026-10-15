// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/util.py (pad_to) and
// openkartd/fuji.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.fuji

/**
 * Zero-pads [x] on the right to exactly [len] octets, returning a new array.
 *
 * Business logic: every Fuji request is a fixed-layout record whose fields are zero-padded to a set
 * width (a parameter name to 0x80, a length prefix to 0x10, an SSID to 0x20, ...); this is the one
 * place that padding happens. The reference `pad_to` silently returns an over-long input unpadded,
 * which would produce a mis-sized request; here that is a programming error and raises instead.
 *
 * @throws IllegalArgumentException if [x] is longer than [len].
 */
fun padTo(x: ByteArray, len: Int): ByteArray {
    require(x.size <= len) { "cannot pad ${x.size} octets down to $len" }
    return x.copyOf(len)
}

/** The two octets of [value] (0..0xFFFF) most-significant first. */
internal fun u16BigEndian(value: Int): ByteArray {
    require(value in 0..0xFFFF) { "value $value does not fit in 16 bits" }
    return byteArrayOf((value ushr 8).toByte(), value.toByte())
}

/** The four octets of [value] (non-negative) most-significant first. */
internal fun u32BigEndian(value: Int): ByteArray {
    require(value >= 0) { "value $value is negative" }
    return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}

/** Writes [value] (0..0xFFFF) into this array at [offset], least-significant octet first. */
internal fun ByteArray.setU16LittleEndian(offset: Int, value: Int) {
    require(value in 0..0xFFFF) { "value $value does not fit in 16 bits" }
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

/** Writes [value] (non-negative) into this array at [offset], least-significant octet first. */
internal fun ByteArray.setU64LittleEndian(offset: Int, value: Long) {
    require(value >= 0) { "value $value is negative" }
    for (i in 0 until 8) {
        this[offset + i] = (value ushr (8 * i)).toByte()
    }
}

/** Reads the unsigned octet at [offset]. */
internal fun ByteArray.u8At(offset: Int): Int = this[offset].toInt() and 0xFF

/** Reads the unsigned little-endian 16-bit value at [offset]. */
internal fun ByteArray.u16LittleEndianAt(offset: Int): Int = u8At(offset) or (u8At(offset + 1) shl 8)

/**
 * Decodes the octets from [from] up to (not including) the first NUL as latin1 text.
 *
 * Business logic: the kart sends its SHA-1 string and serial as NUL-terminated C strings inside a
 * fixed-size field, with undefined bytes after the terminator; the reference splits at the first NUL
 * and decodes latin1, so every octet maps 1:1 to the code point of the same value. With no NUL the
 * whole remainder is the text.
 */
internal fun ByteArray.latin1UntilNul(from: Int): String {
    val text = StringBuilder()
    for (i in from until size) {
        val octet = u8At(i)
        if (octet == 0) break
        text.append(octet.toChar())
    }
    return text.toString()
}
