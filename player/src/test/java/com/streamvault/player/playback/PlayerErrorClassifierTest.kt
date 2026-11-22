package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import androidx.media3.exoplayer.source.BehindLiveWindowException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Test

class PlayerErrorClassifierTest {

    @Test
    fun `401 maps to auth`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 401")))
            .isEqualTo(PlaybackErrorCategory.HTTP_AUTH)
    }

    @Test
    fun `500 maps to server`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 500")))
            .isEqualTo(PlaybackErrorCategory.HTTP_SERVER)
    }

    @Test
    fun `ssl exception maps to ssl`() {
        assertThat(PlayerErrorClassifier.classify(SSLHandshakeException("certificate verify failed")))
            .isEqualTo(PlaybackErrorCategory.SSL)
    }

    @Test
    fun `behind live window maps to live window`() {
        assertThat(PlayerErrorClassifier.classify(BehindLiveWindowException()))
            .isEqualTo(PlaybackErrorCategory.LIVE_WINDOW)
    }
}


class PlayerErrorHttpCodeTest {

    @Test
    fun `403 from the provider keeps its HTTP code on the PlayerError`() {
        val e = java.io.IOException("Source error", java.io.IOException("Response code: 403"))
        val error = com.streamvault.player.PlayerError.fromException(e)
        assertThat(error).isInstanceOf(com.streamvault.player.PlayerError.NetworkError::class.java)
        assertThat((error as com.streamvault.player.PlayerError.NetworkError).httpCode).isEqualTo(403)
    }

    @Test
    fun `plain network failure has no HTTP code`() {
        val error = com.streamvault.player.PlayerError.fromException(java.net.SocketTimeoutException("timeout"))
        assertThat((error as com.streamvault.player.PlayerError.NetworkError).httpCode).isNull()
    }
}
