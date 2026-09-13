package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.entity.SeriesBrowseEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
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

class SeriesRepositoryImplTest {

    private val seriesDao: SeriesDao = mock()
    private val episodeDao: EpisodeDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val providerDao: ProviderDao = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao = mock()
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }

    @Test
    fun `getSeriesByCategory lazily hydrates xtream category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
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
        whenever(xtreamApiService.getSeriesCategories(any())).thenReturn(
            listOf(XtreamCategory(categoryId = "77", categoryName = "Drama"))
        )
        whenever(xtreamApiService.getSeriesList(any())).thenReturn(
            listOf(
                XtreamSeriesItem(
                    seriesId = 301L,
                    name = "Series",
                    categoryId = "77",
                    cover = null
                )
            )
        )
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        whenever(episodeDao.deleteOrphans()).thenReturn(0)

        val repository = createRepository()

        val result = repository.getSeriesByCategory(7L, 77L).first()

        assertThat(result).isEmpty()
        verify(seriesDao).replaceCategory(eq(7L), eq(77L), any())
        verify(episodeDao).deleteOrphans()
    }

    @Test
    fun `getSeriesDetails falls back to remote series id lookup`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Series",
            providerId = 7L
        )
        whenever(seriesDao.getById(301L)).thenReturn(null)
        whenever(seriesDao.getBySeriesId(7L, 301L)).thenReturn(seriesEntity)
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 301L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.id).isEqualTo(15L)
        assertThat(series.seriesId).isEqualTo(301L)
    }

    @Test
    fun `getSeriesDetails returns local series when xtream details fail`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Stored Series",
            posterUrl = "https://img.example.test/poster.jpg",
            providerId = 7L
        )
        whenever(seriesDao.getById(301L)).thenReturn(null)
        whenever(seriesDao.getBySeriesId(7L, 301L)).thenReturn(seriesEntity)
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
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())
        whenever(xtreamApiService.getSeriesInfo(any())).thenThrow(RuntimeException("bad response"))

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 301L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.id).isEqualTo(15L)
        assertThat(series.name).isEqualTo("Stored Series")
        assertThat(series.posterUrl).isEqualTo("https://img.example.test/poster.jpg")
    }

    @Test
    fun `category hydration stores the provider order of the response`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
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
        whenever(xtreamApiService.getSeriesCategories(any())).thenReturn(
            listOf(XtreamCategory(categoryId = "77", categoryName = "FR | Séries 2026"))
        )
        whenever(xtreamApiService.getSeriesList(any())).thenReturn(
            listOf("Charlie", "Alpha", "Bravo").mapIndexed { index, name ->
                XtreamSeriesItem(seriesId = 301L + index, name = name, categoryId = "77")
            }
        )

        createRepository().getSeriesByCategory(7L, 77L).first()

        val captor = argumentCaptor<List<SeriesEntity>>()
        verify(seriesDao).replaceCategory(eq(7L), eq(77L), captor.capture())
        assertThat(captor.firstValue.map { it.name to it.providerOrder })
            .containsExactly("Charlie" to 1, "Alpha" to 2, "Bravo" to 3)
            .inOrder()
    }

    @Test
    fun `browseSeries asks SQL for the alphabetical window only when sorting by title`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(favoriteDao.getAllByType(ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(3))
        val alpha = browseSeries(id = 1L, name = "Alpha")
        val bravo = browseSeries(id = 2L, name = "Bravo")
        val charlie = browseSeries(id = 3L, name = "Charlie")
        // Fenêtre SQL : l'ordre du fournisseur ne contient pas forcément le premier titre de A à Z.
        whenever(seriesDao.getByCategoryPage(eq(7L), eq(77L), any(), eq(0), eq(false)))
            .thenReturn(flowOf(listOf(charlie, bravo)))
        whenever(seriesDao.getByCategoryPage(eq(7L), eq(77L), any(), eq(0), eq(true)))
            .thenReturn(flowOf(listOf(alpha, bravo)))
        val repository = createRepository()

        val library = repository.browseSeries(LibraryBrowseQuery(providerId = 7L, categoryId = 77L)).first()
        val byTitle = repository.browseSeries(
            LibraryBrowseQuery(providerId = 7L, categoryId = 77L, sortBy = LibrarySortBy.TITLE)
        ).first()

        assertThat(library.items.map { it.name }).containsExactly("Charlie", "Bravo").inOrder()
        assertThat(byTitle.items.map { it.name }).containsExactly("Alpha", "Bravo").inOrder()
    }

    private fun browseSeries(id: Long, name: String) =
        SeriesBrowseEntity(id = id, seriesId = id, name = name, categoryId = 77L, providerId = 7L)

    private fun createRepository() = SeriesRepositoryImpl(
        seriesDao = seriesDao,
        episodeDao = episodeDao,
        categoryDao = categoryDao,
        favoriteDao = favoriteDao,
        playbackHistoryDao = playbackHistoryDao,
        providerDao = providerDao,
        xtreamApiService = xtreamApiService,
        credentialCrypto = credentialCrypto,
        preferencesRepository = preferencesRepository,
        xtreamStreamUrlResolver = xtreamStreamUrlResolver,
        seriesCategoryHydrationDao = seriesCategoryHydrationDao
    )
}
