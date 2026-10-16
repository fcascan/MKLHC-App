package com.mklhc.core.design_system.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.HudCaptionStyle
import com.mklhc.core.design_system.HudReadoutStyle
import com.mklhc.core.design_system.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.abs

/**
 * A large numeric readout with a caption and a centre-zero bar: the speed or the throttle on the HUD.
 *
 * Business logic: pure display; it formats nothing. The caller passes the number already formatted for the
 * quantity (a throttle percentage, a speed in the user's unit) as [value], with its [unit] and a [label]. The
 * kart does not report its speed, so the cockpit most likely shows the commanded throttle here; the same readout
 * serves a speed if one is ever derived. [fraction] drives the thin bar under the number: it grows to the right
 * (cyan) for a positive value and to the left (amber) for a negative one, so reverse is visible without reading
 * the sign. A fraction beyond -1..1 is clamped and a non-finite one draws no bar. The number is monospaced so
 * its digits do not shift as they change, and the three texts are merged into one accessibility node, so
 * a screen reader reads them as one phrase ("THROTTLE 35 %").
 *
 * @param value the already-formatted number, for example "35" or "12.4".
 * @param unit the unit shown beside the number, for example "%" or "km/h".
 * @param label the caption above the number, for example "THROTTLE".
 * @param modifier the modifier to apply.
 * @param fraction the bar, -1 (full reverse) .. 1 (full forward); 0 draws no fill.
 */
@Composable
fun SpeedOrThrottleReadout(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    fraction: Float = 0f,
) {
    val scheme = MaterialTheme.colorScheme
    val forward = scheme.primary
    val reverse = scheme.secondary
    val track = scheme.outlineVariant
    val centreMark = scheme.outline

    Column(
        // As wide as the number row (and no narrower than a minimum), so the bar tracks the readout rather than
        // stretching across whatever row the readout is placed in.
        modifier = modifier
            .widthIn(min = READOUT_MIN_WIDTH)
            .width(IntrinsicSize.Max)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall / 2),
    ) {
        Text(
            text = label,
            style = HudCaptionStyle,
            color = scheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            Text(
                text = value,
                style = HudReadoutStyle,
                color = scheme.onSurface,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            val half = size.width / 2f
            drawRoundRect(color = track, size = size, cornerRadius = CornerRadius(size.height / 2f))
            val clamped = if (fraction.isFinite()) fraction.coerceIn(-1f, 1f) else 0f
            if (clamped != 0f) {
                val length = half * abs(clamped)
                drawRect(
                    color = if (clamped > 0f) forward else reverse,
                    topLeft = Offset(if (clamped > 0f) half else half - length, 0f),
                    size = Size(length, size.height),
                )
            }
            drawRect(color = centreMark, topLeft = Offset(half - 1f, 0f), size = Size(2f, size.height))
        }
    }
}

private val READOUT_MIN_WIDTH = 120.dp

@Preview
@Composable
private fun SpeedOrThrottleReadoutPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            Column(modifier = Modifier.height(100.dp)) {
                SpeedOrThrottleReadout(value = "35", unit = "%", label = "THROTTLE", fraction = 0.35f)
            }
            Column(modifier = Modifier.height(100.dp)) {
                SpeedOrThrottleReadout(value = "-20", unit = "%", label = "THROTTLE", fraction = -0.2f)
            }
            Column(modifier = Modifier.height(100.dp)) {
                SpeedOrThrottleReadout(value = "12.4", unit = "km/h", label = "SPEED")
            }
        }
    }
}
