package com.streamvault.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import com.streamvault.app.ui.accessibility.rememberReducedMotionEnabled
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppShapes
import com.streamvault.app.ui.design.appSpacingFor
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalAppSpacing
import com.streamvault.app.ui.design.LocalFormFactor
import com.streamvault.app.ui.design.LocalGlassProfile
import com.streamvault.app.ui.design.LocalReducedMotion
import com.streamvault.app.ui.design.isTouch
import com.streamvault.app.ui.design.rememberFormFactor
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
    val reducedMotion = rememberReducedMotionEnabled()
    val formFactor = rememberFormFactor()
    val m3Typography = remember(typography) { typography.toMaterial3() }
    CompositionLocalProvider(
        LocalFormFactor provides formFactor,
        LocalAppSpacing provides appSpacingFor(formFactor),
        LocalAppShapes provides AppShapes(),
        LocalAppHeroStyles provides heroStyles,
        LocalGlassProfile provides glassProfile,
        LocalReducedMotion provides reducedMotion
    ) {
        val tvThemedContent: @Composable () -> Unit = {
            MaterialTheme(
                colorScheme = DarkColorScheme,
                typography = typography,
                content = content
            )
        }

        // The standard Material 3 theme is provided on TOUCH DEVICES ONLY.
        //
        // The two libraries expose distinct CompositionLocals, so nesting them is
        // unambiguous — every composable reads the one from its own package. The
        // mobile layer needs the M3 tree because NavigationBar, ModalBottomSheet,
        // TopAppBar, PullToRefreshBox and Scaffold exist only there; without it
        // they would render on M3's default purple baseline.
        //
        // It is NOT provided on TV, deliberately. The app already mixes in a few
        // plain-M3 components (12 Switch and 3 AlertDialog pass no explicit
        // colors), so providing the brand scheme there would visibly restyle the
        // shipping TV product — arguably a fix, but not something this work
        // should decide. Flip the condition to `true` to unify TV as well.
        if (formFactor.isTouch) {
            M3MaterialTheme(
                colorScheme = Material3DarkColorScheme,
                typography = m3Typography,
                content = tvThemedContent
            )
        } else {
            tvThemedContent()
        }
    }
}
