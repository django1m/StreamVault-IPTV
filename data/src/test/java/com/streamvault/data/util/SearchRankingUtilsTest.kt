package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchRankingUtilsTest {

    @Test
    fun `rankSearchResults prioritizes exact then prefix then contains matches`() {
        val results = listOf(
            "Ultra Sports Replay",
            "Sports Center",
            "Daily Sports",
            "Sports",
            "Arena"
        )

        val ranked = results.rankSearchResults("sports") { it }

        assertThat(ranked).containsExactly(
            "Sports",
            "Sports Center",
            "Daily Sports",
            "Ultra Sports Replay",
            "Arena"
        ).inOrder()
    }

    @Test
    fun `rankSearchResults prioritizes all token prefix matches over loose contains`() {
        val results = listOf(
            "Star Documentary Wars",
            "Star Wars Chronicles",
            "Galactic Wars of the Stars"
        )

        val ranked = results.rankSearchResults("star wars") { it }

        assertThat(ranked).containsExactly(
            "Star Wars Chronicles",
            "Star Documentary Wars",
            "Galactic Wars of the Stars"
        ).inOrder()
    }

    /** L'index FTS replie les accents (`unicode61`) : le classement doit les replier aussi. */
    @Test
    fun `rankSearchResults ignores accents and case`() {
        val results = listOf(
            "Le Fabuleux Destin d'Amélie Poulain",
            "Amélie Poulain"
        )

        val ranked = results.rankSearchResults("amelie") { it }

        assertThat(ranked).containsExactly(
            "Amélie Poulain",
            "Le Fabuleux Destin d'Amélie Poulain"
        ).inOrder()
    }

    /** Repli permissif : plus il manque de mots de la requête, plus le résultat descend. */
    @Test
    fun `rankSearchResults orders partial matches by how many tokens matched`() {
        val results = listOf(
            "VF Sport",
            "Matrix",
            "Matrix Reloaded"
        )

        val ranked = results.rankSearchResults("matrix reloaded vf") { it }

        assertThat(ranked).containsExactly(
            "Matrix Reloaded",
            "Matrix",
            "VF Sport"
        ).inOrder()
    }
}