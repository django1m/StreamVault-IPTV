package com.streamvault.app.playback

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-slot transfer point used to hand a still-playing [PlayerEngine] from
 * the live preview pane in HomeScreen to the fullscreen PlayerScreen without
 * tearing down and re-opening the upstream IPTV connection.
 *
 * The provider rate-limits per-IP : when the preview engine is released and a
 * fresh fullscreen engine immediately reopens the same stream URL, the second
 * connection often hangs ("black screen") or stalls in BUFFERING. Parking the
 * engine here lets PlayerViewModel adopt it on the next `prepare()` call and
 * skip the network probe + ExoPlayer prepare entirely.
 */
@Singleton
class PlayerEngineHandoffRegistry @Inject constructor() : DefaultLifecycleObserver {

    data class Handoff(
        val engine: PlayerEngine,
        val channelId: Long,
        val providerId: Long,
        val streamInfo: StreamInfo,
        val parkedAtMs: Long
    )

    private var slot: Handoff? = null

    @Synchronized
    fun park(engine: PlayerEngine, channelId: Long, providerId: Long, streamInfo: StreamInfo) {
        slot?.let { previous ->
            runCatching { previous.engine.release() }
        }
        slot = Handoff(
            engine = engine,
            channelId = channelId,
            providerId = providerId,
            streamInfo = streamInfo,
            parkedAtMs = SystemClock.uptimeMillis()
        )
    }

    /**
     * Atomically take whatever is currently parked. Returns `null` if the slot
     * is empty or the parked engine has been sitting for more than [TTL_MS]
     * (in which case the stale engine is released to avoid leaks).
     */
    @Synchronized
    fun consume(): Handoff? {
        val current = slot ?: return null
        slot = null
        return if (SystemClock.uptimeMillis() - current.parkedAtMs > TTL_MS) {
            runCatching { current.engine.release() }
            null
        } else {
            current
        }
    }

    @Synchronized
    fun discardAll() {
        slot?.let { runCatching { it.engine.release() } }
        slot = null
    }

    /**
     * Safety net: if the user parked a preview engine but never reached the
     * fullscreen player (nav cancelled, backed out mid-transition) the slot
     * would otherwise linger until the next park/consume. Release it when the
     * whole app goes to the background — any legitimate handoff is consumed in
     * the foreground within a few hundred ms of parking.
     */
    override fun onStop(owner: LifecycleOwner) {
        discardAll()
    }

    companion object {
        private const val TTL_MS = 3_000L
    }
}
