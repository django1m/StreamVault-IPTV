package com.streamvault.app.ui.components.cinematic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppShapes
import com.streamvault.app.ui.design.GlassLevel
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.glassSurface

/**
 * Alpha-blended glass container. Use for top bars, sidebars, dialogs, chip rows,
 * and anything that sits on top of a hero backdrop or an artwork-driven ambient
 * background. For scrollable containers keep the parent un-glass and glass only
 * the chrome pieces — otherwise the blend is redrawn every frame.
 *
 * Never wrap a `LazyRow`/`LazyColumn` body in this.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Medium,
    shape: Shape = LocalAppShapes.current.glass,
    borderColor: Color = AppColors.GlassBorder,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.glassSurface(level = level, shape = shape, borderColor = borderColor),
        content = content
    )
}

/**
 * Convenience overload that darkens the tint deliberately — used for dialogs and
 * options panels sitting over bright backdrops, where readability beats fidelity.
 */
@Composable
fun GlassDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = LocalAppShapes.current.large,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.glassSurface(
            level = GlassLevel.High,
            shape = shape,
            borderColor = AppColors.GlassBorder
        ),
        content = content
    )
}

internal fun dialogScrim(): Color = AppColors.OverlayDim.compositeOver(AppColors.Canvas)
