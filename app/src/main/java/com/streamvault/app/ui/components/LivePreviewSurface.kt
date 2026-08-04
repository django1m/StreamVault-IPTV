package com.streamvault.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerSurfaceResizeMode

/**
 * Renders a [PlayerEngine] into a plain video surface.
 *
 * Three lines that look copy-pasteable and are not: the create / bind / release triplet
 * has to stay paired with the engine's own lifecycle, and getting it wrong produces a
 * black rectangle rather than an error. Shared between the Home preview pane and the
 * search preview so there is exactly one copy to get right.
 *
 * The auxiliary engine renders into a TextureView (see `NetworkModule`), which is what
 * lets this be laid out and animated like any other composable — the main engine's
 * SurfaceView could not be.
 */
@Composable
fun LivePreviewSurface(
    playerEngine: PlayerEngine,
    modifier: Modifier = Modifier,
    resizeMode: PlayerSurfaceResizeMode = PlayerSurfaceResizeMode.FIT
) {
    AndroidView(
        factory = { context ->
            playerEngine.createRenderView(context = context, resizeMode = resizeMode)
        },
        update = { renderView ->
            playerEngine.bindRenderView(renderView = renderView, resizeMode = resizeMode)
        },
        onRelease = { renderView ->
            playerEngine.releaseRenderView(renderView)
        },
        modifier = modifier
    )
}
