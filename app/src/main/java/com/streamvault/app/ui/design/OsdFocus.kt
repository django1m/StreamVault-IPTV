package com.streamvault.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale

/**
 * The two focus treatments of the app, as ready-made `ClickableSurface*` values.
 *
 * Why an object of factories rather than a single `Modifier`: most focusable things here
 * are `androidx.tv.material3.Surface`, whose focused container colour is a constructor
 * parameter, not something a modifier can reach. [Modifier.osdSlab] and
 * [Modifier.osdTileOutline] below cover the handful of hand-rolled `Box`/`Row` cases.
 *
 * The important part of [slabColors] is `contentColor`/`focusedContentColor`. TV Material
 * publishes those through `LocalContentColor`, so every `Text` and `Icon` underneath that
 * does *not* hard-code its own colour flips to dark automatically when the slab fills in.
 * That is what makes converting ~40 rows safe. The corollary: a child that *does* hard-code
 * `color = …` must have that argument **removed**, not adjusted — otherwise it stays white
 * on a white slab, and nothing warns you.
 */
object OsdFocus {

    /**
     * Row / menu item / chip: nothing at rest, solid slab when focused.
     *
     * @param active the item this row represents is the one currently playing or selected.
     *   It tints the *text*, never the background — see [AppColors.ActiveText].
     */
    @Composable
    fun slabColors(
        active: Boolean = false,
        restingContent: Color = AppColors.TextPrimary
    ): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent,
        focusedContainerColor = AppColors.Focus,
        pressedContainerColor = AppColors.Focus,
        contentColor = if (active) AppColors.ActiveText else restingContent,
        focusedContentColor = AppColors.FocusOnFill,
        pressedContentColor = AppColors.FocusOnFill
    )

    /** Card / tile / poster: transparent in both states — [tileBorder] does all the work. */
    @Composable
    fun tileColors(): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent,
        focusedContainerColor = Color.Transparent,
        pressedContainerColor = Color.Transparent
    )

    @Composable
    fun tileBorder(shape: Shape, dragging: Boolean = false): ClickableSurfaceBorder =
        ClickableSurfaceDefaults.border(
            border = if (dragging) {
                Border(BorderStroke(FocusSpec.DragOutlineWidth, AppColors.Warning), shape = shape)
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.OutlineWidth, AppColors.Focus),
                shape = shape
            )
        )

    /** A slab needs no outline — the fill already is the boundary. */
    @Composable
    fun noBorder(): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
        border = Border.None,
        focusedBorder = Border.None
    )

    /**
     * Nothing grows. Explicit because TV Material's default is `focusedScale = 1.1f`, and
     * a surface that simply omits `scale` silently opts into it.
     */
    @Composable
    fun noScale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(
        focusedScale = 1f,
        pressedScale = FocusSpec.PressedScale
    )
}

/** Slab treatment for hand-rolled focusable containers that are not a TV `Surface`. */
fun Modifier.osdSlab(focused: Boolean, shape: Shape): Modifier =
    if (focused) this.background(AppColors.Focus, shape) else this

/** Outline treatment for the same. */
fun Modifier.osdTileOutline(
    focused: Boolean,
    shape: Shape,
    dragging: Boolean = false
): Modifier = when {
    dragging -> this.border(FocusSpec.DragOutlineWidth, AppColors.Warning, shape)
    focused -> this.border(FocusSpec.OutlineWidth, AppColors.Focus, shape)
    else -> this
}

/** Content colour to pair with [Modifier.osdSlab] where `LocalContentColor` is not in play. */
fun osdContentColor(
    focused: Boolean,
    active: Boolean = false,
    resting: Color = AppColors.TextPrimary
): Color = when {
    focused -> AppColors.FocusOnFill
    active -> AppColors.ActiveText
    else -> resting
}

/** Dimmed companion of [osdContentColor] for numbers, counters and subtitles. */
fun osdSecondaryColor(focused: Boolean): Color =
    if (focused) AppColors.FocusOnFillDim else AppColors.TextTertiary
