package com.mklhc.core.design_system.drive

import com.mklhc.core.kart_protocol.teleop.DriveCommand
import kotlin.math.abs
import kotlin.math.roundToInt

/** Deflection, as a fraction of full travel, within which a touch control reads as exactly neutral. */
internal const val AXIS_DEAD_ZONE = 0.03f

private const val OCTET_MAX = 127f
private const val OCTET_MIN_MAGNITUDE = 128f

/**
 * Maps a pointer position along a control's track to a normalised -1..1 axis value.
 *
 * Business logic: the steering slider and the throttle pedal both report where the finger is, in pixels,
 * along a track of [length] pixels; this turns it into the axis the cockpit works in: the first end of the
 * usable track is -1, the middle 0 and the far end +1. The usable track is the full [length] less an [inset] at
 * each end, because the thumb is drawn inside the track and its centre cannot reach the very edge; the finger
 * and the thumb must agree, so the drawing and this mapping use the same inset. A vertical pedal has its +1 at
 * the top, which is pixel 0, so it passes `inverted = true`. A position beyond the track is clamped, a
 * deflection smaller than [deadZone] snaps to exactly 0 so a resting thumb does not jitter the kart, and a
 * degenerate track or a non-finite position reads as neutral. The result is never -0.
 *
 * @param position the pointer's pixel offset along the track, 0 at the track's start.
 * @param length the track length in pixels.
 * @param inverted whether the axis runs against the pixel direction (true for a vertical, top-is-positive pedal).
 * @param deadZone the neutral band, as a fraction of full deflection.
 * @param inset pixels at each end of the track the thumb centre cannot reach.
 */
internal fun axisFromPosition(
    position: Float,
    length: Float,
    inverted: Boolean = false,
    deadZone: Float = AXIS_DEAD_ZONE,
    inset: Float = 0f,
): Float {
    if (!position.isFinite() || !length.isFinite() || !inset.isFinite()) return 0f
    val usable = length - 2f * inset
    if (usable <= 0f) return 0f
    val raw = ((position - inset) / usable * 2f - 1f).coerceIn(-1f, 1f)
    val signed = if (inverted) -raw else raw
    return if (signed == 0f || abs(signed) < deadZone) 0f else signed
}

/**
 * Converts a normalised -1..1 axis to the signed octet the kart's drive command carries.
 *
 * Business logic: the wire range is asymmetric, -128 (full reverse or left) to 127 (full forward or right),
 * so the positive side scales by 127 and the negative side by 128, which makes +1 map to 127 and -1 to -128
 * exactly. A value beyond +-1 is clamped, and NaN reads as neutral, so the result is always a
 * representable octet and building a [DriveCommand] from it can never throw.
 */
internal fun Float.toAxisOctet(): Int = when {
    isNaN() -> 0
    this >= 0f -> (coerceAtMost(1f) * OCTET_MAX).roundToInt()
    else -> (coerceAtLeast(-1f) * OCTET_MIN_MAGNITUDE).roundToInt()
}

/**
 * Builds the wire drive command from the cockpit's normalised controls.
 *
 * Business logic: the touch controls report throttle and steering as -1..1 (negative throttle is reverse,
 * negative steering is left) and the brake light as a flag; this is the single place that turns them into
 * the [DriveCommand] the session repository sends, so the sign and the range are decided once. Releasing every
 * control gives `driveCommandOf(0f, 0f, false)`, which equals [DriveCommand.NEUTRAL].
 *
 * @param throttle -1 (full reverse) .. 1 (full forward).
 * @param steering -1 (full left) .. 1 (full right).
 * @param brakeLight whether the brake light is lit.
 */
fun driveCommandOf(throttle: Float, steering: Float, brakeLight: Boolean): DriveCommand =
    DriveCommand(throttle = throttle.toAxisOctet(), steering = steering.toAxisOctet(), brakeLight = brakeLight)
