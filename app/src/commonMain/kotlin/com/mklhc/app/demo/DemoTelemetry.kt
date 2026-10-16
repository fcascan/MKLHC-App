package com.mklhc.app.demo

import com.mklhc.core.domain.KartInfo
import com.mklhc.core.kart_protocol.pairing.PAIRING_SEED_SIZE
import com.mklhc.core.kart_protocol.pairing.encodePairingPayload
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.ImuFlags
import com.mklhc.core.kart_protocol.telemetry.ImuSample
import com.mklhc.core.kart_protocol.telemetry.Quaternion
import com.mklhc.core.kart_protocol.telemetry.QuaternionRaw
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.kart_protocol.telemetry.Vector3
import com.mklhc.core.kart_protocol.telemetry.Vector3Int
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * The synthetic data the DEMO / no-hardware session publishes: pure functions, no state, no clock.
 *
 * Business logic: the demo has to make every widget of the cockpit visibly alive without a kart, so the
 * generators produce slow, smooth signals:
 * - Orientation. The artificial horizon rolls and pitches along two sines of different periods (so the motion
 *   does not repeat exactly in a short time) within a gentle range ([ROLL_AMPLITUDE_DEGREES],
 *   [PITCH_AMPLITUDE_DEGREES]). The quaternion is built so that the cockpit's horizon mapping recovers exactly that
 *   roll and pitch: it is the pitch rotation about +X composed with the roll rotation about +Y (the kart's body
 *   frame is +X right, +Y rear, +Z down), which gives the "down" direction `(sin(r) cos(p), sin(p), cos(p) cos(r))`
 *   that the mapping reads (positive pitch is nose up and positive roll banks right, so the rotation about +Y is
 *   by minus the roll). It is unit length by construction.
 * - Acceleration. A small sinusoidal lateral and longitudinal acceleration moves the G meter at rest, and the
 *   held drive command biases it (steering right pushes toward +X, throttle forward pulls the IMU's Y, which points
 *   to the rear, negative), so the demo reacts to the driver's fingers. Gravity sits on Z, as the decoder reports it.
 * - Battery: three bars, no cable.
 * - Video: placeholder access units. They have a valid Annex-B start code but are not decodable pictures (a
 *   non-IDR slice header and filler), so the desktop video surface counts them and Android's decoder gate, which
 *   waits for an IDR with its parameter sets, keeps skipping them instead of rendering garbage.
 *
 * Nothing here is a secret. The pairing seed uses the injected [Random], which is not cryptographically secure,
 * because a demo pairing code pairs nothing.
 */
internal object DemoTelemetry {

    /** Standard gravity in m/s^2; the IMU reports it on the Z axis. */
    const val GRAVITY = 9.81

    /** The largest roll the horizon swings to, in degrees (either side). */
    const val ROLL_AMPLITUDE_DEGREES = 18.0

    /** The largest pitch the horizon swings to, in degrees (either side). */
    const val PITCH_AMPLITUDE_DEGREES = 6.0

    /** The pairing network name the demo QR advertises; short enough for the 32-octet SSID field. */
    const val PAIRING_SSID = "MKLHC-DEMO-PAIRING"

    /** The identified-kart data the demo publishes, clearly labelled as a demo. */
    val SAMPLE_KART_INFO = KartInfo(
        serial = "DEMO-KART-0001",
        character = 1,
        firmwareSystem = 1 to 4,
        firmwareBoot = 0 to 9,
        sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709",
    )

