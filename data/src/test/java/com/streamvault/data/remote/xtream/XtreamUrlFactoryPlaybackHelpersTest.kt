package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamUrlFactoryPlaybackHelpersTest {

    // ---- parsePlaybackUrl --------------------------------------------------

    @Test
    fun `parsePlaybackUrl reads kind, id and extension from a series url`() {
        val token = XtreamUrlFactory.parsePlaybackUrl(
            "http://panel.example.net/series/user1/pass1/1815630.mp4"
        )

        assertThat(token).isNotNull()
        assertThat(token!!.kind).isEqualTo(XtreamStreamKind.SERIES)
        assertThat(token.streamId).isEqualTo(1815630L)
        assertThat(token.containerExtension).isEqualTo("mp4")
    }

    @Test
    fun `parsePlaybackUrl rejects internal and non-http urls`() {
        assertThat(XtreamUrlFactory.parsePlaybackUrl("xtream://1/series/42?ext=mp4")).isNull()
        assertThat(XtreamUrlFactory.parsePlaybackUrl("rtsp://host/series/u/p/1.mp4")).isNull()
        assertThat(XtreamUrlFactory.parsePlaybackUrl(null)).isNull()
        assertThat(XtreamUrlFactory.parsePlaybackUrl("http://host/player_api.php?username=u")).isNull()
    }

    // ---- replaceContainerExtension ----------------------------------------

    @Test
    fun `replaceContainerExtension swaps the extension of a series url`() {
        val rewritten = XtreamUrlFactory.replaceContainerExtension(
            "http://panel.example.net/series/user1/pass1/1815630.mp4",
            "mkv"
        )

        assertThat(rewritten)
            .isEqualTo("http://panel.example.net/series/user1/pass1/1815630.mkv")
    }

    @Test
    fun `replaceContainerExtension appends when the url has no extension`() {
        val rewritten = XtreamUrlFactory.replaceContainerExtension(
            "http://panel.example.net/movie/user1/pass1/777",
            "mkv"
        )

        assertThat(rewritten).isEqualTo("http://panel.example.net/movie/user1/pass1/777.mkv")
    }

    @Test
    fun `replaceContainerExtension preserves encoded credentials byte for byte`() {
        val rewritten = XtreamUrlFactory.replaceContainerExtension(
            "http://panel.example.net/series/us%40er/p%2Bass/99.mp4",
            "mkv"
        )

        assertThat(rewritten).isEqualTo("http://panel.example.net/series/us%40er/p%2Bass/99.mkv")
    }

    @Test
    fun `replaceContainerExtension preserves a query string`() {
        val rewritten = XtreamUrlFactory.replaceContainerExtension(
            "http://panel.example.net/series/u/p/99.mp4?token=abc",
            "mkv"
        )

        assertThat(rewritten).isEqualTo("http://panel.example.net/series/u/p/99.mkv?token=abc")
    }

    @Test
    fun `replaceContainerExtension normalizes the new extension`() {
        val rewritten = XtreamUrlFactory.replaceContainerExtension(
            "http://panel.example.net/series/u/p/99.mp4",
            ".MKV"
        )

        assertThat(rewritten).isEqualTo("http://panel.example.net/series/u/p/99.mkv")
    }

    @Test
    fun `replaceContainerExtension returns null for non playback urls or blank extension`() {
        assertThat(
            XtreamUrlFactory.replaceContainerExtension("http://host/player_api.php?u=1", "mkv")
        ).isNull()
        assertThat(
            XtreamUrlFactory.replaceContainerExtension("not a url", "mkv")
        ).isNull()
        assertThat(
            XtreamUrlFactory.replaceContainerExtension(
                "http://panel.example.net/series/u/p/99.mp4",
                "  "
            )
        ).isNull()
    }

    // ---- maskHost ----------------------------------------------------------

    @Test
    fun `maskHost keeps only a prefix of the first label`() {
        assertThat(XtreamUrlFactory.maskHost("http://vpn.example.net/series/u/p/1.mp4"))
            .isEqualTo("vp****.example.net")
    }

    @Test
    fun `maskHost handles unparseable input`() {
        assertThat(XtreamUrlFactory.maskHost("::::")).isNull()
        assertThat(XtreamUrlFactory.maskHost(null)).isNull()
        assertThat(XtreamUrlFactory.maskHost("")).isNull()
    }

    @Test
    fun `maskHost never leaks credentials from the url`() {
        val masked = XtreamUrlFactory.maskHost("http://panel.example.net/series/secretuser/secretpass/1.mp4")

        assertThat(masked).doesNotContain("secretuser")
        assertThat(masked).doesNotContain("secretpass")
    }
}
