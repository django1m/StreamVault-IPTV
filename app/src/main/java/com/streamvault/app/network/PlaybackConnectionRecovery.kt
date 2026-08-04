package com.streamvault.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.util.Log
import com.streamvault.app.di.PlaybackHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-heals the dedicated playback OkHttp client after a connectivity blip.
 *
 * The playback Dispatcher/ConnectionPool live on a process-global @Singleton
 * client. After a network drop, the pool can keep half-dead keep-alive
 * connections that still count against `maxRequestsPerHost`, stalling every new
 * stream until the process is killed (a box reboot). On a `lost → available`
 * transition we cancel stuck calls and evict the pool so playback recovers
 * WITHOUT a reboot.
 *
 * Scope is deliberately narrow: we only act on a real lost→available transition
 * (the old sockets are already dead by then), so we never cancel a healthy
 * in-flight stream during steady-state playback.
 */
@Singleton
class PlaybackConnectionRecovery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:PlaybackHttpClient private val playbackClient: OkHttpClient,
) {
    private val wasLost = AtomicBoolean(false)
    @Volatile private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            wasLost.set(true)
        }

        override fun onAvailable(network: Network) {
            if (wasLost.compareAndSet(true, false)) {
                runCatching {
                    playbackClient.dispatcher.cancelAll()
                    playbackClient.connectionPool.evictAll()
                    Log.i(TAG, "network restored — playback pool evicted")
                }.onFailure { Log.w(TAG, "playback pool eviction failed", it) }
            }
        }
    }

    fun start() {
        if (registered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            registered = true
        }.onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    companion object {
        private const val TAG = "PlaybackConnRecovery"
    }
}
