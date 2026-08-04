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
import androidx.compose.ui.graphics.Color

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
    val selected: Boolean = false,
    /** Play/pause: bigger and accent-filled, the anchor of the strip. */
    val isPrimary: Boolean = false,
    val restingTint: Color? = null,
    val onHoldStart: (() -> Unit)? = null,
    val onHoldStop: (() -> Unit)? = null
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

    val transportActions = buildList {
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.SkipPrevious,
                label = stringResource(R.string.player_action_previous_channel),
                onClick = onZapPrevious
            )
        )
        add(
            PlayerIconActionSpec(
                icon = if (isPlaying) PlayerIcons.Pause else Icons.Default.PlayArrow,
                label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                contentDescription = stringResource(R.string.player_playback_label),
                isPrimary = true,
                onClick = onTogglePlayPause
            )
        )
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.SkipNext,
                label = stringResource(R.string.player_action_next_channel),
                onClick = onZapNext
            )
        )
        if (hasArchive) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.Restart,
                    label = stringResource(R.string.player_restart),
                    onClick = onRestartProgram
                )
            )
        }
        add(
            PlayerIconActionSpec(
                icon = if (isRecording) PlayerIcons.RecordStop else PlayerIcons.Record,
                label = stringResource(
                    if (isRecording) R.string.player_stop_recording else R.string.player_record
                ),
                selected = isRecording,
                // Red is the one colour convention worth spending here: a recording
                // indicator that looks like every other control is a recording nobody
                // notices is running.
                restingTint = AppColors.Live,
                onClick = if (isRecording) onStopRecording else onStartRecording
            )
        )
    }

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

    // Appliance-style header: one dense line identifying the channel, one for the
    // programme. The description moved back to the INFO banner — the bar is for
    // controlling, the banner for reading, and two wrapped lines of synopsis were the
    // single biggest thing pushing this panel over half the screen.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogoBadge(
            channelName = currentChannelName.orEmpty(),
            logoUrl = channelLogoUrl,
            modifier = Modifier.size(PlayerChromeTokens.channelBadge())
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The channel number is the primary landmark on a live OSD — it is what
                // you dial and what you recognise — so it gets the accent, not a pill.
                Text(
                    text = displayChannelNumber.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Brand,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = currentChannelName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (hasArchive) {
                    PlayerMetaPill(text = stringResource(R.string.player_archive_badge))
                }
                if (isMuted) {
                    PlayerMetaPill(text = stringResource(R.string.player_muted_badge))
                }
            }
            Text(
                text = currentProgram?.title
                    ?: mediaTitle
                    ?: stringResource(R.string.player_no_guide_data),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    val start = currentProgram?.startTime ?: 0L
    val end = currentProgram?.endTime ?: 0L
    if (start > 0 && end > 0) {
        Spacer(modifier = Modifier.height(10.dp))
        val now = System.currentTimeMillis()
        PlayerScrubBar(
            fraction = (now - start).toFloat() / (end - start),
            startLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start)),
            endLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end))
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    // ONE control row: transport cluster, a gap, then the settings cluster. Two stacked
    // rows cost a full button height plus a caption slot for no navigational benefit —
    // LEFT/RIGHT walks the whole strip, and there is no up/down ambiguity left to get
    // wrong. It also removes the `up`/`down` focusProperties entirely.
    PlayerIconActionRow(
        actions = transportActions + actions,
        firstActionFocusRequester = quickActionsFocusRequester,
        primaryFocusRequester = playButtonFocusRequester,
        primaryIndex = transportActions.indexOfFirst { it.isPrimary },
        groupBreakAfter = transportActions.lastIndex
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

    // No title row here: the top bar already shows the title and the content-type pill.
    // Repeating them cost a whole line for information the eye had just read. Only the
    // muted badge stays — that is state, not a label.
    if (isMuted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerMetaPill(text = stringResource(R.string.player_muted_badge))
        }
        Spacer(modifier = Modifier.height(8.dp))
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

    Spacer(modifier = Modifier.height(10.dp))

    // ONE control row, same as LIVE: transport cluster, gap, settings cluster.
    val transportActions = buildList {
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.Rewind,
                label = stringResource(R.string.player_rewind),
                onClick = onSeekBackward,
                onHoldStart = onSeekBackwardHoldStart,
                onHoldStop = onSeekBackwardHoldStop
            )
        )
        add(
            PlayerIconActionSpec(
                icon = if (isPlaying) PlayerIcons.Pause else Icons.Default.PlayArrow,
                label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                contentDescription = playbackLabel,
                isPrimary = true,
                onClick = onTogglePlayPause
            )
        )
        add(
            PlayerIconActionSpec(
                icon = PlayerIcons.Forward,
                label = stringResource(R.string.player_forward),
                onClick = onSeekForward,
                onHoldStart = onSeekForwardHoldStart,
                onHoldStop = onSeekForwardHoldStop
            )
        )
        if (hasNextEpisode) {
            add(
                PlayerIconActionSpec(
                    icon = PlayerIcons.SkipNext,
                    label = stringResource(R.string.player_next_episode),
                    onClick = onPlayNextEpisode
                )
            )
        }
    }

    PlayerIconActionRow(
        actions = transportActions + actions,
        firstActionFocusRequester = quickActionsFocusRequester,
        primaryFocusRequester = playButtonFocusRequester,
        primaryIndex = transportActions.indexOfFirst { it.isPrimary },
        groupBreakAfter = transportActions.lastIndex
    )
}

@Composable
private fun PlayerIconActionRow(
    actions: List<PlayerIconActionSpec>,
    firstActionFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    /** Attached to [primaryIndex]; this is what the bar focuses when it opens. */
    primaryFocusRequester: FocusRequester? = null,
    primaryIndex: Int = -1,
    /** Index after which a wider gap separates the transport cluster from the rest. */
    groupBreakAfter: Int = -1
) {
    if (actions.isEmpty()) return
    val primaryButtonSize = PlayerChromeTokens.primaryButton()
    val iconButtonSize = PlayerChromeTokens.iconButton()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            PlayerChromeTokens.RowGap,
            Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEachIndexed { index, action ->
            PlayerIconButton(
                icon = action.icon,
                label = action.label,
                contentDescription = action.contentDescription,
                selected = action.selected,
                accent = action.isPrimary,
                restingTint = action.restingTint,
                size = if (action.isPrimary) primaryButtonSize else iconButtonSize,
                onHoldStart = action.onHoldStart,
                onHoldStop = action.onHoldStop,
                onClick = action.onClick,
                modifier = Modifier.then(
                    when {
                        index == primaryIndex && primaryFocusRequester != null ->
                            Modifier.focusRequester(primaryFocusRequester)

                        index == 0 -> Modifier.focusRequester(firstActionFocusRequester)
                        else -> Modifier
                    }
                )
            )
            if (index == groupBreakAfter) {
                Spacer(modifier = Modifier.width(PlayerChromeTokens.GroupGap))
            }
        }
    }
}

