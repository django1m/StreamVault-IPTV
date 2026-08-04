package com.streamvault.player

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Routes playback to the appropriate engine based on the resolved stream type.
 *
 * - PROGRESSIVE (MP4 / MKV / etc.) → LibVLC (handles broken moov/stco metadata)
 * - everything else (HLS, DASH, MPEG-TS live, unknown) → Media3
 *
 * The two engines build incompatible render views (Media3 PlayerView vs
 * VLCVideoLayout). To keep a single Compose AndroidView factory call, this
 * engine exposes a stable FrameLayout container and hot-swaps the inner
 * sub-view at prepare() time when the active engine changes.
 *
 * State flows are forwarded from the active engine via a coroutine job that
 * is cancelled and restarted on every swap. Persistent settings (volume,
 * subtitle style, network preferences, …) are applied to BOTH engines so the
 * user-visible state stays consistent across swaps.
 */
class RoutingPlayerEngine(
    private val media3: Media3PlayerEngine,
    private val libvlc: LibVlcPlayerEngine
) : PlayerEngine {

    companion object {
        private const val TAG = "RoutingPlayerEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @Volatile
    private var activeEngine: PlayerEngine = media3
    private var forwardJob: Job? = null

    /**
     * One-shot override consumed by the next call to [prepare]. Lets
     * [com.streamvault.app.ui.screens.player.PlayerViewModel] retry a failing
     * stream on the alternate engine without bypassing the rest of the
     * preparation logic. Reset to `null` after each prepare so subsequent
     * playbacks fall back to the regular routing rules.
     */
    enum class EnginePreference { MEDIA3, LIBVLC }
    @Volatile
    private var nextPrepareEngineOverride: EnginePreference? = null

    private var containerView: FrameLayout? = null
    private var activeSubView: View? = null
    private var activeSubViewEngine: PlayerEngine? = null
    private var lastResizeMode: PlayerSurfaceResizeMode = PlayerSurfaceResizeMode.FIT

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _videoFormat = MutableStateFlow(VideoFormat(0, 0))
    override val videoFormat: StateFlow<VideoFormat> = _videoFormat.asStateFlow()

    private val _error = MutableSharedFlow<PlayerError?>(replay = 1)
    override val error: Flow<PlayerError?> = _error.asSharedFlow()

    private val _retryStatus = MutableStateFlow<PlayerRetryStatus?>(null)
    override val retryStatus: StateFlow<PlayerRetryStatus?> = _retryStatus.asStateFlow()

    private val _playerStats = MutableStateFlow(PlayerStats())
    override val playerStats: StateFlow<PlayerStats> = _playerStats.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _mediaTitle = MutableStateFlow<String?>(null)
    override val mediaTitle: StateFlow<String?> = _mediaTitle.asStateFlow()

    private val _availableAudioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = _availableAudioTracks.asStateFlow()

    private val _availableSubtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = _availableSubtitleTracks.asStateFlow()

    private val _availableVideoTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableVideoTracks: StateFlow<List<PlayerTrack>> = _availableVideoTracks.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    init {
        startForwarding(activeEngine)
    }

    // ---- Routing decision -------------------------------------------------

    private fun engineFor(streamInfo: StreamInfo): PlayerEngine {
        nextPrepareEngineOverride?.let { override ->
            // One-shot override: consume it so the next stream falls back to
            // the regular routing rules.
            nextPrepareEngineOverride = null
            return when (override) {
                EnginePreference.MEDIA3 -> media3
                EnginePreference.LIBVLC -> libvlc
            }
        }
        val type = StreamTypeResolver.resolve(streamInfo)
        // Only progressive containers go through LibVLC. Everything else —
        // including UNKNOWN — stays on Media3 to avoid the green-screen
        // hardware-decoder issue libVLC exhibits on the Amlogic S905X5M for
        // live HLS / MPEG-TS streams.
        return if (type == ResolvedStreamType.PROGRESSIVE) libvlc else media3
    }

    /**
     * Force the next [prepare] call to use a specific engine, regardless of the
     * stream type. The override is consumed by the next prepare() and then
     * cleared. Pass `null` to clear a pending override.
     */
    fun forceEngineForNextPrepare(preference: EnginePreference?) {
        nextPrepareEngineOverride = preference
    }

    /**
     * The engine the next prepare() would currently target — used by the
     * ViewModel to decide whether an engine fallback is even possible (e.g. no
     * point overriding to Media3 if Media3 is already the active engine).
     */
    fun currentEnginePreference(): EnginePreference =
        if (activeEngine === libvlc) EnginePreference.LIBVLC else EnginePreference.MEDIA3

    // ---- Lifecycle --------------------------------------------------------

    override fun prepare(streamInfo: StreamInfo) {
        val target = engineFor(streamInfo)
        if (target !== activeEngine) {
            Log.i(TAG, "swap engine ${describe(activeEngine)} → ${describe(target)} for ${streamInfo.url}")
            // Swap activeEngine FIRST so any in-flight collect from the old
            // forwardJob (cancellation is asynchronous) auto-filters itself
            // out via the `engine === activeEngine` check in startForwarding.
            val previous = activeEngine
            activeEngine = target
            runCatching { previous.stop() }
            resetAggregatedStateForSwap()
            swapSubViewIfNeeded()
            // Forward only AFTER target.prepare(): StateFlow replays its
            // current value to a new collector, so subscribing first would
            // replay the stale position/duration/state left over from the
            // target's previous playback (frozen-seekbar bug). Both engines
            // reset their flows synchronously at the top of prepare(), so the
            // replayed values are fresh. try/finally: even if prepare()
            // throws, the UI must end up observing the target's error flow.
            try {
                target.prepare(streamInfo)
            } finally {
                startForwarding(target)
            }
        } else {
            target.prepare(streamInfo)
            // release() (player screen back-out) cancels forwardJob. A
            // same-engine re-entry never reaches the swap branch above, so
            // without this re-arm the aggregated flows stayed frozen on their
            // last values — the dominant "controls stuck while video plays"
            // path (every second session froze unless the engine changed).
            if (forwardJob?.isActive != true) {
                startForwarding(target)
            }
        }
    }

    /**
     * Clears the aggregated flows at engine-swap time so the UI never shows
     * values from the previous engine while the target engine prepares.
     * BUFFERING (not IDLE) keeps the loading spinner up during the swap.
     */
    private fun resetAggregatedStateForSwap() {
        _playbackState.value = PlaybackState.BUFFERING
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _videoFormat.value = VideoFormat(0, 0)
        _retryStatus.value = null
        _playerStats.value = PlayerStats()
        _mediaTitle.value = null
        _availableAudioTracks.value = emptyList()
        _availableSubtitleTracks.value = emptyList()
        _availableVideoTracks.value = emptyList()
    }

    override fun restart() = activeEngine.restart()
    override fun play() = activeEngine.play()
    override fun pause() = activeEngine.pause()
    override fun stop() = activeEngine.stop()
    override fun stopForBackground() = activeEngine.stopForBackground()
    override fun seekTo(positionMs: Long) = activeEngine.seekTo(positionMs)
    override fun seekForward(ms: Long) = activeEngine.seekForward(ms)
    override fun seekBackward(ms: Long) = activeEngine.seekBackward(ms)

    override fun setDecoderMode(mode: DecoderMode) {
        // Apply to both so the requested mode survives a swap.
        media3.setDecoderMode(mode)
        libvlc.setDecoderMode(mode)
    }

    override fun setMediaSessionEnabled(enabled: Boolean) {
        media3.setMediaSessionEnabled(enabled)
        libvlc.setMediaSessionEnabled(enabled)
    }

    override fun setVolume(volume: Float) {
        media3.setVolume(volume)
        libvlc.setVolume(volume)
    }

    override fun setMuted(muted: Boolean) {
        media3.setMuted(muted)
        libvlc.setMuted(muted)
    }

    override fun toggleMute() {
        // Toggle on the active engine, then mirror the resulting state on the
        // other so a swap keeps the user-visible mute state.
        activeEngine.toggleMute()
        val muted = activeEngine.isMuted.value
        if (activeEngine === media3) libvlc.setMuted(muted) else media3.setMuted(muted)
    }

    override fun setPlaybackSpeed(speed: Float) {
        media3.setPlaybackSpeed(speed)
        libvlc.setPlaybackSpeed(speed)
    }

    override fun setPreferredAudioLanguage(languageTag: String?) {
        media3.setPreferredAudioLanguage(languageTag)
        libvlc.setPreferredAudioLanguage(languageTag)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        media3.setSubtitleStyle(style)
        libvlc.setSubtitleStyle(style)
    }

    override fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) {
        media3.setNetworkQualityPreferences(wifiMaxHeight, ethernetMaxHeight)
        libvlc.setNetworkQualityPreferences(wifiMaxHeight, ethernetMaxHeight)
    }

    override fun selectAudioTrack(trackId: String) = activeEngine.selectAudioTrack(trackId)
    override fun selectVideoTrack(trackId: String) = activeEngine.selectVideoTrack(trackId)
    override fun selectSubtitleTrack(trackId: String?) = activeEngine.selectSubtitleTrack(trackId)
    override fun setScrubbingMode(enabled: Boolean) = activeEngine.setScrubbingMode(enabled)

    override fun preload(streamInfo: StreamInfo?) {
        // Preload only on Media3 — LibVLC's preload is a no-op and the live
        // path (which is what benefits from preload) always routes to Media3.
        media3.preload(streamInfo)
    }

    override fun release() {
        forwardJob?.cancel()
        forwardJob = null
        // This engine is a @Singleton that outlives every PlayerViewModel.
        // Without purging here, the next ViewModel's collectors receive the
        // previous session's error (SharedFlow replay=1) and stale
        // retryStatus/playbackState during their own construction — the
        // replayed error fired handlePlaybackError before field
        // initializers ran (NPE crash in prod v517).
        _error.resetReplayCache()
        _retryStatus.value = null
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        runCatching { media3.release() }
        runCatching { libvlc.release() }
        // The container survives — Compose owns it. Clear our cached
        // references to the sub-view so the next bindRenderView() rebuilds.
        containerView?.let { container ->
            activeSubView?.let { sub -> if (sub.parent === container) container.removeView(sub) }
        }
        activeSubView = null
        activeSubViewEngine = null
    }

    // ---- Render view (container + hot-swap) ------------------------------

    override fun createRenderView(context: Context, resizeMode: PlayerSurfaceResizeMode): View {
        lastResizeMode = resizeMode
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }.also { container ->
            containerView = container
            activeSubView = null
            activeSubViewEngine = null
        }
    }

    override fun bindRenderView(renderView: View, resizeMode: PlayerSurfaceResizeMode) {
        val container = renderView as? FrameLayout ?: run {
            Log.w(TAG, "bindRenderView: expected FrameLayout, got ${renderView.javaClass.name}")
            return
        }
        containerView = container
        lastResizeMode = resizeMode
        ensureSubView(container, activeEngine)
        activeSubView?.let { sub -> activeEngine.bindRenderView(sub, resizeMode) }
    }

    override fun releaseRenderView(renderView: View) {
        val container = renderView as? FrameLayout ?: return
        if (containerView !== container) return
        activeSubView?.let { sub ->
            runCatching { activeSubViewEngine?.releaseRenderView(sub) }
            if (sub.parent === container) container.removeView(sub)
        }
        activeSubView = null
        activeSubViewEngine = null
        containerView = null
    }

    /**
     * Called from prepare() when the active engine changes. If the container
     * exists, swap its sub-view to one produced by the new active engine and
     * rebind. If the container does not exist yet, the swap will happen on
     * the next bindRenderView().
     */
    private fun swapSubViewIfNeeded() {
        val container = containerView ?: return
        ensureSubView(container, activeEngine)
        activeSubView?.let { sub -> activeEngine.bindRenderView(sub, lastResizeMode) }
    }

    private fun ensureSubView(container: FrameLayout, engine: PlayerEngine) {
        if (activeSubViewEngine === engine && activeSubView != null) return
        // Tear down the previous sub-view if any.
        activeSubView?.let { previous ->
            runCatching { activeSubViewEngine?.releaseRenderView(previous) }
            if (previous.parent === container) container.removeView(previous)
        }
        val newSub = engine.createRenderView(container.context, lastResizeMode)
        if (newSub.layoutParams == null) {
            newSub.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(newSub)
        activeSubView = newSub
        activeSubViewEngine = engine
    }

    // ---- Forwarding -------------------------------------------------------

    private fun startForwarding(engine: PlayerEngine) {
        forwardJob?.cancel()
        forwardJob = scope.launch {
            // Each collect filters by engine identity: if a swap happens while
            // this job is still cancelling, leftover emissions from the old
            // engine are dropped instead of polluting the unified state.
            launch { engine.playbackState.collect { if (engine === activeEngine) _playbackState.value = it } }
            launch { engine.isPlaying.collect { if (engine === activeEngine) _isPlaying.value = it } }
            launch { engine.currentPosition.collect { if (engine === activeEngine) _currentPosition.value = it } }
            launch { engine.duration.collect { if (engine === activeEngine) _duration.value = it } }
            launch { engine.videoFormat.collect { if (engine === activeEngine) _videoFormat.value = it } }
            launch { engine.error.collect { if (engine === activeEngine) _error.tryEmit(it) } }
            launch { engine.retryStatus.collect { if (engine === activeEngine) _retryStatus.value = it } }
            launch { engine.playerStats.collect { if (engine === activeEngine) _playerStats.value = it } }
            launch { engine.playbackSpeed.collect { if (engine === activeEngine) _playbackSpeed.value = it } }
            launch { engine.mediaTitle.collect { if (engine === activeEngine) _mediaTitle.value = it } }
            launch { engine.availableAudioTracks.collect { if (engine === activeEngine) _availableAudioTracks.value = it } }
            launch { engine.availableSubtitleTracks.collect { if (engine === activeEngine) _availableSubtitleTracks.value = it } }
            launch { engine.availableVideoTracks.collect { if (engine === activeEngine) _availableVideoTracks.value = it } }
            launch { engine.isMuted.collect { if (engine === activeEngine) _isMuted.value = it } }
        }
    }

    private fun describe(engine: PlayerEngine): String = when (engine) {
        media3 -> "Media3"
        libvlc -> "LibVLC"
        else -> engine.javaClass.simpleName
    }
}
