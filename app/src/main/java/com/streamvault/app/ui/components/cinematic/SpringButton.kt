package com.streamvault.app.ui.components.cinematic

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonDefaults
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvButton
import androidx.compose.animation.core.animateFloatAsState

/**
 * Drop-in button with cinematic feel. Scales via a spring on focus / press, keeps
 * the halo modifier so the focus ring reads at TV distance, and delegates the
 * actual click handling to [TvButton] (which already solves mouse / touch support).
 */
@Composable
fun SpringButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    haloColor: Color = AppColors.GlowBrand,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is FocusInteraction.Focus -> focused = true
                is FocusInteraction.Unfocus -> focused = false
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
            }
        }
    }
    val targetScale = when {
        pressed -> FocusSpec.PressedScaleCinematic
        focused -> 1.04f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = AppMotion.ScaleSpring,
        label = "springButtonScale"
    )
    TvButton(
        onClick = onClick,
        modifier = modifier
            .focusGlow(isFocused = focused, color = haloColor)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        content = content
    )
}
