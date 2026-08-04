package com.streamvault.app.ui.components.cinematic

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Parallax offset via [graphicsLayer]. Receives a lambda so Compose can read the
 * latest offset each frame without recomposing the target composable — only the
 * draw pass is repeated.
 *
 * Keep [factor] in [0.1f, 0.5f]. A factor > 0.5f produces disorienting motion on
 * TV because the user has no scroll proprioception.
 */
fun Modifier.parallaxY(
    scrollOffsetPx: () -> Float,
    factor: Float = 0.2f
): Modifier = graphicsLayer {
    translationY = scrollOffsetPx() * factor
}

fun Modifier.parallaxX(
    scrollOffsetPx: () -> Float,
    factor: Float = 0.2f
): Modifier = graphicsLayer {
    translationX = scrollOffsetPx() * factor
}

/**
 * Zoom parallax: as content scrolls, the image scales slightly. Use on poster
 * heroes where the image should subtly expand while the rest of the page slides up.
 */
fun Modifier.parallaxZoom(
    scrollOffsetPx: () -> Float,
    minScale: Float = 1f,
    maxScale: Float = 1.08f,
    rangePx: Float = 600f
): Modifier = graphicsLayer {
    val progress = (scrollOffsetPx() / rangePx).coerceIn(-1f, 1f)
    val s = minScale + (maxScale - minScale) * (1f - progress.coerceAtLeast(0f))
    scaleX = s
    scaleY = s
}
