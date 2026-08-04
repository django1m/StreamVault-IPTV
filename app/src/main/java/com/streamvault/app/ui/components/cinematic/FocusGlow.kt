package com.streamvault.app.ui.components.cinematic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.design.LocalGlassProfile

/**
 * Radial halo drawn behind a focusable composable when it holds focus. Cheaper
 * than `Modifier.blur` and than stacking two shadows. Respects [LocalGlassProfile]
 * — if the profile bans halos (low-spec path) the modifier is a no-op.
 *
 * Placement: apply BEFORE the background/shape modifiers so the glow sits behind
 * the card itself. Example: `Modifier.focusGlow(isFocused).clip(shape).background(...)`.
 */
fun Modifier.focusGlow(
    isFocused: Boolean,
    color: Color = AppColors.GlowBrand,
    radiusMultiplier: Float = 0.9f,
    spread: Dp = FocusSpec.GlowSpread
): Modifier = composed {
    val profile = LocalGlassProfile.current
    if (!profile.allowHalo) return@composed this
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = AppMotion.FocusSpring,
        label = "focusGlowAlpha"
    )
    if (alpha <= 0.01f) return@composed this
    this.drawBehind {
        val spreadPx = spread.toPx()
        val maxDim = maxOf(size.width, size.height)
        val radius = maxDim * radiusMultiplier + spreadPx
        val center = Offset(size.width / 2f, size.height / 2f)
        val gradient = Brush.radialGradient(
            0f to color.copy(alpha = color.alpha * alpha),
            0.6f to color.copy(alpha = color.alpha * alpha * 0.4f),
            1f to Color.Transparent,
            center = center,
            radius = radius
        )
        drawRect(
            brush = gradient,
            topLeft = Offset(
                x = -spreadPx * 2f,
                y = -spreadPx * 2f
            ),
            size = Size(
                width = size.width + spreadPx * 4f,
                height = size.height + spreadPx * 4f
            )
        )
    }
}
