package com.mklhc.core.design_system.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.generated.resources.Res
import com.mklhc.core.design_system.generated.resources.cd_brake_light
import com.mklhc.core.design_system.generated.resources.label_brake
import com.mklhc.core.design_system.generated.resources.state_off
import com.mklhc.core.design_system.generated.resources.state_on
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A hold-to-light brake-light button: the kart's brake light is on exactly while the finger is down.
 *
 * Business logic: the kart's drive command carries a brake-light flag, and this is its touch control. It is
 * momentary, not a toggle: the moment a finger goes down [onActiveChange] reports `true`, and when the finger
 * lifts, or the gesture is cancelled, or the button is disabled or removed while a finger is down, it reports
 * `false` once. A light that stayed on after the finger left would mislead other drivers, so, as with the other
 * drive controls, release means off (and the cockpit's neutral command carries the light off too). The button
 * is stateless: [active] is what it shows, and the caller feeds the reported value back in. It is touch only (a
 * latched accessibility action would have no release); its spoken description says to hold it.
 *
 * @param active whether the brake light is currently lit, which the button shows as a bright red face.
 * @param onActiveChange called with `true` on press and `false` on release.
 * @param modifier the modifier to apply; the button is 96x64dp unless it sizes itself.
 * @param enabled whether the button reacts to touch.
 */
@Composable
fun BrakeLightButton(
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val face = when {
        !enabled -> scheme.surfaceVariant
        active -> scheme.error
        else -> scheme.errorContainer
    }
    val content = when {
        !enabled -> scheme.onSurfaceVariant
        active -> scheme.onError
        else -> scheme.onErrorContainer
    }
    val description = stringResource(Res.string.cd_brake_light)
    val stateText = stringResource(if (active) Res.string.state_on else Res.string.state_off)
    val currentOnActiveChange = rememberUpdatedState(onActiveChange)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width = 96.dp, height = 64.dp)
            .clip(MaterialTheme.shapes.large)
            .background(face)
            .border(width = 2.dp, color = if (enabled) scheme.error else scheme.outlineVariant, shape = MaterialTheme.shapes.large)
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = stateText
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    try {
                        currentOnActiveChange.value(true)
                        waitForUpOrCancellation()
                    } finally {
                        currentOnActiveChange.value(false)
                    }
                }
            },
    ) {
        Text(
            text = stringResource(Res.string.label_brake),
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}

@Preview
@Composable
private fun BrakeLightButtonPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            BrakeLightButton(active = false, onActiveChange = {})
            BrakeLightButton(active = true, onActiveChange = {})
            BrakeLightButton(active = false, onActiveChange = {}, enabled = false)
        }
    }
}

/** Live preview: hold the button in an interactive preview; the light follows the finger. */
@Preview
@Composable
private fun BrakeLightButtonInteractivePreview() {
    PreviewSurface {
        var lit by remember { mutableStateOf(false) }
        BrakeLightButton(active = lit, onActiveChange = { lit = it })
    }
}
