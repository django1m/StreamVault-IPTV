package com.streamvault.app.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mood palette extracted from a hero artwork. All colours pre-mixed onto the
 * app canvas so consumers can render directly without extra blending.
 */
@Immutable
data class AmbientPalette(
    val accent: Color,
    val surface: Color,
    val shadow: Color,
    val vibrant: Color,
    val muted: Color
) {
    companion object {
        val Default = AmbientPalette(
            accent = AppColors.Brand,
            surface = AppColors.CanvasElevated,
            shadow = AppColors.Canvas,
            vibrant = AppColors.BrandStrong,
            muted = AppColors.SurfaceAccent
        )
    }
}

private val paletteCache = LruCache<String, AmbientPalette>(32)

/**
 * Extracts an [AmbientPalette] from an image URL. Downsamples to 128px via Coil to
 * keep extraction cheap (palette is O(pixels)). Safe to call from any thread — the
 * Palette step runs on the Default dispatcher.
 *
 * Returns [AmbientPalette.Default] if the URL is null, the fetch fails, or the
 * bitmap produced cannot be sampled.
 */
suspend fun ambientPaletteFrom(
    imageUrl: String?,
    context: Context,
    imageLoader: ImageLoader
): AmbientPalette {
    if (imageUrl.isNullOrBlank()) return AmbientPalette.Default
    paletteCache.get(imageUrl)?.let { return it }
    return runCatching {
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(Size(128, 128))
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.image as? BitmapImage
            ?: return AmbientPalette.Default
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap.bitmap).maximumColorCount(16).generate()
        }
        val extracted = palette.toAmbient()
        paletteCache.put(imageUrl, extracted)
        extracted
    }.getOrDefault(AmbientPalette.Default)
}

private fun Palette.toAmbient(): AmbientPalette {
    val vibrantSwatch = vibrantSwatch ?: lightVibrantSwatch ?: darkVibrantSwatch
    val mutedSwatch = mutedSwatch ?: darkMutedSwatch ?: lightMutedSwatch
    val dominantSwatch = dominantSwatch

    val accentRgb = vibrantSwatch?.rgb
        ?: dominantSwatch?.rgb
        ?: return AmbientPalette.Default

    val accent = Color(accentRgb)
    val vibrant = vibrantSwatch?.rgb?.let(::Color) ?: accent
    val muted = mutedSwatch?.rgb?.let(::Color) ?: accent.darken(0.4f)
    val surface = accent.darken(0.72f).blend(AppColors.CanvasElevated, 0.55f)
    val shadow = accent.darken(0.88f).blend(AppColors.Canvas, 0.7f)
    return AmbientPalette(
        accent = accent.clampSaturation(),
        surface = surface,
        shadow = shadow,
        vibrant = vibrant,
        muted = muted
    )
}

private fun Color.darken(amount: Float): Color {
    val k = (1f - amount).coerceIn(0f, 1f)
    return Color(red * k, green * k, blue * k, alpha)
}

private fun Color.blend(other: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = red * (1f - r) + other.red * r,
        green = green * (1f - r) + other.green * r,
        blue = blue * (1f - r) + other.blue * r,
        alpha = alpha
    )
}

private fun Color.clampSaturation(max: Float = 0.85f): Color {
    val maxCh = maxOf(red, green, blue)
    val minCh = minOf(red, green, blue)
    val range = maxCh - minCh
    if (range <= max) return this
    val mid = (maxCh + minCh) * 0.5f
    val scale = max / range
    return Color(
        red = mid + (red - mid) * scale,
        green = mid + (green - mid) * scale,
        blue = mid + (blue - mid) * scale,
        alpha = alpha
    )
}
