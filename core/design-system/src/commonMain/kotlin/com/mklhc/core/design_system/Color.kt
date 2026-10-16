package com.mklhc.core.design_system

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens and the two Material colour schemes of the app.
 *
 * Business logic: the app is used while driving a kart by its camera feed, often outdoors, so the dark scheme
 * is the primary one: a near-black blue-grey background keeps the video the brightest thing on screen, and the
 * foreground colours are chosen for strong contrast against it (all body text is well above the 7:1 AAA ratio).
 * The accents follow instrument-panel convention and are mapped onto Material roles so that components never
 * name a colour, they read `MaterialTheme.colorScheme`:
 *
 * - primary (cyan): the live, normal accent, such as forward throttle, a healthy link and the HUD strokes.
 * - secondary (amber): caution and reverse, such as a low battery or a weak signal.
 * - tertiary (green): success, such as a ready kart or a full battery.
 * - error (red): fault and stop, such as a failed link, an empty battery or the brake light.
 *
 * The light scheme exists for completeness (daylight settings screens); [AppTheme] defaults to dark.
 */

// ---- dark palette (primary) ----------------------------------------------------------------------

internal val CockpitBackground = Color(0xFF080B10)
internal val CockpitSurface = Color(0xFF0F141B)
internal val CockpitSurfaceHigh = Color(0xFF1A222D)
internal val CockpitSurfaceHighest = Color(0xFF232E3C)
internal val CockpitOnSurface = Color(0xFFE6EDF5)
internal val CockpitOnSurfaceMuted = Color(0xFFA9B6C6)
internal val CockpitOutline = Color(0xFF6B7A8C)
internal val CockpitOutlineSubtle = Color(0xFF2E3A48)

internal val InstrumentCyan = Color(0xFF4DD8F0)
internal val InstrumentCyanContainer = Color(0xFF004E5C)
internal val InstrumentAmber = Color(0xFFFFB74D)
internal val InstrumentAmberContainer = Color(0xFF5C3A00)
internal val InstrumentGreen = Color(0xFF5BE38B)
internal val InstrumentGreenContainer = Color(0xFF00522A)
internal val InstrumentRed = Color(0xFFFF5449)
internal val InstrumentRedContainer = Color(0xFF93000A)

// ---- light palette -------------------------------------------------------------------------------

internal val DaylightBackground = Color(0xFFF5F8FB)
internal val DaylightSurface = Color(0xFFFFFFFF)
internal val DaylightSurfaceHigh = Color(0xFFE6ECF2)
internal val DaylightOnSurface = Color(0xFF0E141B)
internal val DaylightOnSurfaceMuted = Color(0xFF45515F)
internal val DaylightOutline = Color(0xFF7A8796)

internal val DaylightCyan = Color(0xFF006878)
internal val DaylightCyanContainer = Color(0xFFB8EEFA)
internal val DaylightAmber = Color(0xFF8A5100)
internal val DaylightAmberContainer = Color(0xFFFFDDB0)
internal val DaylightGreen = Color(0xFF006D3B)
internal val DaylightGreenContainer = Color(0xFFB6F7CD)
internal val DaylightRed = Color(0xFFBA1A1A)
internal val DaylightRedContainer = Color(0xFFFFDAD6)

/** The high-contrast dark scheme the cockpit and every other screen use by default. */
internal val AppDarkColorScheme: ColorScheme = darkColorScheme(
    primary = InstrumentCyan,
    onPrimary = Color(0xFF00222A),
    primaryContainer = InstrumentCyanContainer,
    onPrimaryContainer = Color(0xFFB8EEFA),
    secondary = InstrumentAmber,
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = InstrumentAmberContainer,
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = InstrumentGreen,
    onTertiary = Color(0xFF00210E),
    tertiaryContainer = InstrumentGreenContainer,
    onTertiaryContainer = Color(0xFFB6F7CD),
    error = InstrumentRed,
    onError = Color(0xFF410002),
    errorContainer = InstrumentRedContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    background = CockpitBackground,
    onBackground = CockpitOnSurface,
    surface = CockpitSurface,
    onSurface = CockpitOnSurface,
    surfaceVariant = CockpitSurfaceHigh,
    onSurfaceVariant = CockpitOnSurfaceMuted,
    surfaceTint = InstrumentCyan,
    inverseSurface = CockpitOnSurface,
    inverseOnSurface = CockpitBackground,
    inversePrimary = DaylightCyan,
    outline = CockpitOutline,
    outlineVariant = CockpitOutlineSubtle,
    scrim = Color(0xFF000000),
    surfaceBright = CockpitSurfaceHighest,
    surfaceDim = CockpitBackground,
    surfaceContainerLowest = CockpitBackground,
    surfaceContainerLow = CockpitSurface,
    surfaceContainer = CockpitSurfaceHigh,
    surfaceContainerHigh = CockpitSurfaceHighest,
    surfaceContainerHighest = Color(0xFF2C3949),
)

/** The daylight scheme, for the screens that are used away from the video feed. */
internal val AppLightColorScheme: ColorScheme = lightColorScheme(
    primary = DaylightCyan,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = DaylightCyanContainer,
    onPrimaryContainer = Color(0xFF001F26),
    secondary = DaylightAmber,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = DaylightAmberContainer,
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = DaylightGreen,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = DaylightGreenContainer,
    onTertiaryContainer = Color(0xFF00210E),
    error = DaylightRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = DaylightRedContainer,
    onErrorContainer = Color(0xFF410002),
    background = DaylightBackground,
    onBackground = DaylightOnSurface,
    surface = DaylightSurface,
    onSurface = DaylightOnSurface,
    surfaceVariant = DaylightSurfaceHigh,
    onSurfaceVariant = DaylightOnSurfaceMuted,
    surfaceTint = DaylightCyan,
    inverseSurface = CockpitSurface,
    inverseOnSurface = CockpitOnSurface,
    inversePrimary = InstrumentCyan,
    outline = DaylightOutline,
    outlineVariant = Color(0xFFC4CDD8),
    scrim = Color(0xFF000000),
)
