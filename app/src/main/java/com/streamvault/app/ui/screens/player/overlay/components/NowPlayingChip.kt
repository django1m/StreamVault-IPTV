package com.streamvault.app.ui.screens.player.overlay.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.streamvault.app.ui.components.cinematic.GlassSurface
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.GlassLevel
import com.streamvault.app.ui.design.LocalAppHeroStyles
import kotlinx.coroutines.delay

/**
 * Glass pill that fades in when playback starts, announces the title / episode, then
 * fades out after [visibleMillis]. Reads as "Now Playing" ribbon.
 *
 * Reset to show again by changing the [key] (typically the track id / url).
 */
@Composable
fun NowPlayingChip(
    title: String?,
    subtitle: String? = null,
    key: Any? = title,
    visibleMillis: Long = 3000L,
    modifier: Modifier = Modifier
) {
    var visible by remember(key) { mutableStateOf(true) }
    LaunchedEffect(key) {
        visible = true
        delay(visibleMillis)
        visible = false
    }
    AnimatedVisibility(
        visible = visible && !title.isNullOrBlank(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val hero = LocalAppHeroStyles.current
        GlassSurface(level = GlassLevel.Medium) {
            Row(
                modifier = Modifier.padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp)),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title ?: "",
                    style = hero.heroMeta,
                    color = AppColors.TextPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = "·  $subtitle",
                        style = hero.sectionEyebrow,
                        color = AppColors.TextSecondary
                    )
                }
            }
        }
    }
}
