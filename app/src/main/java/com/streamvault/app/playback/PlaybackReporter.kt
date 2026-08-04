package com.streamvault.app.playback

import android.util.Log
import com.streamvault.app.BuildConfig
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports to the FuzionTV admin what the device is currently playing.
 *
 * Strategy: when playback starts (PlayerViewModel.prepare), we POST a
 * heartbeat with playingKind/playingStreamId, then refresh it every
 * REPORT_INTERVAL_MS while playback is alive. When playback stops
 * (PlayerViewModel.onCleared), we POST one final heartbeat with
 * playingStreamId=null so the admin UI flips back to "idle" immediately.
 *
 * All network calls are best-effort — failures never break playback.
 */
@Singleton
class PlaybackReporter @Inject constructor(
    private val device: FuziontvDeviceRepositoryImpl,
    private val commandExecutor: com.streamvault.app.work.DeviceCommandExecutor,
    private val guard: com.streamvault.app.vpn.VpnRegionHealthGuard,
    // Lazy to keep the Hilt graph acyclic (DeviceCommandExecutor → VpnManager).
    private val vpnManager: dagger.Lazy<com.streamvault.app.vpn.VpnManager>,
) {
    companion object {
        private const val TAG = "PlaybackReporter"
        private const val REPORT_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private var currentKind: String? = null
    private var currentStreamId: Long? = null

    /** Called when the PlayerViewModel begins (or switches) playback. */
    fun start(kind: String, streamId: Long) {
        if (streamId <= 0L) return
        val normalizedKind = normalize(kind) ?: return
        // Let the region-health guard know what's playing so it never tears a
        // live tunnel for an on-demand-only ban fallback.
        guard.activePlayback = normalizedKind
        scope.launch {
            mutex.withLock {
                if (currentKind == normalizedKind && currentStreamId == streamId && job?.isActive == true) {
                    return@withLock
                }
                currentKind = normalizedKind
                currentStreamId = streamId
                job?.cancel()
                job = scope.launch { runReportLoop(normalizedKind, streamId) }
            }
        }
    }

    /** Called when playback ends (release / onCleared). */
    fun stop() {
        guard.activePlayback = null
        scope.launch {
            mutex.withLock {
                job?.cancel()
                job = null
                currentKind = null
                currentStreamId = null
            }
            // One last heartbeat with playing=null = explicit stop signal.
            sendOnce(null, null)
        }
    }

    private suspend fun runReportLoop(kind: String, streamId: Long) {
        // Fire one report immediately, then every REPORT_INTERVAL_MS.
        try {
            sendOnce(kind, streamId)
            while (true) {
                delay(REPORT_INTERVAL_MS)
                sendOnce(kind, streamId)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected on stop/switch.
        } catch (e: Exception) {
            Log.w(TAG, "report loop crashed", e)
        }
    }

    private suspend fun sendOnce(kind: String?, streamId: Long?) {
        val deviceId = try { device.deviceId() } catch (_: Exception) { return }
        val token = try { device.deviceToken() } catch (_: Exception) { null } ?: return
        try {
            val res = device.api.heartbeatWithPlayback(
                deviceId = deviceId,
                deviceToken = token,
                appVersionCode = BuildConfig.VERSION_CODE,
                playingKind = kind,
                playingStreamId = streamId,
                appVersionName = BuildConfig.VERSION_NAME,
                vpn = runCatching { vpnManager.get().vpnStateForHeartbeat() }.getOrNull(),
            )
            // The server claims queued remote commands on EVERY heartbeat
            // POST, and delivery is at-most-once: if this reporter dropped
            // them, a command claimed during playback would be lost forever.
            if (res.commands.isNotEmpty()) {
                commandExecutor.executeAll(res.commands)
            }
        } catch (e: Exception) {
            Log.d(TAG, "report failed (non-fatal): ${e.message}")
        }
    }

    private fun normalize(kind: String): String? = when (kind.uppercase()) {
        "LIVE", "CHANNEL"           -> "live"
        "MOVIE", "VOD"              -> "movie"
        "SERIES", "SERIES_EPISODE",
        "EPISODE"                   -> "series"
        else                        -> null
    }
}
