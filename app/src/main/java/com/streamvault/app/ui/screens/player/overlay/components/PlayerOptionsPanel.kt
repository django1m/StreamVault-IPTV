package com.streamvault.app.ui.screens.player.overlay.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.cinematic.CinematicChip
import com.streamvault.app.ui.components.cinematic.CinematicChipStyle
import com.streamvault.app.ui.components.cinematic.GlassSurface
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.GlassLevel
import com.streamvault.app.ui.design.LocalAppHeroStyles

data class PlayerOption(
    val id: String,
    val label: String,
    val selected: Boolean = false
)

/**
 * Bottom-sheet-style options panel, triggered by a hold-OK gesture from the player.
 * Glass-surfaced, four rails: audio, subtitles, speed, quality. Each rail is a
 * horizontal chip strip.
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
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
        modifier = modifier
    ) {
        val hero = LocalAppHeroStyles.current
        GlassSurface(
            level = GlassLevel.High,
            modifier = Modifier.fillMaxWidth()
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
                if (audioOptions.isNotEmpty()) {
                    OptionRail(
                        title = stringResource(R.string.player_option_audio),
                        options = audioOptions,
                        onSelected = onAudioSelected
                    )
                }
                if (subtitleOptions.isNotEmpty()) {
                    OptionRail(
                        title = stringResource(R.string.player_option_subtitles),
                        options = subtitleOptions,
                        onSelected = onSubtitleSelected
                    )
                }
                if (speedOptions.isNotEmpty()) {
                    OptionRail(
                        title = stringResource(R.string.player_option_speed),
                        options = speedOptions,
                        onSelected = onSpeedSelected
                    )
                }
                if (qualityOptions.isNotEmpty()) {
                    OptionRail(
                        title = stringResource(R.string.player_option_quality),
                        options = qualityOptions,
                        onSelected = onQualitySelected
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRail(
    title: String,
    options: List<PlayerOption>,
    onSelected: (String) -> Unit
) {
    val hero = LocalAppHeroStyles.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = hero.sectionEyebrow,
            color = AppColors.BrandStrong
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                CinematicChip(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelected(option.id) },
                    style = CinematicChipStyle.Glass
                )
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}
