package com.streamvault.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Full-bleed legibility scrim behind a chrome bar.
 *
 * Separate from [PlayerGlassBar] on purpose: the scrim has to reach the screen edges
 * (a gradient that stops where the panel stops draws a visible seam over video), while
 * the glass panel itself is inset. Two nodes, two jobs.
 *
 * This is also *the* reason the chrome stays readable at all. The target box
 * (Amlogic S905X5M, 2 GB) resolves to [com.streamvault.app.ui.design.DeviceClass.Mid],
 * so `allowBlur == false` — there is no real backdrop blur to lean on, and a bright
 * video frame would otherwise swallow white text. Never swap this for `Modifier.blur`.
 */
fun Modifier.osdScrim(fromTop: Boolean): Modifier {
    // Weighted stops, not an even fade. With no card behind the content, this gradient is
    // the *only* thing keeping white text off a white frame, and a linear ramp leaves the
    // channel line — which sits near the top of the bar — on almost nothing. The ramp
    // reaches most of its density in the first 45 %, then holds; the far edge still melts
    // into the picture, which is the whole point of not using a card.
    val stops = if (fromTop) {
        arrayOf(
            0f to AppColors.Canvas.copy(alpha = 0.92f),
            0.55f to AppColors.Canvas.copy(alpha = 0.42f),
            1f to Color.Transparent
        )
    } else {
        arrayOf(
            0f to Color.Transparent,
            0.45f to AppColors.Canvas.copy(alpha = 0.66f),
            1f to AppColors.Canvas.copy(alpha = 0.95f)
        )
    }
    return this.background(Brush.verticalGradient(colorStops = stops))
}
