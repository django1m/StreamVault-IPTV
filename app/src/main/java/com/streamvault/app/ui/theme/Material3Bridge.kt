package com.streamvault.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import com.streamvault.app.ui.design.AppColors
import androidx.compose.material3.Typography as M3Typography
import androidx.tv.material3.Typography as TvTypography

/**
 * Bridge between the two Material 3 implementations this app depends on.
 *
 * The UI is built on `androidx.tv.material3`, but a good third of the screens
 * already reach for `androidx.compose.material3` components that TV Material
 * simply does not have (Slider, Switch, AlertDialog, SnackbarHost,
 * CircularProgressIndicator...), and the whole mobile layer needs many more
 * (NavigationBar, ModalBottomSheet, TopAppBar, PullToRefreshBox, Scaffold).
 *
 * The two libraries expose *distinct* CompositionLocals: a composable reads the
 * one from its own package, so nesting both themes is unambiguous and cannot
 * collide. Until now only the TV theme was provided, which left every standard
 * M3 component rendering on M3's default purple baseline — hence the explicit
 * `SliderDefaults.colors(...)` scattered around the player. Providing both fixes
 * that everywhere at once, without touching the ~70 files that import
 * `androidx.tv.material3`.
 */

/**
 * Maps the TV typography onto the M3 one, slot for slot.
 *
 * Verified against tv-material 1.0.1 and material3 1.3.2: both expose exactly
 * the same 15 named slots, so this is a total mapping with nothing to invent.
 */
fun TvTypography.toMaterial3(): M3Typography = M3Typography(
    displayLarge = displayLarge,
    displayMedium = displayMedium,
    displaySmall = displaySmall,
    headlineLarge = headlineLarge,
    headlineMedium = headlineMedium,
    headlineSmall = headlineSmall,
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall
)

/**
 * The brand palette expressed as a standard M3 dark scheme.
 *
 * Mirrors the TV `darkColorScheme` in [StreamVaultTheme], and additionally fills
 * the slots TV Material has no equivalent for but that mobile components read:
 * the `surfaceContainer*` family (NavigationBar, ModalBottomSheet, Card),
 * `outline`/`outlineVariant` (dividers, borders) and the container pairs. Left
 * to M3's defaults these would be purple.
 */
val Material3DarkColorScheme: ColorScheme = darkColorScheme(
    primary = AppColors.Brand,
    onPrimary = AppColors.Canvas,
    primaryContainer = AppColors.SurfaceAccent,
    onPrimaryContainer = AppColors.TextPrimary,

    secondary = AppColors.BrandStrong,
    onSecondary = AppColors.Canvas,
    secondaryContainer = AppColors.SurfaceEmphasis,
    onSecondaryContainer = AppColors.TextPrimary,

    tertiary = AppColors.AccentMint,
    onTertiary = AppColors.Canvas,
    tertiaryContainer = AppColors.SurfaceElevated,
    onTertiaryContainer = AppColors.TextPrimary,

    background = AppColors.Canvas,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceElevated,
    onSurfaceVariant = AppColors.TextSecondary,

    // Elevation tiers used by NavigationBar / BottomSheet / Card on mobile.
    surfaceContainerLowest = AppColors.Canvas,
    surfaceContainerLow = AppColors.CanvasElevated,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceElevated,
    surfaceContainerHighest = AppColors.SurfaceEmphasis,
    surfaceBright = AppColors.SurfaceAccent,
    surfaceDim = AppColors.Canvas,
    inverseSurface = AppColors.TextPrimary,
    inverseOnSurface = AppColors.Canvas,
    inversePrimary = AppColors.SurfaceAccent,

    outline = AppColors.TextTertiary,
    outlineVariant = AppColors.Outline,
    scrim = AppColors.OverlayDim,

    error = AppColors.Live,
    onError = AppColors.TextPrimary,
    errorContainer = AppColors.SurfaceElevated,
    onErrorContainer = AppColors.Live
)
