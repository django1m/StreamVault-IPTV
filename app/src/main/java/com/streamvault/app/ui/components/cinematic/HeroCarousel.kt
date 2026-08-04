package com.streamvault.app.ui.components.cinematic

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamvault.app.ui.design.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Auto-advancing hero pager. DPAD left/right flips the page immediately; auto-advance
 * runs every [autoAdvanceMs] and is suspended for [pauseOnInteractMs] after any user
 * interaction so the carousel never fights the viewer.
 *
 * Caller renders each page through [page]. [onPageChanged] fires once per settled
 * page — use it to drive ambient palette extraction.
 */
@Composable
fun HeroCarousel(
    pageCount: Int,
    modifier: Modifier = Modifier,
    heroHeight: Dp = 520.dp,
    autoAdvanceMs: Long = 8000L,
    pauseOnInteractMs: Long = 15000L,
    onPageChanged: (Int) -> Unit = {},
    page: @Composable (Int) -> Unit
) {
    if (pageCount <= 0) return
    val state = rememberPagerState(pageCount = { pageCount })
    var lastInteractionAt by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            if (it is FocusInteraction.Focus) focused = true
            if (it is FocusInteraction.Unfocus) focused = false
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }
    LaunchedEffect(state, pageCount, autoAdvanceMs) {
        while (true) {
            delay(autoAdvanceMs)
            val now = System.currentTimeMillis()
            if (now - lastInteractionAt < pauseOnInteractMs) continue
            if (pageCount <= 1) continue
            val next = (state.settledPage + 1) % pageCount
            state.animateScrollToPage(next)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        lastInteractionAt = System.currentTimeMillis()
                        val target = (state.currentPage - 1 + pageCount) % pageCount
                        scope.launch { state.animateScrollToPage(target) }
                        true
                    }
                    Key.DirectionRight -> {
                        lastInteractionAt = System.currentTimeMillis()
                        val target = (state.currentPage + 1) % pageCount
                        scope.launch { state.animateScrollToPage(target) }
                        true
                    }
                    else -> false
                }
            }
    ) {
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxWidth().height(heroHeight),
            key = { it },
            userScrollEnabled = true
        ) { index ->
            page(index)
        }
        HeroCarouselIndicators(
            pageCount = pageCount,
            currentPage = state.currentPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        )
    }
}

@Composable
private fun HeroCarouselIndicators(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until pageCount) {
            val active = i == currentPage
            val targetWidth: Dp = if (active) 26.dp else 6.dp
            val animatedWidth by animateDpAsState(
                targetValue = targetWidth,
                label = "indicatorWidth"
            )
            Box(
                modifier = Modifier
                    .size(width = animatedWidth, height = 6.dp)
                    .background(
                        color = if (active) AppColors.BrandStrong else AppColors.GlassHeavy,
                        shape = CircleShape
                    )
            )
        }
    }
}
