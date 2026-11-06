// Layout measured by this project from a capture of the kart's telemetry; original code, no reference implementation.
package com.mklhc.core.kart_protocol.telemetry

/**
 * One per-frame record of the kart's camera, as carried twice in every type-3 telemetry datagram (this frame and the
 * one before).
 *
 * Business logic: the kart sends one record per camera frame (30 a second), stamped with its microsecond timer, the
 * same clock as [Telemetry.Imu.timerMicros]. The values move when the camera's view changes (tilting the kart toward a
 * light, driving into a dark spot) and not with the throttle or the steering, which is why they are read as the
 * camera's automatic exposure and white balance. That meaning is a hypothesis from one capture, not confirmed against
 * the picture: the property names say what each value most likely is, and the ranges are the ones seen on a kart.
 *
 * @property timerMicros the kart's microsecond timer for this frame, a u32 (0..4_294_967_295).
 * @property exposure most likely the exposure time; 6,000 to 15,900 seen, 15,900 most of the time.
 * @property gain most likely the sensor's analog gain, in exact steps of 1/16; 5.625 to 15.875 seen (the top in the
 *   dark).
 * @property brightness most likely the measured picture brightness; 0 to 95.5 seen (5 to 8 in a dark spot, 85 to 95
 *   toward a light).
 * @property redGain most likely the white balance red gain in 1/1024 units; 1,152 to 1,731 seen.
 * @property greenGain most likely the white balance green gain in 1/1024 units; always 1,024 (1.0) seen.
 * @property blueGain most likely the white balance blue gain in 1/1024 units; 1,792 to 3,093 seen.
 */
data class CameraFrameRecord(
    val timerMicros: Long,
    val exposure: Float,
    val gain: Float,
    val brightness: Float,
    val redGain: Int,
    val greenGain: Int,
    val blueGain: Int,
)

/**
 * The drive command the kart reports having received, the last record of every type-3 telemetry datagram.
 *
 * Business logic: [throttle] and [steering] are the signed octets of the app's teleoperation datagram (see
 * [com.mklhc.core.kart_protocol.teleop.TeleopEncoder]) as the kart received them: in a capture they matched the command
 * the app was sending one frame earlier in 99% of the records, the rest falling on a command change. The brake light is
 * not echoed. [unknownValue] is a float of unknown meaning that changed only while the kart moved with the steering
 * turned and kept its value otherwise (-0.037 to 0.155 seen).
 *
 * @property timerMicros the kart's microsecond timer, a u32; the same value as the datagram's current camera record.
 * @property throttle the received throttle, -128 (full reverse) .. 127 (full forward).
 * @property steering the received steering, -128 (full left) .. 127 (full right).
 * @property unknownValue the record's float of unknown meaning, exposed as sent.
 */
data class DriveCommandEcho(
    val timerMicros: Long,
    val throttle: Int,
    val steering: Int,
    val unknownValue: Float,
)
