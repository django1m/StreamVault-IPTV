package com.streamvault.app.ui.components.cinematic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.AppShapes
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalGlassProfile
import com.streamvault.app.ui.design.elevationFor
import com.streamvault.app.ui.interaction.TvClickableSurface

/**
 * Poster card in the Apple-TV idiom: crisp rounded rectangle, halo glow on focus,
 * spring scale, subtle inner vignette so edges don't feel flat against dark
 * backgrounds. Works for both portrait posters and 16:9 stills — the caller
 * controls the [width] and [aspectRatio] via [width] and [height].
 *
 * Prefer [CinematicPosterCard] for VOD / hero shelves. Dense live-TV grids should
 * keep the lighter existing `ChannelCard` (less per-item cost) or use the
 * dedicated [CinematicChannelCard] (see home/components) that's tuned for density.
 */
@Composable
fun CinematicPosterCard(
    title: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 196.dp,
    height: Dp = 280.dp,
    subtitle: String? = null,
    badge: (@Composable () -> Unit)? = null,
    overlayGradient: Brush = defaultPosterGradient,
    accentColor: Color = AppColors.GlowBrand,
    onLongClick: (() -> Unit)? = null
) {
    val shape = LocalAppShapes.current.posterHero
    val profile = LocalGlassProfile.current
    val elevation = elevationFor(profile)
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
        targetValue = if (focused) FocusSpec.FocusedScaleCinematic else 1f,
        animationSpec = AppMotion.ScaleSpring,
        label = "posterScale"
    )
    TvClickableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .focusGlow(isFocused = focused, color = accentColor)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(width, height)
            .shadow(
                elevation = if (focused) elevation.cardFocused else elevation.card,
                shape = shape,
                clip = false,
                ambientColor = accentColor,
                spotColor = accentColor
            ),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = AppColors.TextPrimary
        )
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape)) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(AppColors.SurfaceElevated))
            }
            Box(Modifier.fillMaxSize().background(overlayGradient))
            if (title != null) {
                val hero = LocalAppHeroStyles.current
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = title,
                        style = hero.heroMeta,
                        color = AppColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = hero.sectionEyebrow,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) { badge() }
            }
        }
    }
}

private val defaultPosterGradient: Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.55f to Color.Transparent,
    1f to Color(0xD9000000)
)
