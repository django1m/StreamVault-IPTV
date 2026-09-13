package com.streamvault.app.ui.screens.player

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.model.Program
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import dagger.hilt.android.lifecycle.HiltViewModel
import com.streamvault.app.ui.screens.player.gesture.PlayerGestureFeedback
import com.streamvault.app.ui.screens.player.gesture.PlayerGestureHud
import com.streamvault.app.ui.screens.player.gesture.VerticalDragZone
import com.streamvault.app.ui.screens.player.gesture.playerTouchGestures
import com.streamvault.app.ui.screens.player.gesture.rememberPlayerGestureController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.streamvault.app.ui.components.dialogs.ProgramHistoryDialog
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.streamvault.app.R
import com.streamvault.app.MainActivity
import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.ui.design.LocalFormFactor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.streamvault.app.ui.pip.LocalIsInPip
import com.streamvault.app.ui.pip.PIP_CONTROL_FORWARD
import com.streamvault.app.ui.pip.PIP_CONTROL_PLAY_PAUSE
import com.streamvault.app.ui.pip.PIP_CONTROL_REWIND
import com.streamvault.app.ui.design.isTouch
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.system.ImmersiveSystemBars
import com.streamvault.app.ui.screens.player.overlay.ChannelInfoOverlay
import com.streamvault.app.ui.screens.player.overlay.CategoryListOverlay
import com.streamvault.app.ui.screens.player.overlay.ChannelListOverlay
import com.streamvault.app.ui.screens.player.overlay.DiagnosticsOverlay
import com.streamvault.app.ui.screens.player.overlay.EpgOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerErrorOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerNoticeBanner
import com.streamvault.app.ui.screens.player.overlay.PlayerEpisodeSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerResumePrompt
import com.streamvault.app.ui.screens.player.overlay.PlayerTrackSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerAspectRatioToast
import com.streamvault.app.ui.screens.player.overlay.PlayerControlsOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerNumericInputOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerResolutionBadge
import com.streamvault.app.ui.screens.player.overlay.PlayerSpeedSelectionDialog
import com.streamvault.app.ui.screens.multiview.MultiViewViewModel
import com.streamvault.app.ui.screens.multiview.MultiViewPlannerDialog
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.design.AppMotion



