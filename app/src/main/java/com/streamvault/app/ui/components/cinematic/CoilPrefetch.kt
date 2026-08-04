package com.streamvault.app.ui.components.cinematic

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Warms Coil's memory cache for the next [lookahead] items of a LazyRow. Call once
 * from the row composable, supplying the full url list and the row's scroll state.
 *
 * Safe on low-end devices — enqueue is throttled to lookahead items and Coil itself
 * collapses duplicates when the same URL is already resolved / in flight.
 */
@OptIn(FlowPreview::class)
@Composable
fun CoilPrefetchRow(
    urls: List<String?>,
    listState: LazyListState,
    imageLoader: ImageLoader,
    lookahead: Int = 4
) {
    val context = LocalPlatformContext.current
    val safeUrls = remember(urls) { urls.filterNotNull() }
    if (safeUrls.isEmpty()) return
    LaunchedEffect(listState, safeUrls, imageLoader) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            // Skip prefetch while the user is mid-scroll: rapid index changes
            // would otherwise queue many small request bursts that compete with
            // visible-item loads on low-end TV boxes.
            .debounce(150)
            .collectLatest { first ->
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
                val from = (lastVisible + 1).coerceAtLeast(0)
                val to = (from + lookahead).coerceAtMost(safeUrls.size)
                for (i in from until to) {
                    val url = safeUrls.getOrNull(i) ?: continue
                    imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(url)
                            .build()
                    )
                }
            }
    }
}
