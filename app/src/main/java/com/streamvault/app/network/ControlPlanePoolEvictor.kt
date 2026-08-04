package com.streamvault.app.network

import android.util.Log
import com.streamvault.app.di.PlaybackHttpClient
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Purge les sockets keep-alive INACTIFS des deux pools HTTP à chaque
 * transition du tunnel WireGuard. Le tunnel est app-scoped : une connexion
 * poolée ouverte sur la route précédente (interface résidentielle avant
 * tunnel-up, route tunnel après teardown) reste réutilisable jusqu'à 5 min et
 * épingle silencieusement le trafic sur le mauvais chemin — y compris le probe
 * HTTP du [com.streamvault.app.vpn.VpnTunnelVerifier], qui pourrait « prouver »
 * le tunnel avec un socket qui ne l'a jamais traversé.
 *
 * evictAll() ne ferme que les connexions inactives — les appels en vol
 * (heartbeats, syncs catalogue, probe du verifier) ne sont jamais annulés.
 * Surtout pas de dispatcher.cancelAll() ici.
 */
@Singleton
class ControlPlanePoolEvictor @Inject constructor(
    private val controlPlaneClient: OkHttpClient,
    @param:PlaybackHttpClient private val playbackClient: OkHttpClient,
) {
    fun onTunnelTransition(up: Boolean) {
        runCatching { controlPlaneClient.connectionPool.evictAll() }
            .onFailure { Log.w(TAG, "control-plane pool eviction failed: ${it.message}") }
        runCatching { playbackClient.connectionPool.evictAll() }
            .onFailure { Log.w(TAG, "playback pool eviction failed: ${it.message}") }
        Log.i(TAG, "connection pools evicted (tunnel ${if (up) "up" else "down"})")
    }

    private companion object { const val TAG = "PoolEvictor" }
}
