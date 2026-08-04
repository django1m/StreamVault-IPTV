package com.streamvault.app.ui.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import com.streamvault.app.BuildConfig
import com.streamvault.app.R

/**
 * True while the activity is in Picture-in-Picture.
 *
 * Read by the player to strip every piece of chrome: in a ~200dp floating window
 * there is room for the video and nothing else, and PiP has no touch targets
 * beyond the system's own action buttons.
 */
val LocalIsInPip = staticCompositionLocalOf { false }

/** Actions surfaced on the PiP window. Android allows at most 3. */
private const val ACTION_PIP_CONTROL = "com.streamvault.app.PIP_CONTROL"
private const val EXTRA_CONTROL = "control"
const val PIP_CONTROL_PLAY_PAUSE = 1
const val PIP_CONTROL_REWIND = 2
const val PIP_CONTROL_FORWARD = 3

/**
 * Android rejects PiP aspect ratios outside roughly 1:2.39 … 2.39:1 with an
 * IllegalArgumentException, so every computed ratio is clamped rather than
 * trusted — an odd stream (or a 0×0 frame before the first decode) would
 * otherwise crash the app on entering PiP.
 */
private const val MIN_ASPECT = 1f / 2.39f
private const val MAX_ASPECT = 2.39f

/**
 * Owns everything Picture-in-Picture for [MainActivity][com.streamvault.app.MainActivity].
 *
 * Mobile only: `supportsPictureInPicture` is declared in the mobile manifest
 * alone, and a TV box has no PiP affordance, so every entry point here is a
 * no-op on the TV build.
 */
class PictureInPictureController(private val activity: Activity) {

    private var videoAspect: Rational? = null
    private var sourceRect: Rect? = null
    private var isPlaying: Boolean = false
    private var playerVisible: Boolean = false
    private var receiver: BroadcastReceiver? = null
    private var onControl: ((Int) -> Unit)? = null

    /**
     * PiP needs the feature flag AND API 26. The mobile-flavor check keeps the TV
     * build entirely out of this path even if the code were reached.
     */
    val isSupported: Boolean by lazy {
        BuildConfig.APP_VARIANT == "mobile" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /** Called by the player with the decoded frame size; 0×0 is ignored. */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val ratio = width.toFloat() / height.toFloat()
        val clamped = ratio.coerceIn(MIN_ASPECT, MAX_ASPECT)
        // Rational from a float keeps the clamping honest without integer
        // overflow on unusual sizes.
        val next = Rational((clamped * 1000).toInt(), 1000)
        if (next != videoAspect) {
            videoAspect = next
            syncParams()
        }
    }

    /**
     * Bounds of the video surface on screen. Android animates from this rectangle
     * into the PiP window; without it the transition jumps.
     */
    fun setSourceRect(rect: Rect?) {
        if (rect != sourceRect) {
            sourceRect = rect
            syncParams()
        }
    }

    /**
     * Drives auto-enter. On API 31+ the system takes the app into PiP by itself
     * when the user swipes home — [Activity.onUserLeaveHint] is NOT reliably
     * delivered with gesture navigation, which is why auto-enter exists.
     */
    fun setPlayerState(visible: Boolean, playing: Boolean) {
        if (visible == playerVisible && playing == isPlaying) return
        playerVisible = visible
        isPlaying = playing
        syncParams()
    }

    /** Manual entry, used as the pre-API-31 fallback from onUserLeaveHint(). */
    fun enterIfPlaying(): Boolean {
        if (!isSupported || !playerVisible || !isPlaying) return false
        if (activity.isInPictureInPictureMode) return false
        return try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(buildParams())
            } else {
                false
            }
        } catch (e: IllegalStateException) {
            // Thrown when the activity is not in a state that permits PiP
            // (e.g. already finishing). Never worth crashing the app over.
            android.util.Log.w("PipController", "enterPictureInPictureMode refused: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("PipController", "invalid PiP params: ${e.message}")
            false
        }
    }

    /** Pushes the current params so auto-enter and the action row stay accurate. */
    fun syncParams() {
        if (!isSupported) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            activity.setPictureInPictureParams(buildParams())
        } catch (e: IllegalStateException) {
            android.util.Log.w("PipController", "setPictureInPictureParams refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("PipController", "invalid PiP params: ${e.message}")
        }
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        videoAspect?.let { builder.setAspectRatio(it) }
        sourceRect?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system enters PiP on its own only while the player is on
            // screen and actually playing — never from a browsing screen.
            builder.setAutoEnterEnabled(playerVisible && isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        builder.setActions(buildActions())
        return builder.build()
    }

    private fun buildActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        return listOf(
            remoteAction(
                PIP_CONTROL_REWIND,
                android.R.drawable.ic_media_rew,
                activity.getString(R.string.player_gesture_rewind)
            ),
            remoteAction(
                PIP_CONTROL_PLAY_PAUSE,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                activity.getString(
                    if (isPlaying) R.string.player_pause else R.string.player_play
                )
            ),
            remoteAction(
                PIP_CONTROL_FORWARD,
                android.R.drawable.ic_media_ff,
                activity.getString(R.string.player_gesture_forward)
            )
        )
    }

    private fun remoteAction(control: Int, iconRes: Int, label: String): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_CONTROL, control)
        val pending = PendingIntent.getBroadcast(
            activity,
            control,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(activity, iconRes), label, label, pending)
    }

    /**
     * Registers the receiver backing the PiP action buttons. Registered at
     * runtime and NOT_EXPORTED: these controls are only ever triggered by our own
     * PendingIntents, so no other app should be able to drive playback.
     */
    fun registerControls(onControl: (Int) -> Unit) {
        if (!isSupported || receiver != null) return
        this.onControl = onControl
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_PIP_CONTROL) return
                val control = intent.getIntExtra(EXTRA_CONTROL, -1)
                if (control > 0) this@PictureInPictureController.onControl?.invoke(control)
            }
        }
        ContextCompat.registerReceiver(
            activity,
            r,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = r
    }

    fun unregisterControls() {
        receiver?.let {
            runCatching { activity.unregisterReceiver(it) }
            receiver = null
        }
        onControl = null
    }
}
