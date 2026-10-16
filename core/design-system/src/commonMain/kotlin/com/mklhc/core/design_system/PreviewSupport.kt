package com.mklhc.core.design_system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Wraps a preview in [AppTheme] on the theme's own background, so a preview looks like the component does on a
 * real screen rather than on the preview tool's white canvas.
 *
 * Business logic: none; it exists only for the `@Preview` functions in this module (and for other modules'
 * previews of their screens), and is public so feature previews share the same framing.
 *
 * @param darkTheme whether to preview the dark cockpit scheme (the default) or the daylight one.
 * @param content the previewed UI.
 */
@Composable
fun PreviewSurface(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    AppTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.padding(AppDimens.SpacingMedium)) {
                content()
            }
        }
    }
}
