package com.streamvault.app.ui.components.osd

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.OsdFocus
import com.streamvault.app.ui.interaction.TvClickableSurface

/**
 * The card / poster / tile of the whole app: no container, no growth, a hairline outline
 * when focused.
 *
 * The outline is drawn by [drawWithContent] *after* the content rather than by the
 * surface's own `border`. On a full-bleed poster the surface border can end up beneath the
 * artwork, and a 2 dp line under a bright image is a 2 dp line nobody sees. Drawing it last
 * costs one `drawRoundRect` and removes the question entirely.
 *
 * @param cornerRadius must match [shape]; only used for the overlay stroke.
 */
@Composable
fun OsdTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    dragging: Boolean = false,
    shape: Shape = LocalAppShapes.current.small,
    cornerRadius: Dp = 12.dp,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()

    val outlineColor = if (dragging) AppColors.Warning else AppColors.Focus
    val outlineWidth = if (dragging) FocusSpec.DragOutlineWidth else FocusSpec.OutlineWidth
    val showOutline = isFocused || dragging

    TvClickableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = OsdFocus.tileColors(),
        // Border is drawn below, over the content — see the class docs.
        border = OsdFocus.noBorder(),
        scale = OsdFocus.noScale(),
        interactionSource = source
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (showOutline) {
                        val stroke = outlineWidth.toPx()
                        val r = cornerRadius.toPx()
                        drawRoundRect(
                            color = outlineColor,
                            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
                            size = androidx.compose.ui.geometry.Size(
                                size.width - stroke,
                                size.height - stroke
                            ),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = stroke)
                        )
                    }
                }
        ) {
            content(isFocused)
        }
    }
}
