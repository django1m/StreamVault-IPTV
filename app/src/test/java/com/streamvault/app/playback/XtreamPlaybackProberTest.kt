package com.streamvault.app.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.xtream.XtreamStreamKind
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class XtreamPlaybackProberTest {

    private val seriesUrl = "http://panel.example.net/series/user/pass/1815630.mp4"

    /** Fabricates responses per URL and records every request, no sockets. */
    private class ScriptedInterceptor(
        private val codeFor: (String) -> Int
    ) : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(codeFor(request.url.toString()))
                .message("scripted")
                .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
    }

    private fun prober(interceptor: Interceptor): XtreamPlaybackProber =
        XtreamPlaybackProber(OkHttpClient.Builder().addInterceptor(interceptor).build())

    @Test
    fun `accepted url passes through untouched`() = runTest {
        val interceptor = ScriptedInterceptor { 206 }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isEqualTo(XtreamPlaybackProber.Outcome.Playable(seriesUrl))
        assertThat(interceptor.requests).hasSize(1)
        assertThat(interceptor.requests.single().header("Range")).isEqualTo("bytes=0-0")
    }

    @Test
    fun `403 on mp4 falls back to mkv`() = runTest {
        val interceptor = ScriptedInterceptor { url -> if (url.endsWith(".mkv")) 200 else 403 }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isEqualTo(
            XtreamPlaybackProber.Outcome.Playable(
                url = "http://panel.example.net/series/user/pass/1815630.mkv",
                winningExtension = "mkv",
                initialCode = 403
            )
        )
        assertThat(interceptor.requests).hasSize(2)
        assertThat(interceptor.requests.all { it.header("Range") == "bytes=0-0" }).isTrue()
    }

    @Test
    fun `404 also triggers the extension fallback`() = runTest {
        val interceptor = ScriptedInterceptor { url -> if (url.endsWith(".mkv")) 200 else 404 }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isInstanceOf(XtreamPlaybackProber.Outcome.Playable::class.java)
        assertThat((outcome as XtreamPlaybackProber.Outcome.Playable).winningExtension)
            .isEqualTo("mkv")
    }

    @Test
    fun `preferred extension is probed first`() = runTest {
        val interceptor = ScriptedInterceptor { url -> if (url.endsWith(".avi")) 200 else 403 }

        val outcome = prober(interceptor).probe(
            url = seriesUrl,
            kind = XtreamStreamKind.SERIES,
            preferredExtension = "avi"
        )

        assertThat(outcome).isInstanceOf(XtreamPlaybackProber.Outcome.Playable::class.java)
        assertThat((outcome as XtreamPlaybackProber.Outcome.Playable).winningExtension)
            .isEqualTo("avi")
        // Initial probe + the preferred candidate, nothing else in between.
        assertThat(interceptor.requests).hasSize(2)
        assertThat(interceptor.requests[1].url.toString()).endsWith(".avi")
    }

    @Test
    fun `series rejected everywhere is advisory with at most three requests`() = runTest {
        val interceptor = ScriptedInterceptor { 403 }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isInstanceOf(XtreamPlaybackProber.Outcome.Rejected::class.java)
        val rejected = outcome as XtreamPlaybackProber.Outcome.Rejected
        assertThat(rejected.httpCode).isEqualTo(403)
        assertThat(rejected.advisory).isTrue()
        assertThat(rejected.triedExtensions).containsExactly("mp4", "mkv", "avi").inOrder()
        assertThat(interceptor.requests).hasSize(3)
    }

    @Test
    fun `initial 403 stays advisory even when alternates get false 404s`() = runTest {
        // The panel rate-limits per IP and answers false 404s to back-to-back
        // requests: the alternate probes' codes must not demote the initial
        // 403 out of advisory mode.
        val interceptor = ScriptedInterceptor { url -> if (url.endsWith(".mp4")) 403 else 404 }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isInstanceOf(XtreamPlaybackProber.Outcome.Rejected::class.java)
        val rejected = outcome as XtreamPlaybackProber.Outcome.Rejected
        assertThat(rejected.httpCode).isEqualTo(403)
        assertThat(rejected.advisory).isTrue()
    }

    @Test
    fun `401 is rejected immediately without fallback and without advisory`() = runTest {
        val interceptor = ScriptedInterceptor { 401 }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isEqualTo(
            XtreamPlaybackProber.Outcome.Rejected(
                httpCode = 401,
                triedExtensions = listOf("mp4"),
                advisory = false
            )
        )
        assertThat(interceptor.requests).hasSize(1)
    }

    @Test
    fun `server errors do not trigger fallback`() = runTest {
        val interceptor = ScriptedInterceptor { 503 }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isEqualTo(
            XtreamPlaybackProber.Outcome.Rejected(
                httpCode = 503,
                triedExtensions = listOf("mp4"),
                advisory = false
            )
        )
        assertThat(interceptor.requests).hasSize(1)
    }

    @Test
    fun `live urls get no extension fallback`() = runTest {
        val interceptor = ScriptedInterceptor { 403 }

        val outcome = prober(interceptor).probe(
            "http://panel.example.net/live/user/pass/42.ts",
            XtreamStreamKind.LIVE
        )

        assertThat(outcome).isEqualTo(
            XtreamPlaybackProber.Outcome.Rejected(
                httpCode = 403,
                triedExtensions = listOf("ts"),
                advisory = false
            )
        )
        assertThat(interceptor.requests).hasSize(1)
    }

    @Test
    fun `network exceptions are inconclusive`() = runTest {
        val interceptor = Interceptor { throw SocketTimeoutException("timed out") }

        val outcome = prober(interceptor).probe(seriesUrl, XtreamStreamKind.SERIES)

        assertThat(outcome).isEqualTo(XtreamPlaybackProber.Outcome.Inconclusive)
    }

    @Test
    fun `cancelled session stops probing between requests`() = runTest {
        val interceptor = ScriptedInterceptor { 403 }

        val outcome = prober(interceptor).probe(
            url = seriesUrl,
            kind = XtreamStreamKind.SERIES,
            isActive = { false }
        )

        assertThat(outcome).isEqualTo(XtreamPlaybackProber.Outcome.Inconclusive)
        assertThat(interceptor.requests).hasSize(1)
    }

    @Test
    fun `movie urls also get the fallback`() = runTest {
        val interceptor = ScriptedInterceptor { url -> if (url.endsWith(".mkv")) 200 else 403 }

        val outcome = prober(interceptor).probe(
            "http://panel.example.net/movie/user/pass/1076062.mp4",
            XtreamStreamKind.MOVIE
        )

        assertThat(outcome).isInstanceOf(XtreamPlaybackProber.Outcome.Playable::class.java)
        assertThat((outcome as XtreamPlaybackProber.Outcome.Playable).url).endsWith("1076062.mkv")
    }
}
