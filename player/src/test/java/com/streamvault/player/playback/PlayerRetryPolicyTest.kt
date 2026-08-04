package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import androidx.media3.exoplayer.source.BehindLiveWindowException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Test

class PlayerRetryPolicyTest {

    private val liveContext = PlaybackRetryContext(
        resolvedStreamType = ResolvedStreamType.HLS,
        timeoutProfile = PlayerTimeoutProfile.LIVE
    )

    private val policy = PlayerRetryPolicy(liveContext) { false }
    private val midStreamPolicy = PlayerRetryPolicy(liveContext) { true }

    @Test
    fun `500 before first frame retries 3 times with tolerant live backoff`() {
        val error = IOException("HTTP 500")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 2)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 3)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 4)).isFalse()
        // Live initial start: 1s/2s/3s — slow-start Xtream backends need
        // ~3-5s for the manifest. Cumulative window ~6s before giving up.
        assertThat(policy.retryDelayMs(error, 1)).isEqualTo(1000L)
        assertThat(policy.retryDelayMs(error, 2)).isEqualTo(2000L)
        assertThat(policy.retryDelayMs(error, 3)).isEqualTo(3000L)
    }

    @Test
    fun `500 mid-stream uses aggressive backoff for fast recovery`() {
        val error = IOException("HTTP 500")
        // Mid-stream: 0.5s/1s/2s so a transient upstream hiccup recovers
        // quickly without a visible buffer.
        assertThat(midStreamPolicy.retryDelayMs(error, 1)).isEqualTo(500L)
        assertThat(midStreamPolicy.retryDelayMs(error, 2)).isEqualTo(1000L)
        assertThat(midStreamPolicy.retryDelayMs(error, 3)).isEqualTo(2000L)
    }

    @Test
    fun `vod backoff stays at 1s 2_5s 5s`() {
        val vodContext = PlaybackRetryContext(
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            timeoutProfile = PlayerTimeoutProfile.PROGRESSIVE
        )
        val vodPolicy = PlayerRetryPolicy(vodContext) { false }
        val error = IOException("HTTP 500")
        assertThat(vodPolicy.retryDelayMs(error, 1)).isEqualTo(1000L)
        assertThat(vodPolicy.retryDelayMs(error, 2)).isEqualTo(2500L)
        assertThat(vodPolicy.retryDelayMs(error, 3)).isEqualTo(5000L)
    }

    @Test
    fun `403 never retries`() {
        assertThat(policy.shouldRetry(IOException("HTTP 403"), liveContext, playbackStarted = false, attempt = 1))
            .isFalse()
    }

    @Test
    fun `ssl error never retries`() {
        assertThat(policy.shouldRetry(SSLHandshakeException("bad cert"), liveContext, playbackStarted = false, attempt = 1))
            .isFalse()
    }

    @Test
    fun `behind live window retries once`() {
        val error = BehindLiveWindowException()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 2)).isFalse()
        assertThat(policy.retryDelayMs(error, 1)).isEqualTo(500L)
    }

    @Test
    fun `decoder init failure does not go through network retry policy`() {
        val error = IllegalStateException("decoder init failed")
        assertThat(policy.shouldRetry(error, liveContext, playbackStarted = false, attempt = 1)).isFalse()
    }
}
