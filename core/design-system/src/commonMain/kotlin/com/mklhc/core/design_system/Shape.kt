package com.mklhc.core.design_system

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shape and size tokens.
 *
 * Business logic: corners are generous (the controls are pressed by a thumb on a moving, outdoor screen, so
 * they should look and feel like big soft targets) and the touch target size is a token so every tappable
 * component honours the same 48dp minimum.
 */

/** The Material shape scale used by [AppTheme]. */
internal val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** A fully rounded capsule, for status pills. */
val PillShape: RoundedCornerShape = RoundedCornerShape(percent = 50)

/** Sizes and spacing shared by the design-system components. */
object AppDimens {
    /** The smallest size of anything the user presses, per the platform accessibility guidance. */
    val MinTouchTarget: Dp = 48.dp

    /** Standard gap between related elements. */
    val SpacingSmall: Dp = 8.dp

    /** Standard gap between groups and the standard screen gutter. */
    val SpacingMedium: Dp = 16.dp
}
