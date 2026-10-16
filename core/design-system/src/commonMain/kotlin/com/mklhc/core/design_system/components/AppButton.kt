package com.mklhc.core.design_system.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The visual weight of an [AppButton].
 *
 * Business logic: [Primary] is the one main action of a screen (filled, cyan), [Secondary] is an alternative
 * (outlined), and [Destructive] is an action that powers the kart down or ends the session (filled, red).
 */
enum class AppButtonStyle { Primary, Secondary, Destructive }

/**
 * The design-system text button.
 *
 * Business logic: none; it is stateless and reports the press through [onClick]. It is at least 48dp tall so it
 * is a comfortable thumb target, takes its colours from the theme by [style], and may show a [leadingIcon]
 * before the text. The button's text is its accessibility label.
 *
 * @param text the label.
 * @param onClick called when the button is pressed.
 * @param modifier the modifier to apply.
 * @param enabled whether the button accepts presses.
 * @param style the visual weight.
 * @param leadingIcon an optional icon (or any small content) shown before the label.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: AppButtonStyle = AppButtonStyle.Primary,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val sized = modifier.defaultMinSize(minHeight = AppDimens.MinTouchTarget)
    val label: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) leadingIcon()
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
    when (style) {
        AppButtonStyle.Primary -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            content = { label() },
        )

        AppButtonStyle.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            border = BorderStroke(
                width = 1.5.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            content = { label() },
        )

        AppButtonStyle.Destructive -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            content = { label() },
        )
    }
}

@Preview
@Composable
private fun AppButtonPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            AppButton(text = "Start driving", onClick = {})
            AppButton(text = "Pair a new kart", onClick = {}, style = AppButtonStyle.Secondary)
            AppButton(text = "Power the kart off", onClick = {}, style = AppButtonStyle.Destructive)
            AppButton(text = "Waiting for the kart", onClick = {}, enabled = false)
            AppButton(
                text = "With icon",
                onClick = {},
                leadingIcon = {
                    Box(modifier = Modifier.size(12.dp).background(LocalContentColor.current, CircleShape))
                },
            )
        }
    }
}