    private const val ROLL_PERIOD_SECONDS = 6.0
    private const val PITCH_PERIOD_SECONDS = 4.3
    private const val LATERAL_PERIOD_SECONDS = 5.0
    private const val LONGITUDINAL_PERIOD_SECONDS = 3.2
    private const val LATERAL_AMPLITUDE_G = 0.2
    private const val LONGITUDINAL_AMPLITUDE_G = 0.15
    private const val STEERING_BIAS_G = 0.5
    private const val THROTTLE_BIAS_G = 0.4
    private const val DRIVE_AXIS_FULL_SCALE = 127.0
    private const val QUATERNION_RAW_UNIT = (1 shl 30).toDouble()
    private const val MICROS_PER_SECOND = 1_000_000.0
    private const val SAMPLE_COUNT_MASK = 0xFFFF
    private const val ONE_SAMPLE_FLAGS = 0x01
    private const val DEMO_BATTERY_BARS = 3
    private const val NAL_NON_IDR_SLICE_HEADER: Byte = 0x41
    private const val VIDEO_FILLER_SIZE = 32
    private const val VIDEO_FILLER_BYTE: Byte = 0x55
    private const val SEQUENCE_BYTES = 4
    private const val SEQUENCE_BITS_PER_BYTE = 7
    private const val SEQUENCE_BYTE_MASK = 0x7F
    private const val NON_ZERO_BIT = 0x80
    private const val TWO_PI = 2.0 * PI
    private const val DEGREES_TO_RADIANS = PI / 180.0
    private const val HALF = 0.5

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * One IMU packet for the instant [timeSeconds] since the telemetry started.
     *
     * @param timeSeconds seconds since the stream started; drives the oscillation.
     * @param sequence the packet counter, reported as the running sample count (wraps at 16 bits).
     * @param drive the drive command currently held, which biases the acceleration.
     */
    fun imu(timeSeconds: Double, sequence: Int, drive: DriveCommand = DriveCommand.NEUTRAL): Telemetry.Imu {
        val rollRadians = ROLL_AMPLITUDE_DEGREES * sin(TWO_PI * timeSeconds / ROLL_PERIOD_SECONDS) * DEGREES_TO_RADIANS
        val pitchRadians = PITCH_AMPLITUDE_DEGREES * sin(TWO_PI * timeSeconds / PITCH_PERIOD_SECONDS) * DEGREES_TO_RADIANS
        val quaternion = attitudeQuaternion(pitchRadians = pitchRadians, rollRadians = rollRadians)

        val steering = (drive.steering / DRIVE_AXIS_FULL_SCALE).coerceIn(-1.0, 1.0)
        val throttle = (drive.throttle / DRIVE_AXIS_FULL_SCALE).coerceIn(-1.0, 1.0)
        val lateralG = LATERAL_AMPLITUDE_G * sin(TWO_PI * timeSeconds / LATERAL_PERIOD_SECONDS) + steering * STEERING_BIAS_G
        val forwardG =
            LONGITUDINAL_AMPLITUDE_G * sin(TWO_PI * timeSeconds / LONGITUDINAL_PERIOD_SECONDS) + throttle * THROTTLE_BIAS_G

        val sample = ImuSample(
            // The HUD reads only the scaled values; the raw counts and the gyroscope are left at zero.
            accelRaw = Vector3Int(0, 0, 0),
            gyroRaw = Vector3Int(0, 0, 0),
            accel = Vector3(x = lateralG * GRAVITY, y = -forwardG * GRAVITY, z = GRAVITY),
            gyro = Vector3(0.0, 0.0, 0.0),
        )
        return Telemetry.Imu(
            totalSampleCount = sequence and SAMPLE_COUNT_MASK,
            flags = ImuFlags(ONE_SAMPLE_FLAGS),
            timerMicros = (timeSeconds * MICROS_PER_SECOND).roundToLong(),
            quaternion = quaternion,
            quaternionRaw = quaternion.toRaw(),
            positionA = Vector3Int(0, 0, 0),
            positionB = Vector3Int(0, 0, 0),
            velocityA = Vector3Int(0, 0, 0),
            velocityB = Vector3Int(0, 0, 0),
            samples = listOf(sample),
            truncated = false,
        )
    }

    /** The demo battery report: a few bars, no charging cable. */
    fun battery(): Telemetry.BatteryStatus = Telemetry.BatteryStatus(cableFlags = 0, batteryBars = DEMO_BATTERY_BARS)

    /**
     * A placeholder H.264 access unit: an Annex-B start code, a non-IDR slice header, the [sequence] number encoded
     * in four non-zero octets (so the unit never contains a second start code) and filler.
     */
    fun videoAccessUnit(sequence: Int): ByteArray {
        val unit = ByteArray(START_CODE.size + 1 + SEQUENCE_BYTES + VIDEO_FILLER_SIZE) { VIDEO_FILLER_BYTE }
        START_CODE.copyInto(unit)
        unit[START_CODE.size] = NAL_NON_IDR_SLICE_HEADER
        for (index in 0 until SEQUENCE_BYTES) {
            val group = (sequence shr (SEQUENCE_BITS_PER_BYTE * index)) and SEQUENCE_BYTE_MASK
            unit[START_CODE.size + 1 + index] = (group or NON_ZERO_BIT).toByte()
        }
        return unit
    }

    /** The pairing QR payload for the demo: a fresh seed from [random] and the demo SSID on the current channel. */
    fun pairingPayload(random: Random): ByteArray =
        encodePairingPayload(seed = random.nextBytes(PAIRING_SEED_SIZE), pairingSsid = PAIRING_SSID)

    /** Pitch (about +X) composed with roll (about +Y, by minus the roll); see the class comment for the frame. */
    private fun attitudeQuaternion(pitchRadians: Double, rollRadians: Double): Quaternion {
        val pitchSin = sin(pitchRadians * HALF)
        val pitchCos = cos(pitchRadians * HALF)
        val rollSin = sin(-rollRadians * HALF)
        val rollCos = cos(-rollRadians * HALF)
        return Quaternion(
            x = pitchSin * rollCos,
            y = pitchCos * rollSin,
            z = pitchSin * rollSin,
            w = pitchCos * rollCos,
        )
    }

    private fun Quaternion.toRaw(): QuaternionRaw = QuaternionRaw(
        i = (x * QUATERNION_RAW_UNIT).roundToInt(),
        j = (y * QUATERNION_RAW_UNIT).roundToInt(),
        k = (z * QUATERNION_RAW_UNIT).roundToInt(),
        r = (w * QUATERNION_RAW_UNIT).roundToInt(),
    )
}
