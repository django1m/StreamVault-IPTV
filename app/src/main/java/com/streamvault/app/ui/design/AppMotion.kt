package com.streamvault.app.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Set to true by the theme when the system's animator scale is 0 (reduced
 * motion accessibility setting). Composables that opt into the helpers below
 * collapse their animations to a snap.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

object AppMotion {
    const val Fast = 120
    const val Standard = 160
    const val Emphasis = 180
    const val Ambient = 800

    val FocusSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = Standard,
        easing = LinearOutSlowInEasing
    )

    // Cinematic spring specs. Dense lists (EPG tiles, settings rows) should keep using
    // FocusSpec (tween) because a spring per row multiplies the frame cost.
    // Springs are reserved for hero surfaces, cards, buttons, chips.
    val FocusSpring: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = 520f
    )

    val ScaleSpring: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.68f,
        stiffness = 680f
    )

    val HeroEnterSpring: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.85f,
        stiffness = 260f
    )

    val OverlayFade: FiniteAnimationSpec<Float> = tween(
        durationMillis = 220,
        easing = FastOutSlowInEasing
    )

    val AmbientColorSpring: FiniteAnimationSpec<Color> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 120f
    )

    val ParallaxSpring: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 300f
    )

    val DpSpring: FiniteAnimationSpec<Dp> = spring(
        dampingRatio = 0.72f,
        stiffness = 520f
    )

    private val SnapFloat: FiniteAnimationSpec<Float> = snap()
    private val SnapDp: FiniteAnimationSpec<Dp> = snap()
    private val SnapColor: FiniteAnimationSpec<Color> = snap()

    @Composable
    @ReadOnlyComposable
    fun focusSpec(): FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) SnapFloat else FocusSpec

    @Composable
    @ReadOnlyComposable
    fun focusSpring(): FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) SnapFloat else FocusSpring

    @Composable
    @ReadOnlyComposable
    fun scaleSpring(): FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) SnapFloat else ScaleSpring

    @Composable
    @ReadOnlyComposable
    fun heroEnterSpring(): FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) SnapFloat else HeroEnterSpring

    @Composable
    @ReadOnlyComposable
    fun overlayFade(): FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) SnapFloat else OverlayFade

    @Composable
    @ReadOnlyComposable
    fun dpSpring(): FiniteAnimationSpec<Dp> =
        if (LocalReducedMotion.current) SnapDp else DpSpring

    @Composable
    @ReadOnlyComposable
    fun ambientColorSpring(): FiniteAnimationSpec<Color> =
        if (LocalReducedMotion.current) SnapColor else AmbientColorSpring
}
