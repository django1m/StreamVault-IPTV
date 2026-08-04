package com.streamvault.app.ui.screens.player.overlay.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.cinematic.GlassSurface
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.GlassLevel
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.domain.model.Program

data class MiniGuideEntry(
    val program: Program,
    val isLive: Boolean,
    val isCurrent: Boolean
)

/**
 * Right-docked mini-guide used while the player is active. Lists upcoming slots
 * for the current channel. Glass-surfaced, scrollable.
 */
@Composable
fun MiniGuideFloating(
    visible: Boolean,
    entries: List<MiniGuideEntry>,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 360.dp
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
        exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier
    ) {
        val hero = LocalAppHeroStyles.current
        GlassSurface(level = GlassLevel.High, modifier = Modifier.width(width)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_mini_guide_title),
                    style = hero.sectionEyebrow,
                    color = AppColors.BrandStrong
                )
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entries, key = { it.program.id }) { entry ->
                        MiniGuideRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniGuideRow(entry: MiniGuideEntry) {
    val hero = LocalAppHeroStyles.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = entry.program.title,
            style = hero.heroMeta,
            color = if (entry.isCurrent) AppColors.TextPrimary else AppColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatTimeRange(entry.program),
            style = hero.sectionEyebrow,
            color = if (entry.isLive) AppColors.Live else AppColors.TextTertiary
        )
    }
}

private fun formatTimeRange(p: Program): String {
    val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return "${df.format(java.util.Date(p.startTime))} – ${df.format(java.util.Date(p.endTime))}"
}
