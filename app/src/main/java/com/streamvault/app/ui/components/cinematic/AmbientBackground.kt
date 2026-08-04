package com.streamvault.app.ui.components.cinematic

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.streamvault.app.ui.design.AmbientPalette
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion

/**
 * Full-bleed ambient background. Two concentric radial gradients tinted with the
 * current [palette] fade into the app canvas. The colour transition is a low-stiffness
 * spring so changing the focused hero feels like a lighting rig moving, not a cut.
 *
 * [content] is drawn on top — the background stays flat behind it with no intermediate
 * allocations.
 */
@Composable
fun AmbientBackground(
    palette: AmbientPalette,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val animatedSurface by animateColorAsState(
        targetValue = palette.surface,
        animationSpec = AppMotion.AmbientColorSpring,
        label = "ambientSurface"
    )
    val animatedShadow by animateColorAsState(
        targetValue = palette.shadow,
        animationSpec = AppMotion.AmbientColorSpring,
        label = "ambientShadow"
    )
    val animatedAccent by animateColorAsState(
        targetValue = palette.accent.copy(alpha = 0.28f),
        animationSpec = AppMotion.AmbientColorSpring,
        label = "ambientAccent"
    )
    val baseBrush = remember(animatedSurface, animatedShadow) {
        Brush.verticalGradient(
            0f to animatedSurface,
            0.55f to animatedShadow,
            1f to AppColors.Canvas
        )
    }
    val accentBrush = remember(animatedAccent) {
        Brush.radialGradient(
            0f to animatedAccent,
            0.6f to animatedAccent.copy(alpha = animatedAccent.alpha * 0.25f),
            1f to androidx.compose.ui.graphics.Color.Transparent
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(baseBrush))
        Box(Modifier.fillMaxSize().background(accentBrush))
        content()
    }
}