@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    artworkUrl: String? = null,
    epgChannelId: String? = null,
    internalChannelId: Long = -1L,
    categoryId: Long? = null,
    providerId: Long? = null,
    isVirtual: Boolean = false,
    contentType: String = "LIVE",
    archiveStartMs: Long? = null,
    archiveEndMs: Long? = null,
    archiveTitle: String? = null,
    seriesId: Long? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    returnRoute: String? = null,
    onBack: () -> Unit,
    onNavigate: ((String) -> Unit)? = null,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val sideOverlayWidth = if (screenWidth < 700.dp) {
        (screenWidth * 0.62f).coerceIn(220.dp, 300.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.4f).coerceIn(320.dp, 420.dp)
    } else {
        350.dp
    }
    val epgOverlayWidth = if (screenWidth < 700.dp) {
        (screenWidth * 0.68f).coerceIn(240.dp, 320.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.46f).coerceIn(360.dp, 500.dp)
    } else {
        400.dp
    }
    val mainActivity = LocalContext.current.findMainActivity()
    val playbackState by viewModel.playerEngine.playbackState.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerEngine.isPlaying.collectAsStateWithLifecycle()
    val showControls by viewModel.showControls.collectAsStateWithLifecycle()
    val videoFormat by viewModel.videoFormat.collectAsStateWithLifecycle()
    val playerError by viewModel.playerError.collectAsStateWithLifecycle()
    val currentProgram by viewModel.currentProgram.collectAsStateWithLifecycle()
    val nextProgram by viewModel.nextProgram.collectAsStateWithLifecycle()
    val programHistory by viewModel.programHistory.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val currentSeries by viewModel.currentSeries.collectAsStateWithLifecycle()
    val currentEpisode by viewModel.currentEpisode.collectAsStateWithLifecycle()
    val nextEpisode by viewModel.nextEpisode.collectAsStateWithLifecycle()
    val playbackTitle by viewModel.playbackTitle.collectAsStateWithLifecycle()
    val resumePrompt by viewModel.resumePrompt.collectAsStateWithLifecycle()
    val currentSeriesSeasons = remember(currentSeries) {
        currentSeries?.seasons.sanitizedForPlayer()
    }
    val canOpenEpisodePicker = contentType == "SERIES_EPISODE" &&
        currentSeriesSeasons?.any { it.episodes.isNotEmpty() } == true
    
    val showChannelListOverlay by viewModel.showChannelListOverlay.collectAsStateWithLifecycle()
    val showCategoryListOverlay by viewModel.showCategoryListOverlay.collectAsStateWithLifecycle()
    val availableCategories by viewModel.availableCategories.collectAsStateWithLifecycle()
    val activeCategoryId by viewModel.activeCategoryId.collectAsStateWithLifecycle()
    val showEpgOverlay by viewModel.showEpgOverlay.collectAsStateWithLifecycle()
    val currentChannelList by viewModel.currentChannelList.collectAsStateWithLifecycle()
    val recentChannels by viewModel.recentChannels.collectAsStateWithLifecycle()
    val lastVisitedCategory by viewModel.lastVisitedCategory.collectAsStateWithLifecycle()
    val displayChannelNumber by viewModel.displayChannelNumber.collectAsStateWithLifecycle()
    val upcomingPrograms by viewModel.upcomingPrograms.collectAsStateWithLifecycle()
    val showChannelInfoOverlay by viewModel.showChannelInfoOverlay.collectAsStateWithLifecycle()
    val numericChannelInput by viewModel.numericChannelInput.collectAsStateWithLifecycle()
    
    val availableAudioTracks by viewModel.availableAudioTracks.collectAsStateWithLifecycle()
    val availableSubtitleTracks by viewModel.availableSubtitleTracks.collectAsStateWithLifecycle()
    val availableVideoQualities by viewModel.availableVideoQualities.collectAsStateWithLifecycle()
    val aspectRatio by viewModel.aspectRatio.collectAsStateWithLifecycle()
    val showDiagnostics by viewModel.showDiagnostics.collectAsStateWithLifecycle()
    val playerDiagnostics by viewModel.playerDiagnostics.collectAsStateWithLifecycle()
    val playerNotice by viewModel.playerNotice.collectAsStateWithLifecycle()
    val currentChannelRecording by viewModel.currentChannelRecording.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val mediaTitle by viewModel.mediaTitle.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val castConnectionState by viewModel.castConnectionState.collectAsStateWithLifecycle()
    val seekPreview by viewModel.seekPreview.collectAsStateWithLifecycle()
    val preventStandbyDuringPlayback by viewModel.preventStandbyDuringPlayback.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.playerEngine.currentPosition.collectAsStateWithLifecycle()
    val isTimeshift by viewModel.isTimeshiftPlayback.collectAsStateWithLifecycle()

    var showTrackSelection by remember { mutableStateOf<TrackType?>(null) }
    var showSpeedSelection by remember { mutableStateOf(false) }
    var showProgramHistory by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showEpisodePicker by remember { mutableStateOf(false) }

    // ─── Cinematic player extras ──────────────────────────────────────
    var showOptionsPanel by remember { mutableStateOf(false) }
    var showMiniGuide by remember { mutableStateOf(false) }
    
    val focusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }
    val categoryListFocusRequester = remember { FocusRequester() }
    val epgFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val quickActionsFocusRequester = remember { FocusRequester() }
    val channelInfoFocusRequester = remember { FocusRequester() } // NEW
    var lastFocusedEpgProgramToken by rememberSaveable { mutableStateOf<Long?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Observe the PROCESS lifecycle, not LocalLifecycleOwner (the NavBackStackEntry).
    // Returning to the box launcher (Home) backgrounds the whole process; the
    // nav-entry ON_STOP does not always propagate in a single-activity +
    // Compose-Navigation graph, which left audio playing in the background.
    // ProcessLifecycleOwner fires reliably on real app foreground/background.
    val processLifecycleOwner = ProcessLifecycleOwner.get()
    LifecycleEventEffect(Lifecycle.Event.ON_START, processLifecycleOwner) {
        viewModel.onAppForegrounded()
    }

    val isInPip = LocalIsInPip.current

    LifecycleEventEffect(Lifecycle.Event.ON_STOP, processLifecycleOwner) {
        // In Picture-in-Picture the video is still on screen, so backgrounding
        // the engine here would kill the very playback the user asked to keep.
        // The activity is PAUSED but not STOPPED in PiP, so this should not fire
        // at all — the guard is defensive, because if it ever did, the symptom
        // would be "PiP opens then goes black" with no obvious culprit.
        if (!isInPip) {
            viewModel.onAppBackgrounded()
        }
    }

    DisposableEffect(mainActivity) {
        onDispose {
            viewModel.onPlayerScreenDisposed()
        }
    }

    // On phone/tablet the app chrome is NOT fullscreen (see the mobile theme), so
    // the player has to claim immersion for itself and give it back on exit.
    // No-op on TV, where the theme is already windowFullscreen.
    ImmersiveSystemBars(enabled = LocalFormFactor.current.isTouch)

    // Prevent screen from sleeping during active playback
    val playerWindow = mainActivity?.window
    DisposableEffect(Unit) {
        onDispose { playerWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(preventStandbyDuringPlayback, isPlaying, playbackState) {
        if (preventStandbyDuringPlayback) {
            // Keep screen always on while in player — prevents TV OS standby nag
            playerWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (isPlaying || playbackState == PlaybackState.BUFFERING) {
            playerWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            playerWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Consolidated focus management for all overlays
    val liveOverlayVisible = contentType == "LIVE" && (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay)
    val anyOverlayVisible = liveOverlayVisible || showTrackSelection != null || showSpeedSelection || showProgramHistory || showSplitDialog || showEpisodePicker || showDiagnostics

    // ─── Seek au D-pad ────────────────────────────────────────────────────
    // Rejouable = tout sauf le direct pur. Un catch-up porte contentType "LIVE"
    // mais se cherche comme un VOD : position, durée et seek ont un sens.
    val canSeekContent = contentType != "LIVE" || isTimeshift
    // La barre est visible mais le focus reste sur la vidéo tant que l’utilisateur
    // n’a pas demandé à y descendre (OK / haut / bas) : gauche et droite cherchent
    // dans le média au lieu de déplacer le curseur entre les boutons. Avant, le
    // premier appui appelait revealControls(), le focus filait sur Play et le
    // deuxième appui ne cherchait plus — c’est le bug corrigé ici.
    var chromeFocused by remember { mutableStateOf(false) }
    // Sur du direct pur il n’y a rien à chercher : la barre prend le focus dès
    // qu’elle s’ouvre, exactement comme avant. Garde-fou contre une barre
    // innavigable si chromeFocused restait faux.
    val chromeHasFocus = chromeFocused || !canSeekContent

    LaunchedEffect(contentType, showCategoryListOverlay, showChannelListOverlay, showEpgOverlay, showChannelInfoOverlay) {
        if (contentType == "LIVE" && (showCategoryListOverlay || showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay)) {
            // Give overlays a moment to animate in before requesting focus
            delay(150)
            when {
                showCategoryListOverlay -> categoryListFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Category list overlay")
                showChannelListOverlay -> channelListFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Channel list overlay")
                showEpgOverlay -> epgFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "EPG overlay")
                showChannelInfoOverlay -> channelInfoFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Channel info overlay")
            }
        }
    }

    LaunchedEffect(anyOverlayVisible) {
        if (!anyOverlayVisible) {
            // Restore focus to main player when all overlays are gone
            focusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Player root")
        }
    }

    val resolutionBadgeLabel = buildResolutionBadgeLabel(
        videoFormat = videoFormat,
        videoTracks = availableVideoQualities,
        autoResolutionLabel = stringResource(R.string.player_resolution_auto_label, videoFormat.resolutionLabel)
    )
    var showResolution by remember(streamUrl) { mutableStateOf(false) }
    var lastResolutionBadgeLabel by remember(streamUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(resolutionBadgeLabel) {
        val nextLabel = resolutionBadgeLabel ?: run {
            showResolution = false
            lastResolutionBadgeLabel = null
            return@LaunchedEffect
        }
        if (nextLabel == lastResolutionBadgeLabel) {
            return@LaunchedEffect
        }
        lastResolutionBadgeLabel = nextLabel
        showResolution = true
        delay(3000)
        if (lastResolutionBadgeLabel == nextLabel) {
            showResolution = false
        }
    }

    if (showProgramHistory) {
        ProgramHistoryDialog(
            programs = programHistory,
            onDismiss = { showProgramHistory = false },
            onProgramSelect = { program ->
                viewModel.playCatchUp(program)
                showProgramHistory = false
            }
        )
    }

    // Split Screen Manager dialog
    if (showSplitDialog && currentChannel != null) {
        val multiViewViewModel: MultiViewViewModel = hiltViewModel()
        MultiViewPlannerDialog(
            pendingChannel = currentChannel,
            onDismiss = { showSplitDialog = false },
            onLaunch = {
                showSplitDialog = false
                viewModel.handOffPlaybackToMultiView()
                onNavigate?.invoke(Routes.MULTI_VIEW)
            },
            viewModel = multiViewViewModel
        )
    }

    LaunchedEffect(streamUrl, epgChannelId, title, artworkUrl, internalChannelId, categoryId, providerId, isVirtual, contentType, archiveStartMs, archiveEndMs, archiveTitle, seriesId, seasonNumber, episodeNumber) {
        viewModel.prepare(
            streamUrl = streamUrl,
            epgChannelId = epgChannelId,
            internalChannelId = internalChannelId,
            categoryId = categoryId ?: -1,
            providerId = providerId ?: -1,
            isVirtual = isVirtual,
            contentType = contentType,
            title = title,
            artworkUrl = artworkUrl,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }

    LaunchedEffect(showControls, chromeHasFocus) {
        if (showControls && chromeHasFocus) {
            delay(100)
            // Play/pause on both content types now: the transport and the settings live
            // in one strip, and its centre of gravity is the play button — landing on
            // "previous channel" instead was an artefact of the old two-row layout.
            playButtonFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Player transport")
        } else {
            // Barre visible en mode seek : le focus reste sur la racine (les flèches
            // doivent atteindre son handler), mais l’auto-masquage doit continuer de
            // courir — d’où le garde sur cancelControlsAutoHide.
            if (!showControls) viewModel.cancelControlsAutoHide()
            focusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Player root")
        }
    }

    // Fermer la barre (auto-masquage, BACK, zap, ouverture d’un overlay…) rend
    // toujours la main à la vidéo : le prochain appui sur une flèche cherche.
    LaunchedEffect(showControls) {
        if (!showControls) chromeFocused = false
    }

    LaunchedEffect(showControls, showTrackSelection, showSpeedSelection, showProgramHistory, showSplitDialog, showEpisodePicker) {
        if (!showControls) {
            viewModel.cancelControlsAutoHide()
        } else if (showTrackSelection != null || showSpeedSelection || showProgramHistory || showSplitDialog || showEpisodePicker) {
            viewModel.cancelControlsAutoHide()
        } else {
            viewModel.hideControlsAfterDelay()
        }
    }

    val handlePlayerNoticeAction: (PlayerNoticeAction) -> Unit = remember(returnRoute, onNavigate, onBack) {
        { action ->
            if (action == PlayerNoticeAction.GO_BACK) {
                viewModel.dismissPlayerNotice()
                onBack()
            } else if (action == PlayerNoticeAction.OPEN_GUIDE && !returnRoute.isNullOrBlank() && onNavigate != null) {
                viewModel.dismissPlayerNotice()
                onNavigate(returnRoute)
            } else {
                viewModel.runPlayerNoticeAction(action)
            }
        }
    }

    val handleBackPress = remember(
        playerNotice,
        showProgramHistory,
        showSplitDialog,
        showEpisodePicker,
        showSpeedSelection,
        showTrackSelection,
        showDiagnostics,
        showChannelInfoOverlay,
        showChannelListOverlay,
        showCategoryListOverlay,
        showEpgOverlay,
        showControls,
        numericChannelInput,
        showOptionsPanel,
        showMiniGuide
    ) {
        {
            when {
                // The options panel and the mini-guide were missing from this cascade.
                // A BackHandler further down was meant to catch them, but the root
                // onKeyEvent consumes KEYCODE_BACK first and returns true, so it never
                // ran: BACK from either one fell through to onBack() and left the
                // player altogether instead of closing the panel.
                showOptionsPanel -> showOptionsPanel = false
                showMiniGuide -> showMiniGuide = false
                viewModel.hasPendingNumericChannelInput() -> viewModel.clearNumericChannelInput()
                playerNotice != null -> viewModel.dismissPlayerNotice()
                showProgramHistory -> showProgramHistory = false
                showSplitDialog -> showSplitDialog = false
                showEpisodePicker -> showEpisodePicker = false
                showSpeedSelection -> showSpeedSelection = false
                showTrackSelection != null -> showTrackSelection = null
                showDiagnostics -> viewModel.toggleDiagnostics()
                showChannelInfoOverlay -> viewModel.closeChannelInfoOverlay()
                showChannelListOverlay || showCategoryListOverlay || showEpgOverlay -> viewModel.closeOverlays()
                showControls -> viewModel.toggleControls()
                else -> onBack()
            }
        }
    }

    BackHandler(enabled = !resumePrompt.show) {
        handleBackPress()
    }

    // ── Picture-in-Picture (mobile only) ──────────────────────────────────
    val pipController = mainActivity?.pipController
    val playerStats by viewModel.playerStats.collectAsStateWithLifecycle()

    // Feed the aspect ratio from the decoded frame. Android rejects extreme
    // ratios, so the controller clamps; 0x0 before the first frame is ignored.
    LaunchedEffect(pipController, playerStats.width, playerStats.height) {
        pipController?.setVideoSize(playerStats.width, playerStats.height)
    }
    // Auto-enter is armed only while the player is on screen AND playing, so
    // swiping home from a browsing screen never opens a PiP window.
    LaunchedEffect(pipController, isPlaying) {
        pipController?.setPlayerState(visible = true, playing = isPlaying)
    }
    DisposableEffect(pipController) {
        onDispose {
            // Leaving the player disarms auto-enter; otherwise going home from
            // any other screen would still pop a PiP window.
            pipController?.setPlayerState(visible = false, playing = false)
            mainActivity?.onPipControl = null
        }
    }
    // PiP action buttons drive the same entry points as the on-screen controls.
    DisposableEffect(mainActivity, isPlaying) {
        mainActivity?.onPipControl = { control ->
            when (control) {
                PIP_CONTROL_PLAY_PAUSE -> if (isPlaying) viewModel.pause() else viewModel.play()
                PIP_CONTROL_REWIND -> viewModel.seekBackward()
                PIP_CONTROL_FORWARD -> viewModel.seekForward()
            }
            // The play/pause icon must flip to match the new state.
            pipController?.syncParams()
        }
        onDispose { }
    }

    // ── Touch gestures (phone / tablet only) ──────────────────────────────
    val touchGesturesEnabled = LocalFormFactor.current.isTouch
    val gestureController = rememberPlayerGestureController()
    var gestureFeedback by remember { mutableStateOf<PlayerGestureFeedback?>(null) }
    // Bumped when a drag ends; the effect below then fades the HUD out. A plain
    // key on `gestureFeedback` would restart the timer on every drag frame and
    // the HUD would linger long after the finger lifted.
    var gestureFeedbackNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(gestureFeedback, gestureFeedbackNonce) {
        if (gestureFeedback != null) {
            delay(900)
            gestureFeedback = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusProperties {
                // Only allow focus on the main background when no overlays are active.
                // The options panel counts: while it is up its chips must keep focus,
                // otherwise the root steals it back and the panel is undrivable.
                // La barre de contrôle, elle, ne compte que si le focus y est descendu :
                // en mode seek elle est à l’écran mais c’est la racine qui doit garder
                // le focus, sinon gauche/droite redeviennent de la navigation.
                canFocus = !anyOverlayVisible && !showOptionsPanel &&
                    (!showControls || !chromeHasFocus)
            }
            .focusable()
            // Touch vocabulary of the player. On TV `touchGesturesEnabled` is
            // false and this contributes nothing, leaving the D-pad path exactly
            // as before. Note this REPLACES the previous standalone
            // detectTapGestures — two tap detectors on one node fight over the
            // pointer and the second never fires.
            .playerTouchGestures(
                enabled = touchGesturesEnabled,
                allowZap = contentType == "LIVE",
                onSingleTap = {
                    when {
                        anyOverlayVisible -> Unit
                        showControls -> viewModel.toggleControls()
                        contentType == "LIVE" -> viewModel.openChannelInfoOverlay()
                        else -> viewModel.toggleControls()
                    }
                },
                onDoubleTapSeek = { deltaSeconds ->
                    if (!anyOverlayVisible) {
                        if (deltaSeconds < 0) viewModel.seekBackward() else viewModel.seekForward()
                        gestureFeedback = PlayerGestureFeedback.Seek(deltaSeconds)
                    }
                },
                onTogglePlayPause = {
                    if (!anyOverlayVisible) {
                        if (isPlaying) viewModel.pause() else viewModel.play()
                    }
                },
                onVerticalDrag = { zone, delta ->
                    if (!anyOverlayVisible) {
                        when (zone) {
                            VerticalDragZone.Volume ->
                                gestureController.adjustVolume(delta)?.let {
                                    gestureFeedback = PlayerGestureFeedback.Volume(it)
                                }
                            VerticalDragZone.Brightness ->
                                gestureController.adjustBrightness(delta)?.let {
                                    gestureFeedback = PlayerGestureFeedback.Brightness(it)
                                }
                        }
                    }
                },
                onDragEnd = { gestureFeedbackNonce++ },
                onZap = { next ->
                    if (!anyOverlayVisible) {
                        // Same entry points the D-pad up/down uses, so zapping
                        // behaves identically however it is triggered.
                        if (next) viewModel.playNext() else viewModel.playPrevious()
                    }
                }
            )
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                // Any remote key press = activity → reset the inactivity guard
                // (no-op unless the opt-in guard is enabled on a live stream).
                viewModel.notePlaybackInteraction()
                // Le zapping ne concerne que le direct pur. Un catch-up porte
                // contentType "LIVE" mais rejoue un programme : y zapper sur haut/bas
                // tuerait la lecture en cours.
                if (canSeekContent) {
                    return@onPreviewKeyEvent false
                }
                if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay) {
                    return@onPreviewKeyEvent false
                }
                if (showTrackSelection != null || showSpeedSelection || showProgramHistory || showSplitDialog || showEpisodePicker) {
                    return@onPreviewKeyEvent false
                }
                // Chrome visible → the arrows belong to the focus system, not to zapping.
                // This handler runs BEFORE the focused button sees the key, so without
                // this guard UP/DOWN zapped the channel and the live controls bar was
                // effectively unreachable: only the button holding the initial focus
                // could ever be activated. CHANNEL_UP/DOWN below stay unconditional, so
                // remotes with dedicated channel keys keep zapping either way.
                if (showControls) {
                    return@onPreviewKeyEvent false
                }

                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                        if (showChannelInfoOverlay || showDiagnostics) {
                            viewModel.onLiveOverlayInteraction()
                        }
                        viewModel.playNext()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                        if (showChannelInfoOverlay || showDiagnostics) {
                            viewModel.onLiveOverlayInteraction()
                        }
                        viewModel.playPrevious()
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    val keyCode = event.nativeKeyEvent.keyCode
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (viewModel.isProgressiveSeekActive.value) {
                            viewModel.stopProgressiveSeek()
                            return@onKeyEvent true
                        }
                    }
                    // Fall through to the existing handler so other ACTION_UP behaviour is unaffected.
                }
                // Cinematic options panel — MENU key toggles (works on most TV remotes
                // that don't have a dedicated button). Open → consume; close handled
                // via the back handler below.
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                ) {
                    if (!anyOverlayVisible) {
                        showOptionsPanel = !showOptionsPanel
                        return@onKeyEvent true
                    }
                }
                // Mini-guide toggle on KEYCODE_GUIDE (dedicated TV guide remote button).
                // Only meaningful on live content; defer to the full EPG overlay path
                // if any classic overlay is already on screen.
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_GUIDE &&
                    contentType == "LIVE" && !anyOverlayVisible && !showOptionsPanel
                ) {
                    showMiniGuide = !showMiniGuide
                    return@onKeyEvent true
                }
                // Hold-OK detection — DPAD_CENTER / ENTER held long enough to fire
                // at least 2 repeat key events opens the options panel. A short
                // tap has repeatCount == 0 and is left alone so the existing
                // handlers below (play/pause, channel info, etc.) still run.
                val keyCode = event.nativeKeyEvent.keyCode
                val isOkKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (isOkKey && !anyOverlayVisible && !showOptionsPanel &&
                    event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.repeatCount >= 2
                ) {
                    showOptionsPanel = true
                    return@onKeyEvent true
                }
                // Only handle KeyDown to avoid double actions
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (showTrackSelection != null || showSpeedSelection) {
                        if (showSpeedSelection) {
                            return@onKeyEvent when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_BACK -> {
                                    showSpeedSelection = false
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> false
                                else -> true
                            }
                        }
                        return@onKeyEvent when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_BACK -> {
                                showTrackSelection = null
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> false
                            else -> true
                        }
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (viewModel.isProgressiveSeekActive.value) {
                                viewModel.stopProgressiveSeek()
                                return@onKeyEvent true
                            }
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (contentType == "LIVE" && viewModel.hasPendingNumericChannelInput()) {
                                viewModel.commitNumericChannelInput()
                                true
                            } else if (showControls && !chromeHasFocus) {
                                // Barre à l’écran en mode seek : OK y fait descendre le
                                // focus. C’est la porte d’entrée vers les réglages (son,
                                // sous-titres, vitesse…), que les flèches ne desservent
                                // plus tant qu’on cherche.
                                chromeFocused = true
                                viewModel.refreshControlsAutoHide()
                                true
                            } else if (showControls) {
                                // Let the focused control handle OK. Must come before the
                                // LIVE branch, otherwise OK would toggle the bar shut
                                // instead of pressing the button under the cursor.
                                false
                            } else {
                                // OK now opens the controls bar on LIVE too. It used to
                                // open ChannelInfoOverlay, which left the bar with no
                                // entry point at all. The banner is still what a zap
                                // raises (PlayerZapActions), so the quick "what am I on"
                                // glance is unchanged.
                                // Ouverture explicite → on veut les boutons, pas le seek.
                                chromeFocused = true
                                viewModel.toggleControls()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            // Focus descendu dans la barre → les flèches y pilotent le
                            // curseur, sur LIVE comme ailleurs. (LIVE used to be excluded
                            // here, which is why the live bar could never be navigated.)
                            // Sur du direct pur chromeHasFocus est vrai dès que la barre
                            // est là : comportement LIVE strictement inchangé.
                            if (showControls && chromeHasFocus) return@onKeyEvent false
                            if (showChannelListOverlay && contentType == "LIVE") {
                                // Second left press while channel list is open → open category list
                                viewModel.openCategoryListOverlay()
                                true
                            } else if (!canSeekContent && !showChannelListOverlay && !showCategoryListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.openEpgOverlay() else viewModel.openChannelListOverlay()
                                true
                            } else if (!showChannelListOverlay && !showCategoryListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                // Barre fermée OU visible en mode seek : on cherche dans
                                // le média. La barre monte pour donner le timecode, mais
                                // le focus ne bouge pas — l’appui suivant cherche encore.
                                viewModel.revealControls()
                                // Indispensable : LaunchedEffect(showControls) ne relance
                                // le minuteur qu’au changement d’état, donc sans ça la
                                // barre s’évanouirait au bout de 5 s en plein seek.
                                viewModel.refreshControlsAutoHide()
                                val direction = if (isRtl) SeekDirection.FORWARD else SeekDirection.BACKWARD
                                when (event.nativeKeyEvent.repeatCount) {
                                    0 -> {
                                        // Single tap: seek 10 s as today
                                        if (direction == SeekDirection.FORWARD) viewModel.seekForward()
                                        else viewModel.seekBackward()
                                    }
                                    1 -> {
                                        // First repeat → user is holding; start progressive seek
                                        viewModel.startProgressiveSeek(direction)
                                    }
                                    else -> {
                                        // Subsequent repeats are absorbed by the controller
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            // Voir le commentaire symétrique sur DPAD_LEFT.
                            if (showControls && chromeHasFocus) return@onKeyEvent false
                            if (!canSeekContent && !showChannelListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.openChannelListOverlay() else viewModel.openEpgOverlay()
                                true
                            } else if (!showChannelListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                viewModel.revealControls()
                                viewModel.refreshControlsAutoHide()
                                val direction = if (isRtl) SeekDirection.BACKWARD else SeekDirection.FORWARD
                                when (event.nativeKeyEvent.repeatCount) {
                                    0 -> {
                                        if (direction == SeekDirection.FORWARD) viewModel.seekForward()
                                        else viewModel.seekBackward()
                                    }
                                    1 -> {
                                        viewModel.startProgressiveSeek(direction)
                                    }
                                    else -> { /* absorbed */ }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay) return@onKeyEvent false
                            // Voir le commentaire sur DPAD_LEFT : on ne rend la touche au
                            // focus system que si le focus est déjà dans la barre.
                            if (showControls && chromeHasFocus) return@onKeyEvent false

                            if (canOpenEpisodePicker) {
                                showEpisodePicker = true
                            } else if (!canSeekContent) {
                                viewModel.playNext()
                            } else {
                                // Contenu rejouable : haut fait descendre le focus dans la
                                // barre (en l’ouvrant au besoin) — c’est l’autre porte
                                // d’entrée vers les boutons, avec OK.
                                viewModel.revealControls()
                                viewModel.refreshControlsAutoHide()
                                chromeFocused = true
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay) return@onKeyEvent false
                            // Voir le commentaire sur DPAD_LEFT.
                            if (showControls && chromeHasFocus) return@onKeyEvent false

                            if (!canSeekContent) {
                                viewModel.playPrevious()
                            } else {
                                viewModel.revealControls()
                                viewModel.refreshControlsAutoHide()
                                chromeFocused = true
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            if (viewModel.isProgressiveSeekActive.value) {
                                viewModel.stopProgressiveSeek()
                                // Continue into the existing back-handler so the overlay still closes.
                            }
                            handleBackPress()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (isPlaying) viewModel.pause() else viewModel.play()
                            true
                        }
                        KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                viewModel.toggleMute()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                            if (contentType == "LIVE") {
                                viewModel.playNext()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                            if (contentType == "LIVE") {
                                viewModel.playPrevious()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (contentType == "LIVE") {
                                viewModel.zapToLastChannel()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_GUIDE -> {
                            if (contentType == "LIVE") {
                                viewModel.openEpgOverlay()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_INFO -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            // INFO keeps its "what am I watching" meaning on LIVE: it
                            // raises the lightweight banner, unlike OK which now opens
                            // the full controls bar.
                            if (contentType == "LIVE") {
                                if (showChannelInfoOverlay) viewModel.closeChannelInfoOverlay()
                                else viewModel.openChannelInfoOverlay()
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            viewModel.toggleControls()
                            true
                        }
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                            if (contentType == "LIVE") {
                                val keyCode = event.nativeKeyEvent.keyCode
                                val digit = when (keyCode) {
                                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
                                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
                                    else -> return@onKeyEvent false
                                }
                                viewModel.inputNumericChannelDigit(digit)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        // ExoPlayer Video Surface
        AndroidView(
            factory = { context ->
                viewModel.playerEngine.createRenderView(
                    context = context,
                    resizeMode = aspectRatio.toPlayerSurfaceResizeMode()
                )
            },
            update = { renderView ->
                // Only re-runs when aspectRatio changes (Compose tracks
                // the read of aspectRatio above). PlayerView's internal
                // AspectRatioFrameLayout handles video-size changes on
                // its own — no need to rebind on every format change.
                viewModel.playerEngine.bindRenderView(
                    renderView = renderView,
                    resizeMode = aspectRatio.toPlayerSurfaceResizeMode()
                )
            },
            onRelease = { renderView ->
                viewModel.playerEngine.releaseRenderView(renderView)
            },
            modifier = Modifier
                .fillMaxSize()
                // Android animates from this rectangle into the PiP window.
                // Without it the transition snaps instead of morphing.
                .onGloballyPositioned { coords ->
                    if (pipController != null) {
                        val bounds = coords.boundsInWindow()
                        pipController.setSourceRect(
                            android.graphics.Rect(
                                bounds.left.toInt(),
                                bounds.top.toInt(),
                                bounds.right.toInt(),
                                bounds.bottom.toInt()
                            )
                        )
                    }
                }
        )

        // Loader robuste : ne JAMAIS laisser le spinner centré collé pendant que
        // la lecture tourne. Règles :
        //  - isPlaying == true        → masqué immédiatement (la chaîne joue).
        //  - pas BUFFERING/IDLE       → masqué (READY/ENDED/ERROR).
        //  - BUFFERING/IDLE sans jeu  → affiché ; immédiat au tout premier
        //    démarrage (évite l'écran noir muet), mais débouncé ~1 s une fois
        //    qu'on a déjà joué, pour qu'un micro-rebuffer ne fasse pas clignoter
        //    le gros overlay. `playbackTitle` sert de clé de session : il change
        //    à chaque zap/épisode et réinitialise l'état du loader.
        // In Picture-in-Picture the window is roughly 200dp wide: there is room
        // for the video and nothing else, and PiP exposes no touch targets beyond
        // the system action row. Everything below is chrome (loader, controls,
        // overlays, dialogs, toasts) plus the effects that drive it — no playback
        // logic — so it is simply not composed while in PiP.
        if (!isInPip) {
            val bufferingNow = playbackState == PlaybackState.BUFFERING ||
                playbackState == PlaybackState.IDLE
            var hasEverPlayed by remember(playbackTitle) { mutableStateOf(false) }
            LaunchedEffect(isPlaying) { if (isPlaying) hasEverPlayed = true }
            var showLoader by remember(playbackTitle) { mutableStateOf(false) }
            LaunchedEffect(bufferingNow, isPlaying, hasEverPlayed) {
                when {
                    isPlaying || !bufferingNow -> showLoader = false
                    !hasEverPlayed -> showLoader = true
                    else -> {
                        delay(1000)
                        showLoader = true
                    }
                }
            }
            if (showLoader) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        val loadingLabel = currentChannel?.name
                            ?: currentEpisode?.title
                            ?: playbackTitle
                        if (!loadingLabel.isNullOrBlank()) {
                            Text(
                                text = loadingLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                        Text(
                            text = stringResource(R.string.player_buffering),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = playerNotice != null && !(playbackState == PlaybackState.BUFFERING && playerNotice?.isRetryNotice == false),
                enter = fadeIn(AppMotion.overlayFade()),
                exit = fadeOut(AppMotion.overlayFade()),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 116.dp)
            ) {
                PlayerNoticeBanner(
                    notice = playerNotice,
                    onDismiss = viewModel::dismissPlayerNotice,
                    onAction = handlePlayerNoticeAction
                )
            }

            if (currentChannelRecording?.status == com.streamvault.domain.model.RecordingStatus.RECORDING) {
                val recordingPulse = rememberInfiniteTransition(label = "recordingPulse")
                val recordingAlpha by recordingPulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 750),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "recordingAlpha"
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 18.dp, top = 18.dp)
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFFFF4D4F).copy(alpha = recordingAlpha), RoundedCornerShape(999.dp))
                    )
                    Text(
                        text = stringResource(R.string.settings_recording_status_recording),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Error overlay
            if (playbackState == PlaybackState.ERROR) {
                PlayerErrorOverlay(
                    playerError = playerError,
                    contentType = contentType,
                    hasAlternateStream = viewModel.hasAlternateStream(),
                    hasLastChannel = viewModel.hasLastChannel(),
                    onAction = handlePlayerNoticeAction
                )
            }

            // Above the video, below the controls: a gesture readout must never sit
            // on top of a button the user is trying to press.
            if (touchGesturesEnabled) {
                PlayerGestureHud(
                    feedback = gestureFeedback,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            PlayerControlsOverlayHost(
                playerEngine = viewModel.playerEngine,
                visible = showControls,
                title = playbackTitle.ifBlank { title },
                contentType = contentType,
                isTimeshift = isTimeshift,
                isPlaying = isPlaying,
                currentProgram = currentProgram,
                currentChannelName = currentChannel?.name,
                displayChannelNumber = displayChannelNumber,
                aspectRatioLabel = aspectRatio.modeName,
                subtitleTrackCount = availableSubtitleTracks.size,
                audioTrackCount = availableAudioTracks.size,
                videoQualityCount = availableVideoQualities.size,
                currentRecordingStatus = currentChannelRecording?.status,
                isMuted = isMuted,
                playbackSpeed = playbackSpeed,
                mediaTitle = mediaTitle,
                playButtonFocusRequester = playButtonFocusRequester,
                quickActionsFocusRequester = quickActionsFocusRequester,
                modifier = Modifier.fillMaxSize(),
                onClose = viewModel::toggleControls,
                onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
                onSeekBackward = { viewModel.seekBackward() },
                onSeekForward = { viewModel.seekForward() },
                onSeekBackwardHoldStart = { viewModel.startProgressiveSeek(SeekDirection.BACKWARD) },
                onSeekBackwardHoldStop = { viewModel.stopProgressiveSeek() },
                onSeekForwardHoldStart = { viewModel.startProgressiveSeek(SeekDirection.FORWARD) },
                onSeekForwardHoldStop = { viewModel.stopProgressiveSeek() },
                onRestartProgram = viewModel::restartCurrentProgram,
                onOpenArchive = { showProgramHistory = true },
                onStartRecording = viewModel::startManualRecording,
                onStopRecording = viewModel::stopCurrentRecording,
                onScheduleRecording = viewModel::scheduleRecording,
                onScheduleDailyRecording = viewModel::scheduleDailyRecording,
                onScheduleWeeklyRecording = viewModel::scheduleWeeklyRecording,
                onToggleAspectRatio = viewModel::toggleAspectRatio,
                onOpenSubtitleTracks = { showTrackSelection = TrackType.TEXT },
                onOpenAudioTracks = { showTrackSelection = TrackType.AUDIO },
                onOpenVideoTracks = { showTrackSelection = TrackType.VIDEO },
                onOpenPlaybackSpeed = { showSpeedSelection = true },
                hasNextEpisode = nextEpisode != null,
                onPlayNextEpisode = viewModel::playNextEpisode,
                onOpenSplitScreen = { showSplitDialog = true },
                onToggleMute = viewModel::toggleMute,
                isCastConnected = castConnectionState == CastConnectionState.CONNECTED,
                onCast = { viewModel.castCurrentMedia { mainActivity?.openCastRouteChooser() } },
                onStopCasting = viewModel::stopCasting,
                onSeekToPosition = viewModel::seekTo,
                // Null on TV: no PiP there, so no action is added to the panel.
                onEnterPip = pipController?.takeIf { it.isSupported }?.let {
                    { it.enterIfPlaying() }
                },
                seekPreview = seekPreview,
                onUserInteraction = viewModel::refreshControlsAutoHide,
                // Same panel the hold-OK gesture and MENU open. The controls bar keeps
                // only what a viewer reaches for mid-playback; the rest lives here.
                onOpenOptionsPanel = { showOptionsPanel = true },
                // Lambdas, not bound references: these are all extension functions on
                // PlayerViewModel (PlayerZapActions / PlayerOverlayActions).
                onZapPrevious = { viewModel.playPrevious() },
                onZapNext = { viewModel.playNext() },
                onOpenChannelList = { viewModel.openChannelListOverlay() },
                onOpenEpg = { viewModel.openEpgOverlay() },
                onOpenChannelInfo = { viewModel.openChannelInfoOverlay() },
                channelLogoUrl = currentChannel?.logoUrl
            )

            PlayerNumericInputOverlay(
                state = numericChannelInput,
                visible = contentType == "LIVE" && !showControls,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
            )

            PlayerAspectRatioToast(
                aspectRatioLabel = aspectRatio.modeName,
                controlsVisible = showControls,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
            )

            PlayerResolutionBadge(
                visible = showResolution && !showControls && resolutionBadgeLabel != null,
                resolutionLabel = resolutionBadgeLabel.orEmpty(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
            )
        
            // Resume Prompt Dialog
            if (resumePrompt.show) {
                PlayerResumePrompt(
                    title = resumePrompt.title,
                    onStartOver = { viewModel.dismissResumePrompt(resume = false) },
                    onResume = { viewModel.dismissResumePrompt(resume = true) }
                )
            }
        
            // Track Selection Dialog
            PlayerTrackSelectionDialog(
                trackType = showTrackSelection,
                audioTracks = availableAudioTracks,
                subtitleTracks = availableSubtitleTracks,
                videoTracks = availableVideoQualities,
                onDismiss = { showTrackSelection = null },
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectVideo = viewModel::selectVideoQuality,
                onSelectSubtitle = viewModel::selectSubtitleTrack
            )
            PlayerSpeedSelectionDialog(
                visible = showSpeedSelection,
                selectedSpeed = playbackSpeed,
                onDismiss = { showSpeedSelection = false },
                onSelectSpeed = viewModel::setPlaybackSpeed
            )
            PlayerEpisodeSelectionDialog(
                visible = showEpisodePicker,
                seriesTitle = currentSeries?.name ?: playbackTitle.ifBlank { title },
                seasons = currentSeriesSeasons.orEmpty(),
                currentEpisodeId = currentEpisode?.id ?: internalChannelId,
                currentSeasonNumber = currentEpisode?.seasonNumber ?: seasonNumber,
                onDismiss = { showEpisodePicker = false },
                onSelectEpisode = { episode ->
                    showEpisodePicker = false
                    viewModel.playEpisode(episode)
                }
            )

            // --- Overlays ---
            if (showDiagnostics) {
                val playerStats by viewModel.playerStats.collectAsStateWithLifecycle()
                DiagnosticsOverlay(
                    stats = playerStats,
                    diagnostics = playerDiagnostics,
                    modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
                )
            }

            if (contentType == "LIVE") {
                AnimatedVisibility(
                    visible = showChannelListOverlay,
                    enter = slideInHorizontally(AppMotion.overlaySlide()) { if (isRtl) it else -it } +
                        fadeIn(AppMotion.overlayFade()),
                    exit = slideOutHorizontally(AppMotion.overlaySlide()) { if (isRtl) it else -it } +
                        fadeOut(AppMotion.overlayFade()),
                    modifier = Modifier
                        .align(if (isRtl) Alignment.TopEnd else Alignment.TopStart)
                        .fillMaxHeight()
                        .width(sideOverlayWidth)
                        .focusGroup()
                ) {
                    ChannelListOverlay(
                        channels = currentChannelList,
                        recentChannels = recentChannels,
                        currentChannelId = currentChannel?.id ?: internalChannelId,
                        overlayFocusRequester = channelListFocusRequester,
                        lastVisitedCategoryName = lastVisitedCategory?.name,
                        onOpenLastGroup = { viewModel.openLastVisitedCategory() },
                        onSelectChannel = { channelId -> viewModel.zapToChannel(channelId) },
                        onOpenCategories = { viewModel.openCategoryListOverlay() },
                        onDismiss = { viewModel.closeOverlays() },
                        onOverlayInteracted = viewModel::onLiveOverlayInteraction
                    )
                }

                AnimatedVisibility(
                    visible = showCategoryListOverlay,
                    enter = slideInHorizontally(AppMotion.overlaySlide()) { if (isRtl) it else -it } +
                        fadeIn(AppMotion.overlayFade()),
                    exit = slideOutHorizontally(AppMotion.overlaySlide()) { if (isRtl) it else -it } +
                        fadeOut(AppMotion.overlayFade()),
                    modifier = Modifier
                        .align(if (isRtl) Alignment.TopEnd else Alignment.TopStart)
                        .fillMaxHeight()
                        .width(sideOverlayWidth)
                        .focusGroup()
                ) {
                    CategoryListOverlay(
                        categories = availableCategories,
                        currentCategoryId = activeCategoryId,
                        overlayFocusRequester = categoryListFocusRequester,
                        onSelectCategory = { category ->
                            viewModel.selectCategoryFromOverlay(category)
                        },
                        onDismiss = { viewModel.closeOverlays() },
                        onOverlayInteracted = viewModel::onLiveOverlayInteraction
                    )
                }

                AnimatedVisibility(
                    visible = showEpgOverlay,
                    enter = slideInHorizontally(AppMotion.overlaySlide()) { if (isRtl) -it else it } +
                        fadeIn(AppMotion.overlayFade()),
                    exit = slideOutHorizontally(AppMotion.overlaySlide()) { if (isRtl) -it else it } +
                        fadeOut(AppMotion.overlayFade()),
                    modifier = Modifier
                        .align(if (isRtl) Alignment.TopStart else Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(epgOverlayWidth)
                        .focusGroup()
                ) {
                    EpgOverlay(
                        currentChannel = currentChannel,
                        displayChannelNumber = displayChannelNumber,
                        currentProgram = currentProgram,
                        nextProgram = nextProgram,
                        upcomingPrograms = upcomingPrograms,
                        overlayFocusRequester = epgFocusRequester,
                        preferredFocusedProgramToken = lastFocusedEpgProgramToken,
                        onFocusedProgramChange = { token -> lastFocusedEpgProgramToken = token },
                        onDismiss = { viewModel.closeOverlays() },
                        onOpenArchiveBrowser = {
                            showProgramHistory = true
                            viewModel.closeOverlays()
                        },
                        onPlayCatchUp = { program -> 
                            viewModel.playCatchUp(program)
                            viewModel.closeOverlays()
                        },
                        onOverlayInteracted = viewModel::onLiveOverlayInteraction
                    )
                }

                AnimatedVisibility(
                    visible = showChannelInfoOverlay,
                    enter = slideInVertically(AppMotion.overlaySlide()) { it } + fadeIn(AppMotion.overlayFade()),
                    exit = slideOutVertically(AppMotion.overlaySlide()) { it } + fadeOut(AppMotion.overlayFade()),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .focusGroup()
                ) {
                    ChannelInfoOverlay(
                        currentChannel = currentChannel,
                        displayChannelNumber = displayChannelNumber,
                        currentProgram = currentProgram,
                        nextProgram = nextProgram,
                        focusRequester = channelInfoFocusRequester,
                        lastVisitedCategoryName = lastVisitedCategory?.name,
                        onDismiss = { viewModel.closeChannelInfoOverlay() },
                        onOverlayInteracted = viewModel::onLiveOverlayInteraction,
                        onOpenFullEpg = {
                            viewModel.closeChannelInfoOverlay()
                            viewModel.openEpgOverlay()
                        },
                        onOpenLastGroup = {
                            viewModel.closeChannelInfoOverlay()
                            viewModel.openLastVisitedCategory()
                        },
                        currentRecordingStatus = currentChannelRecording?.status,
                        onStartRecording = viewModel::startManualRecording,
                        onStopRecording = viewModel::stopCurrentRecording,
                        onRestartProgram = { viewModel.restartCurrentProgram() },
                        onToggleAspectRatio = { viewModel.toggleAspectRatio() },
                        onToggleDiagnostics = { viewModel.toggleDiagnostics() },
                        onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
                        isPlaying = isPlaying,
                        currentAspectRatio = aspectRatio.modeName,
                        isDiagnosticsEnabled = showDiagnostics,
                        onOpenSplitScreen = { showSplitDialog = true },
                        subtitleTrackCount = availableSubtitleTracks.size,
                        audioTrackCount = availableAudioTracks.size,
                        videoQualityCount = availableVideoQualities.size,
                        isMuted = isMuted,
                        onToggleMute = viewModel::toggleMute,
                        onOpenSubtitleTracks = { showTrackSelection = TrackType.TEXT },
                        onOpenAudioTracks = { showTrackSelection = TrackType.AUDIO },
                        onOpenVideoTracks = { showTrackSelection = TrackType.VIDEO },
                        isCastConnected = castConnectionState == CastConnectionState.CONNECTED,
                        onCast = { viewModel.castCurrentMedia { mainActivity?.openCastRouteChooser() } },
                        onStopCasting = viewModel::stopCasting
                    )
                }
            }

            // ─── Cinematic overlays ───────────────────────────────────────
            // NowPlayingChip: glass pill announcing what's playing, fades after 3s.
            // Re-triggered each time the stream URL or episode changes.
            val nowPlayingKey = remember(streamUrl, currentEpisode?.id) {
                "$streamUrl|${currentEpisode?.id ?: 0}"
            }
            val nowPlayingSubtitle = currentEpisode?.let { ep ->
                val season = ep.seasonNumber.takeIf { it > 0 }
                val number = ep.episodeNumber.takeIf { it > 0 }
                if (season != null && number != null) "S$season · E$number" else null
            } ?: currentChannel?.let { channel ->
                currentProgram?.title?.takeIf { it.isNotBlank() }
            }
            com.streamvault.app.ui.screens.player.overlay.components.NowPlayingChip(
                title = playbackTitle.takeIf { it.isNotBlank() } ?: title,
                subtitle = nowPlayingSubtitle,
                key = nowPlayingKey,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 40.dp, start = 40.dp)
            )

            // PlayerOptionsPanel: triggered by KEYCODE_MENU or a ≥700 ms hold on OK.
            // Dismissed by the BackHandler just below. Fed directly from the player
            // engine's track and quality lists so ordering matches the native player.
            val speedPresets = remember { listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }
            com.streamvault.app.ui.screens.player.overlay.components.PlayerOptionsPanel(
                visible = showOptionsPanel,
                audioOptions = availableAudioTracks.map { track ->
                    com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                        id = track.id,
                        label = track.name.ifBlank { track.language ?: track.id },
                        selected = track.isSelected
                    )
                },
                subtitleOptions = availableSubtitleTracks.map { track ->
                    com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                        id = track.id,
                        label = track.name.ifBlank { track.language ?: track.id },
                        selected = track.isSelected
                    )
                },
                speedOptions = speedPresets.map { preset ->
                    com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                        id = preset.toString(),
                        label = if (preset == 1.0f) "1×" else "${preset}×",
                        selected = kotlin.math.abs(playbackSpeed - preset) < 0.01f
                    )
                },
                qualityOptions = availableVideoQualities.map { track ->
                    com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                        id = track.id,
                        label = track.name.ifBlank { track.id },
                        selected = track.isSelected
                    )
                },
                onAudioSelected = { id ->
                    viewModel.selectAudioTrack(id)
                    showOptionsPanel = false
                },
                onSubtitleSelected = { id ->
                    viewModel.selectSubtitleTrack(id.takeUnless { it.isBlank() })
                    showOptionsPanel = false
                },
                onSpeedSelected = { id ->
                    id.toFloatOrNull()?.let { viewModel.setPlaybackSpeed(it) }
                    showOptionsPanel = false
                },
                onQualitySelected = { id ->
                    viewModel.selectVideoQuality(id)
                    showOptionsPanel = false
                },
                // Everything the controls bar no longer carries. These are the rare
                // actions — the bar keeps what a viewer reaches for mid-playback, and
                // the rest stays exactly one press away instead of doubling its height.
                actionOptions = buildList {
                    if (contentType == "LIVE") {
                        add(
                            com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                                id = ACTION_CAST,
                                label = stringResource(
                                    if (castConnectionState == CastConnectionState.CONNECTED) {
                                        R.string.player_stop_casting
                                    } else {
                                        R.string.player_cast
                                    }
                                ),
                                selected = castConnectionState == CastConnectionState.CONNECTED
                            )
                        )
                        if (currentProgram?.hasArchive == true) {
                            add(
                                com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                                    id = ACTION_ARCHIVE,
                                    label = stringResource(R.string.player_archive)
                                )
                            )
                        }
                        if (currentChannelRecording?.status != com.streamvault.domain.model.RecordingStatus.RECORDING) {
                            add(
                                com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                                    id = ACTION_SCHEDULE,
                                    label = stringResource(R.string.player_schedule_recording)
                                )
                            )
                            add(
                                com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                                    id = ACTION_SCHEDULE_DAILY,
                                    label = stringResource(R.string.player_schedule_daily_recording)
                                )
                            )
                            add(
                                com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                                    id = ACTION_SCHEDULE_WEEKLY,
                                    label = stringResource(R.string.player_schedule_weekly_recording)
                                )
                            )
                        }
                        add(
                            com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                                id = ACTION_SPLIT,
                                label = stringResource(R.string.player_action_split)
                            )
                        )
                    }
                    add(
                        com.streamvault.app.ui.screens.player.overlay.components.PlayerOption(
                            id = ACTION_DIAGNOSTICS,
                            label = stringResource(R.string.player_action_diagnostics),
                            selected = showDiagnostics
                        )
                    )
                },
                onActionSelected = { id ->
                    showOptionsPanel = false
                    when (id) {
                        ACTION_CAST -> if (castConnectionState == CastConnectionState.CONNECTED) {
                            viewModel.stopCasting()
                        } else {
                            viewModel.castCurrentMedia { mainActivity?.openCastRouteChooser() }
                        }
                        ACTION_ARCHIVE -> showProgramHistory = true
                        ACTION_SCHEDULE -> viewModel.scheduleRecording()
                        ACTION_SCHEDULE_DAILY -> viewModel.scheduleDailyRecording()
                        ACTION_SCHEDULE_WEEKLY -> viewModel.scheduleWeeklyRecording()
                        ACTION_SPLIT -> showSplitDialog = true
                        ACTION_DIAGNOSTICS -> viewModel.toggleDiagnostics()
                    }
                },
                onDismiss = { showOptionsPanel = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.78f)
                    .padding(bottom = 36.dp)
            )

            // MiniGuideFloating: a glass sidebar for the "up next" strip. Exposed
            // behind an explicit state so it never competes with the classic EPG
            // overlay — opened on demand only.
            val miniGuideEntries = remember(upcomingPrograms, currentProgram) {
                val now = System.currentTimeMillis()
                upcomingPrograms.map { program ->
                    com.streamvault.app.ui.screens.player.overlay.components.MiniGuideEntry(
                        program = program,
                        isLive = now in program.startTime until program.endTime,
                        isCurrent = program.id == currentProgram?.id
                    )
                }
            }
            com.streamvault.app.ui.screens.player.overlay.components.MiniGuideFloating(
                visible = showMiniGuide && contentType == "LIVE" && !anyOverlayVisible,
                entries = miniGuideEntries,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .fillMaxHeight(0.72f)
            )
        }
    }

    // Close the options panel / mini-guide on Back before the regular back handler.
    BackHandler(enabled = showOptionsPanel || showMiniGuide) {
        when {
            showOptionsPanel -> showOptionsPanel = false
            showMiniGuide -> showMiniGuide = false
        }
    }
}

private fun AspectRatio.toPlayerSurfaceResizeMode(): PlayerSurfaceResizeMode = when (this) {
    AspectRatio.FIT -> PlayerSurfaceResizeMode.FIT
    AspectRatio.FILL -> PlayerSurfaceResizeMode.FILL
    AspectRatio.ZOOM -> PlayerSurfaceResizeMode.ZOOM
}

private fun buildResolutionBadgeLabel(
    videoFormat: VideoFormat,
    videoTracks: List<PlayerTrack>,
    autoResolutionLabel: String
): String? {
    if (videoFormat.isEmpty) return null
    val selectedTrack = videoTracks.firstOrNull(PlayerTrack::isSelected)
    return if (selectedTrack == null || selectedTrack.id == PLAYER_TRACK_AUTO_ID) {
        autoResolutionLabel
    } else {
        selectedTrack.name
    }
}

@Composable
private fun PlayerControlsOverlayHost(
    playerEngine: PlayerEngine,
    visible: Boolean,
    title: String,
    contentType: String,
    /** Replay / catch-up : "LIVE" mais rejouable — voir PlayerControlsOverlay. */
    isTimeshift: Boolean,
    isPlaying: Boolean,
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: com.streamvault.domain.model.RecordingStatus?,
    isMuted: Boolean,
    playbackSpeed: Float,
    mediaTitle: String?,
    playButtonFocusRequester: FocusRequester,
    quickActionsFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackwardHoldStart: () -> Unit = {},
    onSeekBackwardHoldStop: () -> Unit = {},
    onSeekForwardHoldStart: () -> Unit = {},
    onSeekForwardHoldStop: () -> Unit = {},
    onRestartProgram: () -> Unit,
    onOpenArchive: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onScheduleRecording: () -> Unit,
    onScheduleDailyRecording: () -> Unit,
    onScheduleWeeklyRecording: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit,
    hasNextEpisode: Boolean,
    onPlayNextEpisode: () -> Unit,
    onOpenSplitScreen: () -> Unit,
    onToggleMute: () -> Unit,
    isCastConnected: Boolean,
    onCast: () -> Unit,
    onStopCasting: () -> Unit,
    onSeekToPosition: (Long) -> Unit,
    onEnterPip: (() -> Unit)?,
    seekPreview: SeekPreviewState,
    onUserInteraction: () -> Unit,
    onOpenOptionsPanel: () -> Unit = {},
    onZapPrevious: () -> Unit = {},
    onZapNext: () -> Unit = {},
    onOpenChannelList: () -> Unit = {},
    onOpenEpg: () -> Unit = {},
    onOpenChannelInfo: () -> Unit = {},
    channelLogoUrl: String? = null
) {
    val currentPosition by playerEngine.currentPosition.collectAsStateWithLifecycle()
    val duration by playerEngine.duration.collectAsStateWithLifecycle()

    PlayerControlsOverlay(
        visible = visible,
        title = title,
        contentType = contentType,
        isTimeshift = isTimeshift,
        isPlaying = isPlaying,
        currentProgram = currentProgram,
        currentChannelName = currentChannelName,
        displayChannelNumber = displayChannelNumber,
        currentPosition = currentPosition,
        duration = duration,
        aspectRatioLabel = aspectRatioLabel,
        subtitleTrackCount = subtitleTrackCount,
        audioTrackCount = audioTrackCount,
        videoQualityCount = videoQualityCount,
        currentRecordingStatus = currentRecordingStatus,
        isMuted = isMuted,
        playbackSpeed = playbackSpeed,
        mediaTitle = mediaTitle,
        playButtonFocusRequester = playButtonFocusRequester,
        quickActionsFocusRequester = quickActionsFocusRequester,
        modifier = modifier,
        onClose = onClose,
        onTogglePlayPause = onTogglePlayPause,
        onSeekBackward = onSeekBackward,
        onSeekForward = onSeekForward,
        onSeekBackwardHoldStart = onSeekBackwardHoldStart,
        onSeekBackwardHoldStop = onSeekBackwardHoldStop,
        onSeekForwardHoldStart = onSeekForwardHoldStart,
        onSeekForwardHoldStop = onSeekForwardHoldStop,
        onRestartProgram = onRestartProgram,
        onOpenArchive = onOpenArchive,
        onStartRecording = onStartRecording,
        onStopRecording = onStopRecording,
        onScheduleRecording = onScheduleRecording,
        onScheduleDailyRecording = onScheduleDailyRecording,
        onScheduleWeeklyRecording = onScheduleWeeklyRecording,
        onToggleAspectRatio = onToggleAspectRatio,
        onOpenSubtitleTracks = onOpenSubtitleTracks,
        onOpenAudioTracks = onOpenAudioTracks,
        onOpenVideoTracks = onOpenVideoTracks,
        onOpenPlaybackSpeed = onOpenPlaybackSpeed,
        hasNextEpisode = hasNextEpisode,
        onPlayNextEpisode = onPlayNextEpisode,
        onOpenSplitScreen = onOpenSplitScreen,
        onToggleMute = onToggleMute,
        isCastConnected = isCastConnected,
        onCast = onCast,
        onStopCasting = onStopCasting,
        onSeekToPosition = onSeekToPosition,
        onEnterPip = onEnterPip,
        seekPreview = seekPreview,
        onUserInteraction = onUserInteraction,
        onOpenOptionsPanel = onOpenOptionsPanel,
        onZapPrevious = onZapPrevious,
        onZapNext = onZapNext,
        onOpenChannelList = onOpenChannelList,
        onOpenEpg = onOpenEpg,
        onOpenChannelInfo = onOpenChannelInfo,
        channelLogoUrl = channelLogoUrl
    )
}

// Ids for the options panel "Actions" rail. Plain constants rather than an enum: the
// panel's PlayerOption model is a flat (id, label, selected) triple shared with the
// track/speed/quality rails, whose ids are track ids.
private const val ACTION_CAST = "action:cast"
private const val ACTION_ARCHIVE = "action:archive"
private const val ACTION_SCHEDULE = "action:schedule"
private const val ACTION_SCHEDULE_DAILY = "action:schedule_daily"
private const val ACTION_SCHEDULE_WEEKLY = "action:schedule_weekly"
private const val ACTION_SPLIT = "action:split"
private const val ACTION_DIAGNOSTICS = "action:diagnostics"

private tailrec fun android.content.Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is android.content.ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
