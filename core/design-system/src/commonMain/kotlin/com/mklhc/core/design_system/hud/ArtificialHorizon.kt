package com.mklhc.core.design_system.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.generated.resources.Res
import com.mklhc.core.design_system.generated.resources.cd_horizon
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A round artificial horizon: sky over ground, tilted and shifted by the kart's attitude.
 *
 * Business logic: pure display of an orientation the cockpit derived from the kart's IMU. [pitchDegrees] is
 * positive nose up (the horizon drops in the view) and [rollDegrees] is positive when the kart banks to its
 * right (the horizon tilts the other way, as it does in a real instrument). The fixed amber symbol in the middle is
 * the kart, the pitch ladder marks every 10 degrees, and the ticks round the rim mark 30 and 60 degrees of roll
 * and turn with the horizon against a fixed pointer at the top. The conversion from the IMU's quaternion to
 * pitch and roll is the caller's job; a non-finite angle reads as level (see [horizonShiftPx]).
 *
 * Everything is drawn on a single canvas, below the recomposition layer; the composable only re-runs when the
 * angles change, so the ViewModel should publish them at display rate (conflated), not per IMU packet.
 *
 * @param pitchDegrees nose-up positive pitch in degrees.
 * @param rollDegrees right-bank positive roll in degrees.
 * @param modifier the modifier to apply; the instrument is a 120dp circle unless it sizes itself.
 */
@Composable
fun ArtificialHorizon(
    pitchDegrees: Float,
    rollDegrees: Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val sky = scheme.primaryContainer
    val ground = scheme.secondaryContainer
    val lines = scheme.onSurface
    val symbol = scheme.secondary
    val rim = scheme.outline
    val description = stringResource(
        Res.string.cd_horizon,
        if (pitchDegrees.isFinite()) pitchDegrees.roundToInt() else 0,
        if (rollDegrees.isFinite()) rollDegrees.roundToInt() else 0,
    )

    Canvas(
        modifier = modifier
            .size(120.dp)
            .semantics { contentDescription = description },
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val roll = if (rollDegrees.isFinite()) rollDegrees else 0f
        val circle = Path().apply { addOval(Rect(center = center, radius = radius)) }

        clipPath(circle) {
            // The world rotates against the roll and shifts with the pitch; rotating first means the pitch
            // shift is perpendicular to the (tilted) horizon, as on a real instrument.
            rotate(degrees = -roll, pivot = center) {
                val horizonY = center.y + horizonShiftPx(pitchDegrees, radius)
                val reach = radius * WORLD_REACH
                drawRect(
                    color = sky,
                    topLeft = Offset(center.x - reach, horizonY - reach),
                    size = Size(reach * 2f, reach),
                )
                drawRect(
                    color = ground,
                    topLeft = Offset(center.x - reach, horizonY),
                    size = Size(reach * 2f, reach),
                )
                drawLine(
                    color = lines,
                    start = Offset(center.x - reach, horizonY),
                    end = Offset(center.x + reach, horizonY),
                    strokeWidth = radius * HORIZON_LINE_FRACTION,
                )
                drawPitchLadder(center = center, horizonY = horizonY, radius = radius, color = lines)
                drawRollTicks(center = center, radius = radius, color = lines)
            }
        }

        // Fixed overlay: the rim, the roll pointer at the top, and the kart symbol.
        drawCircle(color = rim, radius = radius - radius * RIM_FRACTION / 2f, center = center, style = Stroke(width = radius * RIM_FRACTION))
        drawRollPointer(center = center, radius = radius, color = symbol)
        drawKartSymbol(center = center, radius = radius, color = symbol)
    }
}

