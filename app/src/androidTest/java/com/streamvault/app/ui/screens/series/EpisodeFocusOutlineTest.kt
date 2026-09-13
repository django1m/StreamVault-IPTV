package com.streamvault.app.ui.screens.series

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Episode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La liste d'épisodes n'avait aucun repère de focus : `EpisodeRowCard` peint son propre
 * fond opaque par-dessus la couleur de focus de la surface, et aucun contour n'était
 * déclaré — l'élément focalisé était indiscernable des autres à 3 mètres.
 *
 * On lit le bord gauche de l'élément à mi-hauteur (hors coins arrondis), là où le contour
 * blanc de 2 dp doit être dessiné : clair au focus, sombre au repos.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeFocusOutlineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val episode = Episode(
        id = 1L,
        title = "Pilote",
        episodeNumber = 1,
        seasonNumber = 1,
        duration = "42 min",
        plot = "Un résumé de deux lignes pour donner sa hauteur réelle à la rangée."
    )

    private fun leftEdgeLuminance(focused: Boolean): Float {
        composeRule.setContent {
            StreamVaultTheme {
                val requester = remember { FocusRequester() }
                Box(
                    modifier = Modifier
                        .background(AppColors.Canvas)
                        .padding(12.dp)
                ) {
                    Box(modifier = Modifier.focusRequester(requester).testTag("episode")) {
                        EpisodeItem(episode = episode, onClick = {})
                    }
                }
                if (focused) {
                    LaunchedEffect(Unit) { requester.requestFocus() }
                }
            }
        }
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag("episode").captureToImage().asAndroidBitmap()
        val y = image.height / 2
        return (0..2).maxOf { x -> luminance(image.getPixel(x, y)) }
    }

    private fun luminance(argb: Int): Float =
        (0.2126f * Color.red(argb) + 0.7152f * Color.green(argb) + 0.0722f * Color.blue(argb)) / 255f

    @Test
    fun focusedEpisode_showsWhiteOutline() {
        val edge = leftEdgeLuminance(focused = true)
        assertTrue("bord gauche au focus = $edge, un contour blanc est attendu", edge > 0.8f)
    }

    @Test
    fun restingEpisode_hasNoOutline() {
        val edge = leftEdgeLuminance(focused = false)
        assertTrue("bord gauche au repos = $edge, aucun contour n'est attendu", edge < 0.3f)
    }
}
