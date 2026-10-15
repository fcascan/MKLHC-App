// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.fuji

import com.mklhc.core.kart_protocol.rcd.RcdProtocolException

/**
 * The kart's `product_code` parameter: which character kart this is, and its serial number.
 *
 * Business logic: the value is the little-endian struct `<HHB` -- two unknown fields around the
 * character id -- followed by the serial as a NUL-terminated latin1 string. Character 1 and 2 are the
 * two known karts; other ids are unknown. The app shows [character] and [serial] as kart information.
 *
 * @property unk1 an unknown 16-bit field.
 * @property character the character id (1 and 2 are known, the rest unknown).
 * @property unk2 an unknown 8-bit field.
 * @property serial the kart serial number text.
 */
data class FujiProductCode(
    val unk1: Int,
    val character: Int,
    val unk2: Int,
    val serial: String,
) {
    companion object {
        /** Octets of the `<HHB` header that precede the serial. */
        private const val HEADER_SIZE = 5

        /**
         * Decodes a `product_code` parameter value.
         *
         * @throws RcdProtocolException if [data] is shorter than the 5-octet header.
         */
        fun decode(data: ByteArray): FujiProductCode {
            if (data.size < HEADER_SIZE) {
                throw RcdProtocolException("product code of ${data.size} octets is shorter than $HEADER_SIZE")
            }
            return FujiProductCode(
                unk1 = data.u16LittleEndianAt(0),
                character = data.u16LittleEndianAt(2),
                unk2 = data.u8At(4),
                serial = data.latin1UntilNul(from = HEADER_SIZE),
            )
        }
    }
}
