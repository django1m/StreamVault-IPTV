package com.streamvault.app.ui.interaction

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.design.LocalFormFactor
import com.streamvault.app.ui.design.isTouch
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonGlow
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ButtonShape
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.IconButton
import androidx.tv.material3.Surface

/**
 * Material's minimum touch target. TV Material enforces no minimum — its targets
 * are sized for a focus ring, not a fingertip — so anything smaller than this is
 * unhittable on a phone. Only widens; larger components are untouched.
 */
private val MinTouchTarget = 48.dp

/**
 * Enlarges a control to [MinTouchTarget] on touch devices, no-op on TV.
 *
 * Kept as a modifier rather than a wrapper Box so it composes with whatever
 * layout modifiers the caller already passed.
 */
@Composable
private fun Modifier.touchTargetSize(): Modifier =
    if (LocalFormFactor.current.isTouch) sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget) else this

/**
 * Drop-in replacement for TV Material3 Surface(onClick) that automatically adds
 * [mouseClickable] to the modifier so the first finger-tap fires onClick on phones/tablets,
 * while D-pad and mouse navigation on TV remain unchanged.
 */
@Composable
fun TvClickableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    // TV Material's own default is focusedScale = 1.1f, so a call site that simply omits
    // `scale` silently opts into growing on focus. 75 of the 134 call sites outside the
    // player did exactly that. Nothing in this language grows: the slab and the outline
    // carry focus, and enlargement costs a layout pass plus zIndex juggling to avoid
    // being clipped by neighbours.
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(
        focusedScale = 1f,
        pressedScale = FocusSpec.PressedScale
    ),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .touchTargetSize()
            .mouseClickable(onClick = onClick, enabled = enabled, onLongClick = onLongClick),
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        scale = scale,
        glow = glow,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Drop-in replacement for TV Material3 Button(onClick) that automatically adds
 * [mouseClickable] so the first finger-tap fires onClick on phones/tablets.
 */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scale: ButtonScale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = FocusSpec.PressedScale),
    glow: ButtonGlow = ButtonDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    shape: ButtonShape = ButtonDefaults.shape(),
    colors: ButtonColors = ButtonDefaults.colors(),
    border: ButtonBorder = ButtonDefaults.border(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .touchTargetSize()
            .mouseClickable(onClick = onClick, enabled = enabled),
        enabled = enabled,
        scale = scale,
        glow = glow,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Drop-in replacement for TV Material3 IconButton(onClick) that automatically adds
 * [mouseClickable] so the first finger-tap fires onClick on phones/tablets.
 */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .touchTargetSize()
            .mouseClickable(onClick = onClick, enabled = enabled),
        enabled = enabled,
        content = content,
    )
}
