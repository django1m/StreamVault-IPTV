package com.streamvault.app.ui.screens.player.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.LocalFormFactor
import com.streamvault.app.ui.design.isTouch
import com.streamvault.app.ui.screens.player.SeekPreviewState
import com.streamvault.app.ui.screens.player.overlay.components.PlayerChromeTokens
import com.streamvault.app.ui.screens.player.overlay.components.PlayerIconButton
import com.streamvault.app.ui.screens.player.overlay.components.PlayerIcons
import com.streamvault.app.ui.screens.player.overlay.components.PlayerMetaPill
import com.streamvault.app.ui.screens.player.overlay.components.PlayerScrubBar
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.streamvault.app.ui.components.ChannelLogoBadge
import com.streamvault.app.ui.design.AppMotion
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn

/**
 * The two transport chromes that fill the bottom bar: LIVE (zapping, EPG progress,
 * recording) and VOD (playhead, transport, tracks), plus the shared icon action row.
 *
 * Split out of PlayerControlsChrome.kt, same package — no import changes anywhere.
 */
/**
 * One button of an icon action row. [label] doubles as the focus caption, so it stays
 * short; [contentDescription] carries the full sentence for screen readers when the
 * two differ (e.g. label "1.5x" / description "Speed 1.5x").
 */
private data class PlayerIconActionSpec(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val contentDescription: String = label,
    val selected: Boolean = false
)

