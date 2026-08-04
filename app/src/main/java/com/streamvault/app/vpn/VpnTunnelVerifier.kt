package com.streamvault.app.vpn

import android.util.Log
import com.streamvault.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confirms that a freshly-connected WireGuard tunnel actually passes traffic —
 * not just that setState(UP) returned. Two gates:
 *
 *  1. Handshake: poll [VpnController.peerStats] a few times after UP. WireGuard
 *     retries handshakes every ~5 s; a live PIA endpoint completes one in well
 *     under 5 s, a dead endpoint never does. 30 s is decisive and bounded.
 *  2. HTTP round-trip: a single GET to OUR OWN backend through the tunnel. It
 *     proves DNS-through-tunnel (the /32 re-add trick) + TCP + TLS end to end,
 *     and — critically — never touches the provider's rate-limited player_api.
 *
 * VERIFIED = handshake seen AND HTTP round-trip OK. There is NO steady-state
 * polling loop (battery/CPU on a 2 GB box): freshness is re-read opportunistically
 * once per heartbeat by [VpnManager].
 */
@Singleton
class VpnTunnelVerifier @Inject constructor(
    private val controller: VpnController,
    okHttpClient: OkHttpClient,
) {
    enum class Result { VERIFIED, NO_HANDSHAKE, HANDSHAKE_BUT_NO_HTTP }

    // A short-timeout clone of the shared client (mirrors XtreamPlaybackProber).
    private val probeClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val probeUrl =
        "${BuildConfig.FUZIONTV_BASE_URL.trimEnd('/')}/api/v1/updates/latest" +
            "?current=${BuildConfig.VERSION_CODE}&variant=${BuildConfig.APP_VARIANT}"

    /** Call ONLY after connect() returned true. Total worst-case budget ~38 s. */
    suspend fun verify(peer: VpnPeerConfig): Result {
        var handshake = false
        var prev = 0L
        for (at in HANDSHAKE_POLL_AT_MS) {
            delay(at - prev)   // poll at 2/5/10/20/30 s after UP
            prev = at
            val stats = controller.peerStats(peer.serverPublicKey)
            if (stats?.handshakeAgeSec != null && stats.handshakeAgeSec <= HANDSHAKE_STALE_SEC) {
                handshake = true
                break
            }
        }
        if (!handshake) return Result.NO_HANDSHAKE
        // Handshake seen → the WireGuard transport is up. A single HTTP round-trip
        // can still time out on a freshly-raised tunnel even though it works: DNS
        // through the tunnel is cold, and on Android TV the resolver first attempts
        // opportunistic DNS-over-TLS to the pushed PIA resolver (which has no DoT),
        // fails, then falls back to plain DNS — all inside the 8 s probe budget.
        // Observed live: the probe timed out at ~7 s, yet the exact same tunnel
        // served a full catalogue sync seconds later. Retry the round-trip a few
        // times so a transient first-probe timeout is not reported as a broken
        // tunnel (which would tear it down and count a spurious Class-A failure).
        // A healthy tunnel answers on the first attempt (< 1 s), so the retries only
        // cost wall-clock when there is a genuine problem.
        repeat(HTTP_PROBE_ATTEMPTS) { attempt ->
            if (httpReachable()) return Result.VERIFIED
            if (attempt < HTTP_PROBE_ATTEMPTS - 1) delay(HTTP_PROBE_RETRY_DELAY_MS)
        }
        return Result.HANDSHAKE_BUT_NO_HTTP
    }

    /**
     * Single GET to our backend. Any HTTP response (2xx–4xx) proves the round
     * trip; only an I/O failure (blackhole / no route) counts as unreachable.
     * Usable both through the tunnel (verification) and with the tunnel DOWN
     * (Class A "is the plain internet even up?" check).
     */
    suspend fun httpReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            probeClient.newCall(Request.Builder().url(probeUrl).build()).execute().use { it.code in 200..499 }
        }.getOrElse {
            Log.d(TAG, "http probe failed: ${it.message}")
            false
        }
    }

    private companion object {
        const val TAG = "VpnTunnelVerifier"
        val HANDSHAKE_POLL_AT_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)
        const val HANDSHAKE_STALE_SEC = 190L
        // A cold tunnel's first HTTP probe can time out on DNS warmup even though
        // the tunnel works. Retry before declaring the tunnel unable to pass HTTP.
        const val HTTP_PROBE_ATTEMPTS = 3
        const val HTTP_PROBE_RETRY_DELAY_MS = 1_500L
    }
}
