package com.streamvault.app.ui.test

import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage

/**
 * Au-dessus : le contour blanc de focus (AppColors.Focus ≈ 0,97). Le bleu de marque le
 * plus clair (BrandStrong, fond focalisé du bouton « Reprendre ») plafonne à 0,72 et les
 * fonds bleu nuit tournent autour de 0,15 : sans contour, aucun ne passe le seuil.
 */
const val FOCUS_OUTLINE_MIN_LUMINANCE = 0.8f

/**
 * Luminance du bord gauche d'un nœud à mi-hauteur, hors coins arrondis : c'est là que le
 * contour de focus de 2 dp doit être dessiné. Le max sur trois colonnes absorbe
 * l'anticrénelage de la première.
 */
fun SemanticsNodeInteraction.leftEdgeLuminance(): Float {
    val image = captureToImage().asAndroidBitmap()
    val y = image.height / 2
    return (0..2).maxOf { x -> luminance(image.getPixel(x, y)) }
}

private fun luminance(argb: Int): Float =
    (0.2126f * Color.red(argb) + 0.7152f * Color.green(argb) + 0.0722f * Color.blue(argb)) / 255f
