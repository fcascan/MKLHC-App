package com.mklhc.core.design_system.hud

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure arithmetic behind the HUD widgets.
 *
 * Business logic: the canvases only draw; how a battery level, a signal strength, a pair of g values or a pitch
 * angle becomes something to draw is decided here, as plain functions with no Compose state, so each rule can be
 * unit-tested without rendering a frame and the draw lambdas stay a thin translation of these results.
 */

/** Bars on the battery gauge; the kart reports its level on the same 0..4 scale. */
internal const val BATTERY_MAX_BARS = 4

/** Bars on the signal indicator. */
internal const val SIGNAL_MAX_BARS = 4

/** The pitch, in degrees, beyond which the horizon stops moving; keeps a runaway angle from drawing off-screen. */
internal const val HORIZON_MAX_PITCH_DEGREES = 60f

/** How many degrees of pitch span one instrument radius, so a quarter turn of the ball is about 30 degrees. */
internal const val HORIZON_DEGREES_PER_RADIUS = 30f

/**
 * Clamps a reported battery level to the bars the gauge can draw.
 *
 * Business logic: the telemetry decoder passes the kart's level through unclamped (it should be 0..4 but a
 * misbehaving kart could send anything), so the gauge, not the decoder, decides what is drawable: below zero
 * is empty and above the top is full.
 */
internal fun batteryBarsOf(bars: Int): Int = bars.coerceIn(0, BATTERY_MAX_BARS)

/**
 * Turns a received signal strength into a 0..4 bar level.
 *
 * Business logic: `null` means no reading yet and draws no bars. Otherwise the usual Wi-Fi rule of thumb: at
 * or above -55 dBm is excellent (4 bars), then -65 (3), -75 (2), -85 (1), and anything weaker is no usable
 * signal (0).
 */
internal fun signalLevel(rssiDbm: Int?): Int = when {
    rssiDbm == null -> 0
    rssiDbm >= -55 -> 4
    rssiDbm >= -65 -> 3
    rssiDbm >= -75 -> 2
    rssiDbm >= -85 -> 1
    else -> 0
}

/**
 * Where the G meter's dot sits, as a screen offset within the unit circle.
 *
 * Business logic: [gX] is lateral acceleration in g, positive toward the driver's right, and [gY] is
 * longitudinal acceleration in g, positive when speeding up (forward). The result is normalised so that
 * `maxG` reaches the rim: x grows to the right and y grows downward as on screen, so forward is negative y
 * (the dot rises under acceleration and falls under braking). A reading beyond `maxG` is scaled back onto the
 * rim in the same direction rather than clipped per axis, so the dot never leaves the instrument or changes
 * the direction it points. A non-finite input reads as at rest.
 */
internal fun gMeterPosition(gX: Float, gY: Float, maxG: Float): Offset {
    if (!gX.isFinite() || !gY.isFinite() || !maxG.isFinite() || maxG <= 0f) return Offset.Zero
    val nx = gX / maxG
    val ny = gY / maxG
    val length = sqrt(nx * nx + ny * ny)
    val scale = if (length > 1f) 1f / length else 1f
    // 0f - ... rather than unary minus so an at-rest reading is +0, not -0 (Offset equality is bitwise).
    return Offset(x = nx * scale + 0f, y = 0f - ny * scale)
}

/**
 * How far the horizon line moves from the instrument's centre for a given pitch, in pixels.
 *
 * Business logic: positive [pitchDegrees] is nose up, and with the nose up the horizon appears lower in the
 * view, so the shift is positive (downward). The pitch is clamped to [HORIZON_MAX_PITCH_DEGREES] and a
 * non-finite value reads as level. [HORIZON_DEGREES_PER_RADIUS] degrees move the line by one [radiusPx].
 */
internal fun horizonShiftPx(pitchDegrees: Float, radiusPx: Float): Float {
    if (!pitchDegrees.isFinite()) return 0f
    val clamped = pitchDegrees.coerceIn(-HORIZON_MAX_PITCH_DEGREES, HORIZON_MAX_PITCH_DEGREES)
    return clamped / HORIZON_DEGREES_PER_RADIUS * radiusPx
}

/**
 * Formats [value] to one decimal place for spoken descriptions, for example 0.5 or -1.2.
 *
 * Business logic: commonMain has no `String.format`, and the accessibility text only needs tenths of a g. A
 * non-finite value reads as 0.0, and a value that rounds to zero never prints as "-0.0".
 */
internal fun formatTenths(value: Float): String {
    if (!value.isFinite()) return "0.0"
    val tenths = (value * 10f).roundToInt()
    val sign = if (tenths < 0) "-" else ""
    val magnitude = abs(tenths)
    return "$sign${magnitude / 10}.${magnitude % 10}"
}
