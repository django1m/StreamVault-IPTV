package com.streamvault.app.work

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamvault.app.BuildConfig
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast foreground heartbeat: keeps `devices.last_seen_at` and the health / VPN /
 * playing telemetry fresh on the panel while the app is on screen, and drains
 * any queued remote commands as a backup to the real-time long-poll
 * ([CommandChannelController]).
 *
 * Interval is 10 s while foreground so the panel reflects box state — and, as a
 * fallback, executes a command — within ~10 s of it changing, instead of the
 * 90 s it used to take. (The webapp does not rate-limit /heartbeat, so this
 * cadence is safe server-side.) Backgrounded, the 15-min [HeartbeatWorker] is
 * the floor. The old lease-pool rationale (pool.js) is gone since the move to
 * the direct credential model — freshness here is now purely about panel
 * reactivity.
 */
@Singleton
class ForegroundHeartbeatController @Inject constructor(
    private val device: FuziontvDeviceRepositoryImpl,
    private val healthSnapshot: com.streamvault.app.diagnostics.DeviceHealthSnapshot,
    private val commandExecutor: DeviceCommandExecutor,
    private val vpnManager: com.streamvault.app.vpn.VpnManager,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        // Retour au premier plan : re-vérifier le tunnel VPN immédiatement (le
        // handshake a pu se périmer pendant l'arrière-plan) pour que la première
        // lecture soit instantanée, au lieu d'attendre que l'utilisateur lance
        // un flux. Indépendant de la boucle heartbeat ci-dessous.
        vpnManager.onAppForegrounded()
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                pingHeartbeat()
                delay(INTERVAL_MS)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }

    private suspend fun pingHeartbeat() {
        val deviceId = try { device.deviceId() } catch (_: Exception) { return }
        val token = try { device.deviceToken() } catch (_: Exception) { null } ?: return
        try {
            val res = device.api.heartbeat(
                deviceId, token, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME,
                health = runCatching { healthSnapshot.capture() }.getOrNull(),
                vpnAutoActivated = vpnManager.consumePendingAutoActivated().takeIf { it },
                vpn = runCatching { vpnManager.vpnStateForHeartbeat() }.getOrNull(),
            )
            vpnManager.applyMode(res.vpnMode)
            vpnManager.applyAssignedRegion(res.vpnRegion)
            if (res.commands.isNotEmpty()) {
                commandExecutor.executeAll(res.commands)
            }
        } catch (e: Exception) {
            Log.d(TAG, "foreground heartbeat failed (non-fatal): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FgHeartbeat"
        // Fast panel reactivity while on screen. /heartbeat is not rate-limited
        // server-side, and each ping is one small POST + one row UPDATE.
        private const val INTERVAL_MS = 10_000L
    }
}
