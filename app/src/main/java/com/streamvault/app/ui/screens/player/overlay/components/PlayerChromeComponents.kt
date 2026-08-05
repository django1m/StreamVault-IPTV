package com.streamvault.app.ui.screens.player.overlay.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.ui.components.cinematic.GlassSurface
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.design.FormFactor
import com.streamvault.app.ui.design.GlassLevel
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalFormFactor
import com.streamvault.app.ui.design.glassSurface
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.design.osdScrim

/**
 * Sizing constants for the player chrome. Player-specific on purpose — these are not
 * app-wide design tokens, they only make sense for the transport bar.
 */
object PlayerChromeTokens {
    /**
     * Fixed height reserved under every icon button for its label. The label fades in
     * on focus but the slot is ALWAYS occupied, so focusing a button never reflows the
     * row — on a 10-foot UI a 16dp jump under the cursor reads as a glitch.
     */
    val LabelSlot: Dp = 14.dp

    /** Max width of a label before it ellipsises. Keeps long strings from widening the row. */
    val LabelMaxWidth: Dp = 88.dp

    val RowGap: Dp = 8.dp

    /** Separates the transport cluster from the settings cluster inside the single row. */
    val GroupGap: Dp = 22.dp

    /**
     * Opacity of the dark base under the glass gradient. Tuned on the worst real case:
     * a news channel's burned-in lower third sitting exactly where the bar lands. Below
     * ~0.8 that white banner reads straight through and fights the controls; much above
     * ~0.88 the panel stops reading as glass and becomes a slab. The gradient sheen and
     * hairline border on top are what keep it translucent to the eye.
     */
    const val BarBaseOpacity: Float = 0.84f

    // Sized for an appliance-style OSD: the bar is something you glance at over the
    // picture, not a page. Every dp here is one the video does not get.
    @Composable
    @ReadOnlyComposable
    fun iconButton(): Dp = when (LocalFormFactor.current) {
        FormFactor.Phone -> 36.dp
        FormFactor.Tablet -> 38.dp
        FormFactor.Tv -> 40.dp
    }

    @Composable
    @ReadOnlyComposable
    fun primaryButton(): Dp = when (LocalFormFactor.current) {
        FormFactor.Phone -> 44.dp
        FormFactor.Tablet -> 46.dp
        FormFactor.Tv -> 50.dp
    }

    @Composable
    @ReadOnlyComposable
    fun barPadding(): Dp = when (LocalFormFactor.current) {
        FormFactor.Phone -> 12.dp
        FormFactor.Tablet -> 14.dp
        FormFactor.Tv -> 16.dp
    }

    /** Logo badge in the bar header. */
    @Composable
    @ReadOnlyComposable
    fun channelBadge(): Dp = when (LocalFormFactor.current) {
        FormFactor.Phone -> 36.dp
        FormFactor.Tablet -> 40.dp
        FormFactor.Tv -> 44.dp
    }
}

/** Kept as the player's name for the shared scrim; the definition lives in design/OsdScrim.kt. */
fun Modifier.playerChromeScrim(fromTop: Boolean): Modifier = this.osdScrim(fromTop)

/**
 * The glass panel every piece of player chrome sits in.
 *
 * Unlike [GlassSurface] elsewhere in the app, this one lays an opaque-ish base under
 * the glass gradient. `GlassLevel.High` alone is a 30 %-alpha tint, which is fine over
 * the app's own dark backdrops but disappears over a bright video frame — and with
 * `allowBlur == false` on the target box there is no frosting to fall back on. The base
 * guarantees contrast on any content; the gradient and hairline border on top are what
 * still make it read as glass rather than as a slab.
 */
