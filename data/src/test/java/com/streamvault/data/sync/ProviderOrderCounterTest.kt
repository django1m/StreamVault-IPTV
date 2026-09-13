package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import org.junit.Test

class ProviderOrderCounterTest {

    @Test
    fun `ranks each category from one in arrival order`() {
        val order = ProviderOrderCounter()

        val ranks = listOf(10L, 20L, 10L, 10L, 20L).map(order::next)

        assertThat(ranks).containsExactly(1, 1, 2, 3, 2).inOrder()
    }

    @Test
    fun `keeps counting across batches of the same sync`() {
        val order = ProviderOrderCounter()
        order.next(10L)
        order.next(10L)

        assertThat(order.next(10L)).isEqualTo(3)
    }

    @Test
    fun `movies without category get their own sequence`() {
        val order = ProviderOrderCounter()

        val ranks = listOf<Long?>(null, 10L, null).map(order::next)

        assertThat(ranks).containsExactly(1, 1, 2).inOrder()
    }

    @Test
    fun `rank stamps the movie with its position in its category`() {
        val order = ProviderOrderCounter()

        val ranked = listOf(movie(1L, 10L), movie(2L, 20L), movie(3L, 10L)).map(order::rank)

        assertThat(ranked.map { it.streamId to it.providerOrder })
            .containsExactly(1L to 1, 2L to 1, 3L to 2)
            .inOrder()
    }

    @Test
    fun `rank stamps the series with its position in its category`() {
        val order = ProviderOrderCounter()

        val ranked = listOf(series(1L, 10L), series(2L, 20L), series(3L, 10L)).map(order::rank)

        assertThat(ranked.map { it.seriesId to it.providerOrder })
            .containsExactly(1L to 1, 2L to 1, 3L to 2)
            .inOrder()
    }

    private fun series(seriesId: Long, categoryId: Long) =
        Series(id = 0L, name = "Series $seriesId", categoryId = categoryId, seriesId = seriesId)

    private fun movie(streamId: Long, categoryId: Long) =
        Movie(id = 0L, name = "Movie $streamId", categoryId = categoryId, streamId = streamId)
}