/** Short marks every 10 degrees above and below the horizon, longer at 20 and 30. */
private fun DrawScope.drawPitchLadder(center: Offset, horizonY: Float, radius: Float, color: Color) {
    for (step in 1..LADDER_STEPS) {
        val degrees = step * LADDER_STEP_DEGREES
        val offset = degrees / HORIZON_DEGREES_PER_RADIUS * radius
        val half = radius * (if (step % 2 == 0) LADDER_LONG_FRACTION else LADDER_SHORT_FRACTION) / 2f
        for (direction in LADDER_DIRECTIONS) {
            val y = horizonY + direction * offset
            drawLine(
                color = color,
                start = Offset(center.x - half, y),
                end = Offset(center.x + half, y),
                strokeWidth = radius * LADDER_LINE_FRACTION,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Ticks on the rim at 0, +-30 and +-60 degrees from the top; they rotate with the horizon. */
private fun DrawScope.drawRollTicks(center: Offset, radius: Float, color: Color) {
    for (tickDegrees in ROLL_TICK_DEGREES) {
        val angle = tickDegrees * PI.toFloat() / 180f
        val length = radius * (if (tickDegrees == 0) TICK_LONG_FRACTION else TICK_SHORT_FRACTION)
        val outer = Offset(center.x + sin(angle) * radius * TICK_RADIUS_FRACTION, center.y - cos(angle) * radius * TICK_RADIUS_FRACTION)
        val inner = Offset(center.x + sin(angle) * (radius * TICK_RADIUS_FRACTION - length), center.y - cos(angle) * (radius * TICK_RADIUS_FRACTION - length))
        drawLine(color = color, start = outer, end = inner, strokeWidth = radius * LADDER_LINE_FRACTION, cap = StrokeCap.Round)
    }
}

/** The fixed triangle at the top of the rim the roll ticks are read against. */
private fun DrawScope.drawRollPointer(center: Offset, radius: Float, color: Color) {
    val tip = Offset(center.x, center.y - radius * POINTER_TIP_FRACTION)
    val half = radius * POINTER_HALF_WIDTH_FRACTION
    val base = center.y - radius * POINTER_BASE_FRACTION
    val pointer = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(center.x - half, base)
        lineTo(center.x + half, base)
        close()
    }
    drawPath(pointer, color)
}

/** The kart: a centre dot flanked by two short wings. */
private fun DrawScope.drawKartSymbol(center: Offset, radius: Float, color: Color) {
    val width = radius * SYMBOL_LINE_FRACTION
    drawLine(color, Offset(center.x - radius * SYMBOL_OUTER, center.y), Offset(center.x - radius * SYMBOL_INNER, center.y), strokeWidth = width, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x + radius * SYMBOL_INNER, center.y), Offset(center.x + radius * SYMBOL_OUTER, center.y), strokeWidth = width, cap = StrokeCap.Round)
    drawCircle(color = color, radius = width, center = center)
}

// Allocated once, not per frame: both are walked in the draw lambda.
private val LADDER_DIRECTIONS = floatArrayOf(-1f, 1f)
private val ROLL_TICK_DEGREES = intArrayOf(-60, -30, 0, 30, 60)

private const val WORLD_REACH = 4f
private const val HORIZON_LINE_FRACTION = 0.025f
private const val RIM_FRACTION = 0.06f
private const val LADDER_STEPS = 3
private const val LADDER_STEP_DEGREES = 10f
private const val LADDER_SHORT_FRACTION = 0.22f
private const val LADDER_LONG_FRACTION = 0.42f
private const val LADDER_LINE_FRACTION = 0.02f
private const val TICK_RADIUS_FRACTION = 0.93f
private const val TICK_LONG_FRACTION = 0.14f
private const val TICK_SHORT_FRACTION = 0.08f
private const val POINTER_TIP_FRACTION = 0.77f
private const val POINTER_BASE_FRACTION = 0.64f
private const val POINTER_HALF_WIDTH_FRACTION = 0.06f
private const val SYMBOL_LINE_FRACTION = 0.04f
private const val SYMBOL_INNER = 0.18f
private const val SYMBOL_OUTER = 0.5f

@Preview
@Composable
private fun ArtificialHorizonPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            ArtificialHorizon(pitchDegrees = 0f, rollDegrees = 0f)
            ArtificialHorizon(pitchDegrees = 8f, rollDegrees = -15f)
            ArtificialHorizon(pitchDegrees = -12f, rollDegrees = 30f)
        }
    }
}
