package com.mklhc.core.design_system.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.generated.resources.Res
import com.mklhc.core.design_system.generated.resources.cd_battery
import com.mklhc.core.design_system.generated.resources.cd_battery_charging
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The kart's battery as a battery outline with up to four bars.
 *
 * Business logic: pure display of what the kart reports (`BatteryStatus.batteryBars`, 0..4). A level above the
 * gauge is drawn full and one below zero empty (see [batteryBarsOf]). The lit bars turn from green to amber at
 * two bars and to red at one or none, so a low battery reads at a glance; the outline turns cyan while the
 * charging cable is connected and red when the battery is empty. The whole gauge is drawn on a single canvas
 * (below the recomposition layer), and its spoken description carries the level and the charging state.
 *
 * @param bars the battery level, 0..4.
 * @param modifier the modifier to apply; the gauge is 44x22dp unless it sizes itself.
 * @param charging whether the charging cable is connected.
 */
@Composable
fun BatteryGauge(
    bars: Int,
    modifier: Modifier = Modifier,
    charging: Boolean = false,
) {
    val level = batteryBarsOf(bars)
    val scheme = MaterialTheme.colorScheme
    val litColor = when {
        level <= 1 -> scheme.error
        level == 2 -> scheme.secondary
        else -> scheme.tertiary
    }
    val dimColor = scheme.outlineVariant
    val outlineColor = when {
        charging -> scheme.primary
        level == 0 -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    val description = if (charging) {
        stringResource(Res.string.cd_battery_charging, level)
    } else {
        stringResource(Res.string.cd_battery, level)
    }

    Canvas(
        modifier = modifier
            .size(width = 44.dp, height = 22.dp)
            .semantics { contentDescription = description },
    ) {
        val stroke = size.height * OUTLINE_FRACTION
        val nubWidth = size.width * NUB_FRACTION
        val bodyWidth = size.width - nubWidth
        val corner = CornerRadius(size.height * CORNER_FRACTION)

        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyWidth - stroke, size.height - stroke),
            cornerRadius = corner,
            style = Stroke(width = stroke),
        )
        val nubHeight = size.height * NUB_HEIGHT_FRACTION
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(bodyWidth, (size.height - nubHeight) / 2f),
            size = Size(nubWidth, nubHeight),
            cornerRadius = CornerRadius(nubWidth / 2f),
        )

        val inset = stroke * 2f
        val gap = stroke
        val innerWidth = bodyWidth - inset * 2f
        val segmentWidth = (innerWidth - gap * (BATTERY_MAX_BARS - 1)) / BATTERY_MAX_BARS
        val segmentHeight = size.height - inset * 2f
        for (index in 0 until BATTERY_MAX_BARS) {
            drawRoundRect(
                color = if (index < level) litColor else dimColor,
                topLeft = Offset(inset + index * (segmentWidth + gap), inset),
                size = Size(segmentWidth, segmentHeight),
                cornerRadius = CornerRadius(stroke / 2f),
            )
        }
    }
}

private const val OUTLINE_FRACTION = 0.09f
private const val NUB_FRACTION = 0.07f
private const val NUB_HEIGHT_FRACTION = 0.42f
private const val CORNER_FRACTION = 0.18f

@Preview
@Composable
private fun BatteryGaugePreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            BatteryGauge(bars = 4)
            BatteryGauge(bars = 3)
            BatteryGauge(bars = 2)
            BatteryGauge(bars = 1)
            BatteryGauge(bars = 0)
            BatteryGauge(bars = 2, charging = true)
        }
    }
}
