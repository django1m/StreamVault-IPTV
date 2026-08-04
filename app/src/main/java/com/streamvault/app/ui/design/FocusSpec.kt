package com.streamvault.app.ui.design

import androidx.compose.ui.unit.dp

/**
 * Focus geometry for the whole app.
 *
 * The language has exactly two focus treatments and neither of them enlarges anything:
 *
 *  - **rows, menu items, chips** → a solid [AppColors.Focus] slab with dark content.
 *  - **cards, tiles, posters** → a hairline outline, no fill.
 *
 * Growing an element on focus was the old model. It costs a layout pass on a Mali-G310,
 * it forces `zIndex` juggling so the grown item is not clipped by its neighbours, and it
 * competes with the two treatments above for the viewer's attention. The scale constants
 * stay declared — pinned to 1f — so the sites that read them become no-ops without
 * needing an edit each.
 *
 * `GlowRadius`/`GlowSpread`/`GlowInnerRadius` are gone on purpose: removing them makes
 * the compiler point at `FocusGlow.kt` and every one of its callers, which is a more
 * reliable inventory than a grep.
 */
object FocusSpec {
    const val FocusedScale = 1f
    const val FocusedScaleCinematic = 1f
    const val PressedScale = 0.98f
    const val PressedScaleCinematic = 0.96f

    /**
     * The one and only focus outline width. Replaces the 2 / 3 / 4 dp that used to
     * coexist. 2 dp rather than 3: an EPG cell is 52 dp tall, where a 3 dp outline eats
     * 12 % of its height, and 2 dp at 1080p is still well above the 10-foot floor.
     */
    val OutlineWidth = 2.dp

    /** Drag is a manipulation, not a focus. It stays deliberately louder than both. */
    val DragOutlineWidth = 4.dp

    @Deprecated("Une seule largeur désormais", ReplaceWith("OutlineWidth"))
    val BorderWidth = OutlineWidth

    @Deprecated("Une seule largeur désormais", ReplaceWith("OutlineWidth"))
    val CardBorderWidth = OutlineWidth
}
