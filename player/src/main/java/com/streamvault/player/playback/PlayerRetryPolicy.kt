package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val LIVE_WINDOW_RETRY_DELAY_MS = 500L

data class PlaybackRetryContext(
    val resolvedStreamType: ResolvedStreamType,
    val timeoutProfile: PlayerTimeoutProfile
) {
    val isLive: Boolean
        get() = resolvedStreamType == ResolvedStreamType.HLS || resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE
}

@UnstableApi
class PlayerRetryPolicy(
    private val streamContext: PlaybackRetryContext,
    private val playbackStarted: () -> Boolean
) : LoadErrorHandlingPolicy {
    fun maxAttempts(error: Throwable, playbackStarted: Boolean): Int {
        return maxAttemptsFor(error, streamContext, playbackStarted)
    }

    fun shouldRetry(
        error: Throwable,
        streamContext: PlaybackRetryContext,
        playbackStarted: Boolean,
        attempt: Int
    ): Boolean {
        val category = PlayerErrorClassifier.classify(error)
        if (playbackStarted && category in setOf(
                PlaybackErrorCategory.DECODER,
                PlaybackErrorCategory.FORMAT_UNSUPPORTED,
                PlaybackErrorCategory.DRM
            )
        ) {
            return false
        }
        val maxAttempts = maxAttemptsFor(error, streamContext, playbackStarted)
        return attempt <= maxAttempts && maxAttempts > 0
    }

    fun retryDelayMs(error: Throwable, attempt: Int): Long {
        if (PlayerErrorClassifier.classify(error) == PlaybackErrorCategory.LIVE_WINDOW) {
            return LIVE_WINDOW_RETRY_DELAY_MS
        }
        // Live splits into two regimes:
        //   - Initial start (before first frame): tolerant 1s/2s/3s so slow-start
        //     Xtream backends have a real chance to serve the manifest.
        //   - Mid-stream (after first frame): aggressive 0.5s/1s/2s so a transient
        //     panel hiccup recovers quickly without a visible buffer.
        val isLive = streamContext.isLive
        val liveMidStream = isLive && playbackStarted()
        return when (attempt) {
            1 -> if (liveMidStream) 500L else 1_000L
            2 -> if (liveMidStream) 1_000L else if (isLive) 2_000L else 2_500L
            else -> if (liveMidStream) 2_000L else if (isLive) 3_000L else 5_000L
        }
    }

    fun retryReason(error: Throwable): String {
        return when (PlayerErrorClassifier.classify(error)) {
            PlaybackErrorCategory.LIVE_WINDOW -> "refresh-live-window"
            PlaybackErrorCategory.NETWORK -> "transient-network"
            PlaybackErrorCategory.HTTP_SERVER -> "server-retryable"
            else -> "retryable-source"
        }
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        // Live mid-stream: cut Media3's silent internal retries so onPlayerError
        // fires fast and PlayerRetryPolicy triggers a /live re-request within
        // ~1-2s (faster recovery from a transient upstream hiccup).
        // Live initial start: keep the standard 3/2 so a slow-start backend
        // (Xtream that needs ~3-5s for the manifest) actually gets that window.
        val liveMidStream = streamContext.isLive && playbackStarted()
        return when (dataType) {
            C.DATA_TYPE_MANIFEST -> if (liveMidStream) 1 else 3
            C.DATA_TYPE_MEDIA -> if (liveMidStream) 1 else 2
            else -> 1
        }
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val attempt = loadErrorInfo.errorCount
        val error = loadErrorInfo.exception
        val started = playbackStarted()
        if (!shouldRetry(error, streamContext, started, attempt)) {
            return C.TIME_UNSET
        }
        return retryDelayMs(error, attempt)
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) = Unit

    private fun maxAttemptsFor(
        error: Throwable,
        streamContext: PlaybackRetryContext,
        playbackStarted: Boolean
    ): Int {
        return when (PlayerErrorClassifier.classify(error)) {
            PlaybackErrorCategory.DRM,
            PlaybackErrorCategory.DECODER,
            PlaybackErrorCategory.CLEAR_TEXT_BLOCKED,
            PlaybackErrorCategory.SSL,
            PlaybackErrorCategory.HTTP_AUTH,
            PlaybackErrorCategory.FORMAT_UNSUPPORTED -> 0

            PlaybackErrorCategory.LIVE_WINDOW -> 1
            PlaybackErrorCategory.HTTP_SERVER -> if (streamContext.isLive) 3 else 2
            PlaybackErrorCategory.NETWORK -> when {
                error.hasCause<UnknownHostException>() -> 1
                error.hasCause<SocketTimeoutException>() || error.hasCause<ConnectException>() ->
                    if (streamContext.isLive) 3 else 2
                !playbackStarted -> if (streamContext.isLive) 3 else 2
                else -> 1
            }

            PlaybackErrorCategory.SOURCE_MALFORMED -> if (playbackStarted) 0 else 1
            PlaybackErrorCategory.UNKNOWN -> if (playbackStarted) 0 else 1
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        return generateSequence(this) { it.cause }.any { it is T }
    }
}
