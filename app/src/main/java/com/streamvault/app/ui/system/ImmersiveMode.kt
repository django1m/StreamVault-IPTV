package com.streamvault.app.ui.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the system bars while [enabled], restoring them on dispose.
 *
 * Only meaningful on touch devices. The TV theme (`src/main/res/values/themes.xml`)
 * sets `windowFullscreen=true`, so a box is already immersive everywhere and this
 * effect is a no-op there — callers pass `enabled = LocalFormFactor.current.isTouch`.
 * The mobile theme deliberately does NOT set `windowFullscreen`, because hiding the
 * status bar while browsing a phone is wrong; immersion belongs to the player only.
 */
@Composable
fun ImmersiveSystemBars(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        // Swipe from an edge brings the bars back transiently, then they auto-hide.
        // Anything else would trap the user with no way back out of the player.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Unwraps the ContextWrapper chain Compose hands us to reach the hosting Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
