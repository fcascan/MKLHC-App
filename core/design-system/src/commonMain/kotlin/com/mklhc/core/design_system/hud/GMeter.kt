package com.mklhc.core.design_system.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.generated.resources.Res
import com.mklhc.core.design_system.generated.resources.cd_g_meter
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/** The acceleration, in g, at the rim of a [GMeter] unless the caller picks another range. */
const val G_METER_DEFAULT_MAX_G = 1.5f

/**
 * A round G meter: a dot that moves with the acceleration the kart is feeling.
 *
 * Business logic: pure display of two accelerations the cockpit derived from the kart's IMU. [gX] is lateral,
 * in g, positive toward the driver's right; [gY] is longitudinal, in g, positive when speeding up, so the
 * dot rises under acceleration, falls under braking and swings to the side in a corner. The kart's IMU frame is
 * +X right and +Y toward the rear, so the caller negates the Y acceleration and divides m/s^2 by 9.81 first. Three
 * rings mark thirds of [maxG]; a reading beyond [maxG] is pinned to the rim in its own direction (see
 * [gMeterPosition]) and a non-finite one reads as at rest. Drawn on a single canvas, below the recomposition
 * layer.
 *
 * @param gX lateral acceleration in g, positive to the right.
 * @param gY longitudinal acceleration in g, positive forward.
 * @param modifier the modifier to apply; the instrument is a 96dp circle unless it sizes itself.
 * @param maxG the acceleration, in g, at the rim.
 */
@Composable
fun GMeter(
    gX: Float,
    gY: Float,
    modifier: Modifier = Modifier,
    maxG: Float = G_METER_DEFAULT_MAX_G,
) {
    val scheme = MaterialTheme.colorScheme
    val face = scheme.surfaceVariant
    val rings = scheme.outline
    val dot = scheme.primary
    val description = stringResource(Res.string.cd_g_meter, formatTenths(gX), formatTenths(gY))

    Canvas(
        modifier = modifier
            .size(96.dp)
            .semantics { contentDescription = description },
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val hairline = radius * HAIRLINE_FRACTION

        drawCircle(color = face, radius = radius, center = center)
        for (ring in 1..RING_COUNT) {
            drawCircle(
                color = rings,
                radius = radius * ring / RING_COUNT - hairline / 2f,
                center = center,
                style = Stroke(width = hairline),
            )
        }
        drawLine(rings, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = hairline)
        drawLine(rings, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth = hairline)

        // Keep the whole dot inside the rim: the travel range is the radius less the dot's own radius.
        val dotRadius = radius * DOT_FRACTION
        val travel = radius - dotRadius
        val position = gMeterPosition(gX = gX, gY = gY, maxG = maxG)
        drawCircle(
            color = dot,
            radius = dotRadius,
            center = Offset(center.x + position.x * travel, center.y + position.y * travel),
        )
    }
}

private const val RING_COUNT = 3
private const val HAIRLINE_FRACTION = 0.02f
private const val DOT_FRACTION = 0.11f

@Preview
@Composable
private fun GMeterPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            GMeter(gX = 0f, gY = 0f)
            GMeter(gX = 0.6f, gY = 0.3f)
            GMeter(gX = -0.9f, gY = -0.7f)
            GMeter(gX = 3f, gY = 3f)
        }
    }
}
