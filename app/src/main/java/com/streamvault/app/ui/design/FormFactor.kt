package com.streamvault.app.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.streamvault.app.device.isTelevisionDevice

/**
 * How the UI should be laid out and driven.
 *
 * This is the single branch point between the 10-foot D-pad experience and the
 * touch one. It is deliberately *not* derived from [com.streamvault.app.BuildConfig]
 * `APP_VARIANT`: the flavor decides what ships in the APK (manifest, theme),
 * while this decides how the running device is actually being used. A tablet
 * running the mobile build and a phone mirrored to a screen both get the right
 * answer, and the TV build keeps behaving exactly as before on a box.
 */
enum class FormFactor { Tv, Tablet, Phone }

/**
 * Defaults to [FormFactor.Tv] on purpose: a composable used outside
 * [com.streamvault.app.ui.theme.StreamVaultTheme] keeps today's behaviour rather
 * than silently switching to a touch layout.
 */
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Tv }

/**
 * Resolves the form factor once per configuration.
 *
 * Uses `smallestScreenWidthDp`, not `screenWidthDp`: it is **invariant to
 * rotation**, so turning a phone never flips the form factor. That matters
 * because [LocalFormFactor] is a `staticCompositionLocalOf` — a change
 * invalidates the whole subtree, and we do not want a full structural
 * recomposition on every rotation.
 *
 * 600dp is the conventional tablet threshold (`sw600dp`).
 */
@Composable
fun rememberFormFactor(): FormFactor {
    val context = LocalContext.current
    val smallestWidthDp = LocalConfiguration.current.smallestScreenWidthDp
    return remember(context, smallestWidthDp) {
        when {
            context.isTelevisionDevice() -> FormFactor.Tv
            smallestWidthDp >= 600 -> FormFactor.Tablet
            else -> FormFactor.Phone
        }
    }
}

/** True for phone and tablet — i.e. anything driven by a finger rather than a D-pad. */
val FormFactor.isTouch: Boolean
    get() = this != FormFactor.Tv
