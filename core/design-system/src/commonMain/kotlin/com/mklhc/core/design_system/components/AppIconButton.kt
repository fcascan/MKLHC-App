package com.mklhc.core.design_system.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The visual weight of an [AppIconButton]: [Filled] is the strongest, [Tonal] a softer container, [Standard]
 * no container at all.
 */
enum class AppIconButtonStyle { Filled, Tonal, Standard }

/**
 * The design-system icon button.
 *
 * Business logic: none; it is stateless and reports the press through [onClick]. The icon is a slot (the design
 * system carries no icon set; the caller supplies a vector, a painter or a canvas drawing), and because an icon
 * alone says nothing to a screen reader the [contentDescription] is required, not optional. It is at least 48dp
 * square.
 *
 * @param onClick called when the button is pressed.
 * @param contentDescription what the button does, spoken by the screen reader.
 * @param modifier the modifier to apply.
 * @param enabled whether the button accepts presses.
 * @param style the visual weight.
 * @param icon the icon content, drawn in the button's content colour.
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: AppIconButtonStyle = AppIconButtonStyle.Tonal,
    icon: @Composable () -> Unit,
) {
    val described = modifier
        .defaultMinSize(minWidth = AppDimens.MinTouchTarget, minHeight = AppDimens.MinTouchTarget)
        .semantics { this.contentDescription = contentDescription }
    when (style) {
        AppIconButtonStyle.Filled -> FilledIconButton(onClick = onClick, modifier = described, enabled = enabled, content = icon)
        AppIconButtonStyle.Tonal -> FilledTonalIconButton(onClick = onClick, modifier = described, enabled = enabled, content = icon)
        AppIconButtonStyle.Standard -> IconButton(onClick = onClick, modifier = described, enabled = enabled, content = icon)
    }
}

@Preview
@Composable
private fun AppIconButtonPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            AppIconButtonStyle.entries.forEach { style ->
                AppIconButton(onClick = {}, contentDescription = "Settings", style = style) {
                    Box(modifier = Modifier.size(20.dp).background(LocalContentColor.current, CircleShape))
                }
            }
            AppIconButton(onClick = {}, contentDescription = "Settings (disabled)", enabled = false) {
                Box(modifier = Modifier.size(20.dp).background(LocalContentColor.current, CircleShape))
            }
        }
    }
}
