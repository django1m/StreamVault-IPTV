package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.data.local.entity.ChannelImportStageEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity

@Dao
interface CatalogSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannelStages(rows: List<ChannelImportStageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovieStages(rows: List<MovieImportStageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesStages(rows: List<SeriesImportStageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryStages(rows: List<CategoryImportStageEntity>)

    @Query("DELETE FROM channel_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearChannelStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM movie_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearMovieStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM series_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearSeriesStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM category_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearCategoryStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM channel_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderChannelStages(providerId: Long)

    @Query("DELETE FROM movie_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderMovieStages(providerId: Long)

    @Query("DELETE FROM series_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderSeriesStages(providerId: Long)

    @Query("DELETE FROM category_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderCategoryStages(providerId: Long)

    @Query(
        """
        UPDATE categories
        SET (name, parent_id, is_adult, sync_fingerprint) = (
            SELECT stage.name, stage.parent_id, stage.is_adult, stage.sync_fingerprint
            FROM category_import_stage AS stage
            WHERE stage.session_id = :sessionId
              AND stage.provider_id = :providerId
              AND stage.type = :type
              AND stage.category_id = categories.category_id
        )
        WHERE provider_id = :providerId
          AND type = :type
          AND EXISTS (
              SELECT 1
              FROM category_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.type = :type
                AND stage.category_id = categories.category_id
                AND categories.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedCategoriesFromStage(providerId: Long, sessionId: Long, type: String)

    @Query(
        """
        INSERT INTO categories (
            category_id,
            name,
            parent_id,
            type,
            provider_id,
            is_adult,
            is_user_protected,
            sync_fingerprint
        )
        SELECT
            stage.category_id,
            stage.name,
            stage.parent_id,
            stage.type,
            stage.provider_id,
            stage.is_adult,
            0,
            stage.sync_fingerprint
        FROM category_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND stage.type = :type
          AND NOT EXISTS (
              SELECT 1
              FROM categories AS existing
              WHERE existing.provider_id = stage.provider_id
                AND existing.type = stage.type
                AND existing.category_id = stage.category_id
          )
        """
    )
    suspend fun insertMissingCategoriesFromStage(providerId: Long, sessionId: Long, type: String)

    @Query(
        """
        DELETE FROM categories
        WHERE provider_id = :providerId
          AND type = :type
          AND NOT EXISTS (
              SELECT 1
              FROM category_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.type = :type
                AND stage.category_id = categories.category_id
          )
        """
    )
    suspend fun deleteStaleCategoriesForStage(providerId: Long, sessionId: Long, type: String)

    @Query(
        """
        UPDATE channels
        SET (
            name, logo_url, group_title, category_id, category_name, stream_url,
            epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
            logical_group_id, error_count, is_adult, sync_fingerprint
        ) = (
            SELECT
                stage.name, stage.logo_url, stage.group_title, stage.category_id, stage.category_name, stage.stream_url,
                stage.epg_channel_id, stage.number, stage.catch_up_supported, stage.catch_up_days, stage.catchUpSource,
                stage.logical_group_id, stage.error_count, stage.is_adult, stage.sync_fingerprint
            FROM channel_import_stage AS stage
            WHERE stage.session_id = :sessionId
              AND stage.provider_id = :providerId
              AND stage.stream_id = channels.stream_id
        )
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM channel_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.stream_id = channels.stream_id
                AND channels.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedChannelsFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        INSERT INTO channels (
            stream_id,
            name,
            logo_url,
            group_title,
            category_id,
            category_name,
            stream_url,
            epg_channel_id,
            number,
            catch_up_supported,
            catch_up_days,
            catchUpSource,
            logical_group_id,
            error_count,
            provider_id,
            is_adult,
            is_user_protected,
            sync_fingerprint
        )
        SELECT
            stage.stream_id,
            stage.name,
            stage.logo_url,
            stage.group_title,
            stage.category_id,
            stage.category_name,
            stage.stream_url,
            stage.epg_channel_id,
            stage.number,
            stage.catch_up_supported,
            stage.catch_up_days,
            stage.catchUpSource,
            stage.logical_group_id,
            stage.error_count,
            stage.provider_id,
            stage.is_adult,
            0,
            stage.sync_fingerprint
        FROM channel_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM channels AS existing
              WHERE existing.provider_id = stage.provider_id
                AND existing.stream_id = stage.stream_id
          )
        """
    )
    suspend fun insertMissingChannelsFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        DELETE FROM channels
        WHERE provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM channel_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.stream_id = channels.stream_id
          )
        """
    )
    suspend fun deleteStaleChannelsForStage(providerId: Long, sessionId: Long)

    @Query(
        """
        UPDATE movies
        SET (
            name, poster_url, backdrop_url, category_id, category_name, stream_url,
            container_extension, plot, cast, director, genre, release_date,
            duration, duration_seconds, rating, year, tmdb_id, youtube_trailer,
            is_adult, sync_fingerprint
        ) = (
            SELECT
                stage.name, stage.poster_url, stage.backdrop_url, stage.category_id, stage.category_name, stage.stream_url,
                stage.container_extension, stage.plot, stage.cast, stage.director, stage.genre, stage.release_date,
                stage.duration, stage.duration_seconds, stage.rating, stage.year, stage.tmdb_id, stage.youtube_trailer,
                stage.is_adult, stage.sync_fingerprint
            FROM movie_import_stage AS stage
            WHERE stage.session_id = :sessionId
              AND stage.provider_id = :providerId
              AND stage.stream_id = movies.stream_id
        )
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM movie_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.stream_id = movies.stream_id
                AND movies.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedMoviesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        INSERT INTO movies (
            stream_id,
            name,
            poster_url,
            backdrop_url,
            category_id,
            category_name,
            stream_url,
            container_extension,
            plot,
            cast,
            director,
            genre,
            release_date,
            duration,
            duration_seconds,
            rating,
            year,
            tmdb_id,
            youtube_trailer,
            provider_id,
            watch_progress,
            last_watched_at,
            is_adult,
            is_user_protected,
            sync_fingerprint
        )
        SELECT
            stage.stream_id,
            stage.name,
            stage.poster_url,
            stage.backdrop_url,
            stage.category_id,
            stage.category_name,
            stage.stream_url,
            stage.container_extension,
            stage.plot,
            stage.cast,
            stage.director,
            stage.genre,
            stage.release_date,
            stage.duration,
            stage.duration_seconds,
            stage.rating,
            stage.year,
            stage.tmdb_id,
            stage.youtube_trailer,
            stage.provider_id,
            0,
            0,
            stage.is_adult,
            0,
            stage.sync_fingerprint
        FROM movie_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM movies AS existing
              WHERE existing.provider_id = stage.provider_id
                AND existing.stream_id = stage.stream_id
          )
        """
    )
    suspend fun insertMissingMoviesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        DELETE FROM movies
        WHERE provider_id = :providerId
          AND movies.stream_id NOT IN (
              SELECT stage.stream_id FROM movie_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
          )
          AND (
              -- Partial-sync guard via IN (SELECT DISTINCT ...) — lets SQLite
              -- materialise the category set once rather than running the
              -- correlated EXISTS subquery per row (O(n²) without an index on
              -- category_id). Full syncs include every category so this is a
              -- no-op there.
              movies.category_id IS NULL OR movies.category_id IN (
                  SELECT DISTINCT scope.category_id FROM movie_import_stage AS scope
                  WHERE scope.session_id = :sessionId
                    AND scope.provider_id = :providerId
                    AND scope.category_id IS NOT NULL
              )
          )
        """
    )
    suspend fun deleteStaleMoviesForStage(providerId: Long, sessionId: Long)

    @Query(
        """
        UPDATE series
        SET (
            name, poster_url, backdrop_url, category_id, category_name,
            plot, cast, director, genre, release_date, rating, tmdb_id,
            youtube_trailer, episode_run_time, last_modified, is_adult, sync_fingerprint
        ) = (
            SELECT
                stage.name, stage.poster_url, stage.backdrop_url, stage.category_id, stage.category_name,
                stage.plot, stage.cast, stage.director, stage.genre, stage.release_date, stage.rating, stage.tmdb_id,
                stage.youtube_trailer, stage.episode_run_time, stage.last_modified, stage.is_adult, stage.sync_fingerprint
            FROM series_import_stage AS stage
            WHERE stage.session_id = :sessionId
              AND stage.provider_id = :providerId
              AND stage.series_id = series.series_id
        )
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM series_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.series_id = series.series_id
                AND series.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedSeriesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        INSERT INTO series (
            series_id,
            name,
            poster_url,
            backdrop_url,
            category_id,
            category_name,
            plot,
            cast,
            director,
            genre,
            release_date,
            rating,
            tmdb_id,
            youtube_trailer,
            episode_run_time,
            last_modified,
            provider_id,
            is_adult,
            is_user_protected,
            sync_fingerprint
        )
        SELECT
            stage.series_id,
            stage.name,
            stage.poster_url,
            stage.backdrop_url,
            stage.category_id,
            stage.category_name,
            stage.plot,
            stage.cast,
            stage.director,
            stage.genre,
            stage.release_date,
            stage.rating,
            stage.tmdb_id,
            stage.youtube_trailer,
            stage.episode_run_time,
            stage.last_modified,
            stage.provider_id,
            stage.is_adult,
            0,
            stage.sync_fingerprint
        FROM series_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM series AS existing
              WHERE existing.provider_id = stage.provider_id
                AND existing.series_id = stage.series_id
          )
        """
    )
    suspend fun insertMissingSeriesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        DELETE FROM series
        WHERE provider_id = :providerId
          AND series.series_id NOT IN (
              SELECT stage.series_id FROM series_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
          )
          AND (
              -- Partial-sync guard: a category-scoped import must not wipe
              -- series of untouched categories. Using IN (SELECT DISTINCT ...)
              -- instead of EXISTS lets SQLite materialise the category set once
              -- (hash join) rather than running the correlated subquery per row
              -- — the EXISTS form is O(n²) without an index on category_id and
              -- was spending 90s+ on 15k-series libraries.
              series.category_id IS NULL OR series.category_id IN (
                  SELECT DISTINCT scope.category_id FROM series_import_stage AS scope
                  WHERE scope.session_id = :sessionId
                    AND scope.provider_id = :providerId
                    AND scope.category_id IS NOT NULL
              )
          )
        """
    )
    suspend fun deleteStaleSeriesForStage(providerId: Long, sessionId: Long)
}
