package com.streamvault.app.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

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
}
