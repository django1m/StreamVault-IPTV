package com.streamvault.app.ui.design

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.streamvault.app.R
import java.util.Locale

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private val LocaleSafeSansFamily = FontFamily.SansSerif

@Composable
fun rememberAppTypography(): Typography {
    val context = LocalContext.current
    val locale = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale ?: Locale.getDefault()
        }
    }
    return remember(locale.toLanguageTag()) {
        appTypographyFor(locale)
    }
}

private fun appTypographyFor(locale: Locale): Typography {
    val fontFamily = if (locale.language.equals("en", ignoreCase = true)) {
        InterFamily
    } else {
        LocaleSafeSansFamily
    }
    return createTypography(fontFamily)
}

private fun createTypography(fontFamily: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp
    ),
    displayMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    displaySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp
    )
)

/**
 * Cinematic hero type scale. Kept separate from [Typography] because the Material
 * typography set has fixed slots — these belong on hero backdrops, carousel titles
 * and marquee metadata, not generic content.
 *
 * Inter ships Normal / Medium / SemiBold / Bold in resources; ExtraBold falls back
 * to Bold via Compose synthesis when requested.
 */
@Immutable
data class AppHeroStyles(
    val heroTitle: TextStyle,
    val heroSubtitle: TextStyle,
    val heroEyebrow: TextStyle,
    val heroMeta: TextStyle,
    val sectionTitle: TextStyle,
    val sectionEyebrow: TextStyle
)

@Composable
fun rememberAppHeroStyles(): AppHeroStyles {
    val context = LocalContext.current
    val locale = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale ?: Locale.getDefault()
        }
    }
    return remember(locale.toLanguageTag()) {
        val family = if (locale.language.equals("en", ignoreCase = true)) InterFamily else LocaleSafeSansFamily
        AppHeroStyles(
            heroTitle = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 44.sp,
                lineHeight = 50.sp,
                letterSpacing = (-0.8).sp
            ),
            heroSubtitle = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                letterSpacing = 0.1.sp
            ),
            heroEyebrow = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                letterSpacing = 2.sp
            ),
            heroMeta = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.4.sp
            ),
            sectionTitle = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.2).sp
            ),
            sectionEyebrow = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.8.sp
            )
        )
    }
}

val LocalAppHeroStyles = staticCompositionLocalOf<AppHeroStyles> {
    error("AppHeroStyles not provided — wrap your UI in StreamVaultTheme.")
}
