package com.streamvault.app.ui.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout metrics that differ between the 10-foot and the hand-held experience.
 *
 * Kept in one place so screens stay declarative: they ask for "the poster grid"
 * rather than each re-deriving breakpoints from `LocalConfiguration`. The screens
 * predating this file compute their own `screenWidth < 700/900/1280` ladders;
 * those still work and are progressively being folded in here.
 */

/** Gap between poster cards. Tighter on a phone, where every dp of width counts. */
@Composable
fun posterGridSpacing(): Dp = when (LocalFormFactor.current) {
    FormFactor.Phone -> 10.dp
    FormFactor.Tablet -> 12.dp
    FormFactor.Tv -> 12.dp
}

/**
 * Column sizing for poster grids (movies, series, favourites, search results).
 *
 * `Adaptive` rather than `Fixed` so a phone in landscape and a tablet both widen
 * on their own. The phone minimum is tuned to yield three columns at the common
 * 360–430dp portrait widths — two columns leaves posters comically large, four
 * makes titles unreadable.
 */
@Composable
fun posterGridCells(): GridCells {
    val formFactor = LocalFormFactor.current
    return remember(formFactor) {
        when (formFactor) {
            FormFactor.Phone -> GridCells.Adaptive(minSize = 104.dp)
            FormFactor.Tablet -> GridCells.Adaptive(minSize = 132.dp)
            FormFactor.Tv -> GridCells.Adaptive(minSize = 136.dp)
        }
    }
}

/**
 * Padding inside a scrolling grid or list that fills the content area.
 *
 * On touch the horizontal gutter is already applied by MobileScreenScaffold, so
 * adding more here would double it; only bottom breathing room above the
 * navigation bar is needed.
 */
@Composable
fun contentScrollPadding(): PaddingValues {
    val formFactor = LocalFormFactor.current
    return remember(formFactor) {
        when (formFactor) {
            FormFactor.Phone -> PaddingValues(bottom = 24.dp)
            FormFactor.Tablet -> PaddingValues(horizontal = 8.dp, vertical = 12.dp)
            FormFactor.Tv -> PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        }
    }
}

/** Height of a channel row in the mobile Live TV list — one comfortable tap target. */
val MobileChannelRowHeight: Dp = 68.dp
