package com.streamvault.app.ui.components.cinematic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppShapes
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalAppSpacing

/**
 * Cinematic hero backdrop. Three stacked layers:
 *  1. The artwork, scaled to crop. Receives a parallax translation through
 *     [parallaxOffsetPx] — a lambda so the parent can drive it from a scroll state
 *     without triggering recomposition of this composable.
 *  2. A dark vertical scrim so bottom-aligned copy stays legible over any image.
 *  3. A brand-tinted horizontal scrim so the left side (where the title lives) is
 *     predictably readable.
 *
 * Content (title / actions / meta) is positioned in the [content] slot; a default
 * `HeroBackdropContent` helper is provided for the common text-over-image layout.
 */
@Composable
fun HeroBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    height: Dp = 520.dp,
    parallaxOffsetPx: () -> Float = { 0f },
    parallaxFactor: Float = 0.25f,
    scrimTint: Color = AppColors.ScrimBottom,
    accentTint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(LocalAppShapes.current.hero)
    ) {
        if (imageUrl != null) {
            AsyncImage(
                // Backdrops sit behind two scrims at TV viewing distance: cap
                // the decode at 720p and use RGB_565 (opaque JPEGs) — ~1.8 MB
                // instead of ~8.3 MB ARGB_8888 at 1080p, which alone consumed
                // more than half of the LOW-tier Coil memory cache.
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .size(1280, 720)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = parallaxOffsetPx() * parallaxFactor }
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AppColors.CanvasElevated)
            )
        }
        // Vertical scrim — darker at the bottom where copy lives.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to AppColors.ScrimMiddle,
                        1f to scrimTint
                    )
                )
        )
        // Horizontal accent — brand-tinted shadow on the left edge to pop the title.
        if (accentTint != Color.Transparent) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to accentTint,
                            0.55f to Color.Transparent
                        )
                    )
            )
        }
        content()
    }
}

/**
 * Default hero content layout: eyebrow / title / subtitle / actions stacked at
 * bottom-left, with safe padding.
 */
@Composable
fun BoxScope.HeroBackdropContent(
    title: String,
    subtitle: String? = null,
    eyebrow: String? = null,
    meta: String? = null,
    actions: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val spacing = LocalAppSpacing.current
    val hero = LocalAppHeroStyles.current
    Column(
        modifier = modifier
            .fillMaxWidth(0.6f)
            .padding(horizontal = spacing.safeHoriz, vertical = spacing.lg)
            .align(Alignment.BottomStart)
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                style = hero.heroEyebrow,
                color = AppColors.BrandStrong
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = title,
            style = hero.heroTitle,
            color = AppColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = subtitle,
                style = hero.heroSubtitle,
                color = AppColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (meta != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = meta,
                style = hero.heroMeta,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (actions != null) {
            Spacer(Modifier.height(spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}
