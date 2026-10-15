// Layout per a community protocol wiki (Fuji kart protocol); format spec, no code copied.
package com.mklhc.core.kart_protocol.telemetry

import co.touchlab.kermit.Logger
import kotlin.math.sqrt

/**
 * Decodes the payload of a type-2 (IMU) telemetry datagram.
 *
 * Business logic: the payload is a 0x48-octet header followed by the sensor samples the header declares.
 * The header holds a running sample counter, the flags octet, the kart's microsecond timer, the fused
 * orientation quaternion and the two integrators; each sample is 12 octets (accelerometer x, y, z then
 * gyroscope x, y, z, all signed 16-bit). A datagram that ends inside the sample block still yields every
 * complete sample and is flagged as truncated; one that ends inside the header carries nothing usable
 * and is rejected.
 *
 * Offsets, relative to the start of the payload (the octet after the type octet):
 *
 * ```
 * 0x00 (2)   total sample count, u16
 * 0x02 (1)   flags IGA??NNN (see ImuFlags)
 * 0x03 (1)   not used
 * 0x04 (4)   microsecond timer, u32
 * 0x08 (16)  quaternion qi, qj, qk, qr as 4 x s32, unit 2^-30
 * 0x18 (12)  integrator A position, 3 x s32        0x24 (12)  integrator B position, 3 x s32
 * 0x30 (12)  integrator A velocity, 3 x s32        0x3C (12)  integrator B velocity, 3 x s32
 * 0x48       NNN samples of 12 octets: accel 3 x s16, gyro 3 x s16
 * ```
 *
 * Scaling: accelerometer counts are multiplied by 9.81 / 4096 m/s^2 when the A flag is set and by
 * 15.99 times that otherwise, then 9.81 is added to Z (the spec's gravity note); gyroscope counts are
 * multiplied by 0.004375 deg/s when the G flag is set and by 0.1 otherwise. The quaternion is converted
 * from 2^-30 units and normalised to unit length.
 *
 * UNVERIFIED AGAINST HARDWARE: the OpenKart reference leaves the IMU path unimplemented, so there is no
 * code to check this against, and the spec was not validated against captured traffic. Everything below
 * is an assumption to confirm with real captures at the hardware spike:
 *  - that the offsets above are relative to the payload after the type octet (not the whole datagram);
 *  - that all multi-octet values are little-endian (the spec text does not say);
 *  - that NNN is the sample count as written (the spec says "1..8", which three bits cannot hold: it may
 *    be NNN + 1, see [SAMPLE_COUNT_BIAS]);
 *  - the accelerometer and gyroscope scale factors, which are read as per-count multipliers;
 *  - whether +9.81 is really added to Z (and its sign, given +Z points down);
 *  - the meaning of the `I` flag and the units of the integrator positions and velocities (exposed
 *    unscaled), and of the two unknown flag bits;
 *  - the axis frame (+X right, +Y rear, +Z down) and the quaternion component order (qi, qj, qk, qr).
 */
internal object ImuDecoder {

    /** Octets of the header, i.e. the offset of the first sample within the payload. */
    const val HEADER_SIZE = 0x48

    /** Octets per sample: accel 3 x s16 then gyro 3 x s16. */
    const val SAMPLE_SIZE = 12

    /**
     * Added to the NNN field to get the number of samples: 0, so the sample count is NNN as written.
     * The wiki's "1..8" wording would instead make this 1 (count = NNN + 1); which is correct is a
     * spike-confirmation item.
     */
    const val SAMPLE_COUNT_BIAS = 0

    /** Standard gravity added to the Z axis of the scaled accelerometer reading, in m/s^2. */
    const val GRAVITY = 9.81

    /** Accelerometer multiplier, m/s^2 per count, with the high-precision flag set. */
    const val ACCEL_SCALE_HIGH = GRAVITY / 4096.0

    /** Accelerometer multiplier, m/s^2 per count, with the high-precision flag clear. */
    const val ACCEL_SCALE_LOW = 15.99 * GRAVITY / 4096.0

    /** Gyroscope multiplier, degrees per second per count, with the high-precision flag set. */
    const val GYRO_SCALE_HIGH = 0.004375

    /** Gyroscope multiplier, degrees per second per count, with the high-precision flag clear. */
    const val GYRO_SCALE_LOW = 0.1

