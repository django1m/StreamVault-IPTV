package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.MovieBrowseEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.PlaybackHistoryLiteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibraryFilterBy
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MovieRepositoryImplTest {

    private val movieDao: MovieDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val providerDao: ProviderDao = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun `getRecommendations prioritizes movies similar to recent history`() = runTest {
        val watchedMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            categoryId = 10L,
            rating = 7.2f
        )
        val recommendedMovie = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            categoryId = 10L,
            rating = 8.8f
        )
        val unrelatedMovie = movieEntity(
            id = 3L,
            name = "Quiet Tea",
            genre = "Drama",
            categoryId = 11L,
            rating = 9.5f
        )

    whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
    whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getTopRatedPreview(7L, 48)).thenReturn(flowOf(listOf(recommendedMovie, unrelatedMovie, watchedMovie)))
        whenever(movieDao.getFreshPreview(7L, 48)).thenReturn(flowOf(listOf(recommendedMovie, unrelatedMovie, watchedMovie)))
        whenever(favoriteDao.getAllByType(ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getRecentlyWatchedByProvider(eq(7L), eq(24))).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistoryLiteEntity(
                        contentId = watchedMovie.id,
                        contentType = ContentType.MOVIE,
                        providerId = 7L,
                        title = watchedMovie.name,
                        lastWatchedAt = 1_000L,
                        resumePositionMs = 1_000L,
                        totalDurationMs = 10_000L
                    )
                )
            )
        )

        val repository = createRepository()

        val result = repository.getRecommendations(7L, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Quiet Tea").inOrder()
    }

    @Test
    fun `getRelatedContent ranks shared genre ahead of category only matches`() = runTest {
        val targetMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            categoryId = 10L,
            rating = 7.2f
        )
        val closeMatch = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            categoryId = 10L,
            rating = 8.8f
        )
        val categoryOnly = movieEntity(
            id = 3L,
            name = "Harbor Escape",
            genre = "Thriller",
            categoryId = 10L,
            rating = 9.0f
        )

        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 10L)).thenReturn(flowOf(1))
        whenever(movieDao.getById(targetMovie.id)).thenReturn(
            movieRecord(
                id = targetMovie.id,
                name = targetMovie.name,
                genre = targetMovie.genre.orEmpty(),
                categoryId = targetMovie.categoryId ?: 0L,
                rating = targetMovie.rating
            )
        )
        whenever(movieDao.getByCategoryPreview(7L, 10L, 48)).thenReturn(flowOf(listOf(targetMovie, closeMatch, categoryOnly)))
        whenever(movieDao.getTopRatedPreview(7L, 32)).thenReturn(flowOf(listOf(closeMatch, categoryOnly)))

        val repository = createRepository()

        val result = repository.getRelatedContent(7L, targetMovie.id, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Harbor Escape").inOrder()
    }

    @Test
    fun `getMoviesByCategory lazily hydrates xtream category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(xtreamApiService.getVodCategories(any())).thenReturn(
            listOf(XtreamCategory(categoryId = "42", categoryName = "Action"))
        )
        whenever(xtreamApiService.getVodStreams(any())).thenReturn(
            listOf(
                XtreamStream(
                    streamId = 101L,
                    name = "Movie",
                    categoryId = "42",
                    streamIcon = null,
                    containerExtension = "mp4"
                )
            )
        )

        val repository = createRepository()

        repository.getMoviesByCategory(7L, 42L).first()

        verify(movieDao).replaceCategory(eq(7L), eq(42L), any())
    }

    @Test
    fun `category hydration stores the provider order of the response`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(xtreamApiService.getVodCategories(any())).thenReturn(
            listOf(XtreamCategory(categoryId = "42", categoryName = "FR | Films 2026"))
        )
        whenever(xtreamApiService.getVodStreams(any())).thenReturn(
            listOf("Charlie", "Alpha", "Bravo").mapIndexed { index, name ->
                XtreamStream(streamId = 101L + index, name = name, categoryId = "42", containerExtension = "mp4")
            }
        )

        createRepository().getMoviesByCategory(7L, 42L).first()

        val captor = argumentCaptor<List<MovieEntity>>()
        verify(movieDao).replaceCategory(eq(7L), eq(42L), captor.capture())
        assertThat(captor.firstValue.map { it.name to it.providerOrder })
            .containsExactly("Charlie" to 1, "Alpha" to 2, "Bravo" to 3)
            .inOrder()
    }

    @Test
    fun `browseMovies asks SQL for the alphabetical window only when sorting by title`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(favoriteDao.getAllByType(ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(3))
        val alpha = movieEntity(id = 1L, name = "Alpha", genre = "", categoryId = 42L, rating = 0f)
        val bravo = movieEntity(id = 2L, name = "Bravo", genre = "", categoryId = 42L, rating = 0f)
        val charlie = movieEntity(id = 3L, name = "Charlie", genre = "", categoryId = 42L, rating = 0f)
        // Fenêtre SQL : l'ordre du fournisseur ne contient pas forcément le premier titre de A à Z.
        whenever(movieDao.getByCategoryPage(eq(7L), eq(42L), any(), eq(0), eq(false)))
            .thenReturn(flowOf(listOf(charlie, bravo)))
        whenever(movieDao.getByCategoryPage(eq(7L), eq(42L), any(), eq(0), eq(true)))
            .thenReturn(flowOf(listOf(alpha, bravo)))
        val repository = createRepository()

        val library = repository.browseMovies(LibraryBrowseQuery(providerId = 7L, categoryId = 42L)).first()
        val byTitle = repository.browseMovies(
            LibraryBrowseQuery(providerId = 7L, categoryId = 42L, sortBy = LibrarySortBy.TITLE)
        ).first()

        assertThat(library.items.map { it.name }).containsExactly("Charlie", "Bravo").inOrder()
        assertThat(byTitle.items.map { it.name }).containsExactly("Alpha", "Bravo").inOrder()
    }

    @Test
    fun `browseMovies newest sort uses the provider added date when SQL cannot sort`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(favoriteDao.getAllByType(ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(
            flowOf(
                listOf(
                    movieEntity(id = 1L, name = "Added long ago", genre = "", categoryId = 42L, rating = 0f)
                        .copy(releaseDate = "2026-01-01", providerAddedAt = 1_000L),
                    movieEntity(id = 2L, name = "Added yesterday", genre = "", categoryId = 42L, rating = 0f)
                        .copy(releaseDate = "2001-01-01", providerAddedAt = 2_000L)
                )
            )
        )

        val result = createRepository().browseMovies(
            LibraryBrowseQuery(
                providerId = 7L,
                categoryId = 42L,
                sortBy = LibrarySortBy.RELEASE,
                filterBy = LibraryFilterBy(type = LibraryFilterType.UNWATCHED)
            )
        ).first()

        assertThat(result.items.map { it.name }).containsExactly("Added yesterday", "Added long ago").inOrder()
    }

    private fun createRepository() = MovieRepositoryImpl(
        movieDao = movieDao,
        categoryDao = categoryDao,
        providerDao = providerDao,
        xtreamApiService = xtreamApiService,
        credentialCrypto = credentialCrypto,
        preferencesRepository = preferencesRepository,
        favoriteDao = favoriteDao,
        playbackHistoryDao = playbackHistoryDao,
        xtreamStreamUrlResolver = xtreamStreamUrlResolver,
        movieCategoryHydrationDao = movieCategoryHydrationDao,
        syncMetadataRepository = syncMetadataRepository,
        transactionRunner = transactionRunner
    )

    private fun movieEntity(
        id: Long,
        name: String,
        genre: String,
        categoryId: Long,
        rating: Float
    ) = MovieBrowseEntity(
        id = id,
        streamId = id,
        name = name,
        genre = genre,
        categoryId = categoryId,
        providerId = 7L,
        rating = rating,
        streamUrl = "https://example.com/$id.m3u8"
    )

    private fun movieRecord(
        id: Long,
        name: String,
        genre: String,
        categoryId: Long,
        rating: Float
    ) = MovieEntity(
        id = id,
        streamId = id,
        name = name,
        genre = genre,
        categoryId = categoryId,
        providerId = 7L,
        rating = rating,
        streamUrl = "https://example.com/$id.m3u8"
    )
}
