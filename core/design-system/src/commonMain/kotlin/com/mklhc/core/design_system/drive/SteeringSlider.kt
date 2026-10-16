package com.mklhc.core.design_system.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.generated.resources.Res
import com.mklhc.core.design_system.generated.resources.cd_steering
import com.mklhc.core.design_system.generated.resources.state_steering_centered
import com.mklhc.core.design_system.generated.resources.state_steering_left
import com.mklhc.core.design_system.generated.resources.state_steering_right
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far in from each end the steering thumb centre stops, as a fraction of the control's height. */
private const val STEERING_THUMB_RADIUS_FRACTION = 0.45f

/**
 * A horizontal steering control: drag a thumb left and right along a track.
 *
 * Business logic: the control is stateless; it shows [value] and reports the finger through [onValue] (a
 * normalised -1 for full left to +1 for full right, the finger's position along the track, with a small dead
 * zone at the centre). The caller owns the value: it feeds each reported value back in (and into the drive
 * command), which is what moves the thumb.
 *
 * SAFETY, release means neutral: when the finger lifts, or the gesture is cancelled, or the control is
 * disabled or removed while a finger is down, [onRelease] is called once. The cockpit must answer it by
 * centring the steering (and sending a neutral command); a control that kept its last value after the finger
 * left would keep steering the kart. The gesture starts on the first touch, not after a drag slop (see
 * [axisTouch]).
 *
 * The control is touch only: it deliberately offers no accessibility "set progress" action, because a value set
 * that way would have no finger-up to release it. It is read as a described, ranged element with its state
 * ("30 percent left").
 *
 * @param value the current steering, -1 (full left) .. 1 (full right); out-of-range values are clamped.
 * @param onValue called with the new steering while a finger is down.
 * @param onRelease called once when the finger lifts or the gesture ends, to return to neutral.
 * @param modifier the modifier to apply; the control is 280x64dp unless it sizes itself.
 * @param enabled whether the control reacts to touch.
 */
@Composable
fun SteeringSlider(
    value: Float,
    onValue: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shown = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
    val percent = (abs(shown) * PERCENT).roundToInt()
    val description = stringResource(Res.string.cd_steering)
    val stateText = when {
        percent == 0 -> stringResource(Res.string.state_steering_centered)
        shown < 0f -> stringResource(Res.string.state_steering_left, percent)
        else -> stringResource(Res.string.state_steering_right, percent)
    }

    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceVariant
    val centreMark = scheme.outline
    val fill = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val thumb = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = DISABLED_ALPHA)

    val currentOnValue = rememberUpdatedState(onValue)
    val currentOnRelease = rememberUpdatedState(onRelease)

    Canvas(
        modifier = modifier
            .size(width = 280.dp, height = 64.dp)
            .semantics {
                contentDescription = description
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(current = shown, range = -1f..1f)
            }
            .axisTouch(
                enabled = enabled,
                vertical = false,
                insetFraction = STEERING_THUMB_RADIUS_FRACTION,
                onValue = currentOnValue,
                onRelease = currentOnRelease,
            ),
    ) {
        val thumbRadius = size.height * STEERING_THUMB_RADIUS_FRACTION
        val trackHeight = size.height * TRACK_FRACTION
        val trackTop = (size.height - trackHeight) / 2f
        val centreX = size.width / 2f
        val thumbX = thumbRadius + (shown + 1f) / 2f * (size.width - 2f * thumbRadius)

        drawRoundRect(
            color = track,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        if (shown != 0f) {
            val left = minOf(centreX, thumbX)
            val right = maxOf(centreX, thumbX)
            drawRect(color = fill, topLeft = Offset(left, trackTop), size = Size(right - left, trackHeight))
        }
        drawRect(color = centreMark, topLeft = Offset(centreX - 1.5f, trackTop - trackHeight / 2f), size = Size(3f, trackHeight * 2f))
        drawCircle(color = thumb, radius = thumbRadius, center = Offset(thumbX, size.height / 2f))
        drawCircle(color = track, radius = thumbRadius * THUMB_HOLE_FRACTION, center = Offset(thumbX, size.height / 2f))
    }
}

private const val PERCENT = 100
private const val TRACK_FRACTION = 0.34f
private const val THUMB_HOLE_FRACTION = 0.35f
private const val DISABLED_ALPHA = 0.38f

@Preview
@Composable
private fun SteeringSliderPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            SteeringSlider(value = 0f, onValue = {}, onRelease = {})
            SteeringSlider(value = -0.6f, onValue = {}, onRelease = {})
            SteeringSlider(value = 0.85f, onValue = {}, onRelease = {})
            SteeringSlider(value = 0.3f, onValue = {}, onRelease = {}, enabled = false)
        }
    }
}

/** Live preview: drag the thumb in an interactive preview; it springs back to centre on release. */
@Preview
@Composable
private fun SteeringSliderInteractivePreview() {
    PreviewSurface {
        var steering by remember { mutableFloatStateOf(0f) }
        SteeringSlider(
            value = steering,
            onValue = { steering = it },
            onRelease = { steering = 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
