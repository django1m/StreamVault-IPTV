package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeriesDaoTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var seriesDao: SeriesDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        seriesDao = db.seriesDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun categoryListsFollowProviderOrderWithUnrankedSeriesLast() = runTest {
        insertProvider()
        seriesDao.insertAll(
            listOf(
                rankedSeries(seriesId = 1L, name = "Alpha", categoryId = 10L, providerOrder = 3),
                rankedSeries(seriesId = 2L, name = "Bravo", categoryId = 10L, providerOrder = 1),
                rankedSeries(seriesId = 3L, name = "Charlie", categoryId = 10L, providerOrder = 2),
                rankedSeries(seriesId = 4L, name = "Aardvark", categoryId = 10L, providerOrder = 0)
            )
        )

        assertThat(seriesDao.getByCategory(1L, 10L).first().map { it.name })
            .containsExactly("Bravo", "Charlie", "Alpha", "Aardvark")
            .inOrder()
        assertThat(seriesDao.getByCategoryPreview(1L, 10L, 2).first().map { it.name })
            .containsExactly("Bravo", "Charlie")
            .inOrder()
    }

    @Test
    fun fullLibraryFollowsCategoryListThenProviderOrder() = runTest {
        insertProvider()
        db.categoryDao().insertAll(
            listOf(
                CategoryEntity(categoryId = 20L, name = "FR | Séries 2026", type = ContentType.SERIES, providerId = 1L),
                CategoryEntity(categoryId = 10L, name = "Drame", type = ContentType.SERIES, providerId = 1L)
            )
        )
        seriesDao.insertAll(
            listOf(
                rankedSeries(seriesId = 1L, name = "Alpha", categoryId = 10L, providerOrder = 2),
                rankedSeries(seriesId = 2L, name = "Bravo", categoryId = 10L, providerOrder = 1),
                rankedSeries(seriesId = 3L, name = "Zulu", categoryId = 20L, providerOrder = 1),
                rankedSeries(seriesId = 4L, name = "Yankee", categoryId = 20L, providerOrder = 2)
            )
        )

        assertThat(seriesDao.getByProvider(1L).first().map { it.name })
            .containsExactly("Zulu", "Yankee", "Bravo", "Alpha")
            .inOrder()
    }

    @Test
    fun pagedListsUseProviderOrderUnlessSortingAlphabetically() = runTest {
        insertProvider()
        seriesDao.insertAll(
            listOf(
                rankedSeries(seriesId = 1L, name = "Alpha", categoryId = 10L, providerOrder = 2),
                rankedSeries(seriesId = 2L, name = "Bravo", categoryId = 10L, providerOrder = 1)
            )
        )

        assertThat(seriesDao.getByCategoryPage(1L, 10L, 10, 0, alphabetical = false).first().map { it.name })
            .containsExactly("Bravo", "Alpha")
            .inOrder()
        assertThat(seriesDao.getByCategoryPage(1L, 10L, 10, 0, alphabetical = true).first().map { it.name })
            .containsExactly("Alpha", "Bravo")
            .inOrder()
        assertThat(seriesDao.getByProviderPage(1L, 10, 0, alphabetical = false).first().map { it.name })
            .containsExactly("Bravo", "Alpha")
            .inOrder()
        assertThat(seriesDao.getByProviderPage(1L, 10, 0, alphabetical = true).first().map { it.name })
            .containsExactly("Alpha", "Bravo")
            .inOrder()
    }

    private suspend fun insertProvider() {
        db.providerDao().insert(
            ProviderEntity(id = 1L, name = "Xtream", type = ProviderType.XTREAM_CODES, serverUrl = "http://example.com")
        )
    }

    private fun rankedSeries(seriesId: Long, name: String, categoryId: Long, providerOrder: Int) = SeriesEntity(
        seriesId = seriesId,
        name = name,
        categoryId = categoryId,
        providerId = 1L,
        providerOrder = providerOrder
    )
}
