// Layout per a community protocol wiki (Fuji kart protocol); format spec, no code copied.
package com.mklhc.core.kart_protocol.telemetry

/**
 * The flags octet of an IMU packet, bit layout `IGA??NNN` (most-significant bit first).
 *
 * Business logic: three bits pick the precision of the packet's units, two bits are unknown and the low
 * three bits say how many sensor samples follow the header. [raw] is the single source of truth and every
 * accessor decodes from it, so no bit is lost.
 *
 * @property raw the flags octet as an unsigned value (0..255).
 */
data class ImuFlags(val raw: Int) {
    /** `I` (bit 7): high-precision position units for the integrator positions. */
    val highPrecisionPosition: Boolean get() = (raw and 0x80) != 0

    /** `G` (bit 6): high-precision gyroscope scale (otherwise the coarse one). */
    val highPrecisionGyro: Boolean get() = (raw and 0x40) != 0

    /** `A` (bit 5): high-precision accelerometer scale (otherwise the coarse one). */
    val highPrecisionAccel: Boolean get() = (raw and 0x20) != 0

    /** `??` (bits 4 and 3): two bits of unknown meaning, 0..3, exposed untouched. */
    val unknownBits: Int get() = (raw shr 3) and 0x03

    /** `NNN` (bits 2..0): how many sensor samples the packet declares, as written (0..7). */
    val declaredSampleCount: Int get() = raw and 0x07
}

/** Three signed integers (x, y, z), used for raw sensor and integrator values. */
data class Vector3Int(val x: Int, val y: Int, val z: Int)

/** Three real numbers (x, y, z), used for scaled sensor values; see [ImuSample] for the axis frame. */
data class Vector3(val x: Double, val y: Double, val z: Double)

/**
 * A quaternion as the kart sends it: four signed 32-bit values (qi, qj, qk, qr) in units of 2^-30, so
 * 0x40000000 is 1.0. It is not necessarily unit length; use [Quaternion] for the normalised form.
 */
data class QuaternionRaw(val i: Int, val j: Int, val k: Int, val r: Int)

/**
 * A unit-length orientation quaternion with the vector part (x, y, z) = (qi, qj, qk) and the scalar
 * part w = qr.
 */
data class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double) {
    companion object {
        /** The no-rotation quaternion, used when the kart sends an all-zero (un-normalisable) one. */
        val IDENTITY = Quaternion(x = 0.0, y = 0.0, z = 0.0, w = 1.0)
    }
}

/**
 * One accelerometer and gyroscope sample from an IMU packet, raw and scaled.
 *
 * Business logic: the axis frame is +X toward the driver's right, +Y toward the rear and +Z down. The
 * scaled values depend on the packet's precision flags (see [ImuFlags]). Accelerations are in m/s^2 and
 * include a gravity offset of +9.81 m/s^2 on the Z axis, as the wiki spec notes; use [accelRaw] to
 * recompute without it. Rotation rates are in degrees per second.
 *
 * @property accelRaw the accelerometer reading as sent, signed 16-bit per axis.
 * @property gyroRaw the gyroscope reading as sent, signed 16-bit per axis.
 * @property accel the accelerometer reading in m/s^2, with +9.81 added to Z.
 * @property gyro the gyroscope reading in degrees per second.
 */
data class ImuSample(
    val accelRaw: Vector3Int,
    val gyroRaw: Vector3Int,
    val accel: Vector3,
    val gyro: Vector3,
)