@Composable
fun PlayerGlassBar(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.High,
    shape: Shape = LocalAppShapes.current.xLarge,
    contentPadding: Dp = PlayerChromeTokens.barPadding(),
    baseOpacity: Float = PlayerChromeTokens.BarBaseOpacity,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(AppColors.Canvas.copy(alpha = baseOpacity))
            .glassSurface(level = level, shape = shape)
    ) {
        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Icon-first control for the transport / action rows.
 *
 * Two invariants worth stating because breaking either is invisible until it is on a TV:
 *
 *  1. **[label] is always composed**, only its alpha animates. That keeps the row from
 *     reflowing on focus, and it keeps the node in the semantics tree — `PlayerSmokeTest`
 *     asserts `onNodeWithText("Unmute").assertExists()`, which passes at alpha 0 but
 *     would fail if the Text were composed conditionally.
 *  2. **The hold-OK protocol is byte-for-byte the one from the old `PlayerTransportButton`.**
 *     `repeatCount == 0` is left to `onClick`, `repeatCount == 1` starts the hold, further
 *     repeats are absorbed, `ACTION_UP` stops it — and the `DisposableEffect` stops it
 *     again if the button leaves the composition mid-hold (which is exactly what the
 *     controls auto-hide does), otherwise progressive seek keeps running with nothing
 *     on screen to stop it.
 */
@Composable
fun PlayerIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    selected: Boolean = false,
    /** Play/pause: sized up a notch. It no longer carries a colour — the focus fill does. */
    accent: Boolean = false,
    enabled: Boolean = true,
    size: Dp = PlayerChromeTokens.iconButton(),
    /**
     * Icon colour while unfocused. Only for buttons whose colour carries meaning —
     * record is red because that is universal — never as decoration; a row of
     * differently tinted icons reads as noise rather than as a control surface.
     */
    restingTint: Color? = null,
    onHoldStart: (() -> Unit)? = null,
    onHoldStop: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) FocusSpec.FocusedScaleCinematic else 1f,
        animationSpec = AppMotion.scaleSpring(),
        label = "playerIconScale"
    )
    // Tween, not a spring: sweeping across eight buttons with a spring makes the label
    // flicker as each one over/undershoots.
    val labelAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = AppMotion.focusSpec(),
        label = "playerIconLabelAlpha"
    )
    val labelOffset by animateDpAsState(
        targetValue = if (isFocused) 0.dp else 4.dp,
        animationSpec = AppMotion.dpSpring(),
        label = "playerIconLabelOffset"
    )

    // Bare glyph at rest, solid light fill when focused. A row of filled chips reads as
    // a toolbar in an app; a row of bare glyphs with one lit up reads as an appliance
    // OSD, and it is far quieter over moving picture. `selected` keeps a faint fill so
    // an active toggle (muted, recording) is still legible without focus.
    val container = when {
        selected -> AppColors.GlassMedium
        else -> Color.Transparent
    }
    val focusedContainer = AppColors.Focus
    val iconTint = when {
        isFocused -> AppColors.Canvas
        else -> restingTint ?: AppColors.TextPrimary.copy(alpha = 0.82f)
    }

    val latestOnHoldStart by rememberUpdatedState(onHoldStart)
    val latestOnHoldStop by rememberUpdatedState(onHoldStop)
    var holdActive by remember { mutableStateOf(false) }
    val supportsHold = onHoldStart != null || onHoldStop != null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        TvClickableSurface(
            onClick = onClick,
            enabled = enabled,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = container,
                focusedContainerColor = focusedContainer,
                pressedContainerColor = focusedContainer
            ),
            // The scale is driven by our own graphicsLayer so the halo scales with the
            // button; letting TV Material scale too would compound the two.
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            interactionSource = interactionSource,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .size(size)
                .then(
                    if (supportsHold) {
                        Modifier.onPreviewKeyEvent { event ->
                            when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    when (event.nativeKeyEvent.action) {
                                        android.view.KeyEvent.ACTION_DOWN -> {
                                            if (event.nativeKeyEvent.repeatCount == 0) {
                                                // Tap: leave it to the surface's onClick.
                                                false
                                            } else if (event.nativeKeyEvent.repeatCount == 1 && !holdActive) {
                                                holdActive = true
                                                latestOnHoldStart?.invoke()
                                                true
                                            } else {
                                                // Subsequent repeats absorbed.
                                                true
                                            }
                                        }

                                        android.view.KeyEvent.ACTION_UP -> {
                                            if (holdActive) {
                                                holdActive = false
                                                latestOnHoldStop?.invoke()
                                                true
                                            } else {
                                                false
                                            }
                                        }

                                        else -> false
                                    }
                                }

                                else -> false
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .semantics { this.contentDescription = contentDescription }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(size * 0.46f)
                )
            }
        }

        Box(
            modifier = Modifier
                .height(PlayerChromeTokens.LabelSlot)
                .widthIn(max = PlayerChromeTokens.LabelMaxWidth),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = labelAlpha
                    translationY = labelOffset.toPx()
                }
            )
        }
    }

    if (supportsHold) {
        DisposableEffect(Unit) {
            onDispose {
                if (holdActive) {
                    holdActive = false
                    latestOnHoldStop?.invoke()
                }
            }
        }
    }
}

