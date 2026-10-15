package com.mklhc.core.kart_protocol

/**
 * Parses a hand-written hex vector such as `"7f 00 00 00"` into octets.
 *
 * Business logic: the Phase 4C vectors are written out by hand from the protocol spec, one field per
 * group of octets with a comment saying what it is; the blanks and line breaks are only for readability.
 */
internal fun hexBytes(spaced: String): ByteArray = spaced.filterNot { it.isWhitespace() }.hexToByteArray()

/** [count] zero octets as spaced hex (`"00 00 00"`), for writing the zero-filled spans of a vector. */
internal fun zeros(count: Int): String = List(count) { "00" }.joinToString(" ")
