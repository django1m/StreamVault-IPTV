package com.streamvault.app.ui.screens.player.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import com.streamvault.app.R
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.LocalFormFactor
import com.streamvault.app.ui.design.isTouch
import com.streamvault.app.ui.screens.player.overlay.components.PlayerChromeTokens
import com.streamvault.app.ui.screens.player.overlay.components.PlayerGlassBar
import com.streamvault.app.ui.screens.player.overlay.components.PlayerIconButton
import com.streamvault.app.ui.screens.player.overlay.components.PlayerIcons
import com.streamvault.app.ui.screens.player.overlay.components.PlayerMetaPill
import com.streamvault.app.ui.screens.player.overlay.components.PlayerScrubBar
import com.streamvault.app.ui.screens.player.overlay.components.playerChromeScrim
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.ui.screens.player.NumericChannelInputState
import com.streamvault.app.ui.screens.player.SeekPreviewState
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingStatus
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton

@Composable
fun PlayerControlsOverlay(
    visible: Boolean,
    title: String,
    contentType: String,
    isPlaying: Boolean,
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    currentPosition: Long,
    duration: Long,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: RecordingStatus?,
    isMuted: Boolean,
    playbackSpeed: Float = 1f,
    mediaTitle: String?,
    playButtonFocusRequester: FocusRequester,
    quickActionsFocusRequester: FocusRequester = FocusRequester(),
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
    onScheduleDailyRecording: () -> Unit = {},
    onScheduleWeeklyRecording: () -> Unit = {},
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit = {},
    hasNextEpisode: Boolean = false,
    onPlayNextEpisode: () -> Unit = {},
    onOpenSplitScreen: () -> Unit,
    onToggleMute: () -> Unit,
    isCastConnected: Boolean = false,
    onCast: () -> Unit = {},
    onStopCasting: () -> Unit = {},
    onSeekToPosition: (Long) -> Unit = {},
    /** Non-null only on form factors where PiP exists (mobile). */
    onEnterPip: (() -> Unit)? = null,
    seekPreview: SeekPreviewState = SeekPreviewState(),
    clockLabelOverride: String? = null,
    onUserInteraction: () -> Unit = {},
    /**
     * Opens the hold-OK options panel. The bar only carries the controls a viewer
     * reaches for mid-playback; everything rarer (cast, recording schedules, archive,
     * split screen, diagnostics) lives one press away behind this button instead of
     * inflating the bar into a wall of buttons.
     */
    onOpenOptionsPanel: () -> Unit = {},
    /**
     * LIVE-only navigation. Now that the arrows drive focus while the chrome is up,
     * these give the bar explicit buttons for what the hidden-chrome shortcuts do
     * (UP/DOWN zap, LEFT channel list, RIGHT guide, OK channel info).
     */
    onZapPrevious: () -> Unit = {},
    onZapNext: () -> Unit = {},
    onOpenChannelList: () -> Unit = {},
    onOpenEpg: () -> Unit = {},
    onOpenChannelInfo: () -> Unit = {},
    /** LIVE only: lets the bar show the channel badge the info banner used to carry. */
    channelLogoUrl: String? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        // Was fadeIn() + expandVertically(): expandVertically re-measures the whole bar
        // on every frame of the transition, which is the worst thing to ask of a Mali-G310.
        // The container now only fades; each bar slides in from its own edge via
        // animateEnterExit below, which is both cheaper and directionally correct — a
        // single container slide would have dragged the top bar up from the bottom.
        enter = fadeIn(AppMotion.overlayFade()),
        exit = fadeOut(AppMotion.overlayFade()),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> onUserInteraction()
                    }
                    false
                }
        ) {
            PlayerTopBar(
                title = title,
                contentType = contentType,
                clockLabelOverride = clockLabelOverride,
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .animateEnterExit(
                        enter = slideInVertically(AppMotion.overlaySlide()) { -it },
                        exit = slideOutVertically(AppMotion.overlaySlide()) { -it }
                    )
            )

            PlayerBottomBar(
                title = title,
                contentType = contentType,
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .animateEnterExit(
                        enter = slideInVertically(AppMotion.overlaySlide()) { it },
                        exit = slideOutVertically(AppMotion.overlaySlide()) { it }
                    ),
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
                onTogglePlayPause = onTogglePlayPause,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
                onSeekBackwardHoldStart = onSeekBackwardHoldStart,
                onSeekBackwardHoldStop = onSeekBackwardHoldStop,
                onSeekForwardHoldStart = onSeekForwardHoldStart,
                onSeekForwardHoldStop = onSeekForwardHoldStop,
                onSeekToPosition = onSeekToPosition,
                onEnterPip = onEnterPip,
                seekPreview = seekPreview,
                onOpenOptionsPanel = onOpenOptionsPanel,
                onZapPrevious = onZapPrevious,
                onZapNext = onZapNext,
                onOpenChannelList = onOpenChannelList,
                onOpenEpg = onOpenEpg,
                onOpenChannelInfo = onOpenChannelInfo,
                channelLogoUrl = channelLogoUrl
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    contentType: String,
    clockLabelOverride: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val topBarHeight = when {
        screenWidth < 700.dp -> 84.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 92.dp
        else -> 104.dp
    }
    val horizontalPadding = when {
        screenWidth < 700.dp -> 18.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 24.dp
        else -> 32.dp
    }
    val verticalPadding = when {
        screenWidth < 700.dp -> 16.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 20.dp
        else -> 24.dp
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight)
            .playerChromeScrim(fromTop = true)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerMetaPill(
                    text = when (contentType) {
                        "LIVE" -> stringResource(R.string.nav_live_tv)
                        "MOVIE" -> stringResource(R.string.player_type_movie)
                        else -> stringResource(R.string.player_type_series)
                    },
                    accent = true
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val currentTime = remember(clockLabelOverride) {
                    mutableStateOf(
                        clockLabelOverride ?: SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    )
                }
                LaunchedEffect(clockLabelOverride) {
                    if (clockLabelOverride == null) {
                        while (true) {
                            delay(10_000)
                            currentTime.value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        }
                    } else {
                        currentTime.value = clockLabelOverride
                    }
                }
                Text(
                    text = currentTime.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextSecondary
                )

                // The close button only exists where there is no BACK key. On a remote it
                // is a focusable target that duplicates a button the viewer already has
                // under their thumb, and it drags the eye to the corner of the picture.
                if (LocalFormFactor.current.isTouch) {
                    PlayerIconButton(
                        icon = Icons.Default.Close,
                        label = stringResource(R.string.player_close),
                        onClick = onClose
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerBottomBar(
    title: String,
    contentType: String,
    isPlaying: Boolean,
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    currentPosition: Long,
    duration: Long,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: RecordingStatus?,
    isMuted: Boolean,
    playbackSpeed: Float,
    mediaTitle: String?,
    playButtonFocusRequester: FocusRequester,
    quickActionsFocusRequester: FocusRequester,
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
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackwardHoldStart: () -> Unit = {},
    onSeekBackwardHoldStop: () -> Unit = {},
    onSeekForwardHoldStart: () -> Unit = {},
    onSeekForwardHoldStop: () -> Unit = {},
    onSeekToPosition: (Long) -> Unit,
    onEnterPip: (() -> Unit)?,
    seekPreview: SeekPreviewState,
    onOpenOptionsPanel: () -> Unit = {},
    onZapPrevious: () -> Unit = {},
    onZapNext: () -> Unit = {},
    onOpenChannelList: () -> Unit = {},
    onOpenEpg: () -> Unit = {},
    onOpenChannelInfo: () -> Unit = {},
    channelLogoUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val isVod = contentType != "LIVE"
    Box(
        modifier = modifier
            .fillMaxWidth()
            .playerChromeScrim(fromTop = false)
            .padding(start = 48.dp, end = 48.dp, top = 110.dp, bottom = 22.dp)
    ) {
        // No card. The bar is the scrim plus its content — a floating panel with a border
        // is a dialog, and this is an OSD you glance past. The three-stop scrim above
        // carries the legibility a card used to.
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.Center)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (contentType == "LIVE") {
                    PlayerLiveInfo(
                        currentProgram = currentProgram,
                        currentChannelName = currentChannelName,
                        displayChannelNumber = displayChannelNumber,
                        aspectRatioLabel = aspectRatioLabel,
                        subtitleTrackCount = subtitleTrackCount,
                        audioTrackCount = audioTrackCount,
                        videoQualityCount = videoQualityCount,
                        currentRecordingStatus = currentRecordingStatus,
                        isMuted = isMuted,
                        mediaTitle = mediaTitle,
                        playButtonFocusRequester = playButtonFocusRequester,
                        quickActionsFocusRequester = quickActionsFocusRequester,
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
                        onOpenSplitScreen = onOpenSplitScreen,
                        onToggleMute = onToggleMute,
                        isCastConnected = isCastConnected,
                        onCast = onCast,
                        onStopCasting = onStopCasting,
                        isPlaying = isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onOpenOptionsPanel = onOpenOptionsPanel,
                        onZapPrevious = onZapPrevious,
                        onZapNext = onZapNext,
                        onOpenChannelList = onOpenChannelList,
                        onOpenEpg = onOpenEpg,
                        onOpenChannelInfo = onOpenChannelInfo,
                        channelLogoUrl = channelLogoUrl
                    )
                } else {
                    PlayerVodInfo(
                        title = title,
                        contentType = contentType,
                        isPlaying = isPlaying,
                        currentPosition = currentPosition,
                        duration = duration,
                        aspectRatioLabel = aspectRatioLabel,
                        subtitleTrackCount = subtitleTrackCount,
                        audioTrackCount = audioTrackCount,
                        videoQualityCount = videoQualityCount,
                        isMuted = isMuted,
                        playbackSpeed = playbackSpeed,
                        playButtonFocusRequester = playButtonFocusRequester,
                        quickActionsFocusRequester = quickActionsFocusRequester,
                        onSeekToPosition = onSeekToPosition,
                        onEnterPip = onEnterPip,
                        onToggleAspectRatio = onToggleAspectRatio,
                        onOpenSubtitleTracks = onOpenSubtitleTracks,
                        onOpenAudioTracks = onOpenAudioTracks,
                        onOpenVideoTracks = onOpenVideoTracks,
                        onOpenPlaybackSpeed = onOpenPlaybackSpeed,
                        hasNextEpisode = hasNextEpisode,
                        onPlayNextEpisode = onPlayNextEpisode,
                        onToggleMute = onToggleMute,
                        isCastConnected = isCastConnected,
                        onCast = onCast,
                        onStopCasting = onStopCasting,
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekBackward = onSeekBackward,
                        onSeekForward = onSeekForward,
                        onSeekBackwardHoldStart = onSeekBackwardHoldStart,
                        onSeekBackwardHoldStop = onSeekBackwardHoldStop,
                        onSeekForwardHoldStart = onSeekForwardHoldStart,
                        onSeekForwardHoldStop = onSeekForwardHoldStop,
                        seekPreview = seekPreview,
                        onOpenOptionsPanel = onOpenOptionsPanel
                    )
                }
            }
        }
    }
}

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) {
        "${speed.toInt()}x"
    } else {
        "${("%.2f".format(Locale.US, speed)).trimEnd('0').trimEnd('.')}x"
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, remainingMinutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", remainingMinutes, seconds)
    }
}