@Composable
internal fun PlayerLiveInfo(
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: RecordingStatus?,
    isMuted: Boolean,
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
    onOpenSplitScreen: () -> Unit,
    onToggleMute: () -> Unit,
    isCastConnected: Boolean,
    onCast: () -> Unit,
    onStopCasting: () -> Unit,
    isPlaying: Boolean = true,
    onTogglePlayPause: () -> Unit = {},
    onOpenOptionsPanel: () -> Unit = {},
    onZapPrevious: () -> Unit = {},
    onZapNext: () -> Unit = {},
    onOpenChannelList: () -> Unit = {},
    onOpenEpg: () -> Unit = {},
    onOpenChannelInfo: () -> Unit = {},
    channelLogoUrl: String? = null
) {
    val isRecording = currentRecordingStatus == RecordingStatus.RECORDING
    val hasArchive = currentProgram?.hasArchive == true

    val actions = buildList {
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.ChannelList,
                label = stringResource(R.string.player_action_channels),
                onClick = onOpenChannelList
            )
        )
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.Guide,
                label = stringResource(R.string.player_action_guide),
                onClick = onOpenEpg
            )
        )
        add(
            PlayerIconActionSpec(
                icon = Icons.Default.Info,
                label = stringResource(R.string.player_action_info),
                onClick = onOpenChannelInfo
            )
        )
        add(
            PlayerIconActionSpec(
                icon = if (isMuted) PlayerIcons.VolumeOff else PlayerIcons.VolumeOn,
                label = stringResource(if (isMuted) R.string.player_unmute else R.string.player_mute),
                selected = isMuted,
                onClick = onToggleMute
            )
        )
        if (subtitleTrackCount > 0) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.Subtitles,
                    label = stringResource(R.string.player_subs),
                    onClick = onOpenSubtitleTracks
                )
            )
        }
        if (audioTrackCount > 0) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.AudioTrack,
                    label = stringResource(R.string.player_audio),
                    onClick = onOpenAudioTracks
                )
            )
        }
        if (videoQualityCount > 0) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.Quality,
                    label = stringResource(R.string.player_video_quality),
                    onClick = onOpenVideoTracks
                )
            )
        }
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.AspectRatio,
                label = aspectRatioLabel,
                contentDescription = stringResource(R.string.player_aspect_ratio_label, aspectRatioLabel),
                onClick = onToggleAspectRatio
            )
        )
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.Options,
                label = stringResource(R.string.player_more_controls),
                onClick = onOpenOptionsPanel
            )
        )
    }

    // Channel badge + programme blurb: carried over from ChannelInfoOverlay, which OK
    // no longer opens. Without them the bar would be a downgrade on the one thing the
    // banner did well — telling you what you are actually watching.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        ChannelLogoBadge(
            channelName = currentChannelName.orEmpty(),
            logoUrl = channelLogoUrl,
            modifier = Modifier.size(64.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerMetaPill(text = stringResource(R.string.player_live_now), accent = true)
                PlayerMetaPill(text = stringResource(R.string.player_live_channel, displayChannelNumber))
                if (hasArchive) {
                    PlayerMetaPill(text = stringResource(R.string.player_archive_badge))
                }
                if (isMuted) {
                    PlayerMetaPill(text = stringResource(R.string.player_muted_badge))
                }
            }
            Text(
                text = currentProgram?.title ?: mediaTitle ?: currentChannelName.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (currentProgram != null) {
                    stringResource(
                        R.string.channel_number_name_format,
                        displayChannelNumber,
                        currentChannelName.orEmpty()
                    )
                } else {
                    stringResource(R.string.player_no_guide_data)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            currentProgram?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    val start = currentProgram?.startTime ?: 0L
    val end = currentProgram?.endTime ?: 0L
    if (start > 0 && end > 0) {
        val now = System.currentTimeMillis()
        PlayerScrubBar(
            fraction = (now - start).toFloat() / (end - start),
            startLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start)),
            endLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end))
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Transport. `down = quickActionsFocusRequester` on every button so the action row
    // is one press away whichever transport button holds focus. The play/pause button
    // finally carries `playButtonFocusRequester` on LIVE too — until now that requester
    // was handed to the row below as an `up` target without ever being attached, which
    // would have thrown as soon as vertical focus navigation started working here.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            PlayerChromeTokens.RowGap,
            Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.Top
    ) {
        PlayerIconButton(
            icon = PlayerIcons.SkipPrevious,
            label = stringResource(R.string.player_action_previous_channel),
            onClick = onZapPrevious,
            modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
        )
        PlayerIconButton(
            icon = if (isPlaying) PlayerIcons.Pause else Icons.Default.PlayArrow,
            label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
            contentDescription = stringResource(R.string.player_playback_label),
            accent = true,
            size = PlayerChromeTokens.primaryButton(),
            onClick = onTogglePlayPause,
            modifier = Modifier
                .focusRequester(playButtonFocusRequester)
                .focusProperties { down = quickActionsFocusRequester }
        )
        PlayerIconButton(
            icon = PlayerIcons.SkipNext,
            label = stringResource(R.string.player_action_next_channel),
            onClick = onZapNext,
            modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
        )
        if (hasArchive) {
            PlayerIconButton(
                icon = PlayerIcons.Restart,
                label = stringResource(R.string.player_restart),
                onClick = onRestartProgram,
                modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
            )
        }
        PlayerIconButton(
            icon = if (isRecording) PlayerIcons.RecordStop else PlayerIcons.Record,
            label = stringResource(
                if (isRecording) R.string.player_stop_recording else R.string.player_record
            ),
            selected = isRecording,
            // Red is the one colour convention worth spending here: a recording
            // indicator that looks like every other control is a recording nobody
            // notices is running.
            restingTint = AppColors.Live,
            onClick = if (isRecording) onStopRecording else onStartRecording,
            modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    PlayerIconActionRow(
        actions = actions,
        firstActionFocusRequester = quickActionsFocusRequester,
        upFocusRequester = playButtonFocusRequester
    )
}

@Composable
internal fun PlayerVodInfo(
    title: String,
    contentType: String,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    isMuted: Boolean,
    playbackSpeed: Float,
    playButtonFocusRequester: FocusRequester,
    quickActionsFocusRequester: FocusRequester,
    onSeekToPosition: (Long) -> Unit,
    onEnterPip: (() -> Unit)?,
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit,
    hasNextEpisode: Boolean,
    onPlayNextEpisode: () -> Unit,
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
    seekPreview: SeekPreviewState,
    onOpenOptionsPanel: () -> Unit = {}
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val compactControls = screenWidth < 700.dp
    val tabletControls = !isTelevisionDevice && screenWidth >= 700.dp && screenWidth < 1280.dp
    val seekPreviewWidth = when {
        compactControls -> 148.dp
        tabletControls -> 168.dp
        else -> 188.dp
    }

    val playbackLabel = stringResource(R.string.player_playback_label)

    // Seek on Android TV is driven by the parent PlayerScreen KeyEvent handler
    // (DPAD LEFT/RIGHT → seekBackward/seekForward, long-hold → progressive seek).
    // Material3 Slider's DPAD handling is unreliable on TV (onValueChangeFinished does
    // not fire on DPAD release), so PlayerScrubBar draws a plain non-focusable Canvas
    // there and simply mirrors the engine's currentPosition. That removes the whole
    // scrubbing state machine and the bugs it caused (frozen timecode at the last
    // slider value, focus captured by the slider, play/pause feeling dead).
    //
    // On TOUCH devices there is no D-pad, so the bar becomes the only way to seek and
    // must be draggable — that, and only that, is what `interactiveSeek` unlocks.
    val interactiveSeek = LocalFormFactor.current.isTouch && duration > 0
    val fraction = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f

    val actions = buildList {
        add(
            PlayerIconActionSpec(
                icon = if (isMuted) PlayerIcons.VolumeOff else PlayerIcons.VolumeOn,
                label = stringResource(if (isMuted) R.string.player_unmute else R.string.player_mute),
                selected = isMuted,
                onClick = onToggleMute
            )
        )
        if (subtitleTrackCount > 0) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.Subtitles,
                    label = stringResource(R.string.player_subs),
                    onClick = onOpenSubtitleTracks
                )
            )
        }
        if (audioTrackCount > 0) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.AudioTrack,
                    label = stringResource(R.string.player_audio),
                    onClick = onOpenAudioTracks
                )
            )
        }
        if (videoQualityCount > 0) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.Quality,
                    label = stringResource(R.string.player_video_quality),
                    onClick = onOpenVideoTracks
                )
            )
        }
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.Speed,
                label = formatPlaybackSpeedLabel(playbackSpeed),
                contentDescription = stringResource(
                    R.string.player_playback_speed_value,
                    formatPlaybackSpeedLabel(playbackSpeed)
                ),
                selected = playbackSpeed != 1f,
                onClick = onOpenPlaybackSpeed
            )
        )
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.AspectRatio,
                label = aspectRatioLabel,
                contentDescription = stringResource(R.string.player_aspect_ratio_label, aspectRatioLabel),
                onClick = onToggleAspectRatio
            )
        )
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.Cast,
                label = stringResource(if (isCastConnected) R.string.player_stop_casting else R.string.player_cast),
                selected = isCastConnected,
                onClick = if (isCastConnected) onStopCasting else onCast
            )
        )
        // Explicit way into Picture-in-Picture. Auto-enter already handles the home
        // gesture, but without a button the feature is undiscoverable — nothing on
        // screen suggests the video can keep playing in a corner. Null on TV.
        if (onEnterPip != null) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.Pip,
                    label = stringResource(R.string.player_pip_short),
                    contentDescription = stringResource(R.string.player_pip),
                    onClick = onEnterPip
                )
            )
        }
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.Options,
                label = stringResource(R.string.player_more_controls),
                onClick = onOpenOptionsPanel
            )
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PlayerMetaPill(
            text = if (contentType == "MOVIE") {
                stringResource(R.string.player_type_movie)
            } else {
                stringResource(R.string.player_type_series)
            },
            accent = true
        )
        if (isMuted) {
            PlayerMetaPill(text = stringResource(R.string.player_muted_badge))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    AnimatedVisibility(
        visible = seekPreview.visible,
        enter = fadeIn(AppMotion.overlayFade()) + scaleIn(AppMotion.scaleSpring(), initialScale = 0.92f),
        exit = fadeOut(AppMotion.overlayFade()) + scaleOut(AppMotion.scaleSpring(), targetScale = 0.92f)
    ) {
        Column {
            Spacer(modifier = Modifier.height(12.dp))
            PlayerSeekPreviewCard(
                preview = seekPreview,
                previewHeight = when {
                    compactControls -> 96.dp
                    tabletControls -> 106.dp
                    else -> 118.dp
                },
                modifier = Modifier.width(seekPreviewWidth)
            )
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    PlayerScrubBar(
        fraction = fraction,
        startLabel = formatDuration(currentPosition),
        endLabel = formatDuration(duration),
        interactiveSeek = interactiveSeek,
        onSeekFraction = { value -> onSeekToPosition((value * duration).toLong()) },
        modifier = Modifier.semantics { contentDescription = playbackLabel }
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Transport. `down = quickActionsFocusRequester` on every button so the action row
    // below is always one press away, whichever transport button holds focus.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            PlayerChromeTokens.RowGap,
            Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.Top
    ) {
        PlayerIconButton(
            icon = PlayerIcons.Rewind,
            label = stringResource(R.string.player_rewind),
            onClick = onSeekBackward,
            onHoldStart = onSeekBackwardHoldStart,
            onHoldStop = onSeekBackwardHoldStop,
            modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
        )
        PlayerIconButton(
            icon = if (isPlaying) PlayerIcons.Pause else Icons.Default.PlayArrow,
            label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
            contentDescription = playbackLabel,
            accent = true,
            size = PlayerChromeTokens.primaryButton(),
            onClick = onTogglePlayPause,
            modifier = Modifier
                .focusRequester(playButtonFocusRequester)
                .focusProperties { down = quickActionsFocusRequester }
        )
        PlayerIconButton(
            icon = PlayerIcons.Forward,
            label = stringResource(R.string.player_forward),
            onClick = onSeekForward,
            onHoldStart = onSeekForwardHoldStart,
            onHoldStop = onSeekForwardHoldStop,
            modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
        )
        if (hasNextEpisode) {
            PlayerIconButton(
                icon = PlayerIcons.SkipNext,
                label = stringResource(R.string.player_next_episode),
                onClick = onPlayNextEpisode,
                modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    PlayerIconActionRow(
        actions = actions,
        firstActionFocusRequester = quickActionsFocusRequester,
        upFocusRequester = playButtonFocusRequester
    )
}

/**
 * Single centred row of icon buttons.
 *
 * Replaces the old `FlowRow` of text pills: with up to ten labelled buttons that row
 * wrapped onto two lines and ate a third of the screen. Icons keep it to one line, and
 * the caption only appears under whichever button holds focus.
 */
@Composable
private fun PlayerIconActionRow(
    actions: List<PlayerIconActionSpec>,
    firstActionFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            PlayerChromeTokens.RowGap,
            Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.Top
    ) {
        actions.forEachIndexed { index, action ->
            PlayerIconButton(
                icon = action.icon,
                label = action.label,
                contentDescription = action.contentDescription,
                selected = action.selected,
                onClick = action.onClick,
                modifier = Modifier
                    .then(
                        if (index == 0) {
                            Modifier.focusRequester(firstActionFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .focusProperties {
                        if (index == 0 && upFocusRequester != null) {
                            up = upFocusRequester
                        }
                    }
            )
        }
    }
}

