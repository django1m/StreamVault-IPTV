package com.streamvault.app.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic elevations for cinematic surfaces. Scaled by [DeviceClass] so the
 * shadows don't starve the compositor on 2 GB / Mali-G310 boxes while still
 * letting premium hardware enjoy the full depth.
 */
@Immutable
data class AppElevation(
    val card: Dp,
    val cardFocused: Dp,
    val hero: Dp,
    val dialog: Dp,
    val topBar: Dp,
    val rail: Dp
)

object AppElevationDefaults {
    /** Premium: Shield / Google TV Streamer / phones. Full depth. */
    val Premium = AppElevation(
        card = 6.dp,
        cardFocused = 18.dp,
        hero = 24.dp,
        dialog = 32.dp,
        topBar = 10.dp,
        rail = 12.dp
    )

    /**
     * Mid tier: Amlogic S905X4/X5/X5M, Rockchip 3566/3568, 2 GB boxes.
     * Halved shadows to keep the cinematic depth readable without piling too
     * many draw calls on the compositor. Still clearly above the flat Low tier.
     */
    val Mid = AppElevation(
        card = 3.dp,
        cardFocused = 8.dp,
        hero = 10.dp,
        dialog = 16.dp,
        topBar = 4.dp,
        rail = 5.dp
    )

    /** Low tier: Fire TV Lite class — flat. */
    val LowSpec = AppElevation(
        card = 0.dp,
        cardFocused = 0.dp,
        hero = 0.dp,
        dialog = 2.dp,
        topBar = 0.dp,
        rail = 0.dp
    )
}

@Composable
fun elevationFor(profile: GlassProfile): AppElevation = when (profile.deviceClass) {
    DeviceClass.Premium -> AppElevationDefaults.Premium
    DeviceClass.Mid -> AppElevationDefaults.Mid
    DeviceClass.Low -> AppElevationDefaults.LowSpec
}
