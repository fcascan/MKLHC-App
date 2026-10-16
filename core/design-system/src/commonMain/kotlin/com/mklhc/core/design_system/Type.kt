package com.mklhc.core.design_system

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tokens.
 *
 * Business logic: the app uses the platform's default sans-serif for prose (no bundled fonts, so nothing to
 * license or load) and a monospace face for live numbers, so a changing value does not jitter the layout as its
 * digits change width. Material's type scale is kept and only tuned where a screen is read at a glance: body
 * and label sizes are nudged up a little for legibility on a phone held at arm's length.
 */

/** Large live number: the speed or throttle readout. Monospace so the digits do not shift as they change. */
val HudReadoutStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 44.sp,
    lineHeight = 48.sp,
    letterSpacing = 0.sp,
)

/** Small caption above or below a HUD value, for example "THROTTLE". */
val HudCaptionStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.2.sp,
)

private val Base = Typography()

/** The Material type scale used by [AppTheme]. */
internal val AppTypography: Typography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    displayMedium = Base.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)
