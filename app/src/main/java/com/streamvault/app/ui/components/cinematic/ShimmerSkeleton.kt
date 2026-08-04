package com.streamvault.app.ui.components.cinematic

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamvault.app.ui.design.AppColors

private val shimmerAnimSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = 1200, easing = LinearEasing),
    repeatMode = RepeatMode.Restart
)

/**
 * Stateless linear-gradient shimmer brush. Animated from off-screen left → off-screen
 * right. Safe to call from multiple skeletons — the [rememberInfiniteTransition]
 * is per-call but Compose deduplicates identical infinite specs.
 */
@Composable
fun rememberShimmerBrush(
    baseColor: Color = AppColors.SurfaceElevated,
    highlightColor: Color = AppColors.SurfaceAccent
): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = shimmerAnimSpec,
        label = "shimmerProgress"
    )
    // A linear gradient from base → highlight → base, shifted along [progress].
    val start = progress * 600f
    val end = start + 320f
    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = androidx.compose.ui.geometry.Offset(start, 0f),
        end = androidx.compose.ui.geometry.Offset(end, 0f)
    )
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    Box(
        modifier = modifier.background(brush = rememberShimmerBrush(), shape = shape)
    )
}

@Composable
fun ShimmerPoster(
    width: Dp = 200.dp,
    height: Dp = 296.dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp)
) {
    ShimmerBox(modifier = modifier.size(width, height), shape = shape)
}

@Composable
fun ShimmerRowCell(
    width: Dp = 320.dp,
    height: Dp = 180.dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp)
) {
    ShimmerBox(modifier = modifier.size(width, height), shape = shape)
}

@Composable
fun ShimmerHero(
    height: Dp = 480.dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(32.dp)
) {
    ShimmerBox(modifier = modifier.fillMaxWidth().height(height), shape = shape)
}