    /** Quaternion unit: one raw count is 2^-30. */
    private const val QUATERNION_SCALE = 1.0 / (1 shl 30)

    private const val TOTAL_COUNT_OFFSET = 0x00
    private const val FLAGS_OFFSET = 0x02
    private const val TIMER_OFFSET = 0x04
    private const val QUATERNION_OFFSET = 0x08
    private const val POSITION_A_OFFSET = 0x18
    private const val POSITION_B_OFFSET = 0x24
    private const val VELOCITY_A_OFFSET = 0x30
    private const val VELOCITY_B_OFFSET = 0x3C

    /**
     * Decodes [payload] (the datagram without its type octet).
     *
     * @return the decoded packet, or null (and a warning logged to [log]) if [payload] is shorter than
     *   the 0x48-octet header.
     */
    fun decode(payload: ByteArray, log: Logger): Telemetry.Imu? {
        if (payload.size < HEADER_SIZE) {
            log.w { "decode(): IMU payload shorter than its header -> size=${payload.size} header=$HEADER_SIZE" }
            return null
        }

        val flags = ImuFlags(payload.u8At(FLAGS_OFFSET))
        val declared = flags.declaredSampleCount + SAMPLE_COUNT_BIAS
        val present = minOf(declared, (payload.size - HEADER_SIZE) / SAMPLE_SIZE)
        val truncated = present < declared
        if (truncated) {
            log.w { "decode(): IMU samples cut short -> declared=$declared present=$present size=${payload.size}" }
        }

        val quaternionRaw = QuaternionRaw(
            i = payload.s32LeAt(QUATERNION_OFFSET),
            j = payload.s32LeAt(QUATERNION_OFFSET + 4),
            k = payload.s32LeAt(QUATERNION_OFFSET + 8),
            r = payload.s32LeAt(QUATERNION_OFFSET + 12),
        )

        return Telemetry.Imu(
            totalSampleCount = payload.u16LeAt(TOTAL_COUNT_OFFSET),
            flags = flags,
            timerMicros = payload.u32LeAt(TIMER_OFFSET),
            quaternion = normalize(quaternionRaw, log),
            quaternionRaw = quaternionRaw,
            positionA = payload.vector3S32At(POSITION_A_OFFSET),
            positionB = payload.vector3S32At(POSITION_B_OFFSET),
            velocityA = payload.vector3S32At(VELOCITY_A_OFFSET),
            velocityB = payload.vector3S32At(VELOCITY_B_OFFSET),
            samples = List(present) { index -> decodeSample(payload, HEADER_SIZE + index * SAMPLE_SIZE, flags) },
            truncated = truncated,
        )
    }

    /** Reads the 12-octet sample at [offset] and scales it according to the precision bits of [flags]. */
    private fun decodeSample(payload: ByteArray, offset: Int, flags: ImuFlags): ImuSample {
        val accelRaw = payload.vector3S16At(offset)
        val gyroRaw = payload.vector3S16At(offset + 6)
        val accelScale = if (flags.highPrecisionAccel) ACCEL_SCALE_HIGH else ACCEL_SCALE_LOW
        val gyroScale = if (flags.highPrecisionGyro) GYRO_SCALE_HIGH else GYRO_SCALE_LOW
        return ImuSample(
            accelRaw = accelRaw,
            gyroRaw = gyroRaw,
            accel = Vector3(accelRaw.x * accelScale, accelRaw.y * accelScale, accelRaw.z * accelScale + GRAVITY),
            gyro = Vector3(gyroRaw.x * gyroScale, gyroRaw.y * gyroScale, gyroRaw.z * gyroScale),
        )
    }

    /**
     * Converts [raw] from 2^-30 units and scales it to unit length. A zero quaternion has no direction to
     * normalise, so it becomes [Quaternion.IDENTITY] and a warning is logged.
     */
    private fun normalize(raw: QuaternionRaw, log: Logger): Quaternion {
        val x = raw.i * QUATERNION_SCALE
        val y = raw.j * QUATERNION_SCALE
        val z = raw.k * QUATERNION_SCALE
        val w = raw.r * QUATERNION_SCALE
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (norm == 0.0) {
            log.w { "normalize(): all-zero quaternion cannot be normalised -> using identity" }
            return Quaternion.IDENTITY
        }
        return Quaternion(x / norm, y / norm, z / norm, w / norm)
    }
}
