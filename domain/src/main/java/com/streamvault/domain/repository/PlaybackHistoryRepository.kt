package com.streamvault.domain.repository

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface PlaybackHistoryRepository {
    fun getRecentlyWatched(limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getRecentlyWatchedByProvider(providerId: Long, limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int>
    suspend fun getPlaybackHistory(contentId: Long, contentType: ContentType, providerId: Long): PlaybackHistory?

    suspend fun markAsWatched(history: PlaybackHistory): Result<Unit>

    /**
     * Upserts a history row for a playback session. [incrementWatchCount] is
     * false for the automatic session-start write (one playback must count as
     * one view at most, regardless of how many times the row is refreshed).
     */
    suspend fun recordPlayback(history: PlaybackHistory, incrementWatchCount: Boolean = true): Result<Unit>
    suspend fun updateResumePosition(history: PlaybackHistory, syncDenormalized: Boolean = true): Result<Unit>
    suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long): Result<Unit>
    suspend fun clearAllHistory(): Result<Unit>
    suspend fun clearHistoryForProvider(providerId: Long): Result<Unit>
}
