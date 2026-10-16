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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.generated.resources.Res
import com.mklhc.core.design_system.generated.resources.cd_signal
import com.mklhc.core.design_system.generated.resources.cd_signal_unknown
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The radio signal strength as four ascending bars.
 *
 * Business logic: pure display of a received signal strength in dBm. `null` means there is no reading yet and
 * draws no lit bars; otherwise [signalLevel] turns the dBm into 0..4 lit bars. A strong signal is green, a
 * middling one amber and a barely usable one red, so a weak link reads at a glance, and the spoken description
 * carries the bar count and the raw dBm (the colour is never the only cue). Drawn on one canvas, below the
 * recomposition layer.
 *
 * @param rssiDbm the received signal strength in dBm (for example -58), or null when unknown.
 * @param modifier the modifier to apply; the indicator is 28x22dp unless it sizes itself.
 */
@Composable
fun SignalIndicator(
    rssiDbm: Int?,
    modifier: Modifier = Modifier,
) {
    val level = signalLevel(rssiDbm)
    val scheme = MaterialTheme.colorScheme
    val litColor = when {
        level >= 3 -> scheme.tertiary
        level == 2 -> scheme.secondary
        else -> scheme.error
    }
    val dimColor = scheme.outlineVariant
    val description = if (rssiDbm == null) {
        stringResource(Res.string.cd_signal_unknown)
    } else {
        stringResource(Res.string.cd_signal, level, rssiDbm)
    }

    Canvas(
        modifier = modifier
            .size(width = 28.dp, height = 22.dp)
            .semantics { contentDescription = description },
    ) {
        val gap = size.width * GAP_FRACTION
        val barWidth = (size.width - gap * (SIGNAL_MAX_BARS - 1)) / SIGNAL_MAX_BARS
        for (index in 0 until SIGNAL_MAX_BARS) {
            val barHeight = size.height * (index + 1) / SIGNAL_MAX_BARS
            drawRoundRect(
                color = if (index < level) litColor else dimColor,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 3f),
            )
        }
    }
}

private const val GAP_FRACTION = 0.08f

@Preview
@Composable
private fun SignalIndicatorPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            SignalIndicator(rssiDbm = -48)
            SignalIndicator(rssiDbm = -62)
            SignalIndicator(rssiDbm = -72)
            SignalIndicator(rssiDbm = -82)
            SignalIndicator(rssiDbm = -95)
            SignalIndicator(rssiDbm = null)
        }
    }
}
