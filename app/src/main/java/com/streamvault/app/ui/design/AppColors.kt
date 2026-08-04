package com.streamvault.app.ui.design

import androidx.compose.ui.graphics.Color

object AppColors {
    val Canvas = Color(0xFF07111B)
    val CanvasElevated = Color(0xFF0B1622)
    val Surface = Color(0xFF0F1B29)
    val SurfaceElevated = Color(0xFF162338)
    val SurfaceEmphasis = Color(0xFF1D2E46)
    val SurfaceAccent = Color(0xFF223754)

    val Brand = Color(0xFF69A8FF)
    val BrandMuted = Color(0x335FA4FF)
    val BrandStrong = Color(0xFF8BBCFF)
    val Focus = Color(0xFFF4F8FF)

    // ── Langage OSD ───────────────────────────────────────────────────────────
    // Focus above *is* the slab: rows and menu items fill with it and flip their
    // content to FocusOnFill. Without that inverse token every conversion to a
    // slab silently produces white on white, and nothing in the type system says so.
    val FocusOnFill = Canvas
    val FocusOnFillDim = Canvas.copy(alpha = 0.62f)

    /**
     * The ACTIVE item — the channel currently playing, the selected setting. It is a
     * text colour, never a background: a filled row already means "focused here", and
     * using a fill for both makes the two states indistinguishable at 3 metres.
     */
    val ActiveText = Brand

    /** Resting glyph: bright enough to read, dim enough that the focused one wins. */
    val IconRest = Color(0xD1F5F7FB)

    /** Ambient base. Deep teal rather than another navy so the gradient actually reads. */
    val CanvasTeal = Color(0xFF06181C)

    val TextPrimary = Color(0xFFF5F7FB)
    val TextSecondary = Color(0xFFBBC6D8)
    val TextTertiary = Color(0xFF7F8DA5)
    val TextDisabled = Color(0xFF566173)

    val Live = Color(0xFFFF5C61)
    val Success = Color(0xFF4FD39A)
    val Warning = Color(0xFFFFC766)
    val Info = Color(0xFF57C9FF)

    val Divider = Color(0x1AF4F8FF)
    val Outline = Color(0x264C6D95)

    val HeroTop = Color(0xCC07111B)
    val HeroBottom = Color(0xF207111B)

    // Cinematic accents — used by chips, highlights, progress bars in alternate contexts.
    val AccentPlum = Color(0xFFB478FF)
    val AccentCoral = Color(0xFFFF8A65)
    val AccentMint = Color(0xFF5EE3A1)
    val AccentGold = Color(0xFFFFD479)

    // Glass tints — applied on top of backdrops / video. Low alpha deliberately so
    // we do not tint the underlying artwork into a monotone.
    val GlassLight = Color(0x14FFFFFF)
    val GlassMedium = Color(0x1FFFFFFF)
    val GlassHeavy = Color(0x33FFFFFF)
    val GlassBorder = Color(0x33FFFFFF)

    // Focus / hover glows — radial gradient tints drawn behind focused cards.
    val GlowWarm = Color(0x33FFC766)
    val GlowCool = Color(0x336FB2FF)
    val GlowBrand = Color(0x5569A8FF)

    // Scrim gradient stops — used over hero backdrops to keep text legible.
    val ScrimTop = Color(0xE005080E)
    val ScrimMiddle = Color(0x5005080E)
    val ScrimBottom = Color(0xF205080E)

    // Dialog / modal backgrounds.
    val OverlayDim = Color(0x99000000)

    // Ambient background default (used when palette extraction fails).
    val BackdropTint = Color(0xCC07111B)
}
