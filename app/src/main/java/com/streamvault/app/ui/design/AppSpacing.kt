package com.streamvault.app.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class AppSpacing(
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 40.dp,
    val screenGutter: Dp = 56.dp,
    val railWidth: Dp = 124.dp,
    val sectionGap: Dp = 32.dp,
    val cardGap: Dp = 16.dp,
    val chipGap: Dp = 10.dp,
    val safeTop: Dp = 32.dp,
    val safeBottom: Dp = 32.dp,
    val safeHoriz: Dp = 56.dp
)

/**
 * TV: the defaults above. `screenGutter`/`safeHoriz` of 56dp and 32dp vertical
 * are overscan margins — a 10-foot screen can physically crop the edges.
 */
val TvAppSpacing = AppSpacing()

/** Tablet: overscan is meaningless, but there is still room to breathe. */
val TabletAppSpacing = AppSpacing(
    screenGutter = 32.dp,
    railWidth = 88.dp,
    sectionGap = 28.dp,
    cardGap = 14.dp,
    safeTop = 12.dp,
    safeBottom = 12.dp,
    safeHoriz = 24.dp
)

/**
 * Phone: no overscan, no rail, and the safe insets are 0 because the real ones
 * come from `WindowInsets` (status bar, navigation bar, display cutout) — baking
 * a constant in would double up with them.
 */
val PhoneAppSpacing = AppSpacing(
    lg = 20.dp,
    xl = 24.dp,
    xxl = 32.dp,
    screenGutter = 16.dp,
    railWidth = 0.dp,
    sectionGap = 20.dp,
    cardGap = 12.dp,
    chipGap = 8.dp,
    safeTop = 0.dp,
    safeBottom = 0.dp,
    safeHoriz = 16.dp
)

fun appSpacingFor(formFactor: FormFactor): AppSpacing = when (formFactor) {
    FormFactor.Tv -> TvAppSpacing
    FormFactor.Tablet -> TabletAppSpacing
    FormFactor.Phone -> PhoneAppSpacing
}

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
