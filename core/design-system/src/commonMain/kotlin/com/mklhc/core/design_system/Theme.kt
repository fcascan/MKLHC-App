package com.mklhc.core.design_system

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The app theme: Material 3 with the cockpit colour scheme, type scale and shapes.
 *
 * Business logic: every screen is wrapped in this once, at the root. It defaults to the dark, high-contrast
 * scheme because the app is used while watching a live camera feed; pass `darkTheme = false` (typically from
 * the system setting, on screens away from the video) for the daylight scheme. Components read colours, text
 * styles and shapes from [MaterialTheme] and never hard-code them, so switching the scheme restyles everything.
 *
 * @param darkTheme whether to use the dark cockpit scheme; true by default.
 * @param content the UI to style.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AppDarkColorScheme else AppLightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
