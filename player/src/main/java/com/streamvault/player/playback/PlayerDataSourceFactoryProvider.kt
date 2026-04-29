package com.streamvault.player.playback

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamvault.domain.model.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class PlayerDataSourceFactoryProvider(
    private val context: Context,
    private val baseClient: OkHttpClient
) {
    private val clientsByProfile = ConcurrentHashMap<PlayerTimeoutProfile, OkHttpClient>()

    fun createFactory(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, DataSource.Factory> {
        val profile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload)
        val headers = buildMap {
            putAll(streamInfo.headers)
            streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val client = clientsByProfile.computeIfAbsent(profile) {
            baseClient.newBuilder()
                .connectTimeout(profile.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(profile.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(profile.writeTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
        // Progressive (VOD/series MP4/MKV) : on chunke les requêtes HTTP en
        // Range bornées car le CDN Xtream coupe les longues connexions
        // open-ended après ~30s, ce qui fait planter Mp4Extractor avec EOFException.
        val upstreamFactory: DataSource.Factory = if (resolvedStreamType == ResolvedStreamType.PROGRESSIVE) {
            ChunkingDataSource.Factory(httpFactory)
        } else {
            httpFactory
        }
        val factory = DefaultDataSource.Factory(context, upstreamFactory)
        return profile to factory
    }
}

