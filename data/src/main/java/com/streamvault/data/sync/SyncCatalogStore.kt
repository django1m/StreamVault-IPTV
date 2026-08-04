package com.streamvault.data.sync

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.StoredCategoryIdentity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelImportStageEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal class SyncCatalogStore(
    private val movieDao: MovieDao,
    private val catalogSyncDao: CatalogSyncDao,
    private val transactionRunner: DatabaseTransactionRunner
) {
    private val sessionIds = AtomicLong(System.currentTimeMillis())

    fun newSessionId(): Long = sessionIds.incrementAndGet()

    suspend fun replaceLiveCatalog(providerId: Long, categories: List<CategoryEntity>?, channels: Sequence<ChannelEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            // Staged in batches off a Sequence — same pattern as movies/series.
            // The previous List-based path materialised a second full copy of
            // a 10k+ channel catalogue (distinct + stage rows) on the 2 GB box.
            val acceptedCount = stageChannelSequence(providerId, sessionId, channels)
            transactionRunner.inTransaction {
                categories?.let { applyCategories(providerId, sessionId, "LIVE") }
                applyChannels(providerId, sessionId)
            }
            return acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceMovieCatalog(providerId: Long, categories: List<CategoryEntity>?, movies: Sequence<MovieEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val acceptedCount = stageMovieSequence(providerId, sessionId, movies)
            transactionRunner.inTransaction {
                categories?.let { applyCategories(providerId, sessionId, "MOVIE") }
                applyMovies(providerId, sessionId)
            }
            movieDao.restoreWatchProgress(providerId)
            return acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceSeriesCatalog(providerId: Long, categories: List<CategoryEntity>?, series: Sequence<SeriesEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val acceptedCount = stageSeriesSequence(providerId, sessionId, series)
            transactionRunner.inTransaction {
                categories?.let { applyCategories(providerId, sessionId, "SERIES") }
                applySeries(providerId, sessionId)
            }
            return acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun applyStagedMovieCatalog(providerId: Long, sessionId: Long, categories: List<CategoryEntity>?) {
        try {
            transactionRunner.inTransaction {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "MOVIE") }
                applyMovies(providerId, sessionId)
            }
            movieDao.restoreWatchProgress(providerId)
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun applyStagedSeriesCatalog(providerId: Long, sessionId: Long, categories: List<CategoryEntity>?) {
        try {
            transactionRunner.inTransaction {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "SERIES") }
                applySeries(providerId, sessionId)
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceCategories(providerId: Long, type: String, categories: List<CategoryEntity>) {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            stageCategories(providerId, sessionId, categories)
            transactionRunner.inTransaction {
                applyCategories(providerId, sessionId, type)
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun stageChannelBatch(providerId: Long, sessionId: Long, channels: List<ChannelEntity>) {
        val rows = buildChannelStages(providerId, sessionId, channels)
        transactionRunner.inTransaction {
            insertStageRows(rows, catalogSyncDao::insertChannelStages)
        }
    }

    suspend fun stageMovieBatch(providerId: Long, sessionId: Long, movies: List<MovieEntity>) {
        val rows = buildMovieStages(providerId, sessionId, movies)
        transactionRunner.inTransaction {
            insertStageRows(rows, catalogSyncDao::insertMovieStages)
        }
    }

    suspend fun stageSeriesBatch(providerId: Long, sessionId: Long, series: List<SeriesEntity>) {
        val rows = buildSeriesStages(providerId, sessionId, series)
        transactionRunner.inTransaction {
            insertStageRows(rows, catalogSyncDao::insertSeriesStages)
        }
    }

    suspend fun stageCategories(providerId: Long, sessionId: Long, categories: List<CategoryEntity>) {
        val resolved = preserveKnownCategoryNames(
            providerId,
            categories.distinctBy { it.categoryId to it.type }
        )
        val rows = resolved.map { category ->
            CategoryImportStageEntity(
                sessionId = sessionId,
                providerId = providerId,
                categoryId = category.categoryId,
                name = category.name,
                parentId = category.parentId,
                type = category.type,
                isAdult = category.isAdult,
                syncFingerprint = categoryFingerprint(category)
            )
        }
        insertStageRows(rows, catalogSyncDao::insertCategoryStages)
    }

    /**
     * When a provider's `get_*_categories` request fails mid-sync, categories fall back to a
     * synthetic "Category <id>" placeholder name (see `SyncManagerCatalogStrategySupport`).
     * Without this guard the placeholder would overwrite the real category name persisted by a
     * previous healthy sync, so the user would suddenly see raw numeric ids (e.g. "Category 513")
     * instead of the real label.
     *
     * For any incoming category that is a placeholder we restore the last-known-good
     * name / parent / adult flag still stored in the DB. Genuinely new placeholder categories
     * (no stored row) and real names are left untouched, so legitimate renames still apply and
     * a later healthy sync can recover. The restored fields make the staged fingerprint match
     * the stored row, so the apply step performs no spurious update.
     */
    private suspend fun preserveKnownCategoryNames(
        providerId: Long,
        categories: List<CategoryEntity>
    ): List<CategoryEntity> {
        val placeholderTypes = categories
            .asSequence()
            .filter { isPlaceholderCategoryName(it.categoryId, it.name) }
            .map { it.type.name }
            .toSet()
        if (placeholderTypes.isEmpty()) {
            return categories
        }

        val knownByTypeAndId = HashMap<Pair<String, Long>, StoredCategoryIdentity>()
        placeholderTypes.forEach { typeName ->
            catalogSyncDao.storedCategoryIdentities(providerId, typeName).forEach { stored ->
                if (!isPlaceholderCategoryName(stored.categoryId, stored.name)) {
                    knownByTypeAndId[typeName to stored.categoryId] = stored
                }
            }
        }
        if (knownByTypeAndId.isEmpty()) {
            return categories
        }

        return categories.map { category ->
            if (!isPlaceholderCategoryName(category.categoryId, category.name)) {
                return@map category
            }
            val known = knownByTypeAndId[category.type.name to category.categoryId]
                ?: return@map category
            category.copy(
                name = known.name,
                parentId = known.parentId,
                isAdult = known.isAdult
            )
        }
    }

    private fun isPlaceholderCategoryName(categoryId: Long, name: String): Boolean =
        name == "$PLACEHOLDER_CATEGORY_PREFIX$categoryId"

    suspend fun finalizeStagedImport(
        providerId: Long,
        sessionId: Long,
        liveCategories: List<CategoryEntity>?,
        movieCategories: List<CategoryEntity>?,
        includeLive: Boolean,
        includeMovies: Boolean
    ) {
        transactionRunner.inTransaction {
            if (includeLive) {
                stageCategories(providerId, sessionId, liveCategories.orEmpty())
                applyCategories(providerId, sessionId, "LIVE")
                applyChannels(providerId, sessionId)
            }
            if (includeMovies) {
                stageCategories(providerId, sessionId, movieCategories.orEmpty())
                applyCategories(providerId, sessionId, "MOVIE")
                applyMovies(providerId, sessionId)
            }
        }
        if (includeMovies) {
            movieDao.restoreWatchProgress(providerId)
        }
    }

    suspend fun discardStagedImport(providerId: Long, sessionId: Long) {
        clearSession(providerId, sessionId)
    }

    suspend fun clearProviderStaging(providerId: Long) {
        catalogSyncDao.clearProviderChannelStages(providerId)
        catalogSyncDao.clearProviderMovieStages(providerId)
        catalogSyncDao.clearProviderSeriesStages(providerId)
        catalogSyncDao.clearProviderCategoryStages(providerId)
    }

    private suspend fun applyCategories(providerId: Long, sessionId: Long, type: String) {
        catalogSyncDao.updateChangedCategoriesFromStage(providerId, sessionId, type)
        catalogSyncDao.insertMissingCategoriesFromStage(providerId, sessionId, type)
        catalogSyncDao.deleteStaleCategoriesForStage(providerId, sessionId, type)
    }

    private suspend fun applyChannels(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedChannelsFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingChannelsFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleChannelsForStage(providerId, sessionId)
    }

    private suspend fun applyMovies(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedMoviesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingMoviesFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleMoviesForStage(providerId, sessionId)
    }

    private suspend fun applySeries(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedSeriesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingSeriesFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleSeriesForStage(providerId, sessionId)
    }

    private suspend fun clearSession(providerId: Long, sessionId: Long) {
        catalogSyncDao.clearChannelStages(providerId, sessionId)
        catalogSyncDao.clearMovieStages(providerId, sessionId)
        catalogSyncDao.clearSeriesStages(providerId, sessionId)
        catalogSyncDao.clearCategoryStages(providerId, sessionId)
    }

    private suspend fun buildChannelStages(
        providerId: Long,
        sessionId: Long,
        channels: List<ChannelEntity>
    ): List<ChannelImportStageEntity> {
        val distinct = channels.distinctBy { it.streamId }
        return parallelMap(distinct) { channel ->
            channelStageEntity(providerId, sessionId, channel)
        }
    }

    private fun channelStageEntity(
        providerId: Long,
        sessionId: Long,
        channel: ChannelEntity
    ): ChannelImportStageEntity {
        return ChannelImportStageEntity(
            sessionId = sessionId,
            providerId = providerId,
            streamId = channel.streamId,
            name = channel.name,
            logoUrl = channel.logoUrl,
            groupTitle = channel.groupTitle,
            categoryId = channel.categoryId,
            categoryName = channel.categoryName,
            streamUrl = channel.streamUrl,
            epgChannelId = channel.epgChannelId,
            number = channel.number,
            catchUpSupported = channel.catchUpSupported,
            catchUpDays = channel.catchUpDays,
            catchUpSource = channel.catchUpSource,
            isAdult = channel.isAdult,
            logicalGroupId = channel.logicalGroupId,
            errorCount = channel.errorCount,
            syncFingerprint = channelFingerprint(channel)
        )
    }

    private suspend fun buildMovieStages(
        providerId: Long,
        sessionId: Long,
        movies: List<MovieEntity>
    ): List<MovieImportStageEntity> {
        val distinct = movies.distinctBy { it.streamId }
        return parallelMap(distinct) { movie ->
            MovieImportStageEntity(
                sessionId = sessionId,
                providerId = providerId,
                streamId = movie.streamId,
                name = movie.name,
                posterUrl = movie.posterUrl,
                backdropUrl = movie.backdropUrl,
                categoryId = movie.categoryId,
                categoryName = movie.categoryName,
                streamUrl = movie.streamUrl,
                containerExtension = movie.containerExtension,
                plot = movie.plot,
                cast = movie.cast,
                director = movie.director,
                genre = movie.genre,
                releaseDate = movie.releaseDate,
                duration = movie.duration,
                durationSeconds = movie.durationSeconds,
                rating = movie.rating,
                year = movie.year,
                tmdbId = movie.tmdbId,
                youtubeTrailer = movie.youtubeTrailer,
                isAdult = movie.isAdult,
                syncFingerprint = movieFingerprint(movie)
            )
        }
    }

    private suspend fun buildSeriesStages(
        providerId: Long,
        sessionId: Long,
        series: List<SeriesEntity>
    ): List<SeriesImportStageEntity> {
        val distinct = series.distinctBy { it.seriesId }
        return parallelMap(distinct) { item ->
            SeriesImportStageEntity(
                sessionId = sessionId,
                providerId = providerId,
                seriesId = item.seriesId,
                name = item.name,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                categoryId = item.categoryId,
                categoryName = item.categoryName,
                plot = item.plot,
                cast = item.cast,
                director = item.director,
                genre = item.genre,
                releaseDate = item.releaseDate,
                rating = item.rating,
                tmdbId = item.tmdbId,
                youtubeTrailer = item.youtubeTrailer,
                episodeRunTime = item.episodeRunTime,
                lastModified = item.lastModified,
                isAdult = item.isAdult,
                syncFingerprint = seriesFingerprint(item)
            )
        }
    }

    private suspend fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R> {
        if (items.size < 500) {
            return items.map(transform)
        }
        val chunkSize = (items.size + FINGERPRINT_PARALLELISM - 1) / FINGERPRINT_PARALLELISM
        return coroutineScope {
            items.chunked(chunkSize).map { chunk ->
                async(Dispatchers.Default) { chunk.map(transform) }
            }.awaitAll().flatten()
        }
    }

    private suspend fun stageChannelSequence(
        providerId: Long,
        sessionId: Long,
        channels: Sequence<ChannelEntity>
    ): Int {
        return stageDistinctRows(
            items = channels,
            keySelector = { channel -> channel.streamId },
            stageBuilder = { channel -> channelStageEntity(providerId, sessionId, channel) },
            insert = catalogSyncDao::insertChannelStages
        )
    }

    private suspend fun stageMovieSequence(
        providerId: Long,
        sessionId: Long,
        movies: Sequence<MovieEntity>
    ): Int {
        return stageDistinctRows(
            items = movies,
            keySelector = { movie -> movie.streamId },
            stageBuilder = { movie -> movieStageEntity(providerId, sessionId, movie) },
            insert = catalogSyncDao::insertMovieStages
        )
    }

    private suspend fun stageSeriesSequence(
        providerId: Long,
        sessionId: Long,
        series: Sequence<SeriesEntity>
    ): Int {
        return stageDistinctRows(
            items = series,
            keySelector = { item -> item.seriesId },
            stageBuilder = { item -> seriesStageEntity(providerId, sessionId, item) },
            insert = catalogSyncDao::insertSeriesStages
        )
    }

    private suspend fun <T, K, S> stageDistinctRows(
        items: Sequence<T>,
        keySelector: (T) -> K,
        stageBuilder: (T) -> S,
        insert: suspend (List<S>) -> Unit
    ): Int {
        val seenKeys = HashSet<K>()
        val batch = ArrayList<S>(STAGE_BATCH_SIZE)
        var acceptedCount = 0

        items.forEach { item ->
            if (!seenKeys.add(keySelector(item))) {
                return@forEach
            }
            batch += stageBuilder(item)
            acceptedCount++
            if (batch.size >= STAGE_BATCH_SIZE) {
                insert(batch)
                batch.clear()
            }
        }

        if (batch.isNotEmpty()) {
            insert(batch)
        }

        return acceptedCount
    }

    private suspend fun <T> insertStageRows(
        rows: List<T>,
        insert: suspend (List<T>) -> Unit
    ) {
        rows.chunked(STAGE_BATCH_SIZE).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                insert(chunk)
            }
        }
    }

    private fun movieStageEntity(
        providerId: Long,
        sessionId: Long,
        movie: MovieEntity
    ): MovieImportStageEntity {
        return MovieImportStageEntity(
            sessionId = sessionId,
            providerId = providerId,
            streamId = movie.streamId,
            name = movie.name,
            posterUrl = movie.posterUrl,
            backdropUrl = movie.backdropUrl,
            categoryId = movie.categoryId,
            categoryName = movie.categoryName,
            streamUrl = movie.streamUrl,
            containerExtension = movie.containerExtension,
            plot = movie.plot,
            cast = movie.cast,
            director = movie.director,
            genre = movie.genre,
            releaseDate = movie.releaseDate,
            duration = movie.duration,
            durationSeconds = movie.durationSeconds,
            rating = movie.rating,
            year = movie.year,
            tmdbId = movie.tmdbId,
            youtubeTrailer = movie.youtubeTrailer,
            isAdult = movie.isAdult,
            syncFingerprint = movieFingerprint(movie)
        )
    }

    private fun seriesStageEntity(
        providerId: Long,
        sessionId: Long,
        item: SeriesEntity
    ): SeriesImportStageEntity {
        return SeriesImportStageEntity(
            sessionId = sessionId,
            providerId = providerId,
            seriesId = item.seriesId,
            name = item.name,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            categoryId = item.categoryId,
            categoryName = item.categoryName,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating,
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            episodeRunTime = item.episodeRunTime,
            lastModified = item.lastModified,
            isAdult = item.isAdult,
            syncFingerprint = seriesFingerprint(item)
        )
    }

    private fun channelFingerprint(channel: ChannelEntity): String {
        return fingerprint(
            normalizeText(channel.name),
            normalizeUrl(channel.logoUrl),
            normalizeText(channel.groupTitle),
            channel.categoryId?.toString().orEmpty(),
            normalizeText(channel.categoryName),
            normalizeUrl(channel.streamUrl),
            normalizeText(channel.epgChannelId),
            channel.number.toString(),
            channel.catchUpSupported.toString(),
            channel.catchUpDays.toString(),
            normalizeUrl(channel.catchUpSource),
            channel.isAdult.toString()
        )
    }

    private fun movieFingerprint(movie: MovieEntity): String {
        return fingerprint(
            normalizeText(movie.name),
            normalizeUrl(movie.posterUrl),
            normalizeUrl(movie.backdropUrl),
            movie.categoryId?.toString().orEmpty(),
            normalizeText(movie.categoryName),
            normalizeUrl(movie.streamUrl),
            normalizeText(movie.containerExtension),
            normalizeText(movie.plot),
            normalizeText(movie.cast),
            normalizeText(movie.director),
            normalizeText(movie.genre),
            normalizeText(movie.releaseDate),
            normalizeText(movie.duration),
            movie.durationSeconds.toString(),
            movie.rating.toString(),
            normalizeText(movie.year),
            movie.tmdbId?.toString().orEmpty(),
            normalizeUrl(movie.youtubeTrailer),
            movie.isAdult.toString()
        )
    }

    private fun seriesFingerprint(series: SeriesEntity): String {
        return fingerprint(
            normalizeText(series.name),
            normalizeUrl(series.posterUrl),
            normalizeUrl(series.backdropUrl),
            series.categoryId?.toString().orEmpty(),
            normalizeText(series.categoryName),
            normalizeText(series.plot),
            normalizeText(series.cast),
            normalizeText(series.director),
            normalizeText(series.genre),
            normalizeText(series.releaseDate),
            series.rating.toString(),
            series.tmdbId?.toString().orEmpty(),
            normalizeUrl(series.youtubeTrailer),
            normalizeText(series.episodeRunTime),
            series.lastModified.toString(),
            series.isAdult.toString()
        )
    }

    private fun categoryFingerprint(category: CategoryEntity): String {
        return fingerprint(
            category.categoryId.toString(),
            normalizeText(category.name),
            category.parentId?.toString().orEmpty(),
            category.type.name,
            category.isAdult.toString()
        )
    }

    private fun fingerprint(vararg values: String): String {
        var hash = 1L
        values.forEach { value ->
            hash = hash * 31L + value.hashCode()
        }
        return hash.toString(16)
    }

    private fun normalizeText(value: String?): String {
        return value?.trim()?.lowercase().orEmpty()
    }

    private fun normalizeUrl(value: String?): String {
        return value?.trim()?.lowercase().orEmpty()
    }

    private companion object {
        private const val STAGE_BATCH_SIZE = 500
        private const val PLACEHOLDER_CATEGORY_PREFIX = "Category "
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val FINGERPRINT_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
