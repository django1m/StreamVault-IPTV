package com.streamvault.app.ui.screens.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.cast.CastManager
import com.streamvault.app.cast.CastMediaRequest
import com.streamvault.app.cast.CastStartResult
import com.streamvault.app.di.ApplicationScope
import com.streamvault.app.di.MainPlayerEngine
import com.streamvault.app.di.PlaybackHttpClient
import com.streamvault.app.playback.PlayerEngineHandoffRegistry
import com.streamvault.app.playback.XtreamContainerExtensionMemory
import com.streamvault.app.playback.XtreamPlaybackProber
import com.streamvault.app.util.isPlaybackComplete
import com.streamvault.app.vpn.allowsPanelCalls
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.MarkAsWatched
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerSubtitleStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import java.util.Locale
import okhttp3.OkHttpClient

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel @Inject constructor(
    @param:MainPlayerEngine
    private val injectedPlayerEngine: PlayerEngine,
    private val playerEngineHandoffRegistry: PlayerEngineHandoffRegistry,
    private val epgRepository: EpgRepository,
    internal val channelRepository: ChannelRepository,
    internal val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val favoriteRepository: com.streamvault.domain.repository.FavoriteRepository,
    internal val playbackHistoryRepository: PlaybackHistoryRepository,
    private val providerRepository: com.streamvault.domain.repository.ProviderRepository,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    private val getCustomCategories: GetCustomCategories,
    private val markAsWatched: MarkAsWatched,
    internal val scheduleRecordingUseCase: ScheduleRecording,
    internal val recordingManager: RecordingManager,
    private val watchNextManager: WatchNextManager,
    private val launcherRecommendationsManager: LauncherRecommendationsManager,
    internal val castManager: CastManager,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val seekThumbnailProvider: SeekThumbnailProvider,
    @param:PlaybackHttpClient private val playbackHttpClient: OkHttpClient,
    private val xtreamPlaybackProber: XtreamPlaybackProber,
    private val extensionMemory: XtreamContainerExtensionMemory,
    private val playbackReporter: com.streamvault.app.playback.PlaybackReporter,
    private val networkAvailability: com.streamvault.app.util.NetworkAvailability,
    private val diagnosticReporter: com.streamvault.app.diagnostics.DiagnosticReporter,
    private val vpnManager: com.streamvault.app.vpn.VpnManager,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    /**
     * Engine adopted from the live preview pane in HomeScreen via
     * [PlayerEngineHandoffRegistry]. Consumed eagerly during construction so
     * any Composable that later reads `viewModel.playerEngine` already sees
     * the adopted instance — no reactive wrapper needed. Null when the
     * fullscreen player was entered from a non-preview source.
     */
    private val handoffSnapshot: PlayerEngineHandoffRegistry.Handoff? =
        playerEngineHandoffRegistry.consume()

    /**
     * The active playback engine. Either the [injectedPlayerEngine] (the
     * @MainPlayerEngine RoutingPlayerEngine singleton) or — if the user got
     * here by double-clicking a previewed live channel — the still-playing
     * auxiliary Media3 engine taken from the handoff registry. Adoption only
     * happens once, before first composition, so [val]-like semantics hold
     * for all consumers despite the `var` declaration.
     */
    var playerEngine: PlayerEngine = handoffSnapshot?.engine ?: injectedPlayerEngine
        private set

    init {
        // The auxiliary Media3 used for the home preview pane is built with
        // bypassAudioFocus=true and enableMediaSession=false (see
        // NetworkModule.provideAuxiliaryPlayerEngine). After handoff the
        // fullscreen player owns the engine and we want proper audio focus +
        // a media-session for headphone / Bluetooth controls. Flip them
        // immediately so the user does not have to re-zap to get the right
        // behavior.
        val adopted = handoffSnapshot?.engine
        if (adopted is com.streamvault.player.Media3PlayerEngine) {
            adopted.bypassAudioFocus = false
            adopted.enableMediaSession = true
        }
    }

    /**
     * Live-only: true after the first adopted [prepare] call validated the
     * channel match. We then know there is no need to redo the engine prepare.
     * Reset after consumption so any subsequent zap re-runs the normal flow.
     */
    private var pendingHandoffChannelId: Long = handoffSnapshot?.channelId ?: -1L
    private var pendingHandoffProviderId: Long = handoffSnapshot?.providerId ?: -1L

    private fun consumeAdoptionMatch(channelId: Long, providerId: Long): Boolean {
        if (pendingHandoffChannelId == -1L) return false
        val match = pendingHandoffChannelId == channelId &&
            (pendingHandoffProviderId <= 0L ||
                providerId <= 0L ||
                pendingHandoffProviderId == providerId)
        pendingHandoffChannelId = -1L
        pendingHandoffProviderId = -1L
        return match
    }

    companion object {
        private const val MUTE_TOGGLE_DEBOUNCE_MS = 250L
        private const val MAX_PROGRAM_HISTORY_ITEMS = 18
        private const val MAX_UPCOMING_PROGRAM_ITEMS = 24
        private const val PLAYER_EPG_REFRESH_INTERVAL_MS = 30_000L
        // Grace period after backgrounding before we fully stop the engine and
        // close the upstream socket. Short app switches resume instantly.
        private const val BACKGROUND_STOP_GRACE_MS = 30_000L
        // Mid-stream stall recovery: time in READY→BUFFERING before suspecting
        // a panel-side stall. 8s absorbs a routine HLS segment reload (typical
        // Xtream segment = 2-4s, slow panel push = 5-7s) without false alarms.
        // Used for VOD (catch-up / movie / episode) where first-byte after a
        // re-buffer can legitimately be slower.
        private const val MID_STREAM_STALL_TIMEOUT_MS = 8_000L
        // Live channels stream continuous MPEG-TS, so a BUFFERING longer than a
        // few seconds is almost always a real panel-side stall, not a segment
        // reload. Tightening to 6s makes recovery feel closer to TiViMate without
        // false-triggering on the routine 2-4s Xtream hiccup. Tunable: raise it
        // if a slow provider triggers spurious reconnects.
        private const val MID_STREAM_STALL_TIMEOUT_LIVE_MS = 6_000L
        // Live "frozen frame" detection: the decoder stays in READY and isPlaying
        // is true, but the playback position stops advancing (panel feeds no new
        // bytes / hardware decoder wedged) WITHOUT any onPlayerError or a
        // transition to BUFFERING — so neither PlayerRetryPolicy nor the
        // BUFFERING watchdog above can see it. We poll the position and treat a
        // sustained freeze as a stall, routed through the same recovery policy.
        private const val LIVE_PROGRESS_CHECK_INTERVAL_MS = 2_000L
        private const val LIVE_PROGRESS_STALL_THRESHOLD_MS = 6_000L
        // Position jitter below this is treated as "not advancing" (rounding
        // while the surface is wedged on a single frame).
        private const val LIVE_PROGRESS_MIN_ADVANCE_MS = 250L
        // Max consecutive restarts without an intervening READY. After this,
        // we fall back instead of thrashing DNS + TLS on a stream that the
        // upstream truly cannot deliver right now. Bumped 2→3 : un hoquet
        // transitoire de 10-15 s du panel rate-limité mérite une tentative de
        // plus avant d'abandonner.
        private const val MID_STREAM_STALL_MAX_RESTARTS = 3
        // Cooldown between two restart attempts. Aligné sur la fenêtre ~30 s du
        // panel rate-limité (1 req/IP) : 60 s laissait passer des fenêtres de
        // récupération valides. 30 s reste sobre pour la box 2 GB S905X5M.
        private const val MID_STREAM_RESTART_COOLDOWN_MS = 30_000L
        // Initial-buffer timeout for VOD / series episodes. Unlike live (9s),
        // VOD first-byte can be legitimately slower (server seek into a large
        // MP4, on-the-fly remux), so we give it more room before declaring the
        // prepare dead and clearing the spinner. Without this the user can
        // stare at a frozen loader forever if the panel never feeds bytes.
        private const val VOD_PREPARE_TIMEOUT_MS = 25_000L
        // Si l'engine émet ENDED avant ce % de la durée totale, on considère
        // que c'est un faux EOF (coupure HTTP Xtream, métadonnées MP4/MKV
        // tronquées) et on tente une reconnexion au lieu d'enchaîner.
        private const val PREMATURE_END_THRESHOLD_PERCENT = 96L
        private const val PREMATURE_END_MAX_RETRIES = 3
        private const val PREMATURE_END_RETRY_BACKOFF_MS = 1_500L
        // Attente du tunnel VPN avant/pendant une lecture VOD : couvre un
        // cycle complet provision + double-vérification (~110-120 s).
        private const val VPN_PLAYBACK_GATE_MS = 120_000L
        // Une seule relance automatique post-VPN par fenêtre — évite toute
        // boucle relance→403→relance si le tunnel retombe aussitôt.
        private const val VPN_AUTO_RETRY_COOLDOWN_MS = 120_000L
        private const val VPN_WAIT_NOTICE =
            "Connexion au VPN sécurisé… la lecture reprendra automatiquement."
    }

    internal val showControlsFlow = MutableStateFlow(false)
    val showControls: StateFlow<Boolean> = showControlsFlow.asStateFlow()

    internal val showZapOverlayFlow = MutableStateFlow(false)
    val showZapOverlay: StateFlow<Boolean> = showZapOverlayFlow.asStateFlow()
    
    private val _currentProgram = MutableStateFlow<Program?>(null)
    val currentProgram: StateFlow<Program?> = _currentProgram.asStateFlow()

    private val _nextProgram = MutableStateFlow<Program?>(null)
    val nextProgram: StateFlow<Program?> = _nextProgram.asStateFlow()

    private val _programHistory = MutableStateFlow<List<Program>>(emptyList())
    val programHistory: StateFlow<List<Program>> = _programHistory.asStateFlow()

    private val _upcomingPrograms = MutableStateFlow<List<Program>>(emptyList())
    val upcomingPrograms: StateFlow<List<Program>> = _upcomingPrograms.asStateFlow()

    internal val currentChannelFlow = MutableStateFlow<com.streamvault.domain.model.Channel?>(null)
    val currentChannel: StateFlow<com.streamvault.domain.model.Channel?> = currentChannelFlow.asStateFlow()

    private val _currentSeries = MutableStateFlow<Series?>(null)
    val currentSeries: StateFlow<Series?> = _currentSeries.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode: StateFlow<Episode?> = _currentEpisode.asStateFlow()

    private val _nextEpisode = MutableStateFlow<Episode?>(null)
    val nextEpisode: StateFlow<Episode?> = _nextEpisode.asStateFlow()

    internal val playbackTitleFlow = MutableStateFlow("")
    val playbackTitle: StateFlow<String> = playbackTitleFlow.asStateFlow()
    
    private val _resumePrompt = MutableStateFlow(ResumePromptState())
    val resumePrompt: StateFlow<ResumePromptState> = _resumePrompt.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.FIT)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    internal val showChannelListOverlayFlow = MutableStateFlow(false)
    val showChannelListOverlay: StateFlow<Boolean> = showChannelListOverlayFlow.asStateFlow()

    internal val showEpgOverlayFlow = MutableStateFlow(false)
    val showEpgOverlay: StateFlow<Boolean> = showEpgOverlayFlow.asStateFlow()

    private val currentChannelFlowList = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val currentChannelList: StateFlow<List<com.streamvault.domain.model.Channel>> = currentChannelFlowList.asStateFlow()

    internal val recentChannelsFlow = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val recentChannels: StateFlow<List<com.streamvault.domain.model.Channel>> = recentChannelsFlow.asStateFlow()

    private val _lastVisitedCategory = MutableStateFlow<Category?>(null)
    val lastVisitedCategory: StateFlow<Category?> = _lastVisitedCategory.asStateFlow()

    internal val showCategoryListOverlayFlow = MutableStateFlow(false)
    val showCategoryListOverlay: StateFlow<Boolean> = showCategoryListOverlayFlow.asStateFlow()

    internal val availableCategoriesFlow = MutableStateFlow<List<Category>>(emptyList())
    val availableCategories: StateFlow<List<Category>> = availableCategoriesFlow.asStateFlow()

    internal val activeCategoryIdFlow = MutableStateFlow(-1L)
    val activeCategoryId: StateFlow<Long> = activeCategoryIdFlow.asStateFlow()

    internal val displayChannelNumberFlow = MutableStateFlow(0)
    val displayChannelNumber: StateFlow<Int> = displayChannelNumberFlow.asStateFlow()

    internal val showChannelInfoOverlayFlow = MutableStateFlow(false)
    val showChannelInfoOverlay: StateFlow<Boolean> = showChannelInfoOverlayFlow.asStateFlow()

    internal val numericChannelInputFlow = MutableStateFlow<NumericChannelInputState?>(null)
    val numericChannelInput: StateFlow<NumericChannelInputState?> = numericChannelInputFlow.asStateFlow()

    internal val showDiagnosticsFlow = MutableStateFlow(false)
    val showDiagnostics: StateFlow<Boolean> = showDiagnosticsFlow.asStateFlow()

    private val _playerNotice = MutableStateFlow<PlayerNoticeState?>(null)
    val playerNotice: StateFlow<PlayerNoticeState?> = _playerNotice.asStateFlow()
    private val _playerDiagnostics = MutableStateFlow(PlayerDiagnosticsUiState())
    val playerDiagnostics: StateFlow<PlayerDiagnosticsUiState> = _playerDiagnostics.asStateFlow()
    private val _seekPreview = MutableStateFlow(SeekPreviewState())
    val seekPreview: StateFlow<SeekPreviewState> = _seekPreview.asStateFlow()
    private val _recordingItems = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordingItems: StateFlow<List<RecordingItem>> = _recordingItems.asStateFlow()
    private val currentChannelFlowRecording = MutableStateFlow<RecordingItem?>(null)
    val currentChannelRecording: StateFlow<RecordingItem?> = currentChannelFlowRecording.asStateFlow()

    internal var channelInfoHideJob: Job? = null
    internal var liveOverlayHideJob: Job? = null
    internal var diagnosticsHideJob: Job? = null
    internal var numericInputCommitJob: Job? = null
    internal var numericInputFeedbackJob: Job? = null
    private var playerNoticeHideJob: Job? = null
    private var mutePersistJob: Job? = null
    private var recoveryJob: Job? = null
    internal var numericInputBuffer: String = ""
    internal val triedAlternativeStreams = mutableSetOf<String>()
    private val failedStreamsThisSession = mutableMapOf<String, Int>()
    private var hasRetriedWithSoftwareDecoder = false
    private var hasRetriedWithAlternateEngine = false
    private var hasRetriedXtreamAuthRefresh = false
    // Set when the pre-playback probe got 401/403 on a series/movie URL but
    // playback proceeded anyway (the engine's HTTP stack differs from the
    // probe's). Lets the terminal error paths show the provider-refusal
    // message instead of a generic one. Reset by beginPlaybackSession().
    private var probeAdvisoryHttpCode: Int? = null

    // Whether the CURRENT live session ever reached READY. A live stream that the
    // panel accepted and that never produces a first frame is the blackholed-exit
    // signature — there is no error to catch, so this flag is the only way to tell
    // "never started" from "started then stalled" when a watchdog fires.
    private var liveFirstFrameSeen = false

    /** Dernière relance auto post-activation VPN (epoch ms) — voir cooldown. */
    @Volatile private var lastVpnAutoRetryAtMs = 0L
    // Last probe-failure diagnostic identity — dedups the RETRY button
    // (each retry is a new session, so lastDiagnosedSession alone would
    // re-report the same rejection on every click).
    private var lastProbeDiagKey: Triple<Long, Long, Int>? = null
    internal var lastRecordedLivePlaybackKey: Pair<Long, Long>? = null
    private var currentStreamClassLabel: String = "Primary"

    // Replay / catch-up : le contentType reste "LIVE" (c’est bien une chaîne), mais le
    // flux est rejouable — position, durée et seek ont un sens, le zapping non. Suivi
    // PAR SESSION de lecture et non dérivé de currentStreamClassLabel : une bascule
    // vers un flux alternatif (tryAlternateStreamInternal) réécrit ce label en
    // "Alternate" et ferait silencieusement retomber le replay en mode zapping.
    private val _isTimeshiftPlayback = MutableStateFlow(false)
    val isTimeshiftPlayback: StateFlow<Boolean> = _isTimeshiftPlayback.asStateFlow()
    private var prepareRequestVersion: Long = 0L
    internal var currentArtworkUrl: String? = null
    private var currentResolvedPlaybackUrl: String = ""
    // Store current URL to find index later. Declared BEFORE the init block:
    // Kotlin runs initializers in declaration order, and the init block's
    // error collector (Main.immediate) can invoke handlePlaybackError during
    // construction — any field declared after init would still be null then
    // (prod v517 NPE via StringsKt.isBlank on this very field).
    internal var currentStreamUrl: String = ""
    internal var pendingCatchUpUrls: List<String> = emptyList()
    internal var channelNumberingMode: ChannelNumberingMode = ChannelNumberingMode.GROUP
    private var activeEpgRequestKey: EpgRequestKey? = null
    internal var playerControlsTimeoutMs: Long = 5_000L
    internal var liveOverlayTimeoutMs: Long = 4_000L
    private var playerNoticeTimeoutMs: Long = 6_000L
    internal var diagnosticsTimeoutMs: Long = 15_000L
    // Inactivity guard ("are you still watching?"). 0 = disabled. Re-armed on
    // every remote interaction; fires only for LIVE after this long with none.
    private var inactivityTimeoutMs: Long = 0L
    private var inactivityWatchdogJob: Job? = null
    private var preferredDecoderMode: DecoderMode = DecoderMode.AUTO

    // Zapping state
    internal var channelList: List<com.streamvault.domain.model.Channel> = emptyList()
    internal var currentChannelIndex = -1
    internal var previousChannelIndex = -1
    internal var currentCategoryId: Long = -1
    internal var currentProviderId: Long = -1L
    internal var currentContentId: Long = -1L
    internal var currentContentType: ContentType = ContentType.LIVE
    internal var currentTitle: String = ""
    private var currentSeriesId: Long? = null
    private var currentSeasonNumber: Int? = null
    private var currentEpisodeNumber: Int? = null
    internal var isVirtualCategory: Boolean = false
    private var lastObservedPlaybackState: PlaybackState = PlaybackState.IDLE
    // Gates every history write to an immutable per-session snapshot — see
    // PlaybackSessionGate. Never read currentContentId/currentSeriesId/... in
    // persistence code: those mutate before the engine actually switches media.
    private val sessionGate = PlaybackSessionGate()
    private var lastFinalPersistKey: Pair<Long, Long>? = null
    private var postSeekPersistJob: Job? = null

    private var epgJob: Job? = null
    private var playlistJob: Job? = null
    private var recentChannelsJob: Job? = null
    private var lastVisitedCategoryJob: Job? = null
    internal var controlsHideJob: Job? = null
    private var seekPreviewJob: Job? = null
    private var progressTrackingJob: Job? = null
    internal var zapOverlayJob: Job? = null
    private var aspectRatioJob: Job? = null
    internal var zapBufferWatchdogJob: Job? = null
    private var vodPrepareWatchdogJob: Job? = null
    private var midStreamStallWatchdogJob: Job? = null
    private var liveProgressWatchdogJob: Job? = null
    private var vodProgressWatchdogJob: Job? = null
    internal var preloadJob: Job? = null
    private val midStreamStallPolicy = MidStreamStallRecoveryPolicy(
        maxConsecutiveRestarts = MID_STREAM_STALL_MAX_RESTARTS,
        restartCooldownMs = MID_STREAM_RESTART_COOLDOWN_MS,
        clock = SystemClock::elapsedRealtime
    )
    private var prematureEndRetryJob: Job? = null
    private var prematureEndRetryAttempt: Int = 0
    private var pendingPrematureEndResumeMs: Long = 0L
    // URL the pending resume position belongs to. The post-ERROR retry
    // pipeline may go through several engine state changes before READY; we
    // must NOT re-apply the seek to a different stream the user navigated to
    // in the meantime.
    private var pendingPrematureEndUrl: String = ""
    private var isAppInForeground: Boolean = true
    private var shouldResumeAfterForeground: Boolean = false
    // Background connection-release: onAppBackgrounded() keeps the immediate
    // pause(), then after a short grace it stop()s the engine (closing the
    // upstream socket so the provider line is freed) if still backgrounded.
    // onAppForegrounded() cancels the timer and resumes via restart().
    // isEngineAlive guards the delayed job from touching a released engine
    // (onCleared() sets it false BEFORE release()).
    private var backgroundStopJob: Job? = null
    private var stoppedInBackground: Boolean = false
    private var backgroundResumePositionMs: Long = 0L
    @Volatile private var isEngineAlive: Boolean = true
    private var seekPreviewRequestVersion: Long = 0L
    private var lastMuteToggleAtMs: Long = 0L

    val castConnectionState: StateFlow<CastConnectionState> = castManager.connectionState

    internal fun logRepositoryFailure(operation: String, result: com.streamvault.domain.model.Result<Unit>) {
        if (result is com.streamvault.domain.model.Result.Error) {
            android.util.Log.w("PlayerVM", "$operation failed: ${result.message}", result.exception)
        }
    }

    private val progressiveSeekController = ProgressiveSeekController(scope = viewModelScope)
    private var wasPlayingBeforeProgressiveSeek = false

    val isProgressiveSeekActive: StateFlow<Boolean> = progressiveSeekController.isActive

    init {
        viewModelScope.launch {
            playerEngine.error.collect { error ->
                if (error != null) {
                    handlePlaybackError(error)
                }
            }
        }
        viewModelScope.launch {
            playerEngine.playbackState.collect { state ->
                _playerDiagnostics.update { it.copy(playbackStateLabel = state.name.replace('_', ' ')) }
                if (state == PlaybackState.ENDED && lastObservedPlaybackState != PlaybackState.ENDED) {
                    handlePlaybackEnded()
                }
                val previousState = lastObservedPlaybackState
                lastObservedPlaybackState = state
                if (state == PlaybackState.BUFFERING &&
                    previousState == PlaybackState.IDLE &&
                    currentContentType == ContentType.LIVE &&
                    currentChannelIndex in channelList.indices
                ) {
                    scheduleZapBufferWatchdog(currentChannelIndex)
                }
                // VOD / series have no zap watchdog and no "previous channel"
                // fallback, so a prepare that never leaves IDLE/BUFFERING would
                // hang the loader forever. Arm a dedicated timeout that stops
                // the engine and surfaces a retryable notice.
                if (state == PlaybackState.BUFFERING &&
                    previousState == PlaybackState.IDLE &&
                    (currentContentType == ContentType.MOVIE ||
                        currentContentType == ContentType.SERIES_EPISODE)
                ) {
                    scheduleVodPrepareWatchdog()
                }
                // Mid-stream stall (panel stopped feeding bytes without closing
                // the socket): PlayerRetryPolicy never fires because the
                // engine does not surface this as onPlayerError. Force-reconnect
                // via playerEngine.restart() so the user does not have to back
                // out and re-enter. Applies to BOTH live and VOD: VOD MP4s on
                // Xtream panels stall the same way as live channels. Skip if a
                // channel zap is in flight — that READY→BUFFERING is the zap
                // itself, handled by scheduleZapBufferWatchdog from
                // changeChannel().
                if (state == PlaybackState.BUFFERING &&
                    zapBufferWatchdogJob?.isActive != true &&
                    midStreamStallWatchdogJob?.isActive != true
                ) {
                    // LIVE : armer sur TOUTE entrée en BUFFERING (pas seulement
                    // READY→BUFFERING). Sans ça, un BUFFERING soutenu venu d'IDLE
                    // — restart qui re-bufferise sans atteindre READY, ou démarrage
                    // qui patine hors du chemin zap — n'avait AUCUN watchdog qui
                    // décompte → spinner figé pour toujours. Le zap watchdog garde
                    // la priorité (garde zap-active) pour la sélection initiale.
                    // VOD : on garde uniquement READY→BUFFERING (l'IDLE→BUFFERING
                    // initial est couvert par scheduleVodPrepareWatchdog).
                    if (currentContentType == ContentType.LIVE) {
                        val targetIndex = currentChannelIndex.takeIf { it in channelList.indices } ?: -1
                        scheduleMidStreamStallWatchdog(targetIndex)
                    } else if (previousState == PlaybackState.READY) {
                        scheduleMidStreamStallWatchdog(-1)
                    }
                }
                if (state == PlaybackState.READY) {
                    if (sessionGate.onEngineReady(prepareRequestVersion)) {
                        // First READY of this session: write the history row
                        // immediately so Continue Watching points at THIS item
                        // even if the user exits before the first 30 s tick.
                        recordSessionStart()
                        // A live channel that reaches READY through the tunnel
                        // proves any on-demand block is selective (region-specific),
                        // not a provider-wide outage — Class B ban evidence needs it.
                        if (currentContentType == ContentType.LIVE) {
                            liveFirstFrameSeen = true
                            vpnManager.reportLivePlaybackSuccess()
                        }
                    }
                    // Playback started, so the panel did NOT refuse this
                    // stream after all — a later unrelated error must not
                    // surface the provider-refusal message.
                    probeAdvisoryHttpCode = null
                    zapBufferWatchdogJob?.cancel()
                    vodPrepareWatchdogJob?.cancel()
                    midStreamStallWatchdogJob?.cancel()
                    midStreamStallPolicy.onPlaybackRecovered()
                    dismissRecoveredNoticeIfPresent()
                    // Arm the live "frozen frame" watchdog: a live channel that
                    // is playing should keep advancing its position. A stale
                    // requestVersion job (previous channel) self-exits on its
                    // next tick, so re-arming here is safe.
                    if (currentContentType == ContentType.LIVE) {
                        startLiveProgressWatchdog()
                        vodProgressWatchdogJob?.cancel()
                        // Arm the opt-in inactivity guard (no-op unless enabled).
                        scheduleInactivityWatchdog()
                    } else {
                        liveProgressWatchdogJob?.cancel()
                        inactivityWatchdogJob?.cancel()
                        // Filet de sécurité VOD : détecte une position figée alors
                        // que la lecture tourne (cf. correctif moteur A) et
                        // reconcilie/relance si jamais ça regèle.
                        startVodProgressWatchdog()
                    }
                    if (pendingPrematureEndResumeMs > 0L &&
                        pendingPrematureEndUrl.isNotEmpty() &&
                        pendingPrematureEndUrl == currentStreamUrl
                    ) {
                        val resumeMs = pendingPrematureEndResumeMs
                        pendingPrematureEndResumeMs = 0L
                        pendingPrematureEndUrl = ""
                        prematureEndRetryAttempt = 0
                        playerEngine.seekTo(resumeMs)
                    }
                    if (currentContentType == ContentType.LIVE) {
                        currentChannelFlow.value?.sanitizedForPlayer()?.let { channel ->
                            if (channel.errorCount > 0) {
                                logRepositoryFailure(
                                    operation = "Reset channel error count",
                                    result = channelRepository.resetChannelErrorCount(channel.id)
                                )
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            playerEngine.retryStatus.collect { status ->
                status ?: return@collect
                showRetryNotice(status)
            }
        }
        viewModelScope.launch {
            recordingManager.observeRecordingItems().collect { items ->
                _recordingItems.value = items
                refreshCurrentChannelRecording(items)
            }
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.playerSubtitleTextScale,
                preferencesRepository.playerSubtitleTextColor,
                preferencesRepository.playerSubtitleBackgroundColor
            ) { textScale, textColor, backgroundColor ->
                PlayerSubtitleStyle(
                    textScale = textScale,
                    foregroundColorArgb = textColor,
                    backgroundColorArgb = backgroundColor
                )
            }.collect(playerEngine::setSubtitleStyle)
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.playerControlsTimeoutSeconds,
                preferencesRepository.playerLiveOverlayTimeoutSeconds,
                preferencesRepository.playerNoticeTimeoutSeconds,
                preferencesRepository.playerDiagnosticsTimeoutSeconds
            ) { controlsSeconds, liveOverlaySeconds, noticeSeconds, diagnosticsSeconds ->
                PlayerUiTimeouts(
                    controlsMs = controlsSeconds * 1000L,
                    liveOverlayMs = liveOverlaySeconds * 1000L,
                    noticeMs = noticeSeconds * 1000L,
                    diagnosticsMs = diagnosticsSeconds * 1000L
                )
            }.collect { timeouts ->
                playerControlsTimeoutMs = timeouts.controlsMs
                liveOverlayTimeoutMs = timeouts.liveOverlayMs
                playerNoticeTimeoutMs = timeouts.noticeMs
                diagnosticsTimeoutMs = timeouts.diagnosticsMs
            }
        }
        viewModelScope.launch {
            preferencesRepository.playbackInactivityTimeoutSeconds.collect { seconds ->
                inactivityTimeoutMs = seconds * 1000L
                // Re-arm (or disarm) immediately when the setting changes.
                scheduleInactivityWatchdog()
            }
        }
        viewModelScope.launch {
            preferencesRepository.playerMediaSessionEnabled.collect(playerEngine::setMediaSessionEnabled)
        }
        viewModelScope.launch {
            preferencesRepository.playerDecoderMode.collect { mode ->
                preferredDecoderMode = mode
                if (!hasRetriedWithSoftwareDecoder) {
                    playerEngine.setDecoderMode(mode)
                    updateDecoderMode(mode)
                }
            }
        }
        viewModelScope.launch {
            progressiveSeekController.ticks.collect { tick ->
                when (tick.direction) {
                    SeekDirection.FORWARD  -> playerEngine.seekForward(tick.stepMs)
                    SeekDirection.BACKWARD -> playerEngine.seekBackward(tick.stepMs)
                }
            }
        }
    }

    // Tracks the playback session that already produced a diagnostic so a
    // re-entered handlePlaybackError never double-logs the same failure.
    private var lastDiagnosedSession: Long = -1L

    /** Emit ONE clear diagnostic per session, only when recovery is exhausted. */
    private fun reportTerminalPlaybackFailure(error: PlayerError) {
        if (lastDiagnosedSession == prepareRequestVersion) return
        lastDiagnosedSession = prepareRequestVersion
        diagnosticReporter.reportPlaybackFailure(
            error = error,
            contentType = currentContentType.name,
            title = currentTitle,
            engine = (playerEngine as? com.streamvault.player.RoutingPlayerEngine)
                ?.currentEnginePreference()?.name ?: "MEDIA3",
        )
        // Enforced on-demand 403: the pre-playback probe was refused 403 AND the
        // engine's real attempt then also failed terminally, on a movie/series.
        // This is region-ban evidence for the VPN health guard — but only if it
        // happened THROUGH the tunnel (the guard re-checks handshake freshness).
        if (probeAdvisoryHttpCode == 403 &&
            (currentContentType == ContentType.MOVIE || currentContentType == ContentType.SERIES_EPISODE)
        ) {
            vpnManager.reportEnforcedOnDemand403(currentProviderId, currentContentId)
        }
    }

    private fun handlePlaybackError(error: PlayerError) {
        // The engines are @Singletons: their error SharedFlow (replay=1) can
        // replay the previous ViewModel's failure to this one at construction
        // time. Until THIS ViewModel has started a session
        // (beginPlaybackSession pre-increments, so the first session is 1),
        // any error received here belongs to a dead session — ignore it.
        if (prepareRequestVersion == 0L) {
            android.util.Log.w(
                "PlayerVM",
                "Ignoring replayed pre-session player error: ${error.message}"
            )
            return
        }
        val requestVersion = prepareRequestVersion
        val playbackUrl = currentPlaybackIdentityUrl()
        // Always trace playback errors with their context — HTTP-code errors
        // (401/403/404…) from the engines were previously only visible when
        // they became terminal.
        android.util.Log.w(
            "PlayerVM",
            "playback error [${error.javaClass.simpleName}] " +
                "type=$currentContentType session=$requestVersion " +
                "advisoryHttp=$probeAdvisoryHttpCode " +
                "msg=${XtreamUrlFactory.sanitizeLogMessage(error.message)} " +
                "url=${XtreamUrlFactory.sanitizeLogMessage(playbackUrl)}"
        )
        recoveryJob?.cancel()
        if (error is PlayerError.DecoderError && !hasRetriedWithSoftwareDecoder) {
            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return
            hasRetriedWithSoftwareDecoder = true
            android.util.Log.w("PlayerVM", "Decoder error detected. Retrying with software decoder mode.")
            playerEngine.setDecoderMode(DecoderMode.SOFTWARE)
            updateDecoderMode(DecoderMode.SOFTWARE)
            setLastFailureReason(error.message)
            appendRecoveryAction("Switched to software decoder")
            playerEngine.play()
            showPlayerNotice(
                message = "Retrying with software decoding for this stream.",
                recoveryType = PlayerRecoveryType.DECODER,
                actions = buildRecoveryActions(PlayerRecoveryType.DECODER)
            )
            return
        }
        if ((error is PlayerError.SourceError || error is PlayerError.DecoderError) &&
            !hasRetriedWithAlternateEngine &&
            tryAlternateEngineInternal(error)
        ) {
            return
        }
        recoveryJob = viewModelScope.launch {
            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
            if (tryRefreshXtreamPlaybackAfterAuthError(error, requestVersion, playbackUrl)) {
                return@launch
            }

            val recoveryType = classifyPlaybackError(error)
            val channel = currentChannelFlow.value?.sanitizedForPlayer()

            if (currentContentType != ContentType.LIVE || channel == null) {
                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                reportTerminalPlaybackFailure(error)
                showPlayerNotice(
                    // When the pre-playback probe was already refused with
                    // 401/403 (advisory attempt), the provider refusal is the
                    // real story — not the engine's generic failure.
                    message = probeAdvisoryHttpCode?.let { probeFailureMessage(it) }
                        ?: resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
                return@launch
            }

            if (isCatchUpPlayback()) {
                markStreamFailure(currentStreamUrl)
                setLastFailureReason(error.message)

                val switched = when (recoveryType) {
                    PlayerRecoveryType.NETWORK,
                    PlayerRecoveryType.SOURCE,
                    PlayerRecoveryType.BUFFER_TIMEOUT -> tryNextCatchUpVariantInternal()
                    else -> false
                }

                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                if (switched) {
                    appendRecoveryAction("Trying alternate catch-up URL")
                    showPlayerNotice(
                        message = "Trying another replay path for ${channel.name}.",
                        recoveryType = PlayerRecoveryType.CATCH_UP,
                        actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP),
                        isRetryNotice = true
                    )
                    return@launch
                }

                reportTerminalPlaybackFailure(error)
                showPlayerNotice(
                    message = resolveCatchUpFailureMessage(
                        channel = channel,
                        archiveRequested = true,
                        programHasArchive = _currentProgram.value?.hasArchive == true
                    ),
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
                return@launch
            }

            markStreamFailure(currentStreamUrl)
            setLastFailureReason(error.message)
            logRepositoryFailure(
                operation = "Increment channel error count",
                result = channelRepository.incrementChannelErrorCount(channel.id)
            )

            val switched = when (recoveryType) {
                PlayerRecoveryType.NETWORK,
                PlayerRecoveryType.SOURCE,
                PlayerRecoveryType.BUFFER_TIMEOUT -> tryAlternateStreamInternal(channel)
                else -> false
            }

            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
            if (switched) {
                appendRecoveryAction("Trying alternate stream")
                showPlayerNotice(
                    message = "Trying an alternate stream for ${channel.name}.",
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType),
                    isRetryNotice = true
                )
                return@launch
            }

            reportTerminalPlaybackFailure(error)
            if (fallbackToPreviousChannel("Recovery path exhausted for ${recoveryType.name.lowercase()}")) {
                appendRecoveryAction("Returned to last channel")
                showPlayerNotice(
                    message = "Playback failed on this stream. Returned to the last channel.",
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
            } else {
                showPlayerNotice(
                    message = resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
            }
        }
    }

    internal fun refreshCurrentChannelRecording(items: List<RecordingItem> = _recordingItems.value) {
        val channelId = currentChannelFlow.value?.id ?: -1L
        currentChannelFlowRecording.value = items.firstOrNull {
            it.providerId == currentProviderId &&
                it.channelId == channelId &&
                (it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.SCHEDULED)
        }
    }

    val playerError: StateFlow<PlayerError?> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.error
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)
    }

    val videoFormat: StateFlow<VideoFormat> = playerEngine.videoFormat
    
    val playerStats = playerEngine.playerStats
    val availableAudioTracks: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.availableAudioTracks
            .map { tracks -> tracks as? List<com.streamvault.player.PlayerTrack> ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    val availableSubtitleTracks: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.availableSubtitleTracks
            .map { tracks -> tracks as? List<com.streamvault.player.PlayerTrack> ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    val availableVideoQualities: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.availableVideoTracks
            .map { tracks -> tracks as? List<com.streamvault.player.PlayerTrack> ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    val isMuted: StateFlow<Boolean> = playerEngine.isMuted
    val mediaTitle: StateFlow<String?> = playerEngine.mediaTitle
    val playbackSpeed: StateFlow<Float> = playerEngine.playbackSpeed

    val preventStandbyDuringPlayback: StateFlow<Boolean> by lazy(LazyThreadSafetyMode.NONE) {
        preferencesRepository.preventStandbyDuringPlayback
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), true)
    }

    fun selectAudioTrack(trackId: String) {
        playerEngine.selectAudioTrack(trackId)
    }

    fun selectSubtitleTrack(trackId: String?) {
        playerEngine.selectSubtitleTrack(trackId)
    }

    fun selectVideoQuality(trackId: String) {
        playerEngine.selectVideoTrack(trackId)
    }

    fun setPlaybackSpeed(speed: Float) {
        val normalizedSpeed = speed.coerceIn(0.5f, 2f)
        playerEngine.setPlaybackSpeed(normalizedSpeed)
        viewModelScope.launch {
            preferencesRepository.setPlayerPlaybackSpeed(normalizedSpeed)
        }
    }

    fun seekTo(positionMs: Long) {
        playerEngine.seekTo(positionMs)
        clearSeekPreview()
        schedulePostSeekPersist()
    }

    /**
     * Event-based save shortly after the last seek of a burst (D-pad scrubbing
     * fires many seekTo in a row — debounce instead of one write per press).
     */
    private fun schedulePostSeekPersist() {
        postSeekPersistJob?.cancel()
        postSeekPersistJob = viewModelScope.launch {
            delay(2_000)
            persistPlaybackProgress()
        }
    }
    // Note: setScrubbingMode and updateSeekPreview were removed when the
    // VOD controls slider became non-focusable. Channel-zap optimisation
    // still calls playerEngine.setScrubbingMode directly from PlayerZapActions.

    internal fun beginPlaybackSession(): Long {
        recoveryJob?.cancel()
        hasRetriedXtreamAuthRefresh = false
        probeAdvisoryHttpCode = null
        liveFirstFrameSeen = false
        val version = ++prepareRequestVersion
        // Disarm history writes until the engine is prepared + READY for this
        // version. Same-content retries (engine fallback, alternate stream)
        // keep their identity and re-arm at the next READY.
        sessionGate.onNewSession(version)
        return version
    }

    private fun clearSeriesEpisodeContext() {
        currentSeriesId = null
        currentSeasonNumber = null
        currentEpisodeNumber = null
        _currentSeries.value = null
        _currentEpisode.value = null
        _nextEpisode.value = null
    }

    private fun resolveEpisode(
        series: Series,
        episodeId: Long,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Episode? {
        val episodes = series.seasons
            .sanitizedForPlayer()
            .sortedBy { it.seasonNumber }
            .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
        return episodes.firstOrNull { it.id == episodeId }
            ?: episodes.firstOrNull { it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber }
    }

    private fun findNextEpisode(series: Series, episode: Episode): Episode? {
        val orderedEpisodes = series.seasons
            .sanitizedForPlayer()
            .sortedBy { it.seasonNumber }
            .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
        val currentIndex = orderedEpisodes.indexOfFirst { it.id == episode.id }
        return orderedEpisodes.getOrNull(currentIndex + 1)
    }

    private fun buildEpisodePlaybackTitle(episode: Episode): String =
        "${episode.title} - S${episode.seasonNumber}E${episode.episodeNumber}"

    private fun loadSeriesEpisodeContext(
        requestVersion: Long,
        providerId: Long,
        seriesId: Long,
        episodeId: Long,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) {
        viewModelScope.launch {
            when (val result = seriesRepository.getSeriesDetails(providerId, seriesId)) {
                is Result.Success -> {
                    if (!isActivePlaybackSession(requestVersion)) return@launch
                    val series = result.data.sanitizedForPlayer()
                    val resolvedEpisode = resolveEpisode(series, episodeId, seasonNumber, episodeNumber)
                    _currentSeries.value = series
                    _currentEpisode.value = resolvedEpisode
                    _nextEpisode.value = resolvedEpisode?.let { findNextEpisode(series, it) }
                    currentSeriesId = seriesId
                    // Now that we know the upstream Xtream series_id, report
                    // it to the FuzionTV admin so the activity panel shows
                    // the right series name (provider_inventory_series is
                    // keyed by xtream series_id, not by the local Room id).
                    val xtreamSid = series.seriesId.takeIf { it > 0L } ?: seriesId
                    if (xtreamSid > 0L) {
                        playbackReporter.start("series", xtreamSid)
                    }
                    currentSeasonNumber = resolvedEpisode?.seasonNumber ?: seasonNumber
                    currentEpisodeNumber = resolvedEpisode?.episodeNumber ?: episodeNumber
                    if (resolvedEpisode != null && currentContentType == ContentType.SERIES_EPISODE) {
                        currentArtworkUrl = resolvedEpisode.coverUrl
                            ?: currentArtworkUrl
                            ?: series.posterUrl
                            ?: series.backdropUrl
                        currentTitle = buildEpisodePlaybackTitle(resolvedEpisode)
                        playbackTitleFlow.value = currentTitle
                    }
                    sessionGate.enrich(
                        requestVersion = requestVersion,
                        seriesId = seriesId,
                        seasonNumber = currentSeasonNumber,
                        episodeNumber = currentEpisodeNumber,
                        title = currentTitle.takeIf { it.isNotBlank() }
                    )
                }

                else -> {
                    if (!isActivePlaybackSession(requestVersion)) return@launch
                    _currentSeries.value = null
                    _currentEpisode.value = null
                    _nextEpisode.value = null
                    currentSeriesId = seriesId
                    currentSeasonNumber = seasonNumber
                    currentEpisodeNumber = episodeNumber
                    sessionGate.enrich(
                        requestVersion = requestVersion,
                        seriesId = seriesId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        title = null
                    )
                }
            }
        }
    }

    private fun buildPlaybackHistorySnapshot(
        session: PlaybackSessionGate.Snapshot,
        positionMs: Long,
        durationMs: Long
    ): PlaybackHistory? {
        if (positionMs < 0 || durationMs <= 0 || session.contentId == -1L || session.providerId == -1L) {
            return null
        }
        return PlaybackHistory(
            contentId = session.contentId,
            contentType = session.contentType,
            providerId = session.providerId,
            title = session.title,
            posterUrl = session.artworkUrl,
            streamUrl = session.streamUrl,
            resumePositionMs = positionMs,
            totalDurationMs = durationMs,
            lastWatchedAt = System.currentTimeMillis(),
            seriesId = session.seriesId,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber
        )
    }

    private suspend fun persistPlaybackCompletion() {
        val session = sessionGate.activeSnapshotOrNull() ?: return
        if (session.contentType == ContentType.LIVE) return
        val durationMs = playerEngine.duration.value
        val completedHistory = buildPlaybackHistorySnapshot(
            session = session,
            positionMs = durationMs.coerceAtLeast(playerEngine.currentPosition.value),
            durationMs = durationMs
        ) ?: return
        logRepositoryFailure(
            operation = "Mark playback watched",
            result = markAsWatched(completedHistory)
        )
        watchNextManager.refreshWatchNext()
        launcherRecommendationsManager.refreshRecommendations()
    }

    /**
     * One-shot write at the first READY of a VOD/episode session so the item
     * exists in playback_history right away. Without it, "next episode" + an
     * exit within 30 s left no row for the new episode and Continue Watching
     * fell back to the series' previous incomplete entry — often episode 1.
     */
    private fun recordSessionStart() {
        val session = sessionGate.activeSnapshotOrNull() ?: return
        if (session.contentType == ContentType.LIVE) return
        if (session.contentId == -1L || session.providerId == -1L) return
        val history = PlaybackHistory(
            contentId = session.contentId,
            contentType = session.contentType,
            providerId = session.providerId,
            title = session.title,
            posterUrl = session.artworkUrl,
            streamUrl = session.streamUrl,
            resumePositionMs = playerEngine.currentPosition.value.coerceAtLeast(0L),
            totalDurationMs = playerEngine.duration.value.coerceAtLeast(0L),
            lastWatchedAt = System.currentTimeMillis(),
            seriesId = session.seriesId,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber
        )
        // Application scope: this row is what Continue Watching points at —
        // it must survive an exit that lands microseconds after the first
        // READY (viewModelScope would cancel the write).
        applicationScope.launch {
            logRepositoryFailure(
                operation = "Record playback session start",
                result = playbackHistoryRepository.recordPlayback(history, incrementWatchCount = false)
            )
        }
    }

    private fun handlePlaybackEnded() {
        if (currentContentType == ContentType.LIVE) return

        val position = playerEngine.currentPosition.value
        val duration = playerEngine.duration.value
        // Garde-fou: certains streams Xtream ferment la connexion HTTP en plein
        // milieu, et certains MP4/MKV avec moov/stco tronqués déclenchent un
        // EOF anticipé. Si on n'a pas atteint le seuil, on traite ENDED comme
        // une erreur récupérable au lieu d'enchaîner l'épisode suivant.
        if (duration > 0L && position < duration * PREMATURE_END_THRESHOLD_PERCENT / 100L) {
            handlePrematureEnd(position)
            return
        }

        prematureEndRetryAttempt = 0
        pendingPrematureEndResumeMs = 0L
        pendingPrematureEndUrl = ""
        viewModelScope.launch {
            persistPlaybackCompletion()
            if (currentContentType == ContentType.SERIES_EPISODE) {
                _nextEpisode.value?.let { episode ->
                    playEpisode(episode, showResumePrompt = false)
                }
            }
        }
    }

    private fun handlePrematureEnd(positionMs: Long) {
        prematureEndRetryJob?.cancel()
        val attempt = prematureEndRetryAttempt + 1
        android.util.Log.w(
            "PlayerVM",
            "Premature ENDED at ${positionMs}ms / ${playerEngine.duration.value}ms " +
                "(attempt $attempt/$PREMATURE_END_MAX_RETRIES)"
        )

        if (attempt > PREMATURE_END_MAX_RETRIES) {
            prematureEndRetryAttempt = 0
            pendingPrematureEndResumeMs = 0L
            pendingPrematureEndUrl = ""
            showPlayerNotice(
                message = "Lecture interrompue. Réessayez plus tard.",
                recoveryType = PlayerRecoveryType.NETWORK,
                actions = buildRecoveryActions(PlayerRecoveryType.NETWORK)
            )
            return
        }

        prematureEndRetryAttempt = attempt
        pendingPrematureEndResumeMs = positionMs
        pendingPrematureEndUrl = currentStreamUrl

        showPlayerNotice(
            message = "Lecture interrompue, reconnexion ($attempt/$PREMATURE_END_MAX_RETRIES)…",
            recoveryType = PlayerRecoveryType.NETWORK,
            durationMs = PREMATURE_END_RETRY_BACKOFF_MS + 3_000L,
            isRetryNotice = true
        )

        prematureEndRetryJob = viewModelScope.launch {
            persistPlaybackProgress()
            delay(PREMATURE_END_RETRY_BACKOFF_MS)
            val episode = _currentEpisode.value
            if (episode != null && currentContentType == ContentType.SERIES_EPISODE) {
                playEpisode(episode, showResumePrompt = false)
            } else {
                val streamUrl = currentStreamUrl
                if (streamUrl.isNotBlank()) {
                    retryStream(streamUrl, null)
                }
            }
        }
    }

    fun playNextEpisode() {
        _nextEpisode.value?.let { episode ->
            playEpisode(episode, showResumePrompt = false)
        }
    }

    private fun currentPlaybackIdentityUrl(): String =
        currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }

    internal fun isActivePlaybackSession(
        requestVersion: Long,
        expectedLogicalUrl: String? = null
    ): Boolean {
        if (requestVersion != prepareRequestVersion) return false
        val expectedUrl = expectedLogicalUrl?.takeIf { it.isNotBlank() } ?: return true
        val activeUrl = currentPlaybackIdentityUrl()
        return activeUrl.isBlank() || activeUrl == expectedUrl || currentStreamUrl == expectedUrl
    }

    internal fun requestEpg(
        providerId: Long,
        epgChannelId: String?,
        streamId: Long = 0L,
        internalChannelId: Long = 0L
    ) {
        val normalizedChannelId = epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
        if (providerId <= 0L || (internalChannelId <= 0L && normalizedChannelId == null && streamId <= 0L)) {
            activeEpgRequestKey = null
            fetchEpg(providerId = -1L, internalChannelId = 0L, epgChannelId = null)
            return
        }

        val key = EpgRequestKey(
            providerId = providerId,
            internalChannelId = internalChannelId,
            epgChannelId = normalizedChannelId,
            streamId = streamId.takeIf { it > 0L } ?: 0L
        )
        if (key == activeEpgRequestKey) return
        activeEpgRequestKey = key
        fetchEpg(
            providerId = key.providerId,
            internalChannelId = key.internalChannelId,
            epgChannelId = key.epgChannelId,
            streamId = key.streamId
        )
    }

    internal suspend fun preparePlayer(
        streamInfo: com.streamvault.domain.model.StreamInfo,
        requestVersion: Long,
        skipEnginePrepare: Boolean = false
    ): Boolean {
        if (!isActivePlaybackSession(requestVersion)) return false

        // Fast-path expiry check: if the stream URL already carries an expiration timestamp
        // that is in the past, skip the network probe entirely and surface a clear message.
        val expiry = streamInfo.expirationTime
        if (expiry != null && expiry > 0L && expiry < System.currentTimeMillis()) {
            if (!isActivePlaybackSession(requestVersion)) return false
            val expiryMessage = "This stream's subscription has expired. " +
                "Please renew your subscription with the provider."
            setLastFailureReason(expiryMessage)
            showPlayerNotice(
                message = expiryMessage,
                recoveryType = PlayerRecoveryType.SOURCE,
                actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
            )
            return false
        }

        var effectiveStreamInfo = streamInfo
        if (!skipEnginePrepare) {
            // Probe + container-extension fallback. Null = terminal failure
            // (notice shown + diagnostic reported inside). A non-null URL may
            // differ from streamInfo.url when an alternate extension won.
            val probedUrl = probeAndResolvePlaybackUrl(streamInfo, requestVersion)
                ?: return false
            if (probedUrl != streamInfo.url) {
                effectiveStreamInfo = streamInfo.copy(url = probedUrl)
            }
        }
        playerEngine.setMuted(preferencesRepository.playerMuted.first())
        playerEngine.setPlaybackSpeed(
            if (currentContentType == ContentType.LIVE) {
                1f
            } else {
                preferencesRepository.playerPlaybackSpeed.first()
            }
        )
        playerEngine.setPreferredAudioLanguage(
            resolvePreferredAudioLanguage(
                preferredAudioLanguage = preferencesRepository.preferredAudioLanguage.first(),
                appLanguage = preferencesRepository.appLanguage.first()
            )
        )
        playerEngine.setNetworkQualityPreferences(
            wifiMaxHeight = preferencesRepository.playerWifiMaxVideoHeight.first(),
            ethernetMaxHeight = preferencesRepository.playerEthernetMaxVideoHeight.first()
        )
        if (!isActivePlaybackSession(requestVersion)) return false
        currentResolvedPlaybackUrl = effectiveStreamInfo.url
        if (!skipEnginePrepare) {
            playerEngine.prepare(effectiveStreamInfo)
        }
        // From here on, READY events belong to this session's media — the
        // gate may arm on the next READY (a stale READY from the previous
        // media could not, because this mark was missing for its version).
        sessionGate.onEnginePrepared(requestVersion)
        return true
    }

    /**
     * Probes the playback URL before handing it to the engine.
     * Returns the URL to play — possibly rewritten with an alternate
     * container extension that the panel accepted — or null when the failure
     * is terminal (user notice shown and diagnostic reported here).
     */
    private suspend fun probeAndResolvePlaybackUrl(
        streamInfo: com.streamvault.domain.model.StreamInfo,
        requestVersion: Long
    ): String? {
        // VOD/séries seulement : quand le mode de l'appareil exige le VPN et
        // que le tunnel n'est pas encore vérifié, attendre AVANT de sonder —
        // sinon la sonde et le moteur partent sur l'IP résidentielle bloquée.
        // Le LIVE n'est jamais gaté (il fonctionne sans VPN en mode auto ; la
        // latence de zapping est sacrée).
        if (!awaitVpnBeforeOnDemand(requestVersion)) return null

        if (!shouldProbePlaybackUrl(streamInfo.url)) return streamInfo.url

        val token = XtreamUrlFactory.parsePlaybackUrl(streamInfo.url)
        val seriesKey = currentSeriesId.takeIf { token?.kind == XtreamStreamKind.SERIES }
        val outcome = xtreamPlaybackProber.probe(
            url = streamInfo.url,
            kind = token?.kind,
            preferredExtension = extensionMemory.preferred(currentProviderId, seriesKey),
            isActive = { isActivePlaybackSession(requestVersion) }
        )
        if (!isActivePlaybackSession(requestVersion)) return null

        return when (outcome) {
            is XtreamPlaybackProber.Outcome.Inconclusive -> streamInfo.url
            is XtreamPlaybackProber.Outcome.Playable -> {
                outcome.winningExtension?.let { winner ->
                    extensionMemory.remember(currentProviderId, seriesKey, winner)
                    appendRecoveryAction(
                        "Extension .$winner substituée après HTTP ${outcome.initialCode}"
                    )
                    reportProbeDiagnostic(
                        httpCode = outcome.initialCode ?: 0,
                        url = streamInfo.url,
                        requestedExtension = token?.containerExtension,
                        triedExtensions = listOfNotNull(token?.containerExtension, winner),
                        recoveredExtension = winner
                    )
                }
                outcome.url
            }
            is XtreamPlaybackProber.Outcome.Rejected -> {
                reportProbeDiagnostic(
                    httpCode = outcome.httpCode,
                    url = streamInfo.url,
                    requestedExtension = token?.containerExtension,
                    triedExtensions = outcome.triedExtensions,
                    recoveredExtension = null
                )
                // A 403 on LIVE through the tunnel means the provider is refusing
                // everything (provider-wide), not banning this region for VOD —
                // let the VPN guard wipe any region-ban evidence it's gathering.
                if (outcome.httpCode == 403 && currentContentType == ContentType.LIVE) {
                    vpnManager.reportLive403()
                }
                // A 403 on an on-demand stream is the provider's IP block —
                // kick the VPN, wait for the tunnel, then auto-retry once
                // instead of letting the engine burn its attempt on the
                // blocked IP. Returns true when it took ownership of the flow.
                if (awaitVpnAndAutoRetry(outcome.httpCode, requestVersion)) {
                    return null
                }
                if (outcome.advisory) {
                    // 401/403 on a series/movie: the probe's HTTP client is
                    // not the engine's (LibVLC sends a different User-Agent) —
                    // give the engine its one real attempt. The terminal
                    // error paths swap in the provider-refusal message.
                    probeAdvisoryHttpCode = outcome.httpCode
                    appendRecoveryAction(
                        "Sonde refusée (HTTP ${outcome.httpCode}) — tentative moteur quand même"
                    )
                    streamInfo.url
                } else {
                    val message = probeFailureMessage(outcome.httpCode)
                    setLastFailureReason(message)
                    val recoveryType = if (outcome.httpCode in 500..599) {
                        PlayerRecoveryType.NETWORK
                    } else {
                        PlayerRecoveryType.SOURCE
                    }
                    showPlayerNotice(
                        message = message,
                        recoveryType = recoveryType,
                        actions = buildRecoveryActions(recoveryType)
                    )
                    null
                }
            }
        }
    }

    /**
     * Gate pré-sonde pour la VOD/séries : si le mode de l'appareil exige le
     * VPN et que le tunnel n'est pas vérifié, attend (notice à l'écran) puis
     * poursuit sur tunnel prêt. @return false = flux interrompu (notice
     * terminale affichée, ou session zappée pendant l'attente).
     */
    private suspend fun awaitVpnBeforeOnDemand(requestVersion: Long): Boolean {
        if (currentContentType != ContentType.SERIES_EPISODE &&
            currentContentType != ContentType.MOVIE
        ) return true
        val snapshot = runCatching {
            vpnManager.awaitVpnReadyIfRequired(timeoutMs = 0)
        }.getOrDefault(com.streamvault.app.vpn.VpnGateResult.NOT_REQUIRED)
        if (snapshot.allowsPanelCalls()) return true

        showPlayerNotice(
            message = VPN_WAIT_NOTICE,
            recoveryType = PlayerRecoveryType.SOURCE,
            durationMs = VPN_PLAYBACK_GATE_MS + 5_000L,
        )
        val result = runCatching {
            vpnManager.awaitVpnReadyIfRequired(VPN_PLAYBACK_GATE_MS)
        }.getOrDefault(com.streamvault.app.vpn.VpnGateResult.FAILED)
        if (!isActivePlaybackSession(requestVersion)) return false
        if (result.allowsPanelCalls()) {
            appendRecoveryAction("VPN vérifié avant lecture on-demand")
            dismissPlayerNotice()
            return true
        }
        val message = "Le tunnel VPN sécurisé n'a pas pu être établi ($result). " +
            "Réessayez, ou contactez votre revendeur si le problème persiste."
        setLastFailureReason(message)
        showPlayerNotice(
            message = message,
            recoveryType = PlayerRecoveryType.SOURCE,
            actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
        )
        return false
    }

    /**
     * 403 sur un film/épisode = blocage de l'IP de sortie par les edges
     * on-demand du fournisseur (le live n'est pas touché). Active le VPN,
     * attend que le tunnel soit vérifié, puis relance la lecture UNE fois —
     * au lieu de laisser le moteur brûler sa tentative sur l'IP bloquée puis
     * d'exiger un RETRY manuel. @return true quand cette fonction a pris la
     * main sur le flux (l'appelant doit retourner null sans autre notice).
     */
    private suspend fun awaitVpnAndAutoRetry(httpCode: Int, requestVersion: Long): Boolean {
        if (httpCode != 403) return false
        if (currentContentType != ContentType.SERIES_EPISODE &&
            currentContentType != ContentType.MOVIE
        ) return false
        val activated = runCatching { vpnManager.onOnDemandBlocked() }
            .onFailure { android.util.Log.w("PlayerVM", "VPN auto-activate failed: ${it.message}") }
            .getOrDefault(false)
        if (!activated) return false // mode off : comportement historique
        // Tunnel déjà vérifié ⇒ le 403 est passé À TRAVERS le tunnel : c'est le
        // territoire du guard région (enforced403), pas de l'activation — on
        // laisse la tentative advisory du moteur suivre son cours.
        val snapshot = runCatching {
            vpnManager.awaitVpnReadyIfRequired(timeoutMs = 0)
        }.getOrDefault(com.streamvault.app.vpn.VpnGateResult.FAILED)
        if (snapshot == com.streamvault.app.vpn.VpnGateResult.READY) return false
        // Anti-boucle : une seule relance automatique par fenêtre de cooldown.
        val now = System.currentTimeMillis()
        if (now - lastVpnAutoRetryAtMs < VPN_AUTO_RETRY_COOLDOWN_MS) return false
        lastVpnAutoRetryAtMs = now

        showPlayerNotice(
            message = VPN_WAIT_NOTICE,
            recoveryType = PlayerRecoveryType.SOURCE,
            durationMs = VPN_PLAYBACK_GATE_MS + 5_000L,
        )
        val result = runCatching {
            vpnManager.awaitVpnReadyIfRequired(VPN_PLAYBACK_GATE_MS)
        }.getOrDefault(com.streamvault.app.vpn.VpnGateResult.FAILED)
        if (!isActivePlaybackSession(requestVersion)) return true // zap/BACK pendant l'attente
        if (result == com.streamvault.app.vpn.VpnGateResult.READY) {
            appendRecoveryAction("VPN vérifié — relance automatique de la lecture")
            dismissPlayerNotice()
            retryStream(currentStreamUrl, currentChannelFlow.value?.epgChannelId)
        } else {
            val message = probeFailureMessage(httpCode)
            setLastFailureReason(message)
            showPlayerNotice(
                message = message,
                recoveryType = PlayerRecoveryType.SOURCE,
                actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
            )
        }
        return true
    }

    /**
     * One probe-failure diagnostic per session (shares [lastDiagnosedSession]
     * with [reportTerminalPlaybackFailure], so an advisory engine attempt
     * never double-reports), plus one per content+code across sessions (the
     * RETRY button starts a new session on every click).
     */
    private fun reportProbeDiagnostic(
        httpCode: Int,
        url: String,
        requestedExtension: String?,
        triedExtensions: List<String>,
        recoveredExtension: String?
    ) {
        val key = Triple(currentProviderId, currentContentId, httpCode)
        if (recoveredExtension == null) {
            if (lastDiagnosedSession == prepareRequestVersion) return
            if (key == lastProbeDiagKey) return
            lastDiagnosedSession = prepareRequestVersion
        }
        lastProbeDiagKey = key
        diagnosticReporter.reportStreamProbeRejected(
            httpCode = httpCode,
            contentType = currentContentType.name,
            title = currentTitle,
            requestedExtension = requestedExtension,
            triedExtensions = triedExtensions,
            recoveredExtension = recoveredExtension,
            host = XtreamUrlFactory.maskHost(url)
        )
    }

    /** French, actionable message for a terminal probe rejection. */
    private fun probeFailureMessage(httpCode: Int): String = when {
        httpCode == 401 || httpCode == 403 -> when (currentContentType) {
            ContentType.SERIES_EPISODE ->
                "Le fournisseur refuse l'accès aux séries sur cette ligne (HTTP $httpCode). " +
                    "Si le direct et les films fonctionnent, votre abonnement n'inclut " +
                    "probablement pas les séries — contactez votre fournisseur."
            ContentType.MOVIE ->
                "Le fournisseur refuse l'accès à ce film sur cette ligne (HTTP $httpCode). " +
                    "Contactez votre fournisseur si le problème touche tous les films."
            else ->
                "Ce flux a été refusé par le fournisseur (HTTP $httpCode)."
        }
        httpCode == 404 ->
            "Ce contenu est introuvable chez le fournisseur (HTTP 404), " +
                "y compris sous les formats alternatifs."
        else ->
            "Le serveur du fournisseur est en erreur pour ce flux (HTTP $httpCode). " +
                "Réessayez plus tard."
    }

    private suspend fun shouldProbePlaybackUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val providerId = currentProviderId.takeIf { it > 0L } ?: return false
        val provider = providerRepository.getProvider(providerId) ?: return false
        return provider.type == com.streamvault.domain.model.ProviderType.XTREAM_CODES &&
            (xtreamStreamUrlResolver.isInternalStreamUrl(currentStreamUrl) || xtreamStreamUrlResolver.isInternalStreamUrl(url))
    }

    fun prepare(
        streamUrl: String, 
        epgChannelId: String?, 
        internalChannelId: Long, 
        categoryId: Long = -1, 
        providerId: Long = -1, 
        isVirtual: Boolean = false,
        contentType: String = "CHANNEL",
        title: String = "",
        artworkUrl: String? = null,
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        showResumePrompt: Boolean = true
    ) {
        val hasArchiveRequest = archiveStartMs != null &&
            archiveEndMs != null &&
            archiveStartMs > 0L &&
            archiveEndMs > archiveStartMs &&
            try { ContentType.valueOf(contentType) } catch (e: Exception) { ContentType.LIVE } == ContentType.LIVE
        val requestVersion = beginPlaybackSession()
        val previousProviderId = currentProviderId
        val previousCategoryId = currentCategoryId
        val shouldReloadPlaylist = categoryId != -1L &&
            (categoryId != previousCategoryId || providerId != previousProviderId)
        clearSeekPreview()
        // Wipe the now-playing context SYNCHRONOUSLY before any async loader so
        // the overlay never keeps showing the previously played item while the
        // new one's metadata (channel, EPG, series/episode) is still resolving.
        // currentChannelFlow is repopulated below for LIVE only (async); the
        // EPG flows by the EPG loader; the series/episode flows by
        // loadSeriesEpisodeContext. clearSeriesEpisodeContext() runs here
        // UNCONDITIONALLY (before currentSeriesId is re-set) — without this an
        // episode→episode switch never cleared the previous episode.
        currentChannelFlow.value = null
        _currentProgram.value = null
        _nextProgram.value = null
        _upcomingPrograms.value = emptyList()
        _programHistory.value = emptyList()
        clearSeriesEpisodeContext()
        currentResolvedPlaybackUrl = ""
        currentStreamUrl = streamUrl
        currentContentId = internalChannelId
        currentTitle = title
        playbackTitleFlow.value = title
        currentArtworkUrl = artworkUrl
        currentContentType = try { ContentType.valueOf(contentType) } catch (e: Exception) { ContentType.LIVE }
        currentProviderId = providerId
        currentSeriesId = seriesId?.takeIf { it > 0L }
        currentSeasonNumber = seasonNumber
        currentEpisodeNumber = episodeNumber
        sessionGate.begin(
            PlaybackSessionGate.Snapshot(
                requestVersion = requestVersion,
                contentId = internalChannelId,
                contentType = currentContentType,
                providerId = providerId,
                seriesId = currentSeriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                title = title,
                artworkUrl = artworkUrl,
                streamUrl = streamUrl
            )
        )
        lastFinalPersistKey = null
        currentStreamClassLabel = if (hasArchiveRequest) "Catch-up" else "Primary"
        _isTimeshiftPlayback.value = hasArchiveRequest
        if (!hasArchiveRequest) {
            pendingCatchUpUrls = emptyList()
        }
        if (currentContentType == ContentType.SERIES_EPISODE && providerId > 0 && currentSeriesId != null) {
            loadSeriesEpisodeContext(
                requestVersion = requestVersion,
                providerId = providerId,
                seriesId = currentSeriesId ?: -1L,
                episodeId = internalChannelId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber
            )
        } else {
            clearSeriesEpisodeContext()
        }
        if (currentContentType != ContentType.LIVE) {
            lastRecordedLivePlaybackKey = null
            recentChannelsJob?.cancel()
            recentChannelsFlow.value = emptyList()
            lastVisitedCategoryJob?.cancel()
            _lastVisitedCategory.value = null
        } else if (providerId > 0L && internalChannelId > 0L) {
            // Persist the active LIVE channel so the next app launch can
            // resume it (and zap-last works after a cold start, before any
            // in-session zap has populated previousChannelIndex).
            viewModelScope.launch {
                preferencesRepository.setLastLiveChannelId(providerId, internalChannelId)
            }
        }
        hasRetriedWithSoftwareDecoder = false
        hasRetriedWithAlternateEngine = false
        playerEngine.setDecoderMode(preferredDecoderMode)
        updateDecoderMode(preferredDecoderMode)
        updateStreamClass(currentStreamClassLabel)

        // Report current playback to the FuzionTV admin (best-effort).
        // The Xtream upstream stream_id is the LAST numeric segment of the
        // playback URL (it's NOT internalChannelId, which is the Room PK
        // auto-generated locally). For SERIES_EPISODE we defer the report
        // to loadSeriesEpisodeContext once the upstream Series.seriesId is
        // resolved — the episode_id from the URL would point to the wrong
        // row in provider_inventory_series (which is keyed by series_id).
        val sidFromUrl = Regex("""/(\d+)(?:\.[A-Za-z0-9]+)?(?:\?|$)""")
            .find(streamUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (currentContentType != ContentType.SERIES_EPISODE && sidFromUrl > 0L) {
            playbackReporter.start(currentContentType.name, sidFromUrl)
        }
        
        // Reset tried streams for manual switch
        triedAlternativeStreams.clear()
        if (!hasArchiveRequest) {
            triedAlternativeStreams.add(streamUrl)
        }

        if (!hasArchiveRequest) {
            viewModelScope.launch {
                val streamInfo = resolvePlaybackStreamInfo(streamUrl, internalChannelId, providerId, currentContentType)
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                if (streamInfo == null) {
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    showPlayerNotice(message = "No playable stream URL was available.", recoveryType = PlayerRecoveryType.SOURCE)
                    return@launch
                }
                // If the engine was adopted from the live preview and still
                // points at the same channel, skip the network probe + ExoPlayer
                // prepare — the stream is already flowing. Mismatch falls back
                // to a regular prepare on the adopted engine.
                val adoptedMatch = consumeAdoptionMatch(internalChannelId, providerId)
                if (!preparePlayer(streamInfo, requestVersion, skipEnginePrepare = adoptedMatch)) return@launch
            }
        }
        
        // Show context info on entry for both Live and VOD
        openChannelInfoOverlay()

        if (providerId > 0) {
            viewModelScope.launch {
                providerRepository.getProvider(providerId)?.let { provider ->
                    _playerDiagnostics.update {
                        it.copy(
                            providerName = provider.name,
                            providerSourceLabel = when (provider.type) {
                                com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
                                com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
                            }
                        )
                    }
                }
            }
        } else {
            _playerDiagnostics.update { it.copy(providerName = "", providerSourceLabel = "") }
        }
        
        // 1. Check for Resume Position for VODs
        if (showResumePrompt && currentContentType != ContentType.LIVE && currentContentId != -1L && currentProviderId != -1L) {
            viewModelScope.launch {
                val history = playbackHistoryRepository.getPlaybackHistory(currentContentId, currentContentType, currentProviderId)
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                if (history != null && history.resumePositionMs > 5000L && !isPlaybackComplete(history.resumePositionMs, history.totalDurationMs)) {
                    playerEngine.pause()
                    _resumePrompt.value = ResumePromptState(
                        show = true,
                        positionMs = history.resumePositionMs,
                        title = currentTitle
                    )
                }
            }
        }
        
        // Load playlist if context changed
        if (shouldReloadPlaylist) {
            currentCategoryId = categoryId
            activeCategoryIdFlow.value = categoryId
            isVirtualCategory = isVirtual
            loadPlaylist(categoryId, providerId, isVirtual, internalChannelId)
        } else {
            // If playlist already loaded, just update index
            if (channelList.isNotEmpty() && internalChannelId != -1L) {
                currentChannelIndex = channelList.indexOfFirst { it.id == internalChannelId }
                if (currentChannelIndex == -1) {
                    currentChannelIndex = channelList.indexOfFirst { it.streamUrl == streamUrl }
                }
            }
        }

        if (currentContentType == ContentType.LIVE && hasArchiveRequest) {
            viewModelScope.launch {
                val catchUpUrls = try {
                    providerRepository.buildCatchUpUrls(
                        providerId = currentProviderId,
                        streamId = currentContentId,
                        start = archiveStartMs / 1000L,
                        end = archiveEndMs / 1000L
                    )
                } catch (e: CredentialDecryptionException) {
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    setLastFailureReason(e.message ?: CredentialDecryptionException.MESSAGE)
                    showPlayerNotice(
                        message = e.message ?: CredentialDecryptionException.MESSAGE,
                        recoveryType = PlayerRecoveryType.SOURCE
                    )
                    return@launch
                }
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                if (catchUpUrls.isNotEmpty()) {
                    startCatchUpPlayback(
                        urls = catchUpUrls,
                        title = archiveTitle?.takeIf { it.isNotBlank() } ?: currentTitle,
                        recoveryAction = "Opened catch-up stream",
                        requestVersionOverride = requestVersion
                    )
                } else {
                    val reason = resolveCatchUpFailureMessage(currentChannelFlow.value, archiveRequested = true, programHasArchive = true)
                    setLastFailureReason(reason)
                    showPlayerNotice(
                        message = reason,
                        recoveryType = PlayerRecoveryType.CATCH_UP,
                        actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                    )
                }
            }
        }

        // Fetch EPG if ID provided
        if (currentContentType == ContentType.LIVE) {
            requestEpg(
                providerId = currentProviderId,
                epgChannelId = epgChannelId,
                internalChannelId = internalChannelId
            )
        } else {
            requestEpg(providerId = -1L, epgChannelId = null)
        }
        observeRecentChannels()
        observeLastVisitedCategory()

        // Load Aspect Ratio safely (fallback to FIT if none saved)
        aspectRatioJob?.cancel()
        _aspectRatio.value = AspectRatio.FIT
        if (internalChannelId != -1L) {
            aspectRatioJob = viewModelScope.launch {
                preferencesRepository.getAspectRatioForChannel(internalChannelId).collect { savedRatio ->
                    _aspectRatio.value = try {
                        savedRatio?.let { AspectRatio.valueOf(it) } ?: AspectRatio.FIT
                    } catch (e: Exception) {
                        AspectRatio.FIT
                    }
                }
            }
            
            // Fetch Channel — LIVE only. For MOVIE / SERIES_EPISODE the
            // internalChannelId is a VOD id that can collide with an unrelated
            // channel id in the DB; fetching the channel and writing its
            // streamUrl/title back onto currentStreamUrl/currentTitle both
            // defeats isActivePlaybackSession (hence no prepare() call) and
            // shows a stray live-channel name over the film.
            if (currentContentType == ContentType.LIVE) {
                viewModelScope.launch {
                    val channel = channelRepository.getChannel(internalChannelId)
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    currentChannelFlow.value = channel
                    refreshCurrentChannelRecording()
                    if (channel != null) {
                        currentTitle = channel.name.ifBlank { currentTitle }
                        playbackTitleFlow.value = currentTitle
                        currentStreamUrl = if (currentStreamClassLabel == "Catch-up") currentStreamUrl else channel.streamUrl
                        updateStreamClass(
                            when {
                                currentStreamClassLabel == "Catch-up" -> "Catch-up"
                                streamUrl == channel.streamUrl -> "Primary"
                                channel.alternativeStreams.contains(streamUrl) -> "Alternate"
                                else -> "Direct"
                            }
                        )
                        recordLivePlayback(channel)
                        requestEpg(
                            providerId = currentProviderId,
                            epgChannelId = channel.epgChannelId,
                            streamId = channel.streamId,
                            internalChannelId = channel.id
                        )
                        updateChannelDiagnostics(channel)
                    }
                }
            } else {
                // Clear any stale live channel state so the player UI doesn't
                // leak the previous channel's name / EPG into the movie view.
                currentChannelFlow.value = null
            }
        }

        // 2. Start Progress Tracking for VODs
        startProgressTracking()
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        if (currentContentType == ContentType.LIVE) return

        progressTrackingJob = viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (!isAppInForeground || !playerEngine.isPlaying.value) continue
                persistPlaybackProgress()
            }
        }
    }

    private suspend fun persistPlaybackProgress(refreshShelves: Boolean = false) {
        // Capture session + position synchronously, before any suspension:
        // the gate guarantees the position belongs to this snapshot's media.
        val session = sessionGate.activeSnapshotOrNull() ?: return
        if (session.contentType == ContentType.LIVE) return
        val pos = playerEngine.currentPosition.value
        val dur = playerEngine.duration.value

        if (pos > 0 && dur > 0) {
            val history = buildPlaybackHistorySnapshot(session, pos, dur) ?: return
            // syncDenormalized on every tick (single-row UPDATE, RAM-neutral)
            // so the series screen's episode progress stays fresh during the
            // session; refreshShelves stays reserved for the costly watch-next
            // / launcher refreshes.
            logRepositoryFailure(
                operation = "Persist playback resume position",
                result = playbackHistoryRepository.updateResumePosition(history, syncDenormalized = true)
            )
            if (refreshShelves) {
                watchNextManager.refreshWatchNext()
                launcherRecommendationsManager.refreshRecommendations()
            }
        }
    }

    /**
     * Final save when the session is about to lose its ViewModel (player
     * screen disposed, app backgrounded, onCleared). Captures everything
     * synchronously on the caller thread, then writes on [applicationScope]:
     * a viewModelScope.launch here never executes from onCleared() (the scope
     * is already cancelled) — the historical reason exits never saved — and a
     * runBlocking on Main would be an ANR risk on the 2 GB box.
     */
    private fun persistFinalProgress(refreshShelves: Boolean = true) {
        val session = sessionGate.activeSnapshotOrNull() ?: return
        if (session.contentType == ContentType.LIVE) return
        val pos = playerEngine.currentPosition.value
        val dur = playerEngine.duration.value
        if (pos <= 0 || dur <= 0) return
        val key = session.requestVersion to pos
        if (key == lastFinalPersistKey) return
        lastFinalPersistKey = key
        val history = buildPlaybackHistorySnapshot(session, pos, dur) ?: return
        applicationScope.launch {
            logRepositoryFailure(
                operation = "Persist final playback position",
                result = playbackHistoryRepository.updateResumePosition(history, syncDenormalized = true)
            )
            if (refreshShelves) {
                watchNextManager.refreshWatchNext()
                launcherRecommendationsManager.refreshRecommendations()
            }
        }
    }

    fun onAppBackgrounded() {
        if (!isAppInForeground) return
        isAppInForeground = false
        shouldResumeAfterForeground = playerEngine.isPlaying.value
        if (shouldResumeAfterForeground) {
            playerEngine.pause()
            // After a short grace, fully stop the engine to CLOSE the upstream
            // socket (frees the provider line) if still backgrounded. A quick
            // app switch (back within the grace) stays merely paused and resumes
            // instantly with no re-buffer.
            backgroundStopJob?.cancel()
            backgroundStopJob = viewModelScope.launch {
                delay(BACKGROUND_STOP_GRACE_MS)
                // Bail if the engine was released (back pressed) or we returned
                // to foreground in the meantime.
                if (!isEngineAlive || isAppInForeground) return@launch
                // Capture VOD position BEFORE stop() — currentPosition resets to
                // 0 once the engine is stopped. Live ignores position (live-edge).
                backgroundResumePositionMs =
                    if (currentContentType == ContentType.LIVE) 0L
                    else playerEngine.currentPosition.value.coerceAtLeast(0L)
                playerEngine.stopForBackground()
                evictPlaybackPoolQuietly()
                stoppedInBackground = true
            }
        }
        // Application scope: the process can be killed right after ON_STOP on
        // the 2 GB box — a viewModelScope coroutine may never get to run.
        persistFinalProgress(refreshShelves = true)
    }

    fun onAppForegrounded() {
        if (isAppInForeground) return
        isAppInForeground = true
        backgroundStopJob?.cancel()
        backgroundStopJob = null
        if (stoppedInBackground) {
            // We closed the connection while backgrounded — re-prepare the same
            // stream. restart() reattaches live to the live-edge; for VOD we
            // re-seek to the captured position (restart() can't read it post-stop).
            stoppedInBackground = false
            playerEngine.restart()
            if (currentContentType != ContentType.LIVE && backgroundResumePositionMs > 0L) {
                playerEngine.seekTo(backgroundResumePositionMs)
            }
            backgroundResumePositionMs = 0L
        } else if (shouldResumeAfterForeground && !_resumePrompt.value.show) {
            playerEngine.play()
        }
        shouldResumeAfterForeground = false
    }

    /**
     * Close idle pooled sockets on the playback OkHttp client so a stopped
     * stream doesn't leave a keep-alive connection lingering upstream (the line
     * would stay counted busy). Only evictAll() — never dispatcher.cancelAll() —
     * so concurrent MultiView calls on the shared singleton client are not cut
     * (evictAll only closes IDLE connections, not in-flight ones).
     */
    private fun evictPlaybackPoolQuietly() {
        runCatching { playbackHttpClient.connectionPool.evictAll() }
            .onFailure { android.util.Log.w("PlayerVM", "evictPlaybackPool failed: ${it.message}") }
    }

    private fun hasActiveRecording(): Boolean =
        _recordingItems.value.any {
            it.status == com.streamvault.domain.model.RecordingStatus.RECORDING
        }

    /**
     * (Re)arm the inactivity guard ("are you still watching?"). Stays disarmed
     * unless enabled (timeout > 0), on a LIVE stream, and no recording is in
     * progress (cutting playback while recording the same channel is poor UX —
     * the recording itself uses a separate client and is unaffected either way).
     * After [inactivityTimeoutMs] with no remote interaction it closes the
     * upstream connection (frees the line) and shows a resumable notice.
     * [notePlaybackInteraction] re-arms it on every interaction, so an active
     * viewer is never cut.
     */
    private fun scheduleInactivityWatchdog() {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = null
        val timeout = inactivityTimeoutMs
        if (timeout <= 0L || currentContentType != ContentType.LIVE || hasActiveRecording()) return
        inactivityWatchdogJob = viewModelScope.launch {
            delay(timeout)
            if (!isEngineAlive || currentContentType != ContentType.LIVE) return@launch
            // Keep audio focus (stopForBackground) so the RETRY action resumes
            // cleanly on Amlogic.
            playerEngine.stopForBackground()
            evictPlaybackPoolQuietly()
            val hours = (timeout / 3_600_000L).coerceAtLeast(1)
            appendRecoveryAction("Inactivity auto-stop after ${hours}h")
            showPlayerNotice(
                message = "Lecture arrêtée après ${hours} h sans activité.",
                recoveryType = PlayerRecoveryType.UNKNOWN,
                actions = listOf(PlayerNoticeAction.RETRY),
                durationMs = 3_600_000L
            )
        }
    }

    /** Reset the inactivity guard on any remote interaction during live playback. */
    internal fun notePlaybackInteraction() {
        if (inactivityTimeoutMs > 0L && currentContentType == ContentType.LIVE) {
            scheduleInactivityWatchdog()
        }
    }

    fun onPlayerScreenDisposed() {
        persistFinalProgress(refreshShelves = true)
    }

    fun handOffPlaybackToMultiView() {
        // Same guard as onCleared(): the engine is about to be released.
        isEngineAlive = false
        backgroundStopJob?.cancel()
        backgroundStopJob = null
        persistFinalProgress(refreshShelves = true)
        playerEngine.release()
    }

    private fun fetchEpg(
        providerId: Long,
        internalChannelId: Long,
        epgChannelId: String?,
        streamId: Long = 0L
    ) {
        epgJob?.cancel()
        if (providerId <= 0L || (internalChannelId <= 0L && epgChannelId == null && streamId <= 0L)) {
            clearEpgState()
            return
        }

        epgJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val start = now - (24 * 60 * 60 * 1000L)
                val end = now + (6 * 60 * 60 * 1000L)
                val programs = epgRepository.getResolvedProgramsForPlaybackChannel(
                    providerId = providerId,
                    internalChannelId = internalChannelId,
                    epgChannelId = epgChannelId,
                    streamId = streamId,
                    startTime = start,
                    endTime = end
                )

                if (programs.isNotEmpty()) {
                    applyProgramTimeline(programs, now)
                } else {
                    applyRemoteProgramFallback(providerId, epgChannelId, streamId, now)
                }

                // Wake up at the next program transition (with a small guard
                // so we land just AFTER endTime) instead of waiting the full
                // 30 s tick — keeps the now/next overlay accurate on news
                // channels with 5-15 min slots.
                val currentEndMs = _currentProgram.value?.endTime ?: 0L
                val msUntilTransition = currentEndMs - System.currentTimeMillis()
                val nextDelay = when {
                    currentEndMs <= 0L -> PLAYER_EPG_REFRESH_INTERVAL_MS
                    msUntilTransition <= 0L -> PLAYER_EPG_REFRESH_INTERVAL_MS
                    else -> msUntilTransition.coerceAtMost(PLAYER_EPG_REFRESH_INTERVAL_MS) + 250L
                }
                delay(nextDelay)
            }
        }
    }

    private fun applyProgramTimeline(programs: List<Program>, now: Long) {
        val catchUpSupported = currentChannelFlow.value?.catchUpSupported == true
        val sortedPrograms = programs.sortedBy { it.startTime }
        val current = sortedPrograms.firstOrNull { it.startTime <= now && it.endTime > now }
        _currentProgram.value = current
        _nextProgram.value = sortedPrograms.firstOrNull { it.startTime > now }
        _programHistory.value = sortedPrograms
            .filter { it.endTime <= now && (it.hasArchive || catchUpSupported) }
            .sortedByDescending { it.startTime }
            .take(MAX_PROGRAM_HISTORY_ITEMS)
        _upcomingPrograms.value = sortedPrograms
            .filter { it.endTime > now || it == current }
            .sortedBy { it.startTime }
            .take(MAX_UPCOMING_PROGRAM_ITEMS)
    }

    private suspend fun applyRemoteProgramFallback(
        providerId: Long,
        epgChannelId: String?,
        streamId: Long,
        now: Long
    ) {
        if (streamId <= 0L) {
            clearEpgState()
            return
        }

        val result = providerRepository.getProgramsForLiveStream(
            providerId = providerId,
            streamId = streamId,
            epgChannelId = epgChannelId,
            limit = 12
        )
        val programs = (result as? com.streamvault.domain.model.Result.Success)?.data
            ?.sortedBy { it.startTime }
            .orEmpty()

        if (programs.isEmpty()) {
            clearEpgState()
            return
        }

        applyProgramTimeline(programs, now)
    }

    private fun clearEpgState() {
        _currentProgram.value = null
        _nextProgram.value = null
        _programHistory.value = emptyList()
        _upcomingPrograms.value = emptyList()
    }

    internal fun loadPlaylist(categoryId: Long, providerId: Long, isVirtual: Boolean, initialChannelId: Long) {
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            val flows = if (isVirtual) {
                if (categoryId == VirtualCategoryIds.RECENT) {
                    playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 24)
                        .map { history ->
                            history.asSequence()
                                .filter { it.contentType == ContentType.LIVE }
                                .sortedByDescending { it.lastWatchedAt }
                                .distinctBy { it.contentId }
                                .map { it.contentId }
                                .toList()
                        }
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else channelRepository.getChannelsByIds(ids).map { unsorted ->
                                val map = unsorted.associateBy { it.id }
                                ids.mapNotNull { map[it] }
                            }
                        }
                } else if (categoryId == VirtualCategoryIds.FAVORITES) {
                    // Global Favorites — preserve user-defined position order
                    favoriteRepository.getFavorites(com.streamvault.domain.model.ContentType.LIVE)
                        .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else channelRepository.getChannelsByIds(ids).map { unsorted ->
                                val byId = unsorted.associateBy { it.id }
                                ids.mapNotNull { byId[it] }
                            }
                        }
                } else {
                    val groupId = if (categoryId < 0) -categoryId else categoryId
                    favoriteRepository.getFavoritesByGroup(groupId)
                        .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else channelRepository.getChannelsByIds(ids).map { unsorted ->
                                val byId = unsorted.associateBy { it.id }
                                ids.mapNotNull { byId[it] }
                            }
                        }
                }
            } else {
                channelRepository.getChannelsByNumber(providerId, categoryId)
            }
            
            combine(flows, preferencesRepository.liveChannelNumberingMode) { channels, numberingMode ->
                val displayedChannels = when (numberingMode) {
                    ChannelNumberingMode.GROUP -> channels.mapIndexed { index, channel ->
                        channel.copy(number = index + 1)
                    }
                    ChannelNumberingMode.PROVIDER -> channels
                }
                numberingMode to displayedChannels.sanitizedChannelsForPlayer()
            }.collect { (numberingMode, displayedChannels) ->
                channelNumberingMode = numberingMode
                channelList = displayedChannels
                currentChannelFlowList.value = displayedChannels
                // Recalculate index based on initial ID or URL
                if (initialChannelId != -1L) {
                    currentChannelIndex = channelList.indexOfFirst { it.id == initialChannelId }
                }
                if (currentChannelIndex == -1) {
                    currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
                }
                
                if (currentChannelIndex != -1) {
                    currentChannelFlow.value = channelList[currentChannelIndex].sanitizedForPlayer()
                    refreshCurrentChannelRecording()
                    val ch = channelList[currentChannelIndex]
                    displayChannelNumberFlow.value = resolveChannelNumber(ch, currentChannelIndex)
                    // Cold-start hydration: if zap-last has never been primed
                    // this session, seed previousChannelIndex from the channel
                    // we persisted on the last prepare(LIVE) of the previous
                    // session, so the zap-last remote button works on the very
                    // first press of a fresh app launch.
                    if (previousChannelIndex == -1 && currentProviderId > 0L) {
                        val persistedLastId = preferencesRepository
                            .getLastLiveChannelId(currentProviderId).first()
                        if (persistedLastId != null && persistedLastId != ch.id) {
                            val seedIndex = channelList.indexOfFirst { it.id == persistedLastId }
                            if (seedIndex >= 0) previousChannelIndex = seedIndex
                        }
                    }
                }
            }
        }
    }

    private fun observeRecentChannels() {
        recentChannelsJob?.cancel()
        if (currentContentType != ContentType.LIVE || currentProviderId <= 0) {
            recentChannelsFlow.value = emptyList()
            return
        }

        recentChannelsJob = viewModelScope.launch {
            combine(
                playbackHistoryRepository.getRecentlyWatchedByProvider(currentProviderId, limit = 12)
                    .map { history ->
                        history.asSequence()
                            .filter { it.contentType == ContentType.LIVE }
                            .sortedByDescending { it.lastWatchedAt }
                            .distinctBy { it.contentId }
                            .map { it.contentId }
                            .toList()
                    }
                    .flatMapLatest { ids ->
                        if (ids.isEmpty()) flowOf(emptyList())
                        else channelRepository.getChannelsByIds(ids).map { channels ->
                            val channelMap = channels.associateBy { it.id }
                            ids.mapNotNull { channelMap[it] }
                        }
                    },
                preferencesRepository.liveChannelNumberingMode
            ) { channels, numberingMode ->
                numberingMode to channels
            }.collect { (numberingMode, channels) ->
                channelNumberingMode = numberingMode
                val currentListNumbers = channelList.withIndex().associate { (index, channel) ->
                    channel.id to resolveChannelNumber(channel, index)
                }
                recentChannelsFlow.value = channels
                        .filterNot { it.id == currentContentId }
                        .map { channel ->
                            currentListNumbers[channel.id]?.let { number ->
                                channel.copy(number = number)
                            } ?: channel
                        }
                }
        }
    }

    private fun observeLastVisitedCategory() {
        lastVisitedCategoryJob?.cancel()
        if (currentContentType != ContentType.LIVE || currentProviderId <= 0) {
            _lastVisitedCategory.value = null
            return
        }

        lastVisitedCategoryJob = viewModelScope.launch {
            combine(
                channelRepository.getCategories(currentProviderId),
                getCustomCategories(ContentType.LIVE),
                preferencesRepository.getLastLiveCategoryId(currentProviderId)
            ) { providerCategories, customCategories, lastVisitedCategoryId ->
                val allCategories = customCategories + providerCategories
                val lastVisited = if (lastVisitedCategoryId == null || lastVisitedCategoryId == VirtualCategoryIds.RECENT) {
                    null
                } else {
                    allCategories.firstOrNull { it.id == lastVisitedCategoryId }
                }
                Pair(allCategories, lastVisited)
            }.collect { (allCategories, lastVisited) ->
                availableCategoriesFlow.value = allCategories
                _lastVisitedCategory.value = lastVisited
            }
        }
    }

    fun openLastVisitedCategory() {
        val category = _lastVisitedCategory.value ?: return
        if (currentContentType != ContentType.LIVE || currentProviderId <= 0) return

        currentCategoryId = category.id
        activeCategoryIdFlow.value = category.id
        isVirtualCategory = category.isVirtual
        loadPlaylist(
            categoryId = category.id,
            providerId = currentProviderId,
            isVirtual = category.isVirtual,
            initialChannelId = currentContentId
        )
        openChannelListOverlay()
    }

    private fun resolveCurrentLiveChannelIndex(): Int {
        if (channelList.isEmpty()) return -1

        val currentIndexMatchesChannel = currentChannelIndex in channelList.indices && run {
            val currentChannel = channelList[currentChannelIndex]
            currentChannel.id == currentContentId || currentChannel.streamUrl == currentStreamUrl
        }
        if (currentIndexMatchesChannel) {
            return currentChannelIndex
        }

        currentChannelIndex = when {
            currentContentId > 0 -> channelList.indexOfFirst { it.id == currentContentId }
            else -> -1
        }

        if (currentChannelIndex == -1) {
            currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
        }

        return currentChannelIndex
    }

    internal fun wrappedChannelIndex(offset: Int): Int {
        val resolvedIndex = resolveCurrentLiveChannelIndex()
        if (resolvedIndex == -1) return -1
        val size = channelList.size
        return ((resolvedIndex + offset) % size + size) % size
    }

    private fun classifyPlaybackError(error: PlayerError): PlayerRecoveryType = when (error) {
        is PlayerError.NetworkError -> {
            if (error.message.contains("timeout", ignoreCase = true)) {
                PlayerRecoveryType.BUFFER_TIMEOUT
            } else {
                PlayerRecoveryType.NETWORK
            }
        }
        is PlayerError.SourceError -> PlayerRecoveryType.SOURCE
        is PlayerError.DecoderError -> PlayerRecoveryType.DECODER
        is PlayerError.DrmError -> PlayerRecoveryType.DRM
        is PlayerError.UnknownError -> {
            if (error.message.contains("timeout", ignoreCase = true)) {
                PlayerRecoveryType.BUFFER_TIMEOUT
            } else {
                PlayerRecoveryType.UNKNOWN
            }
        }
    }

    private fun resolvePlaybackErrorMessage(error: PlayerError): String = when (classifyPlaybackError(error)) {
        PlayerRecoveryType.NETWORK ->
            // Distinguish "device is offline" (user action: check wifi) from
            // "panel/stream is not answering" (user action: retry / wait).
            if (!networkAvailability.isConnected()) {
                "Pas de connexion Internet — vérifiez votre Wi-Fi ou votre réseau."
            } else {
                "Le serveur ne répond pas pour le moment. Réessayez ou changez de source."
            }
        PlayerRecoveryType.SOURCE -> "Impossible de démarrer ce flux sur les chemins disponibles."
        PlayerRecoveryType.DECODER -> "Ce flux ne peut pas être lu dans le mode de décodage actuel."
        PlayerRecoveryType.DRM -> "La lecture nécessite des identifiants DRM valides ou un niveau de sécurité supporté."
        PlayerRecoveryType.BUFFER_TIMEOUT -> "La lecture est restée bloquée en mise en mémoire tampon trop longtemps."
        PlayerRecoveryType.CATCH_UP -> "Le replay est indisponible pour ce programme."
        PlayerRecoveryType.UNKNOWN -> error.message.ifBlank { "La lecture a échoué pour une raison inconnue." }
    }

    private fun buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> {
        val actions = mutableListOf(PlayerNoticeAction.RETRY)
        if (hasAlternateStream()) {
            actions += PlayerNoticeAction.ALTERNATE_STREAM
        }
        if (hasLastChannel()) {
            actions += PlayerNoticeAction.LAST_CHANNEL
        }
        if (recoveryType == PlayerRecoveryType.CATCH_UP && currentContentType == ContentType.LIVE) {
            actions += PlayerNoticeAction.OPEN_GUIDE
        }
        actions += PlayerNoticeAction.GO_BACK
        return actions
    }

    private fun markStreamFailure(streamUrl: String) {
        if (streamUrl.isBlank()) return
        failedStreamsThisSession[streamUrl] = (failedStreamsThisSession[streamUrl] ?: 0) + 1
    }

    private fun updateDecoderMode(mode: DecoderMode) {
        _playerDiagnostics.update { it.copy(decoderMode = mode) }
    }

    internal fun updateStreamClass(label: String) {
        currentStreamClassLabel = label
        _playerDiagnostics.update { it.copy(streamClassLabel = label) }
    }

    private fun updateChannelDiagnostics(channel: com.streamvault.domain.model.Channel) {
        val archiveLabel = when {
            channel.catchUpSupported && (channel.streamId > 0L || !channel.catchUpSource.isNullOrBlank()) ->
                "Catch-up supported (${channel.catchUpDays} days)"
            channel.catchUpSupported ->
                "Provider advertises catch-up, but replay metadata is incomplete."
            else -> "No archive support advertised"
        }
        val hints = buildList {
            if (channel.errorCount > 0) {
                add("This channel has failed ${channel.errorCount} time(s) recently.")
            }
            if (channel.alternativeStreams.isNotEmpty()) {
                add("${channel.alternativeStreams.size} alternate stream path(s) available.")
            }
            if (channel.catchUpSupported && channel.streamId <= 0L && channel.catchUpSource.isNullOrBlank()) {
                add("Replay may fail because this provider did not expose a catch-up template.")
            }
        }
        _playerDiagnostics.update {
            it.copy(
                alternativeStreamCount = channel.alternativeStreams.size,
                channelErrorCount = channel.errorCount,
                archiveSupportLabel = archiveLabel,
                troubleshootingHints = hints.take(4)
            )
        }
    }

    private suspend fun tryRefreshXtreamPlaybackAfterAuthError(
        error: PlayerError,
        requestVersion: Long,
        playbackUrl: String
    ): Boolean {
        if (hasRetriedXtreamAuthRefresh) return false
        if (error !is PlayerError.NetworkError) return false
        if (!isAuthExpiryPlaybackError(error.message)) return false
        if (!isXtreamPlaybackSession()) return false

        val refreshedStreamInfo = resolvePlaybackStreamInfo(
            logicalUrl = currentStreamUrl,
            internalContentId = currentContentId,
            providerId = currentProviderId,
            contentType = currentContentType
        ) ?: return false

        if (!isActivePlaybackSession(requestVersion, playbackUrl)) return false

        hasRetriedXtreamAuthRefresh = true
        setLastFailureReason(error.message)
        appendRecoveryAction("Refreshed Xtream playback URL after auth failure")
        showPlayerNotice(
            message = "Refreshing the provider playback URL…",
            recoveryType = PlayerRecoveryType.NETWORK,
            actions = buildRecoveryActions(PlayerRecoveryType.NETWORK),
            isRetryNotice = true
        )
        if (!preparePlayer(refreshedStreamInfo, requestVersion)) return true
        playerEngine.play()
        return true
    }

    private suspend fun isXtreamPlaybackSession(): Boolean {
        val providerId = currentProviderId.takeIf { it > 0L } ?: return false
        val provider = providerRepository.getProvider(providerId) ?: return false
        if (provider.type != ProviderType.XTREAM_CODES) return false
        return xtreamStreamUrlResolver.isInternalStreamUrl(currentStreamUrl) ||
            xtreamStreamUrlResolver.isInternalStreamUrl(currentResolvedPlaybackUrl)
    }

    private fun isAuthExpiryPlaybackError(message: String?): Boolean {
        val normalized = message.orEmpty().lowercase(Locale.ROOT)
        return "401" in normalized ||
            "403" in normalized ||
            "unauthorized" in normalized ||
            "forbidden" in normalized ||
            "authentication" in normalized ||
            "token" in normalized ||
            "expired" in normalized
    }

    private fun setLastFailureReason(message: String?) {
        _playerDiagnostics.update { it.copy(lastFailureReason = message?.takeIf { reason -> reason.isNotBlank() }) }
    }

    private fun appendRecoveryAction(action: String) {
        if (action.isBlank()) return
        _playerDiagnostics.update { state ->
            state.copy(recentRecoveryActions = (listOf(action) + state.recentRecoveryActions).distinct().take(5))
        }
    }

    fun hasAlternateStream(): Boolean {
        if (isCatchUpPlayback()) {
            return pendingCatchUpUrls.any { altUrl ->
                altUrl != currentStreamUrl &&
                    altUrl !in triedAlternativeStreams &&
                    (failedStreamsThisSession[altUrl] ?: 0) == 0
            }
        }
        if (currentContentType != ContentType.LIVE) return false
        val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
        return channel.alternativeStreams.any { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        }
    }

    fun tryAlternateStream(): Boolean {
        if (isCatchUpPlayback()) {
            return tryNextCatchUpVariantInternal()
        }
        if (currentContentType != ContentType.LIVE) return false
        val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
        return tryAlternateStreamInternal(channel)
    }

    /**
     * Force a re-prepare on the alternate playback engine. Used when Media3
     * fails on a malformed progressive container that LibVLC could still play
     * (broken moov/stco metadata), or symmetrically when LibVLC chokes on a
     * stream Media3 handles fine. The override is one-shot: subsequent
     * preparations fall back to the regular routing decision.
     */
    private fun tryAlternateEngineInternal(error: PlayerError): Boolean {
        val routing = playerEngine as? com.streamvault.player.RoutingPlayerEngine ?: return false
        val streamUrl = currentStreamUrl
        if (streamUrl.isBlank()) return false
        val currentPref = routing.currentEnginePreference()
        val swapTo = when (currentPref) {
            com.streamvault.player.RoutingPlayerEngine.EnginePreference.MEDIA3 ->
                com.streamvault.player.RoutingPlayerEngine.EnginePreference.LIBVLC
            com.streamvault.player.RoutingPlayerEngine.EnginePreference.LIBVLC ->
                com.streamvault.player.RoutingPlayerEngine.EnginePreference.MEDIA3
        }

        hasRetriedWithAlternateEngine = true
        setLastFailureReason(error.message)
        appendRecoveryAction("Switching playback engine: ${currentPref.name} → ${swapTo.name}")
        android.util.Log.w("PlayerVM", "Engine fallback: ${currentPref.name} → ${swapTo.name} for $streamUrl")

        routing.forceEngineForNextPrepare(swapTo)
        val requestVersion = beginPlaybackSession()
        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(streamUrl, currentContentId, currentProviderId, currentContentType)
                ?: streamUrl
            if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            if (!preparePlayer(streamInfo, requestVersion)) return@launch
            playerEngine.play()
        }
        showPlayerNotice(
            message = "Retrying with the alternate playback engine…",
            recoveryType = PlayerRecoveryType.SOURCE,
            actions = buildRecoveryActions(PlayerRecoveryType.SOURCE),
            isRetryNotice = true
        )
        return true
    }

    private fun tryAlternateStreamInternal(channel: com.streamvault.domain.model.Channel): Boolean {
        val nextStream = channel.alternativeStreams.firstOrNull { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        } ?: channel.alternativeStreams.firstOrNull { altUrl ->
            altUrl != currentStreamUrl && altUrl !in triedAlternativeStreams
        } ?: return false

        val requestVersion = beginPlaybackSession()
        triedAlternativeStreams.add(nextStream)
        currentStreamUrl = nextStream
        updateStreamClass("Alternate")
        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(nextStream, channel.id, channel.providerId, ContentType.LIVE)
                ?: return@launch
            if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            if (!preparePlayer(streamInfo, requestVersion)) return@launch
            playerEngine.play()
        }
        return true
    }

    private fun isCatchUpPlayback(): Boolean = currentStreamClassLabel == "Catch-up"

    private fun nextCatchUpVariant(): String? {
        return pendingCatchUpUrls.firstOrNull { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        } ?: pendingCatchUpUrls.firstOrNull { altUrl ->
            altUrl != currentStreamUrl && altUrl !in triedAlternativeStreams
        }
    }

    private fun tryNextCatchUpVariantInternal(): Boolean {
        val nextStream = nextCatchUpVariant() ?: return false
        val requestVersion = beginPlaybackSession()
        triedAlternativeStreams.add(nextStream)
        currentStreamUrl = nextStream
        updateStreamClass("Catch-up")
        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(nextStream, currentContentId, currentProviderId, ContentType.LIVE)
                ?: return@launch
            if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            if (!preparePlayer(streamInfo, requestVersion)) return@launch
            playerEngine.play()
        }
        return true
    }

    /**
     * A live channel the panel ACCEPTED never produced a first frame before its
     * watchdog fired. That combination — no HTTP refusal, no bytes — is what a
     * blackholed exit IP looks like from inside the player, and it is invisible to
     * every other signal the VPN guard has (they all key on a 403).
     *
     * Deliberately reported from the watchdogs rather than from a pre-playback
     * body probe: the panel's lines are max=1 and an extra media request steals the
     * slot from the playback that follows it (measured — the origin answers 509 to
     * the engine for seconds afterwards). The watchdogs already know the answer and
     * cost nothing.
     *
     * Reported only when:
     *  - the content is LIVE (VOD refusals are loud, and are already handled);
     *  - no frame was ever shown ([liveFirstFrameSeen]) — a stall AFTER playback
     *    started is a different failure, and its live success already told the guard
     *    this exit delivers bytes;
     *  - the probe did not report a refusal ([probeAdvisoryHttpCode]) — with an HTTP
     *    code in hand this is a refusal, not a blackhole.
     * The guard then needs a SECOND distinct channel before it rotates anything.
     */
    private fun reportLiveBlackholeIfNeverStarted() {
        if (currentContentType != ContentType.LIVE) return
        if (liveFirstFrameSeen || probeAdvisoryHttpCode != null) return
        val streamId = XtreamUrlFactory.parsePlaybackUrl(currentResolvedPlaybackUrl)?.streamId
            ?: currentContentId
        android.util.Log.w(
            "PlayerVM",
            "live accepted but no first frame (id=$streamId) — reporting exit IP as suspect"
        )
        vpnManager.reportLiveBlackhole(currentProviderId, streamId)
    }

    internal fun scheduleZapBufferWatchdog(targetIndex: Int) {
        zapBufferWatchdogJob?.cancel()
        val requestVersion = prepareRequestVersion
        zapBufferWatchdogJob = viewModelScope.launch {
            delay(9000)
            if (!isActivePlaybackSession(requestVersion)) return@launch
            val stillOnTarget = currentChannelIndex == targetIndex
            val state = playerEngine.playbackState.value
            val stalled = state == PlaybackState.BUFFERING || state == PlaybackState.ERROR
            if (stillOnTarget && stalled) {
                reportLiveBlackholeIfNeverStarted()
                markStreamFailure(currentStreamUrl)
                setLastFailureReason("Channel timed out in buffering state")
                appendRecoveryAction("Buffer watchdog triggered")
                val recovered = fallbackToPreviousChannel("Channel timed out in buffering state")
                if (!recovered) {
                    // No previous channel to fall back to: explicitly stop the
                    // engine so it exits BUFFERING. Otherwise PlayerScreen keeps
                    // the centered spinner forever (showLoader = BUFFERING || IDLE)
                    // and the user has to back out manually.
                    playerEngine.stop()
                }
                showPlayerNotice(
                    message = if (recovered) {
                        "That channel stalled too long. Returned to the last channel."
                    } else {
                        "That channel stalled too long. Try another source or open the guide."
                    },
                    recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                    actions = buildRecoveryActions(PlayerRecoveryType.BUFFER_TIMEOUT)
                )
            }
        }
    }

    private fun scheduleVodPrepareWatchdog() {
        vodPrepareWatchdogJob?.cancel()
        val requestVersion = prepareRequestVersion
        vodPrepareWatchdogJob = viewModelScope.launch {
            delay(VOD_PREPARE_TIMEOUT_MS)
            if (!isActivePlaybackSession(requestVersion)) return@launch
            val state = playerEngine.playbackState.value
            // Still stuck before first frame after the grace window: the panel
            // is not feeding bytes. Stop so the loader clears, and offer a
            // retry. No "previous channel" / "alternate stream" — those are
            // live-only and buildRecoveryActions already excludes them for VOD.
            if (state == PlaybackState.BUFFERING || state == PlaybackState.IDLE) {
                markStreamFailure(currentStreamUrl)
                setLastFailureReason("VOD prepare timed out in buffering state")
                appendRecoveryAction("VOD prepare watchdog triggered")
                playerEngine.stop()
                showPlayerNotice(
                    // An advisory probe rejection (401/403) followed by a hung
                    // prepare means the panel silently dropped us: surface the
                    // provider refusal rather than a connectivity hint.
                    message = probeAdvisoryHttpCode?.let { probeFailureMessage(it) }
                        ?: "La lecture n'a pas pu démarrer — vérifiez votre connexion ou réessayez.",
                    recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                    actions = buildRecoveryActions(PlayerRecoveryType.BUFFER_TIMEOUT)
                )
            }
        }
    }

    private fun scheduleMidStreamStallWatchdog(targetIndex: Int) {
        midStreamStallWatchdogJob?.cancel()
        val requestVersion = prepareRequestVersion
        // targetIndex >= 0 means LIVE on a specific channel; -1 means VOD
        // (catch-up, movie, episode) where we track the playback session via
        // requestVersion instead of channelIndex.
        val isLiveTarget = targetIndex >= 0
        // Live = continuous MPEG-TS, so a long BUFFERING is almost always a real
        // stall: react faster. VOD first-byte can be legitimately slow: stay
        // patient.
        val timeoutMs = if (isLiveTarget) {
            MID_STREAM_STALL_TIMEOUT_LIVE_MS
        } else {
            MID_STREAM_STALL_TIMEOUT_MS
        }
        midStreamStallWatchdogJob = viewModelScope.launch {
            delay(timeoutMs)
            if (!isActivePlaybackSession(requestVersion)) return@launch
            val stillOnTarget = if (isLiveTarget) {
                currentChannelIndex == targetIndex
            } else {
                requestVersion == prepareRequestVersion
            }
            val state = playerEngine.playbackState.value
            if (!stillOnTarget || state != PlaybackState.BUFFERING) return@launch
            // First channel of the session has no zap watchdog, so this is where a
            // never-started live surfaces. No-op once a frame has been shown.
            if (isLiveTarget) reportLiveBlackholeIfNeverStarted()
            // Re-arm so the next tick can decide whether to restart again (after
            // cooldown) or fall back — the BUFFERING watchdog drives its own loop.
            handleMidStreamStall(targetIndex, reArmBufferingWatchdog = true)
        }
    }

    // Shared recovery for a detected mid-stream stall, reached from two sources:
    // the BUFFERING watchdog (scheduleMidStreamStallWatchdog) and the live
    // position-progress watchdog (startLiveProgressWatchdog). Both funnel through
    // the same MidStreamStallRecoveryPolicy so cooldown + max-restart accounting
    // is consistent regardless of how the freeze was detected.
    private fun handleMidStreamStall(targetIndex: Int, reArmBufferingWatchdog: Boolean) {
        val isLiveTarget = targetIndex >= 0
        when (midStreamStallPolicy.decide(networkAvailability.isConnected())) {
            MidStreamStallRecoveryPolicy.Action.Restart -> {
                midStreamStallPolicy.onRestart()
                android.util.Log.w(
                    "PlayerVM",
                    "mid-stream stall: restart isLive=$isLiveTarget target=$targetIndex"
                )
                appendRecoveryAction("Mid-stream stall: restart")
                // Surface a user-visible "Reconnexion…" notice so the viewer
                // knows recovery is in progress instead of staring at a frozen
                // frame. Auto-dismisses when state→READY.
                showPlayerNotice(
                    message = "Reconnexion en cours…",
                    recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                    actions = emptyList(),
                    isRetryNotice = true
                )
                playerEngine.restart()
                if (reArmBufferingWatchdog) scheduleMidStreamStallWatchdog(targetIndex)
            }
            MidStreamStallRecoveryPolicy.Action.WaitForNetwork -> {
                android.util.Log.w(
                    "PlayerVM",
                    "mid-stream stall: no network, deferring restart target=$targetIndex"
                )
                appendRecoveryAction("Mid-stream stall: waiting for network")
                showPlayerNotice(
                    message = "Pas de connexion — attente du réseau…",
                    recoveryType = PlayerRecoveryType.NETWORK,
                    actions = emptyList(),
                    isRetryNotice = true
                )
                if (reArmBufferingWatchdog) scheduleMidStreamStallWatchdog(targetIndex)
            }
            MidStreamStallRecoveryPolicy.Action.WaitForCooldown -> {
                android.util.Log.w(
                    "PlayerVM",
                    "mid-stream stall: cooldown active, deferring restart target=$targetIndex"
                )
                appendRecoveryAction("Mid-stream stall: cooldown")
                if (reArmBufferingWatchdog) scheduleMidStreamStallWatchdog(targetIndex)
            }
            MidStreamStallRecoveryPolicy.Action.Fallback -> {
                markStreamFailure(currentStreamUrl)
                setLastFailureReason("Mid-stream stall: restarts exhausted")
                appendRecoveryAction("Mid-stream stall watchdog gave up")
                midStreamStallPolicy.reset()
                val recovered = if (isLiveTarget) {
                    fallbackToPreviousChannel("Mid-stream stall: restarts exhausted")
                } else {
                    // VOD has no "previous channel" concept — just stop so the
                    // spinner clears and the notice is visible.
                    playerEngine.stop()
                    false
                }
                showPlayerNotice(
                    message = when {
                        recovered -> "Stream stalled and could not recover. " +
                            "Returned to the last channel."
                        isLiveTarget -> "Stream stalled and could not recover. " +
                            "Try another source or open the guide."
                        else -> "La lecture est interrompue. Réessaie plus tard ou choisis une autre source."
                    },
                    recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                    actions = buildRecoveryActions(PlayerRecoveryType.BUFFER_TIMEOUT)
                )
            }
        }
    }

    // Live "frozen frame" watchdog: catches the case the BUFFERING watchdog
    // cannot — the decoder stays in READY with isPlaying=true but the playback
    // position stops advancing (no error, no BUFFERING). Polls the position and,
    // after a sustained freeze, routes through the shared recovery path. Live
    // only: VOD pause/seek legitimately freezes the position.
    private fun startLiveProgressWatchdog() {
        liveProgressWatchdogJob?.cancel()
        val targetIndex = currentChannelIndex
        if (currentContentType != ContentType.LIVE || targetIndex !in channelList.indices) return
        val requestVersion = prepareRequestVersion
        liveProgressWatchdogJob = viewModelScope.launch {
            var lastPos = playerEngine.currentPosition.value
            var lastAdvanceAt = SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive) {
                delay(LIVE_PROGRESS_CHECK_INTERVAL_MS)
                if (!isActivePlaybackSession(requestVersion)) return@launch
                val steady = currentContentType == ContentType.LIVE &&
                    currentChannelIndex == targetIndex &&
                    playerEngine.isPlaying.value &&
                    playerEngine.playbackState.value == PlaybackState.READY &&
                    zapBufferWatchdogJob?.isActive != true
                val pos = playerEngine.currentPosition.value
                // Reset the freeze timer whenever we are not in a steady
                // "video should be moving" situation, or the position advanced.
                if (!steady || pos > lastPos + LIVE_PROGRESS_MIN_ADVANCE_MS) {
                    lastPos = pos
                    lastAdvanceAt = SystemClock.elapsedRealtime()
                    continue
                }
                if (SystemClock.elapsedRealtime() - lastAdvanceAt >= LIVE_PROGRESS_STALL_THRESHOLD_MS) {
                    android.util.Log.w(
                        "PlayerVM",
                        "live progress stall: position frozen at $pos target=$targetIndex"
                    )
                    appendRecoveryAction("Live progress stall: position frozen")
                    // reArm=false: this loop keeps watching on its own; the
                    // BUFFERING watchdog will also arm naturally if the restart
                    // pushes us back through READY→BUFFERING.
                    handleMidStreamStall(targetIndex, reArmBufferingWatchdog = false)
                    // Reset so we wait another full threshold before re-triggering
                    // (avoids spamming the policy while the engine churns through
                    // IDLE→BUFFERING→READY after restart).
                    lastPos = playerEngine.currentPosition.value
                    lastAdvanceAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    // Watchdog VOD "position figée" : filet de sécurité symétrique de
    // startLiveProgressWatchdog mais pour le non-LIVE. Le correctif moteur A
    // (LibVlcPlayerEngine) rend déjà la position auto-réparante ; ce watchdog
    // ne sert qu'en dernier recours si la position cesse d'avancer alors que
    // isPlaying==true et READY. Il passe par la même MidStreamStallRecoveryPolicy
    // (cooldown + max-restart partagés) via targetIndex=-1 (chemin VOD :
    // restart, puis stop()+notice si épuisé).
    private fun startVodProgressWatchdog() {
        vodProgressWatchdogJob?.cancel()
        if (currentContentType == ContentType.LIVE) return
        val requestVersion = prepareRequestVersion
        vodProgressWatchdogJob = viewModelScope.launch {
            var lastPos = playerEngine.currentPosition.value
            var lastAdvanceAt = SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive) {
                delay(LIVE_PROGRESS_CHECK_INTERVAL_MS)
                if (!isActivePlaybackSession(requestVersion)) return@launch
                // "steady" = on s'attend à ce que la position avance. Pause et
                // buffering légitimes (isPlaying==false ou state!=READY)
                // réinitialisent le timer → pas de faux positif.
                val steady = currentContentType != ContentType.LIVE &&
                    requestVersion == prepareRequestVersion &&
                    playerEngine.isPlaying.value &&
                    playerEngine.playbackState.value == PlaybackState.READY
                val pos = playerEngine.currentPosition.value
                if (!steady || pos > lastPos + LIVE_PROGRESS_MIN_ADVANCE_MS) {
                    lastPos = pos
                    lastAdvanceAt = SystemClock.elapsedRealtime()
                    continue
                }
                if (SystemClock.elapsedRealtime() - lastAdvanceAt >= LIVE_PROGRESS_STALL_THRESHOLD_MS) {
                    android.util.Log.w("PlayerVM", "vod progress stall: position frozen at $pos")
                    appendRecoveryAction("VOD progress stall: position frozen")
                    handleMidStreamStall(targetIndex = -1, reArmBufferingWatchdog = false)
                    // Laisser le temps au restart de retraverser READY avant de
                    // re-mesurer, pour ne pas spammer la policy.
                    lastPos = playerEngine.currentPosition.value
                    lastAdvanceAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun fallbackToPreviousChannel(reason: String): Boolean {
        val fallbackIndex = previousChannelIndex
        if (fallbackIndex !in channelList.indices) return false
        if (fallbackIndex == currentChannelIndex) return false
        // A playlist reorder (sync, favorite toggle) can leave
        // previousChannelIndex pointing at a DIFFERENT index that now resolves
        // to the SAME channel we're already on. Switching to it would show a
        // misleading "returned to the last channel" notice while nothing
        // actually changed — bail so the caller surfaces the no-fallback path.
        val currentId = channelList.getOrNull(currentChannelIndex)?.id
        val fallbackId = channelList.getOrNull(fallbackIndex)?.id
        if (currentId != null && currentId == fallbackId) return false
        android.util.Log.w("PlayerVM", "Falling back to previous channel: $reason")
        changeChannel(fallbackIndex)
        return true
    }

    fun play() = playerEngine.play()

    fun pause() {
        playerEngine.pause()
        // Event-based save: a user pause is a natural resume point — don't
        // wait up to 30 s for the periodic tick.
        viewModelScope.launch { persistPlaybackProgress() }
    }
    fun seekForward() = playerEngine.seekForward()
    fun seekBackward() = playerEngine.seekBackward()

    fun startProgressiveSeek(direction: SeekDirection) {
        // Le direct pur n’est pas rejouable ; un catch-up l’est, même s’il porte
        // contentType LIVE.
        if (currentContentType == ContentType.LIVE && !_isTimeshiftPlayback.value) return
        if (playerEngine.playbackState.value == PlaybackState.ERROR) return
        if (progressiveSeekController.isActive.value) return
        wasPlayingBeforeProgressiveSeek = playerEngine.isPlaying.value ||
            playerEngine.playbackState.value == PlaybackState.BUFFERING
        playerEngine.pause()
        progressiveSeekController.start(direction)
    }

    fun stopProgressiveSeek() {
        if (!progressiveSeekController.isActive.value) return
        progressiveSeekController.stop()
        if (wasPlayingBeforeProgressiveSeek) {
            playerEngine.play()
        }
        wasPlayingBeforeProgressiveSeek = false
    }

    fun playEpisode(episode: Episode, showResumePrompt: Boolean = true) {
        prepare(
            streamUrl = episode.streamUrl,
            epgChannelId = null,
            internalChannelId = episode.id,
            categoryId = -1,
            providerId = episode.providerId,
            isVirtual = false,
            contentType = ContentType.SERIES_EPISODE.name,
            title = buildEpisodePlaybackTitle(episode),
            artworkUrl = episode.coverUrl ?: _currentSeries.value?.posterUrl ?: _currentSeries.value?.backdropUrl,
            seriesId = currentSeriesId ?: episode.seriesId.takeIf { it > 0L },
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            showResumePrompt = showResumePrompt
        )
    }

    fun toggleMute() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMuteToggleAtMs < MUTE_TOGGLE_DEBOUNCE_MS) return
        lastMuteToggleAtMs = now
        playerEngine.toggleMute()
        val muted = playerEngine.isMuted.value
        mutePersistJob?.cancel()
        mutePersistJob = viewModelScope.launch {
            preferencesRepository.setPlayerMuted(muted)
        }
    }

    fun toggleControls() {
        closeChannelInfoOverlay()
        showControlsFlow.value = !showControlsFlow.value
        if (!showControlsFlow.value) {
            clearSeekPreview()
        }
    }

    fun revealControls() {
        if (!showControlsFlow.value) {
            showControlsFlow.value = true
        }
    }

    private fun clearSeekPreview() {
        seekPreviewJob?.cancel()
        seekPreviewRequestVersion++
        _seekPreview.value = SeekPreviewState()
    }

    fun toggleAspectRatio() {
        val nextRatio = when (_aspectRatio.value) {
            AspectRatio.FIT -> AspectRatio.FILL
            AspectRatio.FILL -> AspectRatio.ZOOM
            AspectRatio.ZOOM -> AspectRatio.FIT
        }
        _aspectRatio.value = nextRatio

        // Save instantly if we have a valid channel ID
        if (currentContentId != -1L) {
            viewModelScope.launch {
                preferencesRepository.setAspectRatioForChannel(currentContentId, nextRatio.name)
            }
        }
    }

    private suspend fun startCatchUpPlayback(
        urls: List<String>,
        title: String,
        recoveryAction: String,
        requestVersionOverride: Long? = null
    ) {
        val requestVersion = requestVersionOverride ?: beginPlaybackSession()
        val candidates = urls
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val primaryUrl = candidates.firstOrNull() ?: return

        currentTitle = title
        pendingCatchUpUrls = candidates
        triedAlternativeStreams.clear()
        triedAlternativeStreams.add(primaryUrl)
        currentStreamUrl = primaryUrl
        updateStreamClass("Catch-up")
        _isTimeshiftPlayback.value = true
        appendRecoveryAction(recoveryAction)

        val catchupStream = com.streamvault.domain.model.StreamInfo(
            url = primaryUrl,
            title = currentTitle,
            streamType = com.streamvault.domain.model.StreamType.UNKNOWN
        )
        if (!preparePlayer(catchupStream, requestVersion)) return
        playerEngine.play()
        showControlsFlow.value = true
    }

    fun playCatchUp(program: Program) {
        viewModelScope.launch {
            val requestVersion = prepareRequestVersion
            val start = program.startTime / 1000L
            val end = program.endTime / 1000L
            val streamId = currentChannelFlow.value?.id ?: 0L
            val providerId = currentProviderId
            
            if (providerId == -1L || streamId == 0L) {
                setLastFailureReason("Catch-up playback needs a valid live channel context.")
                showPlayerNotice(
                    message = "Catch-up playback needs a valid live channel context.",
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
                return@launch
            }

            val catchUpUrls = try {
                providerRepository.buildCatchUpUrls(providerId, streamId, start, end)
            } catch (e: CredentialDecryptionException) {
                if (!isActivePlaybackSession(requestVersion)) return@launch
                setLastFailureReason(e.message ?: CredentialDecryptionException.MESSAGE)
                showPlayerNotice(
                    message = e.message ?: CredentialDecryptionException.MESSAGE,
                    recoveryType = PlayerRecoveryType.SOURCE,
                    actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
                )
                return@launch
            }
            if (!isActivePlaybackSession(requestVersion)) return@launch
            if (catchUpUrls.isNotEmpty()) {
                startCatchUpPlayback(
                    urls = catchUpUrls,
                    title = "${currentChannelFlow.value?.name ?: ""}: ${program.title}",
                    recoveryAction = "Started program replay"
                )
            } else {
                val reason = resolveCatchUpFailureMessage(currentChannelFlow.value, archiveRequested = true, programHasArchive = program.hasArchive)
                setLastFailureReason(reason)
                showPlayerNotice(
                    message = reason,
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
            }
        }
    }

    private fun resolveCatchUpFailureMessage(
        channel: com.streamvault.domain.model.Channel?,
        archiveRequested: Boolean,
        programHasArchive: Boolean
    ): String {
        if (!archiveRequested || channel == null) {
            return "Catch-up playback needs a valid live channel context."
        }
        return when {
            !channel.catchUpSupported && !programHasArchive ->
                "This channel does not advertise archive support on the current provider."
            channel.streamId <= 0L && channel.catchUpSource.isNullOrBlank() ->
                "The provider advertises catch-up, but did not expose replay metadata for this channel."
            else ->
                "Replay is unavailable for the selected program right now."
        }
    }

    fun dismissPlayerNotice() {
        playerNoticeHideJob?.cancel()
        _playerNotice.value = null
    }

    private fun dismissRecoveredNoticeIfPresent() {
        val notice = _playerNotice.value ?: return
        if (notice.isRetryNotice || notice.recoveryType != PlayerRecoveryType.UNKNOWN) {
            dismissPlayerNotice()
        }
    }

    fun runPlayerNoticeAction(action: PlayerNoticeAction) {
        when (action) {
            PlayerNoticeAction.RETRY -> {
                appendRecoveryAction("Manual retry")
                retryStream(currentStreamUrl, currentChannelFlow.value?.epgChannelId)
            }
            PlayerNoticeAction.LAST_CHANNEL -> {
                appendRecoveryAction("Returned to last channel")
                zapToLastChannel()
            }
            PlayerNoticeAction.ALTERNATE_STREAM -> {
                appendRecoveryAction("Manual alternate stream")
                tryAlternateStream()
            }
            PlayerNoticeAction.OPEN_GUIDE -> {
                appendRecoveryAction("Opened guide from recovery")
                openEpgOverlay()
            }
            // Navigation is the screen's job: PlayerScreen intercepts GO_BACK
            // and invokes its onBack callback before reaching here.
            PlayerNoticeAction.GO_BACK -> Unit
        }
        dismissPlayerNotice()
    }

    internal fun showPlayerNotice(
        message: String,
        recoveryType: PlayerRecoveryType = PlayerRecoveryType.UNKNOWN,
        actions: List<PlayerNoticeAction> = emptyList(),
        durationMs: Long = playerNoticeTimeoutMs,
        isRetryNotice: Boolean = false
    ) {
        playerNoticeHideJob?.cancel()
        _playerNotice.value = PlayerNoticeState(
            message = message,
            recoveryType = recoveryType,
            actions = actions.distinct(),
            isRetryNotice = isRetryNotice
        )
        playerNoticeHideJob = viewModelScope.launch {
            delay(durationMs)
            if (_playerNotice.value?.message == message) {
                _playerNotice.value = null
            }
        }
    }

    private fun showRetryNotice(status: com.streamvault.player.PlayerRetryStatus) {
        val formatLabel = currentPlaybackFormatLabel()
        val message = "Retrying $formatLabel ${status.attempt}/${status.maxAttempts} in ${status.delayMs / 1000}s..."
        showPlayerNotice(
            message = message,
            recoveryType = PlayerRecoveryType.NETWORK,
            durationMs = maxOf(playerNoticeTimeoutMs, status.delayMs + 1500L),
            isRetryNotice = true
        )
    }

    private fun currentPlaybackFormatLabel(): String {
        val url = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }.lowercase(Locale.ROOT)
        return when {
            url.contains("ext=m3u8") || url.endsWith(".m3u8") -> "HLS"
            url.contains("ext=ts") || url.endsWith(".ts") -> "TS"
            else -> "stream"
        }
    }

    fun restartCurrentProgram() {
        val program = _currentProgram.value ?: return
        if (program.hasArchive || (currentChannelFlow.value?.catchUpSupported == true)) {
            playCatchUp(program)
        }
    }

    fun retryStream(streamUrl: String, epgChannelId: String?) {
        if (isCatchUpPlayback()) {
            val requestVersion = beginPlaybackSession()
            viewModelScope.launch {
                val resolvedUrl = resolvePlaybackUrl(streamUrl, currentContentId, currentProviderId, currentContentType)
                    ?: return@launch
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                val streamInfo = com.streamvault.domain.model.StreamInfo(
                    url = resolvedUrl,
                    title = currentTitle,
                    streamType = com.streamvault.domain.model.StreamType.UNKNOWN
                )
                if (!preparePlayer(streamInfo, requestVersion)) return@launch
                playerEngine.play()
            }
            return
        }
        val currentId = if (currentChannelIndex != -1 && channelList.isNotEmpty()) channelList[currentChannelIndex].id else -1L
        prepare(streamUrl, epgChannelId, currentId, currentCategoryId, currentProviderId, isVirtualCategory, currentContentType.name, currentTitle)
    }

    private suspend fun resolvePlaybackStreamInfo(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): com.streamvault.domain.model.StreamInfo? {
        var fallbackStreamId: Long? = null
        var fallbackContainerExtension: String? = null

        if (providerId > 0L && internalContentId > 0L) {
            when (contentType) {
                ContentType.LIVE -> {
                    channelRepository.getChannel(internalContentId)?.let { channel ->
                        fallbackStreamId = channel.streamId.takeIf { it > 0L }
                        channelRepository.getStreamInfo(channel).getOrNull()?.let { resolved ->
                            return resolved.copy(title = resolved.title ?: currentTitle)
                        }
                    }
                }

                ContentType.MOVIE -> {
                    movieRepository.getMovie(internalContentId)?.let { movie ->
                        fallbackStreamId = movie.streamId.takeIf { it > 0L }
                        fallbackContainerExtension = movie.containerExtension
                        movieRepository.getStreamInfo(movie).getOrNull()?.let { resolved ->
                            return resolved.copy(title = resolved.title ?: currentTitle)
                        }
                    }
                }

                ContentType.SERIES,
                ContentType.SERIES_EPISODE -> {
                    val episode = when {
                        _currentEpisode.value?.id == internalContentId -> _currentEpisode.value
                        else -> _currentSeries.value
                            ?.seasons
                            .sanitizedForPlayer()
                            ?.asSequence()
                            ?.flatMap { it.episodes.asSequence() }
                            ?.firstOrNull { it.id == internalContentId }
                    }
                    episode?.let {
                        fallbackStreamId = it.episodeId.takeIf { episodeId -> episodeId > 0L } ?: it.id
                        fallbackContainerExtension = it.containerExtension
                        seriesRepository.getEpisodeStreamInfo(it).getOrNull()?.let { resolved ->
                            return resolved.copy(title = resolved.title ?: currentTitle)
                        }
                    }
                }
            }
        }

        try {
            xtreamStreamUrlResolver.resolveWithMetadata(
                url = logicalUrl,
                fallbackProviderId = providerId.takeIf { it > 0 },
                fallbackStreamId = fallbackStreamId,
                fallbackContentType = contentType,
                fallbackContainerExtension = fallbackContainerExtension
            )?.let { resolved ->
                return com.streamvault.domain.model.StreamInfo(
                    url = resolved.url,
                    title = currentTitle,
                    containerExtension = fallbackContainerExtension,
                    expirationTime = resolved.expirationTime
                )
            }
        } catch (e: CredentialDecryptionException) {
            val message = e.message ?: CredentialDecryptionException.MESSAGE
            setLastFailureReason(message)
            showPlayerNotice(message = message, recoveryType = PlayerRecoveryType.SOURCE)
            return null
        }

        return logicalUrl.takeIf { it.isNotBlank() }?.let {
            com.streamvault.domain.model.StreamInfo(
                url = it,
                title = currentTitle,
                containerExtension = fallbackContainerExtension
            )
        }
    }

    internal suspend fun resolvePlaybackUrl(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): String? = resolvePlaybackStreamInfo(
        logicalUrl = logicalUrl,
        internalContentId = internalContentId,
        providerId = providerId,
        contentType = contentType
    )?.url

    fun dismissResumePrompt(resume: Boolean) {
        val prompt = _resumePrompt.value
        _resumePrompt.value = ResumePromptState() // hide
        if (resume && prompt.positionMs > 0) {
            playerEngine.seekTo(prompt.positionMs)
        }
        playerEngine.play()
    }

    override fun onCleared() {
        // Guard the delayed background-stop job: mark the engine dead and cancel
        // the timer BEFORE release(), so it can never call stopForBackground()
        // on a freed engine (crash, esp. LibVLC native threads).
        isEngineAlive = false
        backgroundStopJob?.cancel()
        backgroundStopJob = null
        // Final save FIRST, and on applicationScope: the historical
        // `super.onCleared()` call at the top cancelled viewModelScope before
        // the save coroutine ever ran — exits never persisted the position.
        persistFinalProgress(refreshShelves = true)
        channelInfoHideJob?.cancel()
        liveOverlayHideJob?.cancel()
        diagnosticsHideJob?.cancel()
        numericInputCommitJob?.cancel()
        numericInputFeedbackJob?.cancel()
        playerNoticeHideJob?.cancel()
        epgJob?.cancel()
        playlistJob?.cancel()
        controlsHideJob?.cancel()
        zapOverlayJob?.cancel()
        zapBufferWatchdogJob?.cancel()
        vodPrepareWatchdogJob?.cancel()
        midStreamStallWatchdogJob?.cancel()
        liveProgressWatchdogJob?.cancel()
        vodProgressWatchdogJob?.cancel()
        inactivityWatchdogJob?.cancel()
        preloadJob?.cancel()
        prematureEndRetryJob?.cancel()
        mutePersistJob?.cancel()
        progressTrackingJob?.cancel()
        postSeekPersistJob?.cancel()
        aspectRatioJob?.cancel()
        recentChannelsJob?.cancel()
        lastVisitedCategoryJob?.cancel()
        seekThumbnailProvider.clearCache()
        // Releases the per-screen engine resources only — the global LibVLC
        // singleton must never be freed here (next playback would ANR).
        playerEngine.release()
        evictPlaybackPoolQuietly()
        playbackReporter.stop()
        super.onCleared()
    }
}
