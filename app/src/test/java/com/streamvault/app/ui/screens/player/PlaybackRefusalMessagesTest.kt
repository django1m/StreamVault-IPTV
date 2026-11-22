package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.player.PlayerError
import org.junit.Test

class PlaybackRefusalMessagesTest {

    @Test
    fun `403 on a series episode names the simultaneous-connection limit`() {
        val msg = PlaybackRefusalMessages.forHttpCode(403, ContentType.SERIES_EPISODE)
        assertThat(msg).contains("403")
        assertThat(msg).contains("connexions simultanées")
        assertThat(msg).contains("Fermez les autres lecteurs")
    }

    @Test
    fun `403 names the connection limit for every content type`() {
        for (type in ContentType.entries) {
            assertThat(PlaybackRefusalMessages.forHttpCode(403, type)).contains("connexions simultanées")
        }
    }

    @Test
    fun `403 no longer asserts that the subscription excludes series`() {
        val msg = PlaybackRefusalMessages.forHttpCode(403, ContentType.SERIES_EPISODE)
        assertThat(msg).doesNotContain("n'inclut probablement pas")
    }

    @Test
    fun `401 points to credentials, not to the connection limit`() {
        val msg = PlaybackRefusalMessages.forHttpCode(401, ContentType.MOVIE)
        assertThat(msg).contains("401")
        assertThat(msg).doesNotContain("connexions simultanées")
    }

    @Test
    fun `404 and 5xx keep their own messages`() {
        assertThat(PlaybackRefusalMessages.forHttpCode(404, ContentType.MOVIE)).contains("introuvable")
        assertThat(PlaybackRefusalMessages.forHttpCode(503, ContentType.MOVIE)).contains("Réessayez plus tard")
    }

    @Test
    fun `engine error carrying a 403 maps to the refusal message`() {
        val msg = PlaybackRefusalMessages.forError(
            PlayerError.NetworkError("Source error", httpCode = 403),
            ContentType.SERIES_EPISODE
        )
        assertThat(msg).isEqualTo(PlaybackRefusalMessages.forHttpCode(403, ContentType.SERIES_EPISODE))
    }

    @Test
    fun `engine errors without an auth code are left to the generic mapping`() {
        assertThat(PlaybackRefusalMessages.forError(PlayerError.NetworkError("timeout"), ContentType.MOVIE)).isNull()
        assertThat(PlaybackRefusalMessages.forError(PlayerError.NetworkError("x", httpCode = 503), ContentType.MOVIE)).isNull()
        assertThat(PlaybackRefusalMessages.forError(PlayerError.SourceError("Émission indisponible"), ContentType.MOVIE)).isNull()
    }
}
