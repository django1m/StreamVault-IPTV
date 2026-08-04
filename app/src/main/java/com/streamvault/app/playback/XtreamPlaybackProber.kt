package com.streamvault.app.playback

import android.util.Log
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-playback probe for credentialed Xtream stream URLs, with container
 * extension fallback for episodes and movies.
 *
 * Panels frequently omit `container_extension` in get_series_info; the app
 * then defaults to `.mp4` and many panels answer 403 (not 404) when the real
 * file is `.mkv`. When the initial probe is rejected with 403/404, up to
 * [MAX_EXTRA_PROBES] alternate extensions are probed — strictly sequentially
 * (the panel rate-limits per IP) — and the first accepted URL wins.
 *
 * The probe stays deliberately cheap and honest: HTTP GET with
 * `Range: bytes=0-0`, short timeouts, redirects followed by the shared
 * client. It uses OkHttp's default User-Agent while the actual playback may
 * run on LibVLC with a different one — which is why a fully rejected
 * series/movie probe is reported as [Outcome.Rejected] with
 * `advisory = true`: the engine still deserves one real attempt.
 */
@Singleton
class XtreamPlaybackProber @Inject constructor(okHttpClient: OkHttpClient) {

    // newBuilder() shares the pool/dispatcher; only the timeouts differ. The
    // control-plane client's 30s timeouts would let a 3-probe chain hang the
    // player spinner for 90s — cap the whole chain well under that.
    private val probeClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    sealed interface Outcome {
        /**
         * URL accepted by the panel — [url] differs from the input when an
         * alternate extension won ([winningExtension] non-null, [initialCode]
         * is the HTTP code the original URL got).
         */
        data class Playable(
            val url: String,
            val winningExtension: String? = null,
            val initialCode: Int? = null,
        ) : Outcome

        /**
         * Every variant was refused. [advisory] is true for 401/403 on
         * series/movies: the engine should still attempt playback once (its
         * HTTP client differs from the probe's).
         */
        data class Rejected(
            val httpCode: Int,
            val triedExtensions: List<String>,
            val advisory: Boolean,
        ) : Outcome

        /** Probe not conclusive (network exception, cancelled session…) — never block on it. */
        data object Inconclusive : Outcome
    }

    /**
     * Probes [url] and, for series/movie URLs rejected with 403/404, its
     * extension variants. [preferredExtension] (remembered from an earlier
     * win) is tried first among the alternates. [isActive] is checked between
     * requests so a zapped-away session stops probing immediately.
     */
    suspend fun probe(
        url: String,
        kind: XtreamStreamKind?,
        preferredExtension: String? = null,
        isActive: () -> Boolean = { true },
    ): Outcome = withContext(Dispatchers.IO) {
        val initialCode = probeOnce(url) ?: return@withContext Outcome.Inconclusive
        if (initialCode.isAccepted) return@withContext Outcome.Playable(url)

        val currentExtension = XtreamUrlFactory.parsePlaybackUrl(url)?.containerExtension
        val tried = mutableListOf(currentExtension ?: "?")

        val fallbackEligible = (kind == XtreamStreamKind.SERIES || kind == XtreamStreamKind.MOVIE) &&
            (initialCode == 403 || initialCode == 404)
        if (!fallbackEligible) {
            return@withContext Outcome.Rejected(initialCode, tried, advisory = false)
        }

        var lastCode = initialCode
        val candidates = (listOfNotNull(preferredExtension) + XtreamUrlFactory.SERIES_FALLBACK_EXTENSIONS)
            .map { it.lowercase() }
            .distinct()
            .filter { it != currentExtension }
            .take(MAX_EXTRA_PROBES)
        Log.w(
            TAG,
            "initial probe got HTTP $initialCode for ${kind?.name ?: "?"} stream " +
                "(ext=${currentExtension ?: "none"}) — trying alternates $candidates"
        )
        for (candidate in candidates) {
            if (!isActive()) return@withContext Outcome.Inconclusive
            val candidateUrl = XtreamUrlFactory.replaceContainerExtension(url, candidate) ?: continue
            tried += candidate
            val code = probeOnce(candidateUrl) ?: continue
            if (code.isAccepted) {
                Log.w(TAG, "extension fallback .$candidate accepted after HTTP $initialCode on .$currentExtension")
                return@withContext Outcome.Playable(candidateUrl, candidate, initialCode)
            }
            lastCode = code
        }
        Log.w(
            TAG,
            "all probes rejected (initial HTTP $initialCode, last HTTP $lastCode, " +
                "tried=$tried, kind=${kind?.name}) for ${XtreamUrlFactory.sanitizeLogMessage(url)}"
        )
        // Advisory keys off the INITIAL code too: this panel is known to
        // answer false 404s to back-to-back requests (per-IP rate limit), so
        // a 403 on the announced extension must stay advisory even when the
        // alternate probes came back 404/5xx.
        Outcome.Rejected(
            httpCode = initialCode,
            triedExtensions = tried,
            advisory = initialCode == 403 || lastCode == 401 || lastCode == 403,
        )
    }

    /** One GET probe. Returns the HTTP code, or null on any I/O failure. */
    private fun probeOnce(url: String): Int? {
        val safeUrl = XtreamUrlFactory.sanitizeLogMessage(url)
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .build()
            probeClient.newCall(request).execute().use { it.code }
        }
            .onSuccess { code ->
                if (code.isAccepted) {
                    Log.i(TAG, "probe OK ($code) $safeUrl")
                } else {
                    Log.w(TAG, "probe REJECTED ($code) $safeUrl")
                }
            }
            .onFailure { e ->
                Log.w(TAG, "probe inconclusive (${e.javaClass.simpleName}: ${e.message}) $safeUrl")
            }
            .getOrNull()
    }

    private val Int.isAccepted: Boolean
        get() = this !in REJECT_CODES && this !in 500..599

    private companion object {
        const val TAG = "XtreamProber"

        // Initial probe + at most 2 alternates = 3 requests per playback
        // attempt, the ceiling the panel's per-IP rate limiting tolerates.
        const val MAX_EXTRA_PROBES = 2
        val REJECT_CODES = setOf(401, 403, 404)
    }
}
