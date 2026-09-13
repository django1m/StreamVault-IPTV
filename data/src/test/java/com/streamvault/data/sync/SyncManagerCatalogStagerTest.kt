package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncManagerCatalogStagerTest {
    private val syncCatalogStore: SyncCatalogStore = mock()
    private val stager = SyncManagerCatalogStager(syncCatalogStore, fallbackStageBatchSize = 500)

    @Test
    fun `accepted movies are ranked per category in arrival order across batches`() = runTest {
        whenever(syncCatalogStore.newSessionId()).thenReturn(99L)
        val order = ProviderOrderCounter()
        val seen = HashSet<Long>()
        val collector = FallbackCategoryCollector(7L, ContentType.MOVIE)

        val firstBatch = stager.stageMovieItems(
            7L,
            listOf(movie(1L, 10L), movie(2L, 20L), movie(1L, 10L)),
            seen,
            collector,
            null,
            order
        )
        stager.stageMovieItems(7L, listOf(movie(3L, 10L)), seen, collector, firstBatch.sessionId, order)

        val captor = argumentCaptor<List<MovieEntity>>()
        verify(syncCatalogStore, times(2)).stageMovieBatch(eq(7L), eq(99L), captor.capture())
        assertThat(captor.allValues.flatten().map { it.streamId to it.providerOrder })
            .containsExactly(1L to 1, 2L to 1, 3L to 2)
            .inOrder()
    }

    @Test
    fun `accepted series are ranked per category in arrival order across batches`() = runTest {
        whenever(syncCatalogStore.newSessionId()).thenReturn(99L)
        val order = ProviderOrderCounter()
        val seen = HashSet<Long>()
        val collector = FallbackCategoryCollector(7L, ContentType.SERIES)

        val firstBatch = stager.stageSeriesItems(
            7L,
            listOf(series(1L, 10L), series(2L, 20L), series(1L, 10L)),
            seen,
            collector,
            null,
            order
        )
        stager.stageSeriesItems(7L, listOf(series(3L, 10L)), seen, collector, firstBatch.sessionId, order)

        val captor = argumentCaptor<List<SeriesEntity>>()
        verify(syncCatalogStore, times(2)).stageSeriesBatch(eq(7L), eq(99L), captor.capture())
        assertThat(captor.allValues.flatten().map { it.seriesId to it.providerOrder })
            .containsExactly(1L to 1, 2L to 1, 3L to 2)
            .inOrder()
    }

    private fun series(seriesId: Long, categoryId: Long) = Series(
        id = 0L,
        name = "Series $seriesId",
        categoryId = categoryId,
        categoryName = "Category $categoryId",
        providerId = 7L,
        seriesId = seriesId
    )

    private fun movie(streamId: Long, categoryId: Long) = Movie(
        id = 0L,
        name = "Movie $streamId",
        categoryId = categoryId,
        categoryName = "Category $categoryId",
        providerId = 7L,
        streamId = streamId
    )
}
