package com.mklhc.feature.cockpit.presentation

import com.mklhc.core.kart_protocol.telemetry.Quaternion
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.kart_protocol.telemetry.Vector3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * The mapping from the kart's IMU telemetry to what the HUD widgets display: pure functions, no state.
 *
 * Frame and conventions (the kart's IMU frame, from the telemetry spec): +X toward the driver's right, +Y toward
 * the rear, +Z down. Pitch is positive nose up and roll is positive banking right, which is what the artificial
 * horizon expects. The G meter takes lateral g positive to the right and longitudinal g positive forward.
 *
 * HARDWARE-UNVERIFIED. The IMU decoder itself documents that its layout, scaling and axis frame come from a
 * community wiki and have not been checked against real captures. This mapping adds three assumptions of its own,
 * to confirm with captured traffic at the hardware spike:
 *  1. The quaternion rotates a vector from the kart's body frame into a world frame whose Z axis points down
 *     (gravity), and the identity quaternion means the kart is level. If the sensor instead reports a world
 *     frame with Z up, a level kart would read as a half turn and the pitch and roll here would be wrong.
 *  2. The acceleration reaches the meter exactly as the decoder scales it ([Telemetry.Imu.samples] accel: m/s^2,
 *     gravity expected on Z only), so the lateral and longitudinal axes carry no gravity while the kart is level.
 *     Gravity leaking into X and Y when the kart is tilted (sine of the tilt, about 0.09 g at 5 degrees) is
 *     deliberately not compensated: doing so would need the quaternion frame AND the sign of the accelerometer
 *     reading confirmed, and a wrong guess would double the error instead of cancelling it.
 *  3. The reading is true acceleration (not its negative), so speeding up forward reads a negative Y.
 */

/** Standard gravity in m/s^2, the unit the IMU accelerations are converted to g with. */
internal const val STANDARD_GRAVITY = 9.81

private const val DEGREES_PER_RADIAN = 180.0 / PI
private const val MIN_QUATERNION_NORM = 1e-6

/**
 * The kart's attitude for the artificial horizon.
 *
 * @property pitchDegrees nose-up-positive pitch in degrees, -90..90.
 * @property rollDegrees right-bank-positive roll in degrees, -180..180.
 */
internal data class Attitude(val pitchDegrees: Float, val rollDegrees: Float)

/**
 * The accelerations for the G meter, in g.
 *
 * @property lateral positive to the right.
 * @property longitudinal positive forward.
 */
internal data class GForce(val lateral: Float, val longitudinal: Float)

/**
 * What one IMU packet contributes to the HUD.
 *
 * @property pitchDegrees see [Attitude.pitchDegrees].
 * @property rollDegrees see [Attitude.rollDegrees].
 * @property gForce the acceleration from the packet's newest sample, or null when the packet carried no samples
 *   (the G meter then keeps its last reading).
 */
internal data class HudReading(val pitchDegrees: Float, val rollDegrees: Float, val gForce: GForce?)

/**
 * Derives the pitch and roll of the kart from its orientation quaternion.
 *
 * Business logic: the horizon only needs the direction of gravity as the kart sees it, not the heading, so the
 * heading (rotation about the vertical) is ignored on purpose; a sensor whose heading drifts must not tilt the
 * horizon. With the quaternion taken as body-to-world (see the assumptions above), the world's down axis in body
 * coordinates is the third row of the rotation matrix, `g = (2(xz - yw), 2(yz + xw), 1 - 2(x^2 + y^2))`. A level
 * kart gives `(0, 0, 1)`. Rolling right drops the right side, so `g.x` grows and `roll = atan2(g.x, g.z)`. Raising
 * the nose raises the rear (+Y) toward the ground, so `g.y` grows and `pitch = atan2(g.y, hypot(g.x, g.z))`, which
 * is the standard aerospace roll/pitch pair for this frame. The quaternion is normalised first (the decoder
 * normalises, but a mapper should not trust it), and one that is zero or not finite reads as level.
 */
internal fun Quaternion.toAttitude(): Attitude {
    val norm = sqrt(x * x + y * y + z * z + w * w)
    if (!norm.isFinite() || norm < MIN_QUATERNION_NORM) return Attitude(0f, 0f)
    val qx = x / norm
    val qy = y / norm
    val qz = z / norm
    val qw = w / norm

    val downX = 2.0 * (qx * qz - qy * qw)
    val downY = 2.0 * (qy * qz + qx * qw)
    val downZ = 1.0 - 2.0 * (qx * qx + qy * qy)

    val pitch = atan2(downY, hypot(downX, downZ)) * DEGREES_PER_RADIAN
    val roll = atan2(downX, downZ) * DEGREES_PER_RADIAN
    return Attitude(pitchDegrees = pitch.toFloat().orZero(), rollDegrees = roll.toFloat().orZero())
}

/**
 * Converts an IMU acceleration (m/s^2, frame +X right, +Y rear, +Z down) to the G meter's axes.
 *
 * Business logic: lateral g is the X acceleration over standard gravity; longitudinal g is the Y acceleration
 * over standard gravity, negated, because the IMU's +Y points to the rear while the meter's +Y is forward (so
 * speeding up shows as a positive reading and braking as a negative one). Z carries gravity and is not used,
 * which is what makes a level kart at rest read zero on both axes. See the assumptions above for why tilt is not
 * compensated. A component that is not a number reads as zero, so one bad sample cannot poison the meter.
 */
internal fun Vector3.toGForce(): GForce = GForce(
    lateral = (x / STANDARD_GRAVITY).toFloat().orZero(),
    longitudinal = (-y / STANDARD_GRAVITY).toFloat().orZero(),
)

/**
 * Maps one IMU packet to the HUD: the attitude from its quaternion and the g from its newest sample.
 *
 * Business logic: the packet holds up to eight samples taken since the previous one, oldest first; the newest is
 * the closest to now, so it is the one shown. A packet without samples still moves the horizon and leaves the G
 * meter alone ([HudReading.gForce] is null).
 */
internal fun Telemetry.Imu.toHudReading(): HudReading {
    val attitude = quaternion.toAttitude()
    return HudReading(
        pitchDegrees = attitude.pitchDegrees,
        rollDegrees = attitude.rollDegrees,
        gForce = samples.lastOrNull()?.accel?.toGForce(),
    )
}

/**
 * Replaces negative zero and NaN with zero. Negative zero and zero are different Floats, and NaN is not equal to
 * itself, so either one in the state would make a state flow see a change on every update and keep recomposing the HUD.
 */
private fun Float.orZero(): Float = if (isNaN() || this == 0f) 0f else this
