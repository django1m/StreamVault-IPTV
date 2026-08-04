package com.streamvault.app.ui.components.cinematic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.GlassLevel
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.app.ui.design.glassSurface
import com.streamvault.app.ui.interaction.TvClickableSurface
import androidx.tv.material3.LocalContentColor
import com.streamvault.app.ui.design.OsdFocus

enum class CinematicChipStyle { Solid, Glass, Outlined }

@Composable
fun CinematicChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CinematicChipStyle = CinematicChipStyle.Glass,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is FocusInteraction.Focus -> focused = true
                is FocusInteraction.Unfocus -> focused = false
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = AppMotion.scaleSpring(),
        label = "chipScale"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> AppColors.Canvas
            focused -> AppColors.TextPrimary
            else -> AppColors.TextSecondary
        },
        label = "chipTextColor"
    )
    val shape = RoundedCornerShape(999.dp)
    val hero = LocalAppHeroStyles.current

    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .let { base ->
                when (style) {
                    CinematicChipStyle.Solid -> base
                    CinematicChipStyle.Glass -> base.glassSurface(level = GlassLevel.Medium, shape = shape)
                    CinematicChipStyle.Outlined -> base.border(
                        width = 1.5.dp,
                        color = if (selected) AppColors.Brand else AppColors.Outline,
                        shape = shape
                    )
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        // Point 7 of the language: a chip is a translucent pill at rest and a solid light
        // slab when focused. `selected` no longer fills — it tints the label, so a selected
        // chip and a focused one can never be mistaken for each other.
        colors = OsdFocus.slabColors(
            active = selected,
            restingContent = AppColors.TextSecondary
        ),
        border = OsdFocus.noBorder(),
        scale = OsdFocus.noScale(),
        interactionSource = interactionSource
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(contentPadding)
        ) {
            if (leading != null) leading()
            Text(
                text = label,
                style = hero.heroMeta,
                color = LocalContentColor.current
            )
            if (trailing != null) trailing()
        }
    }
}
