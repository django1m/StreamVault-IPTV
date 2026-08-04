package com.streamvault.player.playback

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType

enum class PlayerTimeoutProfile(
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val writeTimeoutMs: Long,
    // Whole-call timeout. 0 = disabled — REQUIRED for continuous streams (LIVE,
    // VOD, PROGRESSIVE) whose body never ends; a callTimeout there would kill a
    // legitimate stream. Only PRELOAD (a bounded probe) caps the whole call.
    val callTimeoutMs: Long = 0L
) {
    LIVE(
        connectTimeoutMs = 12_000L,
        // Tightened 20s→12s: a rate-limited panel can trickle ~1 byte/19s, which
        // never trips a 20s readTimeout, leaving the live socket occupied and
        // holding a maxRequestsPerHost slot. 12s frees stalled live sockets faster.
        readTimeoutMs = 12_000L,
        writeTimeoutMs = 20_000L
    ),
    VOD(
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 45_000L,
        writeTimeoutMs = 30_000L
    ),
    PROGRESSIVE(
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 35_000L,
        writeTimeoutMs = 30_000L
    ),
    PRELOAD(
        connectTimeoutMs = 10_000L,
        readTimeoutMs = 15_000L,
        writeTimeoutMs = 15_000L,
        callTimeoutMs = 20_000L
    );

    companion object {
        fun resolve(
            streamInfo: StreamInfo,
            resolvedStreamType: ResolvedStreamType,
            preload: Boolean
        ): PlayerTimeoutProfile {
            if (preload) return PRELOAD
            if (streamInfo.streamType == StreamType.RTSP) return LIVE
            return when {
                resolvedStreamType == ResolvedStreamType.HLS -> LIVE
                resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE -> LIVE
                resolvedStreamType == ResolvedStreamType.PROGRESSIVE -> PROGRESSIVE
                streamInfo.streamType == StreamType.PROGRESSIVE -> PROGRESSIVE
                else -> VOD
            }
        }
    }
}

