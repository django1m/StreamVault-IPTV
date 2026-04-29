package com.streamvault.app.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

data class AppShapes(
    val small: RoundedCornerShape = RoundedCornerShape(12.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(18.dp),
    val large: RoundedCornerShape = RoundedCornerShape(24.dp),
    val xSmall: RoundedCornerShape = RoundedCornerShape(8.dp),
    val xLarge: RoundedCornerShape = RoundedCornerShape(28.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
    // Cinematic shapes — used by hero, glass surfaces and VOD poster heroes.
    val hero: RoundedCornerShape = RoundedCornerShape(32.dp),
    val glass: RoundedCornerShape = RoundedCornerShape(22.dp),
    val posterHero: RoundedCornerShape = RoundedCornerShape(24.dp)
)

val LocalAppShapes = staticCompositionLocalOf { AppShapes() }
