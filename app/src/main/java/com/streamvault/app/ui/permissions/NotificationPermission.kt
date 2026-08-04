package com.streamvault.app.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.streamvault.app.BuildConfig

/**
 * Asks for POST_NOTIFICATIONS once, on the mobile build only.
 *
 * The permission is declared in `src/mobile/AndroidManifest.xml` but, since
 * API 33, declaring it is not enough — without an explicit runtime grant the
 * app's notifications are silently dropped. That would quietly break the update
 * download progress and the recording foreground service on every modern phone.
 *
 * Not requested on TV: Android TV grants notification posting implicitly and
 * there is no system dialog to show, so this composable does nothing there.
 *
 * [shouldAsk] lets the caller hold the prompt back until the user is past
 * activation — a permission dialog on the very first frame, before the app has
 * explained anything, is the classic way to get denied forever.
 */
@Composable
fun RequestNotificationPermission(shouldAsk: Boolean) {
    if (BuildConfig.APP_VARIANT != "mobile") return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    // rememberSaveable: surviving rotation matters, otherwise turning the phone
    // while the dialog is up would fire a second request.
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }
    val granted = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declined is fine — nothing in the app is gated on notifications. */ }

    LaunchedEffect(shouldAsk, granted, alreadyAsked) {
        if (shouldAsk && !granted && !alreadyAsked) {
            alreadyAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
