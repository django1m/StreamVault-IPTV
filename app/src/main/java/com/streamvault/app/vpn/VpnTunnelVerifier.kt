package com.streamvault.app.vpn

import android.util.Log
import com.streamvault.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.EventListener
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
    // Inherits its resolver, so the backend gets the DoH rescue (BackendDns).
    private val probeClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // The same probe pinned to the system resolver, without the backend
    // connect-failure listener. Through the tunnel the system resolver IS the PIA
    // DNS, and proving it answers is half the point of verifying the tunnel: a
    // DoH rescue here would pass a tunnel whose DNS is broken while playback,
    // which resolves through the system, keeps failing.
    private val tunnelProbeClient: OkHttpClient = probeClient.newBuilder()
        .dns(Dns.SYSTEM)
        .eventListener(EventListener.NONE)
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
            if (tunnelHttpReachable()) return Result.VERIFIED
            if (attempt < HTTP_PROBE_ATTEMPTS - 1) delay(HTTP_PROBE_RETRY_DELAY_MS)
        }
        return Result.HANDSHAKE_BUT_NO_HTTP
    }

    /**
     * Single GET to our backend with the tunnel DOWN (Class A "is the plain internet
     * even up?" check). Any HTTP response (2xx–4xx) proves the round trip; only an
     * I/O failure counts as unreachable. Resolves the backend with the DoH rescue, so
     * a box whose resolver filters us is not mistaken for a box with no internet.
     */
    suspend fun httpReachable(): Boolean = probe(probeClient) { it in 200..499 }

    /** The same GET through the tunnel, on the system (PIA) resolver: the [verify] semantics. */
    suspend fun tunnelHttpReachable(): Boolean = probe(tunnelProbeClient) { it in 200..499 }

    /**
     * Confirmation for [TunnelDataPlaneWatchdog]: does the tunnel carry a round trip
     * to our backend at all? ANY HTTP answer — even a 5xx during a backend outage —
     * proves DNS, TCP and TLS all made it through; only a transport failure means
     * the data plane is dead.
     */
    suspend fun tunnelCarriesBackendTraffic(): Boolean = probe(tunnelProbeClient) { true }

    private suspend fun probe(client: OkHttpClient, accept: (Int) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(probeUrl).build()).execute().use { accept(it.code) }
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
