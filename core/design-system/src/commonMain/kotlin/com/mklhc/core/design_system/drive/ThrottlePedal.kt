package com.mklhc.core.design_system.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.generated.resources.Res
import com.mklhc.core.design_system.generated.resources.cd_throttle
import com.mklhc.core.design_system.generated.resources.label_forward
import com.mklhc.core.design_system.generated.resources.label_reverse
import com.mklhc.core.design_system.generated.resources.state_throttle_forward
import com.mklhc.core.design_system.generated.resources.state_throttle_neutral
import com.mklhc.core.design_system.generated.resources.state_throttle_reverse
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far in from each end the pedal's thumb centre stops, as a fraction of the control's width. */
private const val PEDAL_INSET_FRACTION = 0.3f

/**
 * A vertical throttle control: drag a pedal up for forward and down for reverse, with neutral in the middle.
 *
 * Business logic: the control is stateless; it shows [value] and reports the finger through [onValue] (a
 * normalised +1 at the top for full forward, 0 in the middle, -1 at the bottom for full reverse, with a small
 * dead zone at the centre). The caller owns the value and feeds it back in, which moves the pedal. Forward fills
 * upward in cyan and reverse fills downward in amber, so reverse is unmistakable.
 *
 * SAFETY, release means neutral: when the finger lifts, or the gesture is cancelled, or the control is disabled
 * or removed while a finger is down, [onRelease] is called once. The cockpit must answer it by returning the
 * throttle to zero and sending a neutral command; a pedal that kept its last value after the finger left would
 * keep the kart driving. The gesture starts on the first touch (see [axisTouch]). Like the steering slider it
 * is touch only and offers no accessibility "set progress" action, since such a value would have no finger-up
 * to release it.
 *
 * @param value the current throttle, -1 (full reverse) .. 1 (full forward); out-of-range values are clamped.
 * @param onValue called with the new throttle while a finger is down.
 * @param onRelease called once when the finger lifts or the gesture ends, to return to neutral.
 * @param modifier the modifier to apply; the pedal is 96x240dp unless it sizes itself.
 * @param enabled whether the control reacts to touch.
 */
@Composable
fun ThrottlePedal(
    value: Float,
    onValue: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shown = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
    val percent = (abs(shown) * PERCENT).roundToInt()
    val description = stringResource(Res.string.cd_throttle)
    val stateText = when {
        percent == 0 -> stringResource(Res.string.state_throttle_neutral)
        shown > 0f -> stringResource(Res.string.state_throttle_forward, percent)
        else -> stringResource(Res.string.state_throttle_reverse, percent)
    }

    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceVariant
    val centreMark = scheme.outline
    val forward = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val reverse = if (enabled) scheme.secondary else scheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val thumb = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = DISABLED_ALPHA)

    val currentOnValue = rememberUpdatedState(onValue)
    val currentOnRelease = rememberUpdatedState(onRelease)

    Box(
        modifier = modifier
            .size(width = 96.dp, height = 240.dp)
            // The visible FWD / REV captions are decoration for sighted users; the spoken description replaces them.
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(current = shown, range = -1f..1f)
            }
            .axisTouch(
                enabled = enabled,
                vertical = true,
                insetFraction = PEDAL_INSET_FRACTION,
                onValue = currentOnValue,
                onRelease = currentOnRelease,
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thumbHeight = size.width * THUMB_HEIGHT_FRACTION
            val thumbWidth = size.width * THUMB_WIDTH_FRACTION
            val inset = size.width * PEDAL_INSET_FRACTION
            val centreY = size.height / 2f
            // +1 is at the top: map the value onto the travel between the two insets.
            val thumbY = inset + (1f - (shown + 1f) / 2f) * (size.height - 2f * inset)
            val corner = CornerRadius(size.width * CORNER_FRACTION)

            drawRoundRect(color = track, size = size, cornerRadius = corner)
            if (shown > 0f) {
                drawRect(color = forward, topLeft = Offset(0f, thumbY), size = Size(size.width, centreY - thumbY))
            } else if (shown < 0f) {
                drawRect(color = reverse, topLeft = Offset(0f, centreY), size = Size(size.width, thumbY - centreY))
            }
            drawRect(color = centreMark, topLeft = Offset(0f, centreY - 1.5f), size = Size(size.width, 3f))
            drawRoundRect(
                color = thumb,
                topLeft = Offset((size.width - thumbWidth) / 2f, thumbY - thumbHeight / 2f),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbHeight / 2f),
            )
        }
        Text(
            text = stringResource(Res.string.label_forward),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
        )
        Text(
            text = stringResource(Res.string.label_reverse),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

private const val PERCENT = 100
private const val THUMB_WIDTH_FRACTION = 0.8f
private const val THUMB_HEIGHT_FRACTION = 0.6f
private const val CORNER_FRACTION = 0.25f
private const val DISABLED_ALPHA = 0.38f

@Preview
@Composable
private fun ThrottlePedalPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            ThrottlePedal(value = 0f, onValue = {}, onRelease = {})
            ThrottlePedal(value = 0.7f, onValue = {}, onRelease = {})
            ThrottlePedal(value = -0.4f, onValue = {}, onRelease = {})
            ThrottlePedal(value = 0.3f, onValue = {}, onRelease = {}, enabled = false)
        }
    }
}

/** Live preview: drag the pedal in an interactive preview; it springs back to neutral on release. */
@Preview
@Composable
private fun ThrottlePedalInteractivePreview() {
    PreviewSurface {
        var throttle by remember { mutableFloatStateOf(0f) }
        ThrottlePedal(
            value = throttle,
            onValue = { throttle = it },
            onRelease = { throttle = 0f },
        )
    }
}
