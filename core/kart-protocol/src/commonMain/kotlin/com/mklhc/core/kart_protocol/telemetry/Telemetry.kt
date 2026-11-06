// The telemetry type dispatch and the battery status (type 1) are ported from OpenKart
// https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
// The IMU variant (type 2) follows the layout of a community protocol wiki (Fuji kart protocol);
// format spec, no code copied. The frame status variant (type 3) follows a layout this project measured from a capture.
package com.mklhc.core.kart_protocol.telemetry

/**
 * One decoded telemetry datagram from the kart.
 *
 * Business logic: the kart streams telemetry over UDP; the first octet of every datagram is a type and
 * the rest is that type's payload. [TelemetryDecoder] turns a datagram into exactly one of these:
 * [BatteryStatus] (type 1), [Imu] (type 2), [FrameStatus] (type 3), or [Unknown] for anything it cannot
 * interpret (unrecognised types, and malformed or too-short payloads). The decoder never throws on kart
 * data, because a malformed datagram must not stop the telemetry stream.
 */
sealed interface Telemetry {

    /**
     * Type 1: the kart's charging cable and battery state.
     *
     * Business logic: of the 0x1f payload octets only two are understood: octet 0, whose bit 0 says the
     * charging cable is plugged in (bits 1 and 2 have unknown meaning and are exposed untouched through
     * [cableFlags]), and octet 3, the battery level in HUD bars. The kart is expected to show 0..4 bars;
     * the value is passed on as the kart sent it, without clamping.
     *
     * @property cableFlags the whole first payload octet as an unsigned value (0..255).
     * @property batteryBars the fourth payload octet as an unsigned value, 0..4 on a healthy kart.
     */
    data class BatteryStatus(
        val cableFlags: Int,
        val batteryBars: Int,
    ) : Telemetry {
        /** True when the charging cable is connected (bit 0 of [cableFlags]). */
        val cableConnected: Boolean get() = (cableFlags and 1) != 0
    }

    /**
     * Type 2: one inertial-measurement packet, an orientation estimate plus up to 8 raw sensor samples.
     *
     * Business logic: the kart's motion processor reports its fused orientation as a quaternion, two
     * dead-reckoning integrators (A and B, each a position and a velocity) and a short burst of raw
     * accelerometer and gyroscope samples taken since the previous packet. The axis frame is +X toward
     * the driver's right, +Y toward the rear and +Z down. There is no reference implementation for this
     * packet, so its offsets, endianness and scaling are taken from the wiki spec and must be confirmed
     * against real captures (see [ImuDecoder]).
     *
     * @property totalSampleCount running count of samples the kart has taken, a u16 that wraps.
     * @property flags the precision flags and the declared per-packet sample count.
     * @property timerMicros the kart's microsecond timer, a u32 (0..4_294_967_295).
     * @property quaternion the orientation (x, y, z, w) normalised to unit length; the identity when the
     *   kart sent an all-zero quaternion.
     * @property quaternionRaw the quaternion exactly as sent, in units of 2^-30.
     * @property positionA integrator A position, raw s32 per axis; the unit is not specified.
     * @property positionB integrator B position, raw s32 per axis; the unit is not specified.
     * @property velocityA integrator A velocity, raw s32 per axis; the unit is not specified.
     * @property velocityB integrator B velocity, raw s32 per axis; the unit is not specified.
     * @property samples the sensor samples that were present in full, oldest first.
     * @property truncated true when the datagram ended before the number of samples the flags declared.
     */
    data class Imu(
        val totalSampleCount: Int,
        val flags: ImuFlags,
        val timerMicros: Long,
        val quaternion: Quaternion,
        val quaternionRaw: QuaternionRaw,
        val positionA: Vector3Int,
        val positionB: Vector3Int,
        val velocityA: Vector3Int,
        val velocityB: Vector3Int,
        val samples: List<ImuSample>,
        val truncated: Boolean,
    ) : Telemetry

    /**
     * Type 3: the kart's per-frame camera record, the previous frame's, and the drive command it received.
     *
     * Business logic: the kart sends one with every camera frame, 30 a second. [current] and [previous] are
     * the camera's records for this frame and the one before ([previous] equals the last datagram's
     * [current], so a lost datagram shows as this [previous] differing from the last [current] received); [driveEcho] is the throttle and steering
     * the kart reports having received. The wiki this protocol work follows calls type 3 "motion
     * feedback", but a capture showed camera values and a command echo, not motion: see
     * [CameraFrameRecord] for which meanings are still hypotheses.
     *
     * @property current the camera record of this frame.
     * @property previous the camera record of the previous frame.
     * @property driveEcho the drive command the kart received, stamped with this frame's timer.
     */
    data class FrameStatus(
        val current: CameraFrameRecord,
        val previous: CameraFrameRecord,
        val driveEcho: DriveCommandEcho,
    ) : Telemetry

    /**
     * A datagram that is not decoded: an unrecognised type, an empty datagram, or a type 1 / 2 / 3
     * payload too short to read.
     *
     * Business logic: the payload is kept so it can be logged or captured for later analysis. Equality
     * compares [raw] by content.
     *
     * @property type the type octet as an unsigned value, or [NO_TYPE] when the datagram was empty.
     * @property raw the payload after the type octet; the whole datagram minus its first octet.
     */
    class Unknown(
        val type: Int,
        val raw: ByteArray,
    ) : Telemetry {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Unknown && type == other.type && raw.contentEquals(other.raw))

        override fun hashCode(): Int = 31 * type + raw.contentHashCode()

        override fun toString(): String = "Unknown(type=$type, raw=${raw.toHexString()})"

        companion object {
            /** The [type] reported for an empty datagram, which has no type octet. */
            const val NO_TYPE = -1
        }
    }
}
