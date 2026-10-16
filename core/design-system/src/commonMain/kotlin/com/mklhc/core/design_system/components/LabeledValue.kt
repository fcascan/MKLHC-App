package com.mklhc.core.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A small caption over a value, for the kart-information rows ("Serial", "Firmware") and the link screen.
 *
 * Business logic: none; it is stateless. The label and value are merged into one accessibility node so a screen
 * reader announces them together ("Serial, 1234").
 *
 * @param label the caption, shown small and muted.
 * @param value the value, shown larger.
 * @param modifier the modifier to apply.
 */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview
@Composable
private fun LabeledValuePreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium)) {
            LabeledValue(label = "Serial", value = "SN-0012345")
            LabeledValue(label = "System firmware", value = "1.4")
            LabeledValue(label = "Boot loader", value = "0.9")
        }
    }
}
