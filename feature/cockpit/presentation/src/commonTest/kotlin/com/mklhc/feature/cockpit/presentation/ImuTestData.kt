package com.mklhc.feature.cockpit.presentation

import com.mklhc.core.kart_protocol.telemetry.ImuFlags
import com.mklhc.core.kart_protocol.telemetry.ImuSample
import com.mklhc.core.kart_protocol.telemetry.Quaternion
import com.mklhc.core.kart_protocol.telemetry.QuaternionRaw
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.kart_protocol.telemetry.Vector3
import com.mklhc.core.kart_protocol.telemetry.Vector3Int
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The three axes of the kart's IMU frame: +X right, +Y rear, +Z down. */
internal enum class ImuAxis { X, Y, Z }

/** The unit quaternion for a rotation of [degrees] about [axis] of the IMU frame (right-hand rule). */
internal fun rotationAbout(axis: ImuAxis, degrees: Double): Quaternion {
    val half = degrees * PI / 180.0 / 2.0
    val s = sin(half)
    val c = cos(half)
    return when (axis) {
        ImuAxis.X -> Quaternion(x = s, y = 0.0, z = 0.0, w = c)
        ImuAxis.Y -> Quaternion(x = 0.0, y = s, z = 0.0, w = c)
        ImuAxis.Z -> Quaternion(x = 0.0, y = 0.0, z = s, w = c)
    }
}

/** One IMU sample with the given scaled acceleration in m/s^2 (frame +X right, +Y rear, +Z down). */
internal fun imuSample(x: Double, y: Double, z: Double) = ImuSample(
    accelRaw = Vector3Int(0, 0, 0),
    gyroRaw = Vector3Int(0, 0, 0),
    accel = Vector3(x, y, z),
    gyro = Vector3(0.0, 0.0, 0.0),
)

/** An IMU packet with the given orientation and samples; every other field is irrelevant to the cockpit. */
internal fun imuPacket(
    quaternion: Quaternion = Quaternion.IDENTITY,
    samples: List<ImuSample> = listOf(imuSample(0.0, 0.0, REST_Z)),
) = Telemetry.Imu(
    totalSampleCount = 0,
    flags = ImuFlags(0),
    timerMicros = 0L,
    quaternion = quaternion,
    quaternionRaw = QuaternionRaw(0, 0, 0, 0),
    positionA = Vector3Int(0, 0, 0),
    positionB = Vector3Int(0, 0, 0),
    velocityA = Vector3Int(0, 0, 0),
    velocityB = Vector3Int(0, 0, 0),
    samples = samples,
    truncated = false,
)

/** What the decoder reports on the Z axis of a kart at rest (the +9.81 gravity offset), in m/s^2. */
internal const val REST_Z = 9.81
