package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.google.common.truth.Truth.assertThat
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MovieDaoTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var movieDao: MovieDao
    private lateinit var historyDao: PlaybackHistoryDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, StreamVaultDatabase::class.java
        ).build()
        movieDao = db.movieDao()
        historyDao = db.playbackHistoryDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testRestoreWatchProgress() = runTest {
        // 1. Insert history for a movie. Use contentId = 1L
        val history = PlaybackHistoryEntity(
            contentId = 1L,
            contentType = ContentType.MOVIE,
            providerId = 1L,
            resumePositionMs = 5000L,
            lastWatchedAt = 10000L
        )
        historyDao.insertOrUpdate(history)

        // 2. Replace movies in provider 1. Give it ID 1L
        val movie = MovieEntity(
            id = 1L, // MUST MATCH contentId in history
            name = "Test Movie",
            providerId = 1L,
            watchProgress = 0L // default
        )
        movieDao.replaceAll(1L, listOf(movie))

        // 3. Verify watch progress was restored from history during replaceAll
        val restoredMovie = movieDao.getById(1L)
        assertThat(restoredMovie).isNotNull()
        assertThat(restoredMovie?.watchProgress).isEqualTo(5000L)
    }

    @Test
    fun syncAllWatchProgressFromHistory_clearsStaleMovieProgressWithoutHistory() = runTest {
        movieDao.insertAll(
            listOf(
                MovieEntity(
                    id = 7L,
                    name = "Stale Progress",
                    providerId = 3L,
                    watchProgress = 9_000L,
                    lastWatchedAt = 12_000L
                )
            )
        )

        movieDao.syncAllWatchProgressFromHistory()

        val movie = movieDao.getById(7L)
        assertThat(movie).isNotNull()
        assertThat(movie?.watchProgress).isEqualTo(0L)
        assertThat(movie?.lastWatchedAt).isEqualTo(0L)
    }

    @Test
    fun categoryListsFollowProviderOrderWithUnrankedMoviesLast() = runTest {
        insertProvider()
        movieDao.insertAll(
            listOf(
                rankedMovie(streamId = 1L, name = "Alpha", categoryId = 10L, providerOrder = 3),
                rankedMovie(streamId = 2L, name = "Bravo", categoryId = 10L, providerOrder = 1),
                rankedMovie(streamId = 3L, name = "Charlie", categoryId = 10L, providerOrder = 2),
                rankedMovie(streamId = 4L, name = "Aardvark", categoryId = 10L, providerOrder = 0)
            )
        )

        assertThat(movieDao.getByCategory(1L, 10L).first().map { it.name })
            .containsExactly("Bravo", "Charlie", "Alpha", "Aardvark")
            .inOrder()
        assertThat(movieDao.getByCategoryPreview(1L, 10L, 2).first().map { it.name })
            .containsExactly("Bravo", "Charlie")
            .inOrder()
    }

    @Test
    fun fullLibraryFollowsCategoryListThenProviderOrder() = runTest {
        insertProvider()
        db.categoryDao().insertAll(
            listOf(
                CategoryEntity(categoryId = 20L, name = "FR | Films 2026", type = ContentType.MOVIE, providerId = 1L),
                CategoryEntity(categoryId = 10L, name = "Action", type = ContentType.MOVIE, providerId = 1L)
            )
        )
        movieDao.insertAll(
            listOf(
                rankedMovie(streamId = 1L, name = "Alpha", categoryId = 10L, providerOrder = 2),
                rankedMovie(streamId = 2L, name = "Bravo", categoryId = 10L, providerOrder = 1),
                rankedMovie(streamId = 3L, name = "Zulu", categoryId = 20L, providerOrder = 1),
                rankedMovie(streamId = 4L, name = "Yankee", categoryId = 20L, providerOrder = 2)
            )
        )

        assertThat(movieDao.getByProvider(1L).first().map { it.name })
            .containsExactly("Zulu", "Yankee", "Bravo", "Alpha")
            .inOrder()
    }

    @Test
    fun freshListsPutLatestProviderAdditionsFirst() = runTest {
        insertProvider()
        movieDao.insertAll(
            listOf(
                rankedMovie(streamId = 1L, name = "Older", categoryId = 10L, providerAddedAt = 1_000L, releaseDate = "2026-01-01"),
                rankedMovie(streamId = 2L, name = "Latest", categoryId = 10L, providerAddedAt = 3_000L, releaseDate = "2001-01-01"),
                rankedMovie(streamId = 3L, name = "Middle", categoryId = 10L, providerAddedAt = 2_000L)
            )
        )

        assertThat(movieDao.getFreshByCategoryPreview(1L, 10L, 10).first().map { it.name })
            .containsExactly("Latest", "Middle", "Older")
            .inOrder()
        assertThat(movieDao.getFreshPreview(1L, 10).first().map { it.name })
            .containsExactly("Latest", "Middle", "Older")
            .inOrder()
    }

    @Test
    fun pagedListsUseProviderOrderUnlessSortingAlphabetically() = runTest {
        insertProvider()
        movieDao.insertAll(
            listOf(
                rankedMovie(streamId = 1L, name = "Alpha", categoryId = 10L, providerOrder = 2),
                rankedMovie(streamId = 2L, name = "Bravo", categoryId = 10L, providerOrder = 1)
            )
        )

        assertThat(movieDao.getByCategoryPage(1L, 10L, 10, 0, alphabetical = false).first().map { it.name })
            .containsExactly("Bravo", "Alpha")
            .inOrder()
        assertThat(movieDao.getByCategoryPage(1L, 10L, 10, 0, alphabetical = true).first().map { it.name })
            .containsExactly("Alpha", "Bravo")
            .inOrder()
        assertThat(movieDao.getByProviderPage(1L, 10, 0, alphabetical = false).first().map { it.name })
            .containsExactly("Bravo", "Alpha")
            .inOrder()
        assertThat(movieDao.getByProviderPage(1L, 10, 0, alphabetical = true).first().map { it.name })
            .containsExactly("Alpha", "Bravo")
            .inOrder()
    }

    private suspend fun insertProvider() {
        db.providerDao().insert(
            ProviderEntity(id = 1L, name = "Xtream", type = ProviderType.XTREAM_CODES, serverUrl = "http://example.com")
        )
    }

    private fun rankedMovie(
        streamId: Long,
        name: String,
        categoryId: Long,
        providerOrder: Int = 0,
        providerAddedAt: Long = 0L,
        releaseDate: String? = null
    ) = MovieEntity(
        streamId = streamId,
        name = name,
        categoryId = categoryId,
        providerId = 1L,
        releaseDate = releaseDate,
        providerOrder = providerOrder,
        providerAddedAt = providerAddedAt
    )
}
