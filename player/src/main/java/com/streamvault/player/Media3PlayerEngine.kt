package com.streamvault.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.MediaSession
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.audio.PlayerAudioFocusController
import com.streamvault.player.playback.DefaultDecoderPreferencePolicy
import com.streamvault.player.playback.DefaultPlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackErrorCategory
import com.streamvault.player.playback.PlaybackLogSanitizer
import com.streamvault.player.playback.PlaybackRetryContext
import com.streamvault.player.playback.PlayerDataSourceFactoryProvider
import com.streamvault.player.playback.PlayerErrorClassifier
import com.streamvault.player.playback.PlayerMediaSourceFactory
import com.streamvault.player.playback.PlayerRetryPolicy
import com.streamvault.player.playback.PlayerTimeoutProfile
import com.streamvault.player.playback.PreloadCoordinator
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import com.streamvault.player.stats.PlayerStatsCollector
import com.streamvault.player.system.DevicePerformanceProfile
import com.streamvault.player.tracks.PlayerTrackController
import com.streamvault.player.ui.PlayerViewBinder
import com.streamvault.player.ui.SubtitleStyleController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class Media3PlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val deviceProfile: DevicePerformanceProfile
) : PlayerEngine {

    companion object {
        private const val TAG = "Media3PlayerEngine"
        private const val SEEK_END_GUARD_MS = 5_000L
    }

    var constrainResolutionForMultiView: Boolean = false
    // Plein écran : rendre via un SurfaceView (couche vidéo matérielle + scaler VPP
    // Amlogic) plutôt qu'une TextureView (upscaling GPU bilinéaire plus mou et plus
    // lourd sur 2 Go). Laissé à false pour la mosaïque/preview (TextureView).
    var useSurfaceView: Boolean = false
    var bypassAudioFocus: Boolean = false
        set(value) {
            field = value
            audioFocusController.bypassAudioFocus = value
        }
    var enableMediaSession: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                exoPlayer?.let { player ->
                    if (mediaSession == null) {
                        mediaSession = MediaSession.Builder(context, player).build()
                    }
                }
            } else {
                releaseMediaSession()
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var exoPlayer: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var analyticsListener: AnalyticsListener? = null
    private var mediaSession: MediaSession? = null
    private var requestedDecoderMode: DecoderMode = DecoderMode.AUTO
    private var activeDecoderMode: DecoderMode = DecoderMode.HARDWARE
    private var lastStreamInfo: StreamInfo? = null
    private var lastMediaId: String? = null
    private var currentResolvedStreamType: ResolvedStreamType = ResolvedStreamType.UNKNOWN
    private var currentTimeoutProfile: PlayerTimeoutProfile = PlayerTimeoutProfile.VOD
    // Profil de buffer du LoadControl du player ACTIF. Le live utilise un buffer
    // court (rebuffer dans la fenêtre watchdog 6 s + heap natif bas sur 2 Go) ;
    // le VOD garde un gros buffer. Un changement de profil force recreatePlayer().
    private var activeBufferProfile: BufferProfile = BufferProfile.VOD
    private var currentRetryPolicy: PlayerRetryPolicy? = null
    private var currentRetryContext: PlaybackRetryContext? = null
    private var playbackStarted = false
    private var retryAttempt = 0
    // Incremented at the top of every prepareInternal(). A nested or stale
    // prepareInternal() (e.g. a future refactor re-adding the recreatePlayer()
    // re-entrance) must abandon itself when the generation moved on, instead
    // of issuing a second setMediaSource+prepare on the same player.
    private var prepareGeneration = 0L
    private var retryJob: Job? = null
    private var retryGeneration = 0L

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

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playerStats = MutableStateFlow(PlayerStats())
    override val playerStats: StateFlow<PlayerStats> = _playerStats.asStateFlow()

    private val _mediaTitle = MutableStateFlow<String?>(null)
    override val mediaTitle: StateFlow<String?> = _mediaTitle.asStateFlow()

    private val subtitleStyleController = SubtitleStyleController()
    private val viewBinder = PlayerViewBinder(subtitleStyleController)
    private val trackController = PlayerTrackController(context)
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = trackController.availableAudioTracks
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = trackController.availableSubtitleTracks
    override val availableVideoTracks: StateFlow<List<PlayerTrack>> = trackController.availableVideoTracks

    private val audioFocusController = PlayerAudioFocusController(
        context = context,
        applyVolume = { volume -> exoPlayer?.volume = volume },
        setPlayWhenReady = { playWhenReady -> exoPlayer?.playWhenReady = playWhenReady }
    ).also {
        it.bypassAudioFocus = bypassAudioFocus
    }
    override val isMuted: StateFlow<Boolean> = audioFocusController.isMuted

    private val statsCollector = PlayerStatsCollector(
        scopeProvider = { scope },
        currentPosition = _currentPosition,
        duration = _duration,
        videoFormat = _videoFormat,
        playerStats = _playerStats
    ).also {
        it.bind { exoPlayer }
    }
    private val dataSourceFactoryProvider = PlayerDataSourceFactoryProvider(context, okHttpClient)
    private val mediaSourceFactory = PlayerMediaSourceFactory(dataSourceFactoryProvider)
    private val preloadCoordinator = PreloadCoordinator()
    private val compatibilityProfile: PlaybackCompatibilityProfile = DefaultPlaybackCompatibilityProfile
    private val decoderPreferencePolicy = DefaultDecoderPreferencePolicy()

    private enum class BufferProfile { LIVE, VOD }

    private fun bufferProfileFor(type: ResolvedStreamType): BufferProfile =
        if (type == ResolvedStreamType.HLS || type == ResolvedStreamType.MPEG_TS_LIVE) {
            BufferProfile.LIVE
        } else {
            BufferProfile.VOD
        }

    override fun prepare(streamInfo: StreamInfo) {
        prepareInternal(streamInfo = streamInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
    }

    override fun restart() {
        val streamInfo = lastStreamInfo ?: return
        // Live streams must re-attach to the live edge (a stored position is
        // stale by the time we re-prepare). VOD / progressive content must
        // resume at the user's current playback position — restarting from
        // 0 would be unrecoverable bad UX after a transient stall mid-film.
        val isLive = currentResolvedStreamType == ResolvedStreamType.HLS ||
            currentResolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE
        val resumePosition = if (isLive) {
            null
        } else {
            exoPlayer?.currentPosition?.takeIf { it > 0L }
        }
        Log.i(
            TAG,
            "restart target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} resumeMs=$resumePosition isLive=$isLive"
        )
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = false,
            seekPositionMs = resumePosition,
            autoPlay = true
        )
    }

    override fun play() {
        if (audioFocusController.requestAudioFocusIfNeeded()) {
            exoPlayer?.playWhenReady = true
        }
    }

    override fun pause() {
        retryJob?.cancel()
        exoPlayer?.playWhenReady = false
        // Keep audio focus on pause — releasing then re-requesting on resume
        // intermittently fails on Amlogic S905X5M and leaves play/pause feeling
        // dead. Focus is only released on stop() / release().
        audioFocusController.onPause()
    }

    override fun stop() {
        retryJob?.cancel()
        statsCollector.stop()
        exoPlayer?.stop()
        _playbackState.value = PlaybackState.IDLE
        audioFocusController.onStop()
    }

    override fun stopForBackground() {
        // Same as stop() but WITHOUT releasing audio focus. exoPlayer.stop()
        // closes the upstream source/socket (frees the line); keeping the focus
        // avoids the Amlogic "play/pause dead" bug on resume (see pause()).
        retryJob?.cancel()
        statsCollector.stop()
        exoPlayer?.stop()
        _playbackState.value = PlaybackState.IDLE
        audioFocusController.onPause()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    override fun seekForward(ms: Long) {
        exoPlayer?.let { player ->
            val duration = player.duration
            if (duration != C.TIME_UNSET) {
                val safeMax = (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)
                if (player.currentPosition < safeMax) {
                    player.seekTo((player.currentPosition + ms).coerceAtMost(safeMax))
                }
            } else {
                player.seekTo(player.currentPosition + ms)
            }
        }
    }

    override fun seekBackward(ms: Long) {
        exoPlayer?.let { player ->
            player.seekTo((player.currentPosition - ms).coerceAtLeast(0L))
        }
    }

    override fun setDecoderMode(mode: DecoderMode) {
        if (requestedDecoderMode == mode) return
        requestedDecoderMode = mode
        lastStreamInfo?.let { streamInfo ->
            val wasPlaying = exoPlayer?.playWhenReady == true
            val position = exoPlayer?.currentPosition
            prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = position, autoPlay = wasPlaying)
        }
    }

    override fun setMediaSessionEnabled(enabled: Boolean) {
        enableMediaSession = enabled
    }

    override fun setVolume(volume: Float) {
        audioFocusController.setVolume(volume)
    }

    override fun setMuted(muted: Boolean) {
        audioFocusController.setMuted(muted)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2f)
        _playbackSpeed.value = clamped
        exoPlayer?.playbackParameters = PlaybackParameters(clamped)
    }

    override fun setPreferredAudioLanguage(languageTag: String?) {
        trackController.setPreferredAudioLanguage(exoPlayer, languageTag)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        subtitleStyleController.updateStyle(style)
        viewBinder.reapplyStyle()
    }

    override fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) {
        trackController.setNetworkQualityPreferences(wifiMaxHeight, ethernetMaxHeight)
        exoPlayer?.let { player -> trackController.applyInitialParameters(player, constrainResolutionForMultiView) }
    }

    override fun selectAudioTrack(trackId: String) {
        exoPlayer?.let { trackController.selectAudioTrack(it, trackId) }
    }

    override fun selectVideoTrack(trackId: String) {
        exoPlayer?.let { trackController.selectVideoTrack(it, trackId) }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        exoPlayer?.let { trackController.selectSubtitleTrack(it, trackId) }
    }

    override fun toggleMute() {
        audioFocusController.toggleMute()
    }

    override fun setScrubbingMode(enabled: Boolean) {
        val player = exoPlayer ?: return
        if (enabled) {
            player.setScrubbingModeEnabled(true)
        } else {
            player.setScrubbingModeEnabled(false)
            player.setScrubbingModeParameters(ScrubbingModeParameters.DEFAULT)
        }
    }

    override fun preload(streamInfo: StreamInfo?) {
        if (streamInfo == null) {
            preloadCoordinator.invalidate("cleared")
            return
        }
        if (streamInfo.url == lastStreamInfo?.url) return

        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        val resolvedStreamType = StreamTypeResolver.resolve(streamInfo)
        val retryContext = PlaybackRetryContext(
            resolvedStreamType = resolvedStreamType,
            timeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload = true)
        )
        val retryPolicy = PlayerRetryPolicy(retryContext) { false }
        val (_, mediaSource) = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = resolvedStreamType,
            retryPolicy = retryPolicy,
            preload = true
        )
        preloadCoordinator.store(mediaId, streamInfo, resolvedStreamType, mediaSource)
    }

    override fun createRenderView(context: Context, resizeMode: PlayerSurfaceResizeMode) =
        viewBinder.createRenderView(context, resizeMode, useSurfaceView)

    override fun bindRenderView(renderView: android.view.View, resizeMode: PlayerSurfaceResizeMode) {
        viewBinder.bind(renderView, getOrCreatePlayer(), resizeMode)
    }

    override fun releaseRenderView(renderView: android.view.View) {
        viewBinder.release(renderView)
    }

    override fun release() {
        retryJob?.cancel()
        retryJob = null
        preloadCoordinator.release()
        statsCollector.stop()
        audioFocusController.release()
        releaseMediaSession()
        viewBinder.clear()
        exoPlayer?.let { player ->
            playerListener?.let { runCatching { player.removeListener(it) } }
            analyticsListener?.let { runCatching { player.removeAnalyticsListener(it) } }
            runCatching { player.release() }
        }
        playerListener = null
        analyticsListener = null
        exoPlayer = null
        lastStreamInfo = null
        lastMediaId = null
        currentRetryPolicy = null
        currentRetryContext = null
        playbackStarted = false
        retryAttempt = 0
        _retryStatus.value = null
        // Purge the replayed error so the next screen's collectors don't
        // re-handle this session's failure at construction time.
        _error.resetReplayCache()
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _mediaTitle.value = null
        trackController.resetSelections()
        statsCollector.reset()
    }

    private fun prepareInternal(
        streamInfo: StreamInfo,
        preserveRetryState: Boolean,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ) {
        retryJob?.cancel()
        retryJob = null
        retryGeneration++
        val generation = ++prepareGeneration
        lastStreamInfo = streamInfo
        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        if (!preserveRetryState || lastMediaId != mediaId) {
            retryAttempt = 0
            _retryStatus.value = null
        }
        if (lastMediaId != mediaId) {
            decoderPreferencePolicy.resetForMedia(mediaId)
        }
        lastMediaId = mediaId
        playbackStarted = false
        _error.tryEmit(null)
        _mediaTitle.value = null
        trackController.resetSelections()
        // Official reset of _currentPosition/_duration for a new prepare —
        // RoutingPlayerEngine subscribes to these flows only after prepare()
        // returns and relies on them being fresh here. Do not remove.
        statsCollector.reset()

        currentResolvedStreamType = StreamTypeResolver.resolve(streamInfo)
        currentTimeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, currentResolvedStreamType, preload = false)
        val retryContext = PlaybackRetryContext(currentResolvedStreamType, currentTimeoutProfile)
        currentRetryContext = retryContext
        val retryPolicy = PlayerRetryPolicy(retryContext) { playbackStarted }
        currentRetryPolicy = retryPolicy

        val preferredDecoderMode = decoderPreferencePolicy.preferredMode(requestedDecoderMode, mediaId)
        val desiredBufferProfile = bufferProfileFor(currentResolvedStreamType)
        // Recréer si le mode décodeur OU le profil de buffer (live↔vod) change.
        // recreatePlayer() est un no-op s'il n'y a pas encore de player (premier
        // prepare) ; createPlayer() lira alors activeBufferProfile mis à jour ici.
        val profileChanged = activeDecoderMode != preferredDecoderMode ||
            activeBufferProfile != desiredBufferProfile
        activeDecoderMode = preferredDecoderMode
        activeBufferProfile = desiredBufferProfile
        if (profileChanged) {
            recreatePlayer()
        }
        if (generation != prepareGeneration) {
            Log.i(TAG, "prepare generation=$generation superseded, abandon")
            return
        }

        Log.i(
            TAG,
            "prepare resolvedStreamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile decoderPreference=$activeDecoderMode target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        val player = getOrCreatePlayer()
        trackController.applyInitialParameters(player, constrainResolutionForMultiView)
        player.playbackParameters = PlaybackParameters(_playbackSpeed.value)

        val mediaSource = preloadCoordinator.tryReuse(mediaId, streamInfo, currentResolvedStreamType)
            ?: mediaSourceFactory.create(
                streamInfo = streamInfo,
                resolvedStreamType = currentResolvedStreamType,
                retryPolicy = retryPolicy,
                preload = false
            ).second
        preloadCoordinator.onPlaybackStarted(mediaId)
        player.setMediaSource(mediaSource)
        player.prepare()
        seekPositionMs?.takeIf { it > 0L }?.let(player::seekTo)
        player.playWhenReady = autoPlay && audioFocusController.requestAudioFocusIfNeeded()
        viewBinder.attachPlayer(player)
    }

    /**
     * Releases the MediaSession safely. Snapshot + null-out FIRST so a
     * re-entrant call (release() racing a setter or recreatePlayer() on the
     * main thread) cannot double-release the same handle, and wrap the native
     * release in runCatching — MediaSession.release() can throw
     * IllegalStateException if the session was already torn down.
     */
    private fun releaseMediaSession() {
        val session = mediaSession
        mediaSession = null
        session?.let { runCatching { it.release() } }
    }

    /**
     * Tears down the current ExoPlayer so the next getOrCreatePlayer() builds
     * a fresh one with the updated decoder mode / buffer profile. Must NOT
     * call prepareInternal() itself: the only caller IS prepareInternal(),
     * which continues with getOrCreatePlayer() right after. The historical
     * re-entrant call here issued a second setMediaSource+prepare (double
     * network open, 1-3 s of extra restart latency on the S905X5M) and seeked
     * the NEW media to the OLD player's position.
     */
    private fun recreatePlayer() {
        val existing = exoPlayer
        if (existing != null) {
            releaseMediaSession()
            playerListener?.let { runCatching { existing.removeListener(it) } }
            analyticsListener?.let { runCatching { existing.removeAnalyticsListener(it) } }
            playerListener = null
            analyticsListener = null
            runCatching { existing.release() }
            exoPlayer = null
            viewBinder.attachPlayer(null)
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        return exoPlayer ?: createPlayer().also { player ->
            exoPlayer = player
            statsCollector.bind { exoPlayer }
            audioFocusController.reapplyVolume()
            if (enableMediaSession) {
                mediaSession = MediaSession.Builder(context, player).build()
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = buildRenderersFactory()
        // Low-end Amlogic boxes (2 GB) need a tighter max-buffer to keep the
        // native heap from spiking on long live streams.
        // [minBuffer, maxBuffer, bufferForPlayback, bufferForPlaybackAfterRebuffer]
        // LIVE : buffer court → rebuffer dans la fenêtre watchdog (6 s), heap bas,
        // moins de matraquage d'un panel rate-limité. VOD : gros buffer conservé.
        val bufferMs = if (activeBufferProfile == BufferProfile.LIVE) {
            when (deviceProfile.tier) {
                // Seul le MAX monte. min/playback/afterRebuffer sont inchangés, donc
                // le zapping démarre exactement aussi vite qu'avant : ce qui change,
                // c'est la RÉSERVE que le lecteur a le droit d'accumuler quand le
                // réseau va bien, et donc le trou qu'il peut traverser sans que
                // l'image s'arrête. Mesuré le 07/09/2026 sur une box en Wi-Fi :
                // des coupures de 60-90 s toutes les 1-2 min, là où un buffer de
                // 10 s ne tenait aucune d'elles. Coût mémoire : ~1,3 Mo par
                // seconde de réserve à 10 Mbps, borné par setTargetBufferBytes.
                DevicePerformanceProfile.Tier.LOW -> intArrayOf(6_000, 30_000, 1_500, 3_000)
                DevicePerformanceProfile.Tier.MID -> intArrayOf(6_000, 45_000, 1_500, 3_500)
                DevicePerformanceProfile.Tier.HIGH -> intArrayOf(8_000, 60_000, 2_000, 4_000)
            }
        } else {
            when (deviceProfile.tier) {
                DevicePerformanceProfile.Tier.LOW -> intArrayOf(15_000, 30_000, 2_500, 5_000)
                DevicePerformanceProfile.Tier.MID -> intArrayOf(20_000, 60_000, 2_500, 7_500)
                DevicePerformanceProfile.Tier.HIGH -> intArrayOf(30_000, 90_000, 2_500, 10_000)
            }
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferMs[0], bufferMs[1], bufferMs[2], bufferMs[3])
            .setPrioritizeTimeOverSizeThresholds(true)
            .apply {
                // VOD/LOW byte cap: 30 s of a very-high-bitrate MKV can exceed
                // 60 MB of native heap on the 2 GB box. 16 MB ≈ 8 s at 16 Mbps;
                // with prioritizeTimeOverSizeThresholds this is a secondary
                // guard, not the primary buffering driver.
                if (deviceProfile.tier == DevicePerformanceProfile.Tier.LOW &&
                    activeBufferProfile == BufferProfile.VOD
                ) {
                    setTargetBufferBytes(16 * 1024 * 1024)
                }
                // Live sur petite box : la réserve de 30 s ci-dessus vaut ~40 Mo à
                // 10 Mbps et bien plus sur une chaîne 4K. Ce plafond laisse le
                // buffer temporel s'exprimer sur un flux normal tout en empêchant
                // une chaîne à très haut débit de faire exploser le heap natif des
                // 2 Go — le garde-fou qui protège déjà le VOD, appliqué au live.
                if (deviceProfile.tier == DevicePerformanceProfile.Tier.LOW &&
                    activeBufferProfile == BufferProfile.LIVE
                ) {
                    setTargetBufferBytes(24 * 1024 * 1024)
                }
            }
            .build()
        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1.0f)
            .setFallbackMaxPlaybackSpeed(1.0f)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(livePlaybackSpeedControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
                    .build(),
                false
            )
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playbackParameters = PlaybackParameters(_playbackSpeed.value)
                val analytics = createAnalyticsListener().also { analyticsListener = it }
                val listener = createPlayerListener().also { playerListener = it }
                addAnalyticsListener(analytics)
                addListener(listener)
            }
    }

    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val baseFactory = if (compatibilityProfile.shouldDisableDecoderReuseWorkaround()) {
            object : NextRenderersFactory(context) {
                override fun buildVideoRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    eventHandler: Handler,
                    eventListener: VideoRendererEventListener,
                    allowedVideoJoiningTimeMs: Long,
                    out: ArrayList<Renderer>
                ) {
                    out.add(object : MediaCodecVideoRenderer(
                        context,
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        50
                    ) {
                        override fun canReuseCodec(
                            codecInfo: MediaCodecInfo,
                            oldFormat: Format,
                            newFormat: Format
                        ): DecoderReuseEvaluation {
                            return DecoderReuseEvaluation(
                                codecInfo.name,
                                oldFormat,
                                newFormat,
                                DecoderReuseEvaluation.REUSE_RESULT_NO,
                                DecoderReuseEvaluation.DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED
                            )
                        }
                    })
                }
            }
        } else {
            NextRenderersFactory(context)
        }

        return baseFactory.apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(
                when (activeDecoderMode) {
                    DecoderMode.AUTO, DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderMode.SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                }
            )
        }
    }

    private fun createAnalyticsListener(): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onVideoFormatChanged(format)
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onAudioFormatChanged(format)
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                statsCollector.onDroppedFrames(droppedFrames)
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                statsCollector.onBandwidthEstimate(bitrateEstimate)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                markPlaybackStarted("first-frame-success")
            }
        }
    }

    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val previousState = _playbackState.value
                _playbackState.value = when (state) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                }
                if (previousState == PlaybackState.READY && _playbackState.value == PlaybackState.BUFFERING) {
                    statsCollector.incrementRebufferCount()
                }
                if (_playbackState.value == PlaybackState.READY) {
                    _retryStatus.value = null
                    // Reset the retry budget once the stream is rendering again so
                    // a long-running channel keeps recovering from successive
                    // transient blips instead of exhausting maxAttempts after
                    // a few hours.
                    retryAttempt = 0
                }
                if (_playbackState.value == PlaybackState.READY && _isPlaying.value) {
                    markPlaybackStarted("ready-while-playing")
                }
                // The position pump must outlive transient stalls: keep it
                // alive through READY and BUFFERING (self-healing even if an
                // isPlaying transition was lost during a swap/recreate), and
                // only stop it when the player truly has nothing to report.
                when (_playbackState.value) {
                    PlaybackState.READY, PlaybackState.BUFFERING -> statsCollector.ensureStarted()
                    PlaybackState.IDLE, PlaybackState.ENDED -> statsCollector.stop()
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    if (_playbackState.value == PlaybackState.READY) {
                        markPlaybackStarted("playing-ready")
                    }
                    statsCollector.ensureStarted()
                    audioFocusController.onPlaybackStarted()
                }
                // isPlaying=false (pause or transient stall) no longer stops
                // the pump: stopping here froze the seekbar whenever the
                // matching isPlaying=true was never observed (frozen-controls
                // bug). The pump stops on IDLE/ENDED, stop() and release().
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _currentPosition.value = newPosition.positionMs
            }

            override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
                _mediaTitle.value = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                trackController.onTracksChanged(tracks)
            }
        }
    }

    private fun markPlaybackStarted(reason: String) {
        if (playbackStarted) return
        playbackStarted = true
        Log.i(
            TAG,
            "$reason streamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url)}"
        )
    }

    private fun handlePlaybackError(error: PlaybackException) {
        retryJob?.cancel()
        retryJob = null
        val streamInfo = lastStreamInfo
        val mediaId = lastMediaId
        val retryPolicy = currentRetryPolicy
        val retryContext = currentRetryContext
        val category = PlayerErrorClassifier.classify(error)

        if (streamInfo == null || mediaId == null || retryPolicy == null || retryContext == null) {
            _retryStatus.value = null
            _error.tryEmit(PlayerError.fromException(error))
            _playbackState.value = PlaybackState.ERROR
            return
        }

        if (category == PlaybackErrorCategory.DECODER) {
            val fallbackMode = decoderPreferencePolicy.onDecoderInitFailure(activeDecoderMode, mediaId)
            if (fallbackMode != null) {
                Log.w(
                    TAG,
                    "decoder-preference fallback=$fallbackMode mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
                )
                activeDecoderMode = fallbackMode
                prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = exoPlayer?.currentPosition, autoPlay = true)
                return
            }
        }

        val nextAttempt = retryAttempt + 1
        if (retryPolicy.shouldRetry(error, retryContext, playbackStarted, nextAttempt)) {
            val delayMs = retryPolicy.retryDelayMs(error, nextAttempt)
            val scheduledRetryGeneration = retryGeneration
            retryAttempt = nextAttempt
            _retryStatus.value = PlayerRetryStatus(
                attempt = nextAttempt,
                maxAttempts = retryPolicy.maxAttempts(error, playbackStarted),
                delayMs = delayMs
            )
            Log.w(
                TAG,
                "retry category=$category attempt=$nextAttempt delayMs=$delayMs reason=${retryPolicy.retryReason(error)} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
            )
            retryJob = scope.launch {
                delay(delayMs)
                if (
                    scheduledRetryGeneration != retryGeneration ||
                    lastMediaId != mediaId ||
                    retryAttempt != nextAttempt
                ) {
                    return@launch
                }
                retryJob = null
                if (category == PlaybackErrorCategory.LIVE_WINDOW) {
                    exoPlayer?.seekToDefaultPosition()
                }
                prepareInternal(streamInfo, preserveRetryState = true, seekPositionMs = null, autoPlay = true)
            }
            return
        }

        _retryStatus.value = null
        Log.e(
            TAG,
            "fatal-error category=$category target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
        )
        _error.tryEmit(PlayerError.fromException(error))
        _playbackState.value = PlaybackState.ERROR
    }
}
