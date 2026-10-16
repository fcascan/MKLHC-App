package com.mklhc.core.design_system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.components.AppButton
import com.mklhc.core.design_system.components.AppButtonStyle
import com.mklhc.core.design_system.components.LabeledValue
import com.mklhc.core.design_system.components.StatusPill
import com.mklhc.core.design_system.components.StatusTone
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A one-screen tour of the theme's tokens: the colour roles as swatches, the type scale, the buttons and the
 * pills.
 *
 * Business logic: none; it exists so a change to [AppTheme] can be reviewed at a glance in a preview (and in a
 * smoke test that renders it), the way a style guide page would be. It is internal to the module.
 */
@Composable
internal fun ThemeShowcase(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(AppDimens.SpacingMedium),
        verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium),
    ) {
        Text("Cockpit theme", style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)

        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            Swatch("primary", scheme.primary, scheme.onPrimary)
            Swatch("secondary", scheme.secondary, scheme.onSecondary)
            Swatch("tertiary", scheme.tertiary, scheme.onTertiary)
            Swatch("error", scheme.error, scheme.onError)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            Swatch("primary container", scheme.primaryContainer, scheme.onPrimaryContainer)
            Swatch("secondary container", scheme.secondaryContainer, scheme.onSecondaryContainer)
            Swatch("tertiary container", scheme.tertiaryContainer, scheme.onTertiaryContainer)
            Swatch("error container", scheme.errorContainer, scheme.onErrorContainer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            Swatch("surface", scheme.surface, scheme.onSurface)
            Swatch("surface variant", scheme.surfaceVariant, scheme.onSurfaceVariant)
            Swatch("outline", scheme.outline, scheme.background)
        }

        Text("Display readout 0123", style = MaterialTheme.typography.displaySmall, color = scheme.onBackground)
        Text("Title medium for values", style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
        Text("Body medium for prose and explanations.", style = MaterialTheme.typography.bodyMedium, color = scheme.onBackground)
        Text("Label medium for captions", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            StatusPill(text = "Ready", tone = StatusTone.Success)
            StatusPill(text = "Weak", tone = StatusTone.Warning)
            StatusPill(text = "Failed", tone = StatusTone.Error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            AppButton(text = "Primary", onClick = {})
            AppButton(text = "Secondary", onClick = {}, style = AppButtonStyle.Secondary)
            AppButton(text = "Destructive", onClick = {}, style = AppButtonStyle.Destructive)
        }
        LabeledValue(label = "Serial", value = "SN-0012345")
    }
}

@Composable
private fun Swatch(name: String, color: Color, onColor: Color) {
    Box(
        modifier = Modifier
            .size(width = 76.dp, height = 56.dp)
            .background(color, RoundedCornerShape(8.dp))
            .padding(4.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(name, color = onColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Preview
@Composable
private fun ThemeShowcaseDarkPreview() {
    AppTheme {
        Box(modifier = Modifier.fillMaxWidth().height(520.dp).background(MaterialTheme.colorScheme.background)) {
            ThemeShowcase()
        }
    }
}

@Preview
@Composable
private fun ThemeShowcaseLightPreview() {
    AppTheme(darkTheme = false) {
        Box(modifier = Modifier.fillMaxWidth().height(520.dp).background(MaterialTheme.colorScheme.background)) {
            ThemeShowcase()
        }
    }
}
