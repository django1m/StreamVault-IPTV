package com.streamvault.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.View
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.playback.LibVlcMediaOptions
import com.streamvault.player.playback.PlaybackSample
import com.streamvault.player.playback.VideoFreezeDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC-backed [PlayerEngine] used as a fallback for PROGRESSIVE MP4/MKV streams
 * where Media3's Mp4Extractor mis-parses the moov atom (invalid stco offsets).
 *
 * This is a minimal POC: only the playback paths needed to render a video on
 * screen are wired. Track selection, subtitle styling, network preferences,
 * scrubbing mode, preload and media-session features are intentionally stubbed.
 *
 * ## Threading model
 *
 * Deux HandlerThread dédiés à durée de vie process (créés en `lazy`, JAMAIS
 * quittés — comme `libVlc`) :
 *
 * - `libvlc-cmd` (cmdThread) : exécute les COMMANDES utilisateur — `prepare`,
 *   `play`, `pause`, `seekTo`, `setVolume`, `setRate`, `attachViews`,
 *   `detachViews`, `vlcVout.setWindowSize`, `videoScale`, `setSpuTrack`,
 *   `setAudioTrack`, etc.
 *
 * - `libvlc-poll` (pollThread) : exécute UNIQUEMENT le polling de position
 *   (lectures `player.time` / `player.length`).
 *
 * **Pourquoi deux threads ?** Sur les MP4 cassés (moov/stco mal formés — la
 * raison d'utiliser cet engine en fallback), les getters `player.time` /
 * `player.length` peuvent bloquer quelques centaines de ms pendant que le
 * decoder se re-sync. Si le polling et les commandes partagent la même
 * queue, un poll bloqué fige TOUTES les commandes utilisateur qui suivent
 * (pause, seek, etc.) → la barre de contrôle se gèle (position figée,
 * bouton coincé, télécommande morte) alors que la lecture continue côté
 * natif. Symptôme typiquement observé lors de la transition d'épisode
 * (auto-next via `EndReached` + click manuel "épisode suivant" → deux
 * `prepare()` rapides).
 *
 * **Pourquoi PAS [release]er le thread ?** Cet engine est `@Singleton`,
 * [release] est appelé à chaque `onCleared()` du `PlayerViewModel` (back du
 * PlayerScreen). Tuer le thread libVLC rendrait le prochain `prepare()`
 * sans Looper et bloquerait le main thread (ANR observé).
 *
 * ## Media generation counter
 *
 * Un compteur atomique [mediaGeneration] est incrémenté à chaque [prepare].
 * Les événements VLC (TimeChanged, LengthChanged, Playing, etc.) arrivent
 * sur le thread de callback natif — potentiellement APRÈS un changement
 * d'épisode. On capture la génération au moment de [prepare] côté Main, et
 * on jette les events dont la génération est obsolète. Sans ça, un
 * TimeChanged de l'ancien épisode peut écraser `_currentPosition` du
 * nouveau (position figée à l'ancienne valeur, symptôme principal du bug).
 */
@Singleton
class LibVlcPlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) : PlayerEngine {

    companion object {
        private const val TAG = "LibVlcPlayerEngine"
        const val FREEZE_TAG = "VideoFreezeFallback"
        private const val POSITION_POLL_INTERVAL_MS = 250L
        private const val SEEK_END_GUARD_MS = 5_000L
        private val LAYOUT_LISTENER_TAG_ID = "libvlc_layout_listener".hashCode()
    }

    // ---- Threads dédiés libVLC (voir doc en tête de classe) ---------------

    private val cmdThread: HandlerThread by lazy {
        HandlerThread("libvlc-cmd").also { it.start() }
    }
    private val cmdHandler: Handler by lazy { Handler(cmdThread.looper) }

    private val pollThread: HandlerThread by lazy {
        HandlerThread("libvlc-poll").also { it.start() }
    }
    private val pollHandler: Handler by lazy { Handler(pollThread.looper) }

    /**
     * Generation incrémentée à chaque [prepare]. Capturée côté Main au moment
     * de l'appel, comparée dans les callbacks VLC pour ignorer les events
     * obsolètes de l'épisode précédent.
     */
    private val mediaGeneration = AtomicInteger(0)

    private inline fun postCmd(label: String, crossinline action: () -> Unit) {
        cmdHandler.post {
            try {
                action()
            } catch (t: Throwable) {
                Log.e(TAG, "$label failed on cmd thread", t)
            }
        }
    }

    /**
     * Polling périodique de la position — tourne sur [pollThread], JAMAIS sur
     * [cmdThread]. Sépare le polling des commandes utilisateur : si un
     * `player.time` natif bloque, seul le polling stalle, pas les commandes.
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null) {
                // Self-heal de la garde de génération. Ce poll est retiré par
                // chaque prepare()/release() (removeCallbacks) PUIS re-posté à
                // neuf par prepare() APRÈS avoir committé activeMediaGeneration.
                // Donc s'il tourne avec un player vivant, c'est qu'il appartient
                // au média courant : on réconcilie un activeMediaGeneration resté
                // en retard (prepare superseded sorti tôt, ou release() qui a
                // incrémenté mediaGeneration sans le committer). Sans ça la garde
                // restait coincée en permanence → position + isPlaying jamais
                // ré-écrits (events jetés), barre figée et play/pause mort alors
                // que la lecture native continue.
                val current = mediaGeneration.get()
                if (activeMediaGeneration != current) {
                    activeMediaGeneration = current
                }
                try {
                    val time = player.time
                    if (time >= 0L) {
                        _currentPosition.value = time
                    }
                    val len = player.length
                    if (len > 0L && _duration.value != len) {
                        _duration.value = len
                    }
                    // isPlaying piloté depuis le lecteur natif unique : la source
                    // de vérité ne peut plus se figer si l'event Playing/Paused a
                    // été jeté pendant une transition. Garantit que le toggle
                    // `if (isPlaying) pause() else play()` ne reste jamais coincé.
                    val playing = player.isPlaying
                    if (_isPlaying.value != playing) {
                        _isPlaying.value = playing
                    }
                    sampleForFreeze(player, time, playing)
                } catch (t: Throwable) {
                    Log.w(TAG, "polling read failed", t)
                }
                // Tant que le player est vivant, on continue de ticker. Après
                // release() (mediaPlayer == null) on s'arrête ; le prochain
                // prepare() re-poste un poll neuf. Plus de condition sur la
                // génération ici : c'était elle qui pouvait geler le poll.
                pollHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Génération média que [pollRunnable] et les events VLC sont autorisés à
     * écrire dans les StateFlows. Mise à jour côté [cmdThread] juste avant
     * `player.play()` du nouveau média.
     */
    @Volatile private var activeMediaGeneration: Int = 0

    // ---- libVLC singleton (process lifetime) ------------------------------

    private val libVlc: LibVLC by lazy {
        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--avcodec-hw=any",
            "--http-reconnect",
            "--network-caching=2000"
        )
        LibVLC(context, args)
    }

    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var attachedLayout: VLCVideoLayout? = null
    @Volatile private var lastVolumeBeforeMute: Int = 100
    @Volatile private var lastStreamInfo: StreamInfo? = null
    /**
     * Consumed by the next [prepare] call to seek to a specific second offset
     * via libVLC's `:start-time` option. Set by [restart] before calling
     * [prepare] so a mid-stream watchdog reconnect resumes at the user's
     * actual position instead of rewinding to 0.
     */
    @Volatile private var pendingResumeSeconds: Long = 0L

    // ---- StateFlows -------------------------------------------------------

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

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _availableAudioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = _availableAudioTracks.asStateFlow()

    private val _availableSubtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = _availableSubtitleTracks.asStateFlow()

    private val _availableVideoTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableVideoTracks: StateFlow<List<PlayerTrack>> = _availableVideoTracks.asStateFlow()

    @Volatile private var requestedDecoderMode: DecoderMode = DecoderMode.AUTO

    /**
     * Mode décodeur imposé au média courant par le repli anti-gel (one-shot,
     * effacé par le prochain [prepare] public). Prioritaire sur
     * [requestedDecoderMode] ; survit à [restart] (watchdog de stall).
     */
    @Volatile private var mediaDecoderOverride: DecoderMode? = null

    /** Mode réellement appliqué au média en cours de lecture. */
    @Volatile private var activeDecoderMode: DecoderMode = DecoderMode.AUTO

    // ---- Watchdog gel vidéo (audio avance, plus d'image) -------------------

    /** Lu/écrit uniquement sur [pollThread] (reset via [pollHandler]). */
    private val freezeDetector = VideoFreezeDetector()

    private val _videoFreezes = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /**
     * Émet la position (ms) quand l'image est figée alors que l'audio avance
     * (`Media.Stats.displayedPictures` immobile ~3 s pendant que la position
     * progresse). Consommé par [RoutingPlayerEngine] qui choisit le repli.
     */
    val videoFreezes: Flow<Long> = _videoFreezes.asSharedFlow()

    fun isSoftwareDecodingActive(): Boolean = activeDecoderMode == DecoderMode.SOFTWARE

    fun currentStreamInfo(): StreamInfo? = lastStreamInfo

    /** Doit tourner sur pollThread. */
    private fun sampleForFreeze(player: MediaPlayer, positionMs: Long, playing: Boolean) {
        val pictures = try {
            // getMedia() retient le Media : il faut le relâcher.
            player.media?.let { media ->
                try { media.stats?.displayedPictures } finally { media.release() }
            }
        } catch (t: Throwable) {
            null
        }
        val sample = PlaybackSample(positionMs, pictures, playing)
        if (freezeDetector.onSample(SystemClock.elapsedRealtime(), sample)) {
            Log.w(
                FREEZE_TAG,
                "gel vidéo détecté pos=${positionMs}ms displayedPictures=$pictures " +
                    "decoder=$activeDecoderMode — demande de repli"
            )
            _videoFreezes.tryEmit(positionMs)
        }
    }

    private fun resetFreezeDetector() {
        pollHandler.post { freezeDetector.reset() }
    }

    /**
     * Repli anti-gel niveau 1 : relance le média courant en décodage logiciel
     * (avcodec) à [positionMs]. N'altère pas le réglage utilisateur.
     */
    fun restartWithSoftwareDecoder(positionMs: Long) {
        val streamInfo = lastStreamInfo ?: return
        pendingResumeSeconds = (positionMs / 1000L).coerceAtLeast(0L)
        Log.w(FREEZE_TAG, "repli 1/2 : LibVLC décodage logiciel à ${positionMs}ms")
        prepareInternal(streamInfo, decoderOverride = DecoderMode.SOFTWARE)
    }

    // ---- Lifecycle --------------------------------------------------------

    /** Doit être appelé sur cmdThread. */
    private fun ensurePlayer(): MediaPlayer {
        return mediaPlayer ?: MediaPlayer(libVlc).also { player ->
            player.setEventListener(::onMediaPlayerEvent)
            mediaPlayer = player
        }
    }

    override fun prepare(streamInfo: StreamInfo) {
        prepareInternal(streamInfo, decoderOverride = null)
    }

    private fun prepareInternal(streamInfo: StreamInfo, decoderOverride: DecoderMode?) {
        mediaDecoderOverride = decoderOverride
        lastStreamInfo = streamInfo
        // Incrément côté Main pour que tout event/poll qui fire APRÈS cette
        // ligne soit considéré obsolète tant que activeMediaGeneration n'est
        // pas mis à jour (juste avant player.play() du nouveau média).
        val gen = mediaGeneration.incrementAndGet()
        Log.i(TAG, "prepare gen=$gen url=${streamInfo.url}")

        // Drop toute commande utilisateur queued pour l'ancien média (un
        // pause/seek cliqué juste avant un auto-next n'a aucun sens sur le
        // nouvel épisode). pollRunnable est sur pollHandler — handlé à part.
        cmdHandler.removeCallbacksAndMessages(null)
        pollHandler.removeCallbacks(pollRunnable)
        resetFreezeDetector()

        // Reset état UI immédiatement (StateFlow.value est thread-safe).
        _error.tryEmit(null)
        _mediaTitle.value = streamInfo.title
        _currentPosition.value = 0L
        _duration.value = 0L
        _videoFormat.value = VideoFormat(0, 0)
        _availableAudioTracks.value = emptyList()
        _availableSubtitleTracks.value = emptyList()
        _playbackState.value = PlaybackState.BUFFERING

        postCmd("prepare(gen=$gen)") {
            // Si une autre prepare() a déjà incrémenté la génération entre
            // temps, on abandonne celui-ci pour éviter d'écraser le média
            // courant avec un setup obsolète.
            if (gen != mediaGeneration.get()) {
                Log.i(TAG, "prepare gen=$gen superseded, skip")
                return@postCmd
            }

            val player = ensurePlayer()

            // Stop any previous playback before swapping media.
            runCatching { player.stop() }

            var media: Media? = null
            try {
                media = Media(libVlc, android.net.Uri.parse(streamInfo.url))
                // Apply HTTP options BEFORE attaching the media to the player.
                streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { ua ->
                    media.addOption(":http-user-agent=$ua")
                }
                streamInfo.headers.forEach { (key, value) ->
                    // VLC has limited per-header configurability; the most common
                    // headers (User-Agent, Referer) have dedicated options. Fall
                    // back to ignoring unknown headers rather than guessing the
                    // syntax. TODO: support arbitrary HTTP headers if/when needed.
                    when (key.lowercase()) {
                        "user-agent" -> media.addOption(":http-user-agent=$value")
                        "referer", "referrer" -> media.addOption(":http-referrer=$value")
                    }
                }
                // Consume any pending resume position (set by restart() after a
                // mid-stream stall) so the user does not get bounced back to 0:00.
                val resumeSeconds = pendingResumeSeconds
                pendingResumeSeconds = 0L
                if (resumeSeconds > 0L) {
                    media.addOption(":start-time=$resumeSeconds")
                }
                // Réglage « Mode décodeur » + mise en cache. Avant, LibVLC
                // forçait MediaCodec quel que soit le réglage (seul Media3 le
                // respectait) → c2.mtk.avc.decoder même en « logiciel ».
                val decoderMode = mediaDecoderOverride ?: requestedDecoderMode
                val config = LibVlcMediaOptions.build(decoderMode)
                media.setHWDecoderEnabled(config.hwDecoderEnabled, config.hwDecoderForced)
                config.options.forEach(media::addOption)
                activeDecoderMode = decoderMode
                Log.i(TAG, "prepare gen=$gen decoder=$decoderMode options=${config.options}")

                // Active la génération AVANT `player.media =` et `player.play()`.
                // Pourquoi entre `stop()` et `media =` :
                //  - Avant: les éventuels events post-stop() de l'ancien média
                //    (Stopped tardif fired asynchrously) ont encore l'ancienne
                //    génération en cours côté handler → dropped (bon).
                //  - Après `media =` : libVLC peut émettre LengthChanged
                //    quasi-synchronously en parsant les métadonnées du nouveau
                //    média. Si activeMediaGeneration était encore l'ancienne,
                //    on perdrait la duration → `persistPlaybackProgress`
                //    skipperait silencieusement (condition `dur > 0`) →
                //    l'épisode courant ne serait jamais enregistré dans la
                //    base, et "Continue Watching" resterait bloqué sur le
                //    dernier épisode persisté.
                activeMediaGeneration = gen

                player.media = media
                media.release()
                media = null

                pollHandler.postDelayed(pollRunnable, POSITION_POLL_INTERVAL_MS)
                player.play()
            } catch (t: Throwable) {
                // Toute exception native libVLC (URL malformée, Media indisponible,
                // play() qui échoue) doit devenir une erreur affichable plutôt
                // qu'un crash de l'app.
                Log.e(TAG, "prepare failed for url=${streamInfo.url}", t)
                runCatching { media?.release() }
                pollHandler.removeCallbacks(pollRunnable)
                _isPlaying.value = false
                _playbackState.value = PlaybackState.ERROR
                _error.tryEmit(classifyPrepareError(t))
            }
        }
    }

    override fun restart() {
        val streamInfo = lastStreamInfo ?: return
        // LibVLC is the progressive (VOD) fallback engine. Restarting from
        // position 0 would be terrible UX after a mid-film stall, so resume
        // at the last polled position via libVLC's :start-time option (set
        // in [pendingResumeSeconds], consumed by [prepare]).
        pendingResumeSeconds = (_currentPosition.value / 1000L).coerceAtLeast(0L)
        Log.i(TAG, "restart url=${streamInfo.url} resumeSeconds=$pendingResumeSeconds")
        prepareInternal(streamInfo, decoderOverride = mediaDecoderOverride)
    }

    private fun classifyPrepareError(t: Throwable): PlayerError {
        val msg = t.message ?: t.javaClass.simpleName
        val lower = msg.lowercase()
        return when {
            lower.contains("network") || lower.contains("connect") || lower.contains("timeout") || lower.contains("host") ->
                PlayerError.NetworkError(msg)
            lower.contains("uri") || lower.contains("url") || lower.contains("source") || lower.contains("parse") ->
                PlayerError.SourceError(msg)
            else -> PlayerError.UnknownError(msg)
        }
    }

    override fun play() {
        postCmd("play") {
            mediaPlayer?.play()
        }
    }

    override fun pause() {
        postCmd("pause") {
            mediaPlayer?.pause()
        }
    }

    override fun stop() {
        // Update UI state immédiatement; le stop natif se fait côté cmdThread.
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        postCmd("stop") {
            pollHandler.removeCallbacks(pollRunnable)
            mediaPlayer?.let { runCatching { it.stop() } }
        }
    }

    // LibVLC holds no audio-focus state of its own (only Media3 does), so the
    // background variant is identical to stop(): it closes the native stream.
    override fun stopForBackground() = stop()

    override fun seekTo(positionMs: Long) {
        resetFreezeDetector()
        postCmd("seekTo($positionMs)") {
            mediaPlayer?.time = positionMs
        }
    }

    override fun seekForward(ms: Long) {
        resetFreezeDetector()
        postCmd("seekForward($ms)") {
            mediaPlayer?.let { player ->
                val len = player.length
                if (len > 0L) {
                    val safeMax = (len - SEEK_END_GUARD_MS).coerceAtLeast(0L)
                    if (player.time < safeMax) {
                        player.time = (player.time + ms).coerceAtMost(safeMax)
                    }
                } else {
                    player.time = (player.time + ms).coerceAtLeast(0L)
                }
            }
        }
    }

    override fun seekBackward(ms: Long) {
        resetFreezeDetector()
        postCmd("seekBackward($ms)") {
            mediaPlayer?.let { player ->
                player.time = (player.time - ms).coerceAtLeast(0L)
            }
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = (volume.coerceIn(0f, 1f) * 100f).toInt()
        if (clamped > 0) lastVolumeBeforeMute = clamped
        _isMuted.value = clamped == 0
        postCmd("setVolume($clamped)") {
            mediaPlayer?.volume = clamped
        }
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        postCmd("setMuted($muted)") {
            mediaPlayer?.let { player ->
                if (muted) {
                    val current = player.volume
                    if (current > 0) lastVolumeBeforeMute = current
                    player.volume = 0
                } else {
                    player.volume = lastVolumeBeforeMute.coerceIn(1, 100)
                }
            }
        }
    }

    override fun toggleMute() {
        setMuted(!_isMuted.value)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4f)
        _playbackSpeed.value = clamped
        postCmd("setPlaybackSpeed($clamped)") {
            mediaPlayer?.rate = clamped
        }
    }

    override fun release() {
        // Invalide la génération courante : tout event/poll en vol sera rejeté.
        mediaGeneration.incrementAndGet()

        // Reset UI state immédiatement.
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _mediaTitle.value = null
        _availableAudioTracks.value = emptyList()
        _availableSubtitleTracks.value = emptyList()
        // Purge l'erreur rejouable (replay=1) : sans ça, le prochain
        // PlayerViewModel re-traite l'échec de la session précédente pendant
        // sa construction.
        _error.resetReplayCache()

        val playerSnapshot = mediaPlayer
        val layoutSnapshot = attachedLayout
        mediaPlayer = null
        attachedLayout = null

        // Drop tout ce qui était queued — l'engine entre en hibernation jusqu'au
        // prochain prepare().
        cmdHandler.removeCallbacksAndMessages(null)
        pollHandler.removeCallbacks(pollRunnable)

        postCmd("release") {
            if (playerSnapshot != null) {
                if (layoutSnapshot != null) {
                    runCatching { playerSnapshot.detachViews() }
                }
                runCatching { playerSnapshot.stop() }
                runCatching { playerSnapshot.release() }
            }
            // Volontairement: NE PAS appeler libVlc.release() ni quitter les
            // HandlerThread. Cet engine est @Singleton, libVlc et les threads
            // sont initialisés une seule fois pour la durée du process.
            // release() est appelé à chaque onCleared() du ViewModel (back du
            // PlayerScreen) — relâcher la lib globale ou tuer les threads VLC
            // rendrait le prochain prepare() inutilisable et bloquerait le main
            // thread (ANR observé).
        }
    }

    // ---- Render view ------------------------------------------------------

    override fun createRenderView(context: Context, resizeMode: PlayerSurfaceResizeMode): View {
        return VLCVideoLayout(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // libVLC ajoute le Surface/TextureView interne au moment de
            // attachViews() — on intercepte chaque ajout pour forcer
            // MATCH_PARENT (sinon il garde la taille intrinsèque vidéo).
            setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    val lp = child.layoutParams ?: android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    child.layoutParams = lp
                }
                override fun onChildViewRemoved(parent: View, child: View) {}
            })
        }
    }

    override fun bindRenderView(renderView: View, resizeMode: PlayerSurfaceResizeMode) {
        val layout = renderView as? VLCVideoLayout ?: run {
            Log.w(TAG, "bindRenderView: expected VLCVideoLayout, got ${renderView.javaClass.name}")
            return
        }
        val previousLayout = attachedLayout
        attachedLayout = layout

        // UI listener (Main thread) — invalide l'ancien et installe le nouveau.
        layout.layoutChangeListener?.let { layout.removeOnLayoutChangeListener(it) }
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0) {
                forceMatchParentChildren(layout)
                applyResizeMode(resizeMode)
            }
        }
        layout.addOnLayoutChangeListener(listener)
        layout.layoutChangeListener = listener

        postCmd("bindRenderView") {
            val player = ensurePlayer()
            if (previousLayout != null && previousLayout !== layout) {
                runCatching { player.detachViews() }
            }
            // useTextureView=true : le SurfaceView attache une couche native qui
            // ignore les re-layouts du parent ; TextureView se scale correctement
            // avec MATCH_PARENT.
            runCatching {
                player.attachViews(layout, /* dm = */ null, /* subtitles = */ false, /* useTextureView = */ true)
            }
        }

        // Force MATCH_PARENT côté UI (Main thread) immédiatement, et applique
        // le resize quand le layout sera mesuré.
        forceMatchParentChildren(layout)
        if (layout.width > 0 && layout.height > 0) {
            applyResizeMode(resizeMode)
        }
    }

    private var VLCVideoLayout.layoutChangeListener: View.OnLayoutChangeListener?
        get() = getTag(LAYOUT_LISTENER_TAG_ID) as? View.OnLayoutChangeListener
        set(value) = setTag(LAYOUT_LISTENER_TAG_ID, value)

    private fun forceMatchParentChildren(layout: VLCVideoLayout) {
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            val lp = child.layoutParams ?: android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            child.layoutParams = lp
        }
        layout.requestLayout()
    }

    override fun releaseRenderView(renderView: View) {
        if (renderView === attachedLayout) {
            attachedLayout = null
            postCmd("releaseRenderView") {
                mediaPlayer?.let { runCatching { it.detachViews() } }
            }
        }
    }

    private fun applyResizeMode(mode: PlayerSurfaceResizeMode) {
        // Snapshot UI thread → cmdThread. Les setters natifs (vlcVout.setWindowSize,
        // videoScale) doivent absolument tourner hors Main.
        val layout = attachedLayout ?: return
        val w = layout.width
        val h = layout.height
        postCmd("applyResizeMode($mode)") {
            val player = mediaPlayer ?: return@postCmd
            // Informe libVLC de la taille réelle du container Compose. Sans ça,
            // SURFACE_BEST_FIT calcule sur la taille intrinsèque vidéo et ne
            // remplit pas le parent.
            if (w > 0 && h > 0) {
                runCatching { player.vlcVout.setWindowSize(w, h) }
            }
            player.videoScale = when (mode) {
                PlayerSurfaceResizeMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                PlayerSurfaceResizeMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
                PlayerSurfaceResizeMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            }
        }
    }

    // ---- Stubs (POC) ------------------------------------------------------

    override fun setDecoderMode(mode: DecoderMode) {
        if (requestedDecoderMode == mode) return
        requestedDecoderMode = mode
        // Comme Media3 : un changement en cours de lecture relance le média à
        // la position courante pour qu'il prenne effet tout de suite. Seulement
        // si un média joue réellement (le singleton garde lastStreamInfo de la
        // session précédente : ne pas la relancer à l'ouverture du lecteur).
        val streamInfo = lastStreamInfo ?: return
        if (mediaPlayer == null || mediaDecoderOverride != null) return
        val state = _playbackState.value
        if (state != PlaybackState.READY && state != PlaybackState.BUFFERING) return
        if (activeDecoderMode == mode) return
        pendingResumeSeconds = (_currentPosition.value / 1000L).coerceAtLeast(0L)
        Log.i(TAG, "decoder mode → $mode, relance à ${pendingResumeSeconds}s")
        prepareInternal(streamInfo, decoderOverride = null)
    }

    override fun setMediaSessionEnabled(enabled: Boolean) {
        // No MediaSession bridge in this POC.
    }

    override fun setPreferredAudioLanguage(languageTag: String?) {
        // POC: no-op.
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        // POC: no-op (libVLC handles subtitle rendering internally).
    }

    override fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) {
        // POC: no-op.
    }

    override fun selectAudioTrack(trackId: String) {
        val id = trackId.toIntOrNull() ?: return
        postCmd("selectAudioTrack($trackId)") {
            mediaPlayer?.let { player ->
                try {
                    player.setAudioTrack(id)
                } catch (t: Throwable) {
                    Log.w(TAG, "selectAudioTrack($trackId) failed", t)
                }
                refreshTracks()
            }
        }
    }

    override fun selectVideoTrack(trackId: String) {
        // libVLC ne sait pas multiplexer plusieurs ESID vidéo en runtime via
        // l'API legacy ; cas non applicable pour les MP4/MKV cassés visés.
    }

    override fun selectSubtitleTrack(trackId: String?) {
        // null/blank → désactiver la piste de sous-titres (id natif -1).
        val id = trackId?.toIntOrNull() ?: -1
        postCmd("selectSubtitleTrack($trackId)") {
            mediaPlayer?.let { player ->
                try {
                    player.setSpuTrack(id)
                } catch (t: Throwable) {
                    Log.w(TAG, "selectSubtitleTrack($trackId) failed", t)
                }
                refreshTracks()
            }
        }
    }

    override fun setScrubbingMode(enabled: Boolean) {
        // POC: no-op.
    }

    override fun preload(streamInfo: StreamInfo?) {
        // POC: no preload.
    }

    // ---- Internals --------------------------------------------------------

    private fun onMediaPlayerEvent(event: MediaPlayer.Event) {
        // Le callback est invoqué depuis le thread VLC natif : toute exception
        // qui s'en échappe tue le process. On garde l'app en vie en émettant
        // une PlayerError à la place.
        try {
            // Garde de génération : pendant un prepare() en cours, l'ancien
            // média peut encore émettre Stopped/TimeChanged/EndReached. Si on
            // les laisse passer, ils écrasent l'état du nouveau média (position
            // figée à l'ancienne valeur, _isPlaying remis à false, etc. —
            // symptôme du bug "title épisode 6, position stuck 1s").
            // On accepte uniquement les events de la génération active.
            val gen = mediaGeneration.get()
            val active = activeMediaGeneration
            val isStale = active != gen
            if (isStale) {
                // Un event arrive pour un média qui est en train d'être remplacé
                // (prepare en cours sur cmdThread, pas encore au point où
                // activeMediaGeneration est mis à jour). Ignorer silencieusement.
                return
            }

            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    _playbackState.value = PlaybackState.BUFFERING
                }
                MediaPlayer.Event.Buffering -> {
                    // libVLC émet Buffering en continu pendant la lecture (rebuffer
                    // partiel). Ne basculer en BUFFERING que si pas encore à 100%.
                    if (event.buffering < 100f) {
                        _playbackState.value = PlaybackState.BUFFERING
                    } else if (_playbackState.value == PlaybackState.BUFFERING) {
                        _playbackState.value = PlaybackState.READY
                    }
                }
                MediaPlayer.Event.Playing -> {
                    _playbackState.value = PlaybackState.READY
                    _isPlaying.value = true
                    refreshVideoFormat()
                    refreshTracks()
                }
                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESDeleted,
                MediaPlayer.Event.ESSelected -> {
                    refreshTracks()
                }
                MediaPlayer.Event.Paused -> {
                    _isPlaying.value = false
                }
                MediaPlayer.Event.Stopped -> {
                    _isPlaying.value = false
                    _playbackState.value = PlaybackState.IDLE
                }
                MediaPlayer.Event.EndReached -> {
                    _isPlaying.value = false
                    _playbackState.value = PlaybackState.ENDED
                }
                MediaPlayer.Event.EncounteredError -> {
                    _isPlaying.value = false
                    _playbackState.value = PlaybackState.ERROR
                    _error.tryEmit(PlayerError.SourceError("Émission indisponible"))
                }
                MediaPlayer.Event.TimeChanged -> {
                    _currentPosition.value = event.timeChanged
                }
                MediaPlayer.Event.LengthChanged -> {
                    _duration.value = event.lengthChanged
                }
                MediaPlayer.Event.Vout -> {
                    refreshVideoFormat()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onMediaPlayerEvent crashed for event type=${event.type}", t)
            _isPlaying.value = false
            _playbackState.value = PlaybackState.ERROR
            _error.tryEmit(PlayerError.UnknownError(t.message ?: "libVLC event error"))
        }
    }

    private fun refreshTracks() {
        val player = mediaPlayer ?: return
        // Énumération + lecture de l'id sélectionné via l'API legacy 3.x.
        // Tout est wrappé : un crash natif ici tuerait le process VLC.
        try {
            val descriptions = player.audioTracks
            val current = player.audioTrack
            _availableAudioTracks.value = descriptions
                ?.asSequence()
                ?.filter { it.id >= 0 } // VLC insère parfois un pseudo "Disable" id=-1
                ?.map { td ->
                    PlayerTrack(
                        id = td.id.toString(),
                        name = td.name?.takeIf { it.isNotBlank() } ?: "Audio ${td.id}",
                        language = null,
                        type = TrackType.AUDIO,
                        isSelected = td.id == current
                    )
                }
                ?.toList()
                .orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "refreshTracks: audio enumeration failed", t)
        }
        try {
            val descriptions = player.spuTracks
            val current = player.spuTrack
            _availableSubtitleTracks.value = descriptions
                ?.asSequence()
                ?.filter { it.id >= 0 }
                ?.map { td ->
                    PlayerTrack(
                        id = td.id.toString(),
                        name = td.name?.takeIf { it.isNotBlank() } ?: "Subtitle ${td.id}",
                        language = null,
                        type = TrackType.TEXT,
                        isSelected = td.id == current
                    )
                }
                ?.toList()
                .orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "refreshTracks: subtitle enumeration failed", t)
        }
    }

    private fun refreshVideoFormat() {
        val track = try {
            mediaPlayer?.currentVideoTrack ?: return
        } catch (t: Throwable) {
            Log.w(TAG, "refreshVideoFormat: read failed", t)
            return
        }
        if (track.width > 0 && track.height > 0) {
            val frameRate = if (track.frameRateDen > 0) {
                track.frameRateNum.toFloat() / track.frameRateDen.toFloat()
            } else 0f
            _videoFormat.value = VideoFormat(
                width = track.width,
                height = track.height,
                frameRate = frameRate
            )
        }
    }
}
