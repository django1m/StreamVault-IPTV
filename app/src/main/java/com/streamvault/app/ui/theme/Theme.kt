package com.streamvault.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppShapes
import com.streamvault.app.ui.design.AppSpacing
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalAppSpacing
import com.streamvault.app.ui.design.LocalGlassProfile
import com.streamvault.app.ui.design.rememberAppHeroStyles
import com.streamvault.app.ui.design.rememberAppTypography
import com.streamvault.app.ui.design.rememberGlassProfile

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Brand,
    onPrimary = OnPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceElevated,
    onSurfaceVariant = AppColors.TextSecondary,
    background = AppColors.CanvasElevated,
    onBackground = AppColors.TextPrimary,
    error = AppColors.Live,
    onError = OnPrimary
)

@Composable
fun StreamVaultTheme(content: @Composable () -> Unit) {
    val typography = rememberAppTypography()
    val heroStyles = rememberAppHeroStyles()
    val glassProfile = rememberGlassProfile()
    CompositionLocalProvider(
        LocalAppSpacing provides AppSpacing(),
        LocalAppShapes provides AppShapes(),
        LocalAppHeroStyles provides heroStyles,
        LocalGlassProfile provides glassProfile
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = typography,
            content = content
        )
    }
}
