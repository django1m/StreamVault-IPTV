package com.streamvault.app.ui.screens.player.overlay.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.cinematic.CinematicChip
import com.streamvault.app.ui.components.cinematic.CinematicChipStyle
import com.streamvault.app.ui.components.cinematic.GlassSurface
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.GlassLevel
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.app.ui.design.requestFocusSafely

data class PlayerOption(
    val id: String,
    val label: String,
    val selected: Boolean = false
)

/**
 * Bottom-sheet-style options panel, triggered by a hold-OK gesture, the MENU key or the
 * "⋯" button on the controls bar. Glass-surfaced, five rails: audio, subtitles, speed,
 * quality, and the actions that were pulled out of the bar (cast, recording schedules,
 * archive, split screen, diagnostics).
 *
 * The panel takes focus on open. It did not before, which made it undrivable from a
 * remote — it appeared, the root Box kept focus, and no chip could ever be selected.
 */
@Composable
fun PlayerOptionsPanel(
    visible: Boolean,
    audioOptions: List<PlayerOption>,
    subtitleOptions: List<PlayerOption>,
    speedOptions: List<PlayerOption>,
    qualityOptions: List<PlayerOption>,
    onAudioSelected: (String) -> Unit,
    onSubtitleSelected: (String) -> Unit,
    onSpeedSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionOptions: List<PlayerOption> = emptyList(),
    onActionSelected: (String) -> Unit = {}
) {
    val firstChipFocusRequester = remember { FocusRequester() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(AppMotion.overlayFade()) +
            slideInVertically(AppMotion.overlaySlide()) { it / 3 },
        exit = fadeOut(AppMotion.overlayFade()) +
            slideOutVertically(AppMotion.overlaySlide()) { it / 3 },
        modifier = modifier
    ) {
        val hero = LocalAppHeroStyles.current
        GlassSurface(
            level = GlassLevel.High,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_options_title),
                    style = hero.sectionTitle,
                    color = AppColors.TextPrimary
                )
                // Every rail is conditional, so the focus requester has to be attached to
                // whichever one actually renders first — otherwise opening the panel with,
                // say, no audio tracks would target a chip that does not exist and the
                // panel would open unfocused again.
                val rails = buildList {
                    if (actionOptions.isNotEmpty()) {
                        add(Triple(stringResource(R.string.player_more_controls), actionOptions, onActionSelected))
                    }
                    if (audioOptions.isNotEmpty()) {
                        add(Triple(stringResource(R.string.player_option_audio), audioOptions, onAudioSelected))
                    }
                    if (subtitleOptions.isNotEmpty()) {
                        add(Triple(stringResource(R.string.player_option_subtitles), subtitleOptions, onSubtitleSelected))
                    }
                    if (speedOptions.isNotEmpty()) {
                        add(Triple(stringResource(R.string.player_option_speed), speedOptions, onSpeedSelected))
                    }
                    if (qualityOptions.isNotEmpty()) {
                        add(Triple(stringResource(R.string.player_option_quality), qualityOptions, onQualitySelected))
                    }
                }
                rails.forEachIndexed { index, (railTitle, railOptions, onRailSelected) ->
                    OptionRail(
                        title = railTitle,
                        options = railOptions,
                        onSelected = onRailSelected,
                        firstChipFocusRequester = firstChipFocusRequester.takeIf { index == 0 }
                    )
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            firstChipFocusRequester.requestFocusSafely(
                tag = "PlayerOptionsPanel",
                target = "First option chip"
            )
        }
    }
}

@Composable
private fun OptionRail(
    title: String,
    options: List<PlayerOption>,
    onSelected: (String) -> Unit,
    firstChipFocusRequester: FocusRequester? = null
) {
    val hero = LocalAppHeroStyles.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = hero.sectionEyebrow,
            color = AppColors.BrandStrong
        )
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // A provider can expose a dozen audio tracks or quality levels; without
                // this the rail overflows silently and the last chips are unreachable.
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.forEachIndexed { index, option ->
                CinematicChip(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelected(option.id) },
                    style = CinematicChipStyle.Glass,
                    modifier = if (index == 0 && firstChipFocusRequester != null) {
                        Modifier.focusRequester(firstChipFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}
