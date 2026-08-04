package com.streamvault.app.ui.design

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Glass surface intensity. `Low` is the safe default on every device; `Medium` and
 * `High` pile more tint / border strength for hero chrome. The actual blur is opt-in
 * per component via `GlassBlurSurface` (see components/cinematic/GlassSurface.kt),
 * because full-screen blur is too costly on Fire TV Lite.
 */
enum class GlassLevel { Low, Medium, High }

/**
 * Hardware tier used to scale cinematic effects. The classification considers RAM,
 * SoC family and manufacturer hints. See [resolveGlassProfile].
 *
 *  - [Premium] : 3 GB+ RAM, modern SoC (Google TV Streamer, Shield, recent Mi boxes).
 *    Full effects: blur, halo, shadow, glass Medium.
 *  - [Mid]     : 2 GB RAM modern SoC (Amlogic S905X4/X5/X5M, Rockchip 3566/3568).
 *    Blur off (GPU-costly on Mali-G310), halo on (near-free), shadow reduced,
 *    glass Medium. Coil cache trimmed to ~10 %.
 *  - [Low]     : 1–1.5 GB RAM, Amazon Fire TV Lite class or very old A53 boxes.
 *    All GPU effects off, glass Low, minimal elevation, tightest Coil budget.
 */
enum class DeviceClass { Premium, Mid, Low }

/**
 * Per-device profile. Consumers read [deviceClass] for tier-specific logic;
 * the booleans are derived shortcuts kept for readability in call sites.
 */
@Immutable
data class GlassProfile(
    val deviceClass: DeviceClass,
    val allowBlur: Boolean,
    val allowHalo: Boolean,
    val allowShadow: Boolean,
    val defaultLevel: GlassLevel
) {
    companion object {
        val Default = GlassProfile(
            deviceClass = DeviceClass.Premium,
            allowBlur = true,
            allowHalo = true,
            allowShadow = true,
            defaultLevel = GlassLevel.Medium
        )
    }
}

val LocalGlassProfile = staticCompositionLocalOf { GlassProfile.Default }

/**
 * Derives a device profile from runtime info. Called once at app start and
 * provided via CompositionLocal.
 *
 * Classification order (first match wins) :
 *  1. **Low** — `ActivityManager.isLowRamDevice`, Amazon (Fire TV Lite / Stick),
 *     RAM ≤ 1800 MB, or a legacy S905 variant (S905X, S905X2, S912 without
 *     modern suffix). These boxes genuinely struggle with Compose rich UIs.
 *  2. **Mid** — modern Amlogic (S905X4, S905X5, S905X5M, S905Y4),
 *     Rockchip (RK3566/3568), Allwinner (H618), or RAM 1801–2800 MB.
 *     Capable of Compose + glow but blur is costly and the RAM budget is tight.
 *  3. **Premium** — everything else (Shield, Google TV Streamer, Xiaomi TV Pro
 *     3+ GB, Apple TV class, flagship phones).
 */
fun resolveGlassProfile(context: Context): GlassProfile {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val isLowRam = am?.isLowRamDevice == true
    val amazon = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    val totalRamMb = runCatching {
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        (info.totalMem / (1024L * 1024L)).toInt()
    }.getOrDefault(0)
    val haystack = socHaystack()
    val legacyAmlogic = haystack.matchesAny(
        "s905x2", "s905x3", "s905y2", "s905d", "s905l", "s912"
    )
    val modernAmlogic = haystack.matchesAny(
        "s905x4", "s905x5", "s905x5m", "s905y4", "s905c3"
    )
    val midRockchip = haystack.matchesAny(
        "rk3566", "rk3568", "rk3588"
    )
    val cheapRockchip = haystack.matchesAny("rk3318", "rk3328", "rk3399")
    val midAllwinner = haystack.matchesAny("h616", "h618", "h313")
    val genericAmlogic = haystack.contains("amlogic") && !modernAmlogic

    val isLow = isLowRam || amazon || totalRamMb in 1..1800 ||
        legacyAmlogic || cheapRockchip || genericAmlogic
    val isMid = !isLow && (
        modernAmlogic || midRockchip || midAllwinner || totalRamMb in 1801..2800
    )
    val deviceClass = when {
        isLow -> DeviceClass.Low
        isMid -> DeviceClass.Mid
        else -> DeviceClass.Premium
    }
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        deviceClass == DeviceClass.Premium
    return GlassProfile(
        deviceClass = deviceClass,
        allowBlur = canBlur,
        allowHalo = deviceClass != DeviceClass.Low,
        allowShadow = deviceClass != DeviceClass.Low,
        defaultLevel = if (deviceClass == DeviceClass.Low) GlassLevel.Low else GlassLevel.Medium
    )
}

@Composable
fun rememberGlassProfile(): GlassProfile {
    val context = LocalContext.current
    return remember(context) { resolveGlassProfile(context) }
}

private fun socHaystack(): String = listOf(
    Build.HARDWARE,
    Build.BOARD,
    Build.DEVICE,
    Build.PRODUCT,
    Build.MODEL
).joinToString(separator = " ") { it.orEmpty().lowercase() }

private fun String.matchesAny(vararg needles: String): Boolean =
    needles.any { this.contains(it) }

private fun tintOf(level: GlassLevel): Color = when (level) {
    GlassLevel.Low -> Color(0x801D2E46)
    GlassLevel.Medium -> Color(0x661D2E46)
    GlassLevel.High -> Color(0x4D0F1B29)
}

private fun accentOf(level: GlassLevel): Color = when (level) {
    GlassLevel.Low -> AppColors.GlassLight
    GlassLevel.Medium -> AppColors.GlassMedium
    GlassLevel.High -> AppColors.GlassHeavy
}

/**
 * Alpha-only glass surface. Fast on any GPU. Applies a translucent dark tint with a
 * subtle top-edge sheen and a hairline border. Pair with a [HeroBackdrop] or any
 * image/gradient parent to get the "frosted" feel without actually blurring.
 *
 * Do NOT wrap large scrollable containers in this modifier — it repaints on every
 * frame. It is meant for chrome pieces (top bar, sidebars, dialogs, chips).
 */
fun Modifier.glassSurface(
    level: GlassLevel = GlassLevel.Medium,
    shape: Shape = RoundedCornerShape(22.dp),
    borderColor: Color = AppColors.GlassBorder
): Modifier = composed {
    val tint = tintOf(level)
    val accent = accentOf(level)
    val gradient = remember(level) {
        Brush.verticalGradient(
            0f to accent,
            0.15f to tint,
            1f to tint
        )
    }
    this
        .background(gradient, shape)
        .border(BorderStroke(1.dp, borderColor), shape)
}

/**
 * Convenience to add ~content-safe padding inside a glass chip / card.
 */
fun Modifier.glassInnerPadding(): Modifier = this.padding(horizontal = 18.dp, vertical = 12.dp)
