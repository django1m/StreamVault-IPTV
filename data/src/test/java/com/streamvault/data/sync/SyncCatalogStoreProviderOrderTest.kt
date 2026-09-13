package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * L'ordre du fournisseur doit traverser la table de staging : sinon la synchro réécrit les films et
 * les séries sans leur rang, et l'« Ordre de la bibliothèque » retombe sur l'ordre alphabétique.
 */
class SyncCatalogStoreProviderOrderTest {
    private val catalogSyncDao: CatalogSyncDao = mock()
    private val movieDao: MovieDao = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
    private val store = SyncCatalogStore(movieDao, catalogSyncDao, transactionRunner)

    @Test
    fun `staged movies carry their provider order and added date`() = runTest {
        val captor = argumentCaptor<List<MovieImportStageEntity>>()

        store.stageMovieBatch(7L, 99L, listOf(movie(streamId = 1L, providerOrder = 3, providerAddedAt = 1_000L)))

        verify(catalogSyncDao).insertMovieStages(captor.capture())
        val staged = captor.allValues.flatten().single()
        assertThat(staged.providerOrder).isEqualTo(3)
        assertThat(staged.providerAddedAt).isEqualTo(1_000L)
    }

    @Test
    fun `a movie that only moved in the provider list gets a new fingerprint`() = runTest {
        val captor = argumentCaptor<List<MovieImportStageEntity>>()

        store.stageMovieBatch(7L, 99L, listOf(movie(streamId = 1L, providerOrder = 1)))
        store.stageMovieBatch(7L, 100L, listOf(movie(streamId = 1L, providerOrder = 2)))

        verify(catalogSyncDao, times(2)).insertMovieStages(captor.capture())
        val (first, second) = captor.allValues.map { it.single().syncFingerprint }
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `staged series carry their provider order`() = runTest {
        val captor = argumentCaptor<List<SeriesImportStageEntity>>()

        store.stageSeriesBatch(7L, 99L, listOf(series(seriesId = 1L, providerOrder = 3)))

        verify(catalogSyncDao).insertSeriesStages(captor.capture())
        assertThat(captor.allValues.flatten().single().providerOrder).isEqualTo(3)
    }

    @Test
    fun `a series that only moved in the provider list gets a new fingerprint`() = runTest {
        val captor = argumentCaptor<List<SeriesImportStageEntity>>()

        store.stageSeriesBatch(7L, 99L, listOf(series(seriesId = 1L, providerOrder = 1)))
        store.stageSeriesBatch(7L, 100L, listOf(series(seriesId = 1L, providerOrder = 2)))

        verify(catalogSyncDao, times(2)).insertSeriesStages(captor.capture())
        val (first, second) = captor.allValues.map { it.single().syncFingerprint }
        assertThat(second).isNotEqualTo(first)
    }

    private fun movie(streamId: Long, providerOrder: Int, providerAddedAt: Long = 0L) = MovieEntity(
        streamId = streamId,
        name = "Movie $streamId",
        categoryId = 10L,
        providerId = 7L,
        providerOrder = providerOrder,
        providerAddedAt = providerAddedAt
    )

    private fun series(seriesId: Long, providerOrder: Int) = SeriesEntity(
        seriesId = seriesId,
        name = "Series $seriesId",
        categoryId = 10L,
        providerId = 7L,
        providerOrder = providerOrder
    )
}
