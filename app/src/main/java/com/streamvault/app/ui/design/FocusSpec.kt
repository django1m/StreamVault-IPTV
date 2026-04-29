package com.streamvault.app.ui.design

import androidx.compose.ui.unit.dp

object FocusSpec {
    const val FocusedScale = 1.06f
    const val PressedScale = 0.98f
    val BorderWidth = 3.dp
    val CardBorderWidth = 4.dp

    // Cinematic focus — amplified scale, wider halo. Only on hero surfaces and cards
    // where over-enlargement won't disturb neighbours (i.e. not dense grids).
    const val FocusedScaleCinematic = 1.08f
    const val PressedScaleCinematic = 0.96f
    val GlowRadius = 16.dp
    val GlowSpread = 8.dp
    val GlowInnerRadius = 4.dp
}