/**
 * Progress bar shared by LIVE (EPG progress) and VOD (playhead).
 *
 * On TV this is a plain [Canvas]: **non-focusable by construction**. The previous
 * implementation was a Material `Slider` with `focusable(false)` + `enabled = false`,
 * which worked but relied on two negative flags staying in place. Seek on TV is driven
 * by `PlayerScreen`'s KeyEvent handler (DPAD LEFT/RIGHT, long-hold → progressive seek);
 * the bar only mirrors `currentPosition`.
 *
 * On touch there is no D-pad, so the Slider is the only way to seek and the scrubbing
 * state machine is reintroduced — but only on that path ([interactiveSeek]).
 */
@Composable
fun PlayerScrubBar(
    fraction: Float,
    startLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier,
    interactiveSeek: Boolean = false,
    onSeekFraction: (Float) -> Unit = {},
    accentColor: Color = AppColors.Brand,
    trackHeight: Dp = 3.dp
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    // While the finger is down we show the dragged position, not the engine's — otherwise
    // the thumb fights the playhead and jumps back under the finger.
    val target = if (isScrubbing) scrubFraction else fraction.coerceIn(0f, 1f)
    // Tween, never a spring: an overshoot on a progress bar reads as "the seek missed".
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = AppMotion.focusSpec(),
        label = "playerScrubFraction"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = startLabel,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextSecondary,
            fontWeight = FontWeight.Medium
        )

        if (interactiveSeek) {
            Slider(
                value = target,
                onValueChange = { value ->
                    isScrubbing = true
                    scrubFraction = value
                },
                onValueChangeFinished = {
                    onSeekFraction(scrubFraction)
                    isScrubbing = false
                },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = AppColors.GlassMedium
                )
            )
        } else {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(trackHeight * 2.4f)
            ) {
                val trackPx = trackHeight.toPx()
                val centerY = size.height / 2f
                val radius = CornerRadius(trackPx / 2f, trackPx / 2f)
                drawRoundRect(
                    color = AppColors.GlassMedium,
                    topLeft = Offset(0f, centerY - trackPx / 2f),
                    size = Size(size.width, trackPx),
                    cornerRadius = radius
                )
                val filled = size.width * animated.coerceIn(0f, 1f)
                if (filled > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(accentColor.copy(alpha = 0.75f), accentColor),
                            startX = 0f,
                            endX = filled.coerceAtLeast(1f)
                        ),
                        topLeft = Offset(0f, centerY - trackPx / 2f),
                        size = Size(filled, trackPx),
                        cornerRadius = radius
                    )
                    drawCircle(
                        color = AppColors.Focus,
                        radius = trackPx * 1.6f,
                        center = Offset(filled, centerY)
                    )
                }
            }
        }

        Text(
            text = endLabel,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Small capsule for status text (LIVE, channel number, MUTED, aspect ratio…).
 *
 * Promoted out of `PlayerControlsChrome` so the top bar, both chromes and the overlays
 * share one capsule. The strings it renders are asserted by `PlayerSmokeTest`
 * (`onNodeWithText("Muted")`), so it must keep rendering plain [Text].
 */
@Composable
internal fun PlayerMetaPill(
    text: String,
    accent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shape = LocalAppShapes.current.pill
    Box(
        modifier = modifier
            .then(
                if (accent) {
                    Modifier
                        .clip(shape)
                        .background(AppColors.BrandMuted)
                } else {
                    Modifier.glassSurface(level = GlassLevel.Low, shape = shape)
                }
            )
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) AppColors.BrandStrong else AppColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
