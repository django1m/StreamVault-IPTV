package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun InfiniteScrollEffect(
    gridState: LazyGridState,
    canLoadMore: Boolean,
    thresholdItems: Int = 12,
    onLoadMore: () -> Unit
) {
    val shouldLoadMore = remember(gridState, canLoadMore, thresholdItems) {
        derivedStateOf {
            if (!canLoadMore) return@derivedStateOf false
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf false
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisibleIndex >= totalItems - thresholdItems
        }
    }

    LaunchedEffect(gridState, canLoadMore) {
        snapshotFlow { shouldLoadMore.value }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }
}
