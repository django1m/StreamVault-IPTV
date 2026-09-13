package com.streamvault.app.ui.screens.player

import android.view.KeyEvent
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.ui.components.dialogs.ProgramHistoryDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerResumePrompt
import com.streamvault.app.ui.test.FOCUS_OUTLINE_MIN_LUMINANCE
import com.streamvault.app.ui.test.TestFixtures
import com.streamvault.app.ui.test.leftEdgeLuminance
import com.streamvault.app.ui.theme.StreamVaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Les listes qu'on parcourt en regardant un film ou une série : le focus y reposait sur
 * un changement de teinte entre deux bleus nuit, ou sur un contour bleu de marque,
 * illisibles à 3 mètres. L'élément atteint à la télécommande doit porter le contour blanc
 * de la liste d'épisodes.
 *
 * On navigue au D-pad avant de mesurer : sur l'émulateur de test (un téléphone), le focus
 * posé à l'ouverture d'une fenêtre neuve ne s'affiche pas toujours, ce qui ferait mesurer
 * l'état au repos. Le choix de piste audio/sous-titres n'est pas couvert pour cette
 * raison : son focus initial n'y entre pas du tout dans la liste.
 */
@RunWith(AndroidJUnit4::class)
class PlayerListsFocusOutlineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun focusedEdge(): Float {
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        // La sémantique passe « focalisée » avant le rendu : la surface TV suit une
        // FocusInteraction émise dans une coroutine, puis anime sa couleur.
        composeRule.waitForIdle()
        return composeRule.onNode(isFocused()).leftEdgeLuminance()
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        composeRule.waitForIdle()
    }

    /** Aller sur l'élément voisin puis revenir : on mesure les deux éléments atteints au D-pad. */
    private fun assertOutlinedAfterNavigating(what: String, away: Int, back: Int) {
        press(away)
        val neighbour = focusedEdge()
        press(back)
        val reached = focusedEdge()
        assertTrue(
            "$what : bord gauche au focus = $reached (voisin $neighbour), un contour blanc est attendu",
            reached > FOCUS_OUTLINE_MIN_LUMINANCE && neighbour > FOCUS_OUTLINE_MIN_LUMINANCE
        )
    }

    @Test
    fun resumePrompt_focusedButton_hasWhiteOutline() {
        composeRule.setContent {
            StreamVaultTheme {
                PlayerResumePrompt(title = "Épisode test", onStartOver = {}, onResume = {})
            }
        }
        composeRule.waitForIdle()
        assertOutlinedAfterNavigating("prompt Reprendre", KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    @Test
    fun programHistory_focusedProgram_hasWhiteOutline() {
        val first = TestFixtures.currentProgram
        val second = first.copy(
            id = 43L,
            title = "Post-game",
            startTime = first.endTime,
            endTime = first.endTime + 3_600_000L,
            isNowPlaying = false
        )
        composeRule.setContent {
            StreamVaultTheme {
                ProgramHistoryDialog(programs = listOf(first, second), onDismiss = {}, onProgramSelect = {})
            }
        }
        composeRule.waitForIdle()
        assertOutlinedAfterNavigating("archives", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)
    }
}
