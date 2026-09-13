package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MovieOrderingTest {

    @Test
    fun `newest first puts the latest provider additions on top`() {
        val older = movie("Older", addedAt = 1_000L, releaseDate = "2026-05-01")
        val latest = movie("Latest", addedAt = 2_000L, releaseDate = "2019-01-01")

        val sorted = listOf(older, latest).sortedWith(MOVIE_NEWEST_FIRST)

        assertThat(sorted.map { it.name }).containsExactly("Latest", "Older").inOrder()
    }

    @Test
    fun `newest first falls back to the release date for movies without added date`() {
        val undated = movie("Undated")
        val released2019 = movie("Released 2019", releaseDate = "2019-03-01")
        val released2024 = movie("Released 2024", releaseDate = "2024-06-15")
        val added = movie("Added", addedAt = 5_000L)

        val sorted = listOf(undated, released2019, released2024, added).sortedWith(MOVIE_NEWEST_FIRST)

        assertThat(sorted.map { it.name })
            .containsExactly("Added", "Released 2024", "Released 2019", "Undated")
            .inOrder()
    }

    private fun movie(name: String, addedAt: Long = 0L, releaseDate: String? = null) =
        Movie(id = 0L, name = name, releaseDate = releaseDate, providerAddedAt = addedAt)
}
