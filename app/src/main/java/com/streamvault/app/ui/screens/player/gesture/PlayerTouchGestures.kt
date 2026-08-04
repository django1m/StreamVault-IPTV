package com.streamvault.app.ui.screens.player.gesture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import kotlin.math.abs

/**
 * Which half of the screen a vertical drag started in.
 * Mirrors the convention every mobile video player uses: brightness left,
 * volume right.
 */
enum class VerticalDragZone { Brightness, Volume }

/** What the gesture layer is currently showing in the centre HUD. */
sealed interface PlayerGestureFeedback {
    data class Seek(val deltaSeconds: Int) : PlayerGestureFeedback
    data class Brightness(val fraction: Float) : PlayerGestureFeedback
    data class Volume(val fraction: Float) : PlayerGestureFeedback
}

/** Seek step for a double-tap on the left/right third of the screen. */
const val DOUBLE_TAP_SEEK_SECONDS = 10

/**
 * A horizontal swipe starting within this fraction of either edge is ignored.
 *
 * Android's predictive-back gesture owns the screen edges. Competing with it
 * makes both gestures feel broken, so channel zapping only accepts swipes that
 * begin in the middle 60% of the width. `Modifier.systemGestureExclusion` would
 * be the tidier answer but its availability is not guaranteed here, and a dead
 * zone needs no API at all.
 */
private const val EDGE_DEAD_ZONE = 0.2f

/** Drag distance (px) before a gesture commits to an axis. */
private const val DRAG_SLOP = 24f

/** Screen fraction that maps to a full sweep of volume or brightness. */
private const val VERTICAL_TRAVEL_FOR_FULL_RANGE = 0.7f

/**
 * Installs the whole touch vocabulary of the player on a single node.
 *
 * IMPORTANT: this REPLACES the previous `detectTapGestures` on the player Box —
 * it does not stack on top of it. Two tap detectors on one node fight over the
 * pointer and the second one silently never fires.
 *
 * When [enabled] is false (TV) it installs *only* the plain tap detector, which
 * is exactly what the Box carried before. That path still matters on TV: air-mouse
 * remotes and trackpads deliver taps, and dropping it would silently break
 * click-to-show-controls on those boxes.
 */
fun Modifier.playerTouchGestures(
    enabled: Boolean,
    allowZap: Boolean,
    onSingleTap: () -> Unit,
    onDoubleTapSeek: (deltaSeconds: Int) -> Unit,
    onTogglePlayPause: () -> Unit,
    onVerticalDrag: (VerticalDragZone, delta: Float) -> Unit,
    onDragEnd: () -> Unit,
    onZap: (next: Boolean) -> Unit,
): Modifier {
    if (!enabled) {
        // TV: unchanged from the original implementation. Notably NO onDoubleTap,
        // which would add ~200 ms of latency to every single tap while the
        // detector waits for a possible second one.
        return this.pointerInput(onSingleTap) {
            detectTapGestures { onSingleTap() }
        }
    }

    return this
        .pointerInput(onSingleTap, onDoubleTapSeek, onTogglePlayPause) {
            detectTapGestures(
                onTap = { onSingleTap() },
                onDoubleTap = { offset ->
                    // Left third rewinds, right third fast-forwards, middle
                    // toggles playback — the YouTube/Netflix convention.
                    val third = size.width / 3f
                    when {
                        offset.x < third -> onDoubleTapSeek(-DOUBLE_TAP_SEEK_SECONDS)
                        offset.x > third * 2f -> onDoubleTapSeek(DOUBLE_TAP_SEEK_SECONDS)
                        else -> onTogglePlayPause()
                    }
                }
            )
        }
        .pointerInput(allowZap, onVerticalDrag, onZap, onDragEnd) {
            // One gesture loop for both axes: whichever axis passes the slop
            // first wins, so a slightly diagonal volume swipe does not also zap.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startX = down.position.x
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                val inEdgeDeadZone =
                    startX < width * EDGE_DEAD_ZONE || startX > width * (1f - EDGE_DEAD_ZONE)
                val zone =
                    if (startX < width / 2f) VerticalDragZone.Brightness else VerticalDragZone.Volume

                var axis: Axis? = null
                var horizontalTotal = 0f
                var didDrag = false

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break

                    val delta = change.positionChange()
                    if (axis == null) {
                        if (abs(delta.y) > DRAG_SLOP && abs(delta.y) > abs(delta.x)) {
                            axis = Axis.Vertical
                        } else if (abs(delta.x) > DRAG_SLOP && abs(delta.x) > abs(delta.y)) {
                            axis = Axis.Horizontal
                        }
                    }

                    when (axis) {
                        Axis.Vertical -> {
                            didDrag = true
                            change.consume()
                            // Up should increase, so invert: screen Y grows downwards.
                            val fraction = -delta.y / (height * VERTICAL_TRAVEL_FOR_FULL_RANGE)
                            onVerticalDrag(zone, fraction)
                        }
                        Axis.Horizontal -> {
                            didDrag = true
                            change.consume()
                            horizontalTotal += delta.x
                        }
                        null -> Unit
                    }
                }

                if (axis == Axis.Horizontal && allowZap && !inEdgeDeadZone) {
                    val threshold = width * 0.18f
                    if (horizontalTotal <= -threshold) onZap(true)
                    else if (horizontalTotal >= threshold) onZap(false)
                }
                if (didDrag) onDragEnd()
            }
        }
}

private enum class Axis { Vertical, Horizontal }

/**
 * Applies volume and brightness changes, and reports the resulting levels so the
 * HUD can show them.
 *
 * Brightness is a *window* attribute, not a system setting: it only affects this
 * app and is restored to the system value when the player leaves, which is what
 * users expect from a video app.
 */
@Composable
fun rememberPlayerGestureController(): PlayerGestureController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService<AudioManager>() }
    val controller = remember(activity, audioManager) {
        PlayerGestureController(activity, audioManager)
    }
    DisposableEffect(controller) {
        onDispose { controller.restoreBrightness() }
    }
    return controller
}

class PlayerGestureController(
    private val activity: Activity?,
    private val audioManager: AudioManager?,
) {
    private var brightness: Float = -1f

    /** @return the new level as a 0..1 fraction, or null if unavailable. */
    fun adjustVolume(deltaFraction: Float): Float? {
        val am = audioManager ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + deltaFraction * max).coerceIn(0f, max.toFloat())
        // flags = 0: never raise the system volume UI, it would sit on top of
        // our own HUD and of the video.
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target.toInt(), 0)
        return target / max
    }

    /** @return the new level as a 0..1 fraction, or null if unavailable. */
    fun adjustBrightness(deltaFraction: Float): Float? {
        val window = activity?.window ?: return null
        if (brightness < 0f) {
            val existing = window.attributes.screenBrightness
            // BRIGHTNESS_OVERRIDE_NONE (-1) means "follow the system"; start the
            // gesture from a sane midpoint rather than jumping to 0.
            brightness = if (existing >= 0f) existing else 0.5f
        }
        brightness = (brightness + deltaFraction).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        return brightness
    }

    fun restoreBrightness() {
        val window = activity?.window ?: return
        if (brightness < 0f) return
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        brightness = -1f
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
