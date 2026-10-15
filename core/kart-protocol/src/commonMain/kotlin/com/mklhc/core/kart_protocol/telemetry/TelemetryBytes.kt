package com.mklhc.core.kart_protocol.telemetry

/** The unsigned octet at [offset]. */
internal fun ByteArray.u8At(offset: Int): Int = this[offset].toInt() and 0xFF

/** The unsigned little-endian 16-bit value at [offset]. */
internal fun ByteArray.u16LeAt(offset: Int): Int = u8At(offset) or (u8At(offset + 1) shl 8)

/** The signed little-endian 16-bit value at [offset], sign-extended to an [Int]. */
internal fun ByteArray.s16LeAt(offset: Int): Int = u16LeAt(offset).toShort().toInt()

/** The signed little-endian 32-bit value at [offset]. */
internal fun ByteArray.s32LeAt(offset: Int): Int =
    u8At(offset) or (u8At(offset + 1) shl 8) or (u8At(offset + 2) shl 16) or (u8At(offset + 3) shl 24)

/** The unsigned little-endian 32-bit value at [offset], as a [Long] (0..4_294_967_295). */
internal fun ByteArray.u32LeAt(offset: Int): Long = s32LeAt(offset).toLong() and 0xFFFFFFFFL

/** Three consecutive signed little-endian 32-bit values starting at [offset], as (x, y, z). */
internal fun ByteArray.vector3S32At(offset: Int): Vector3Int =
    Vector3Int(s32LeAt(offset), s32LeAt(offset + 4), s32LeAt(offset + 8))

/** Three consecutive signed little-endian 16-bit values starting at [offset], as (x, y, z). */
internal fun ByteArray.vector3S16At(offset: Int): Vector3Int =
    Vector3Int(s16LeAt(offset), s16LeAt(offset + 2), s16LeAt(offset + 4))
