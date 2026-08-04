package com.streamvault.player.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.R

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewBinder(
    private val subtitleStyleController: SubtitleStyleController
) {
    private var boundPlayerView: PlayerView? = null
    private var boundResizeMode: PlayerSurfaceResizeMode = PlayerSurfaceResizeMode.FIT

    fun createRenderView(
        context: Context,
        resizeMode: PlayerSurfaceResizeMode,
        useSurfaceView: Boolean = false
    ): View {
        val parent = FrameLayout(context)
        // SurfaceView : laisse le scaler matériel (VPP) de la box agrandir le flux
        // sous-natif (upscaling plus net + GPU/RAM déchargés) — réservé au lecteur
        // plein écran. TextureView pour la mosaïque/preview (plans d'overlay limités).
        val layoutRes = if (useSurfaceView) R.layout.player_surface_view else R.layout.player_texture_view
        return (LayoutInflater.from(context).inflate(layoutRes, parent, false) as PlayerView).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setShutterBackgroundColor(Color.BLACK)
            applyResizeMode(resizeMode)
        }
    }

    fun bind(renderView: View, player: androidx.media3.exoplayer.ExoPlayer, resizeMode: PlayerSurfaceResizeMode) {
        val playerView = renderView as? PlayerView ?: return
        boundResizeMode = resizeMode
        if (boundPlayerView !== playerView) {
            boundPlayerView?.player = null
            playerView.player = player
            boundPlayerView = playerView
        } else if (playerView.player !== player) {
            playerView.player = player
        }
        playerView.applyResizeMode(resizeMode)
        subtitleStyleController.apply(playerView)
    }

    fun attachPlayer(player: androidx.media3.exoplayer.ExoPlayer?) {
        boundPlayerView?.player = player
        boundPlayerView?.applyResizeMode(boundResizeMode)
        subtitleStyleController.apply(boundPlayerView)
    }

    fun release(renderView: View) {
        val playerView = renderView as? PlayerView ?: return
        playerView.player = null
        if (boundPlayerView === playerView) {
            boundPlayerView = null
        }
    }

    fun clear() {
        boundPlayerView?.player = null
        boundPlayerView = null
    }

    fun reapplyStyle() {
        subtitleStyleController.apply(boundPlayerView)
    }

    private fun PlayerView.applyResizeMode(surfaceResizeMode: PlayerSurfaceResizeMode) {
        resizeMode = when (surfaceResizeMode) {
            PlayerSurfaceResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            PlayerSurfaceResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            PlayerSurfaceResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }
}
