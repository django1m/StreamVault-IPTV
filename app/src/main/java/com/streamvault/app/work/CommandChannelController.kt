package com.streamvault.app.work

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time command channel. While the app is foreground, it keeps a long-poll
 * GET on `/api/v1/device/commands/wait` open: the server holds it until an admin
 * queues a command (delivered in ~1 s) or a ~25 s window elapses (returns empty
 * → we immediately reconnect). This is what makes a panel action run on the box
 * in ~1 s instead of waiting for the next heartbeat.
 *
 * The heartbeat loops ([ForegroundHeartbeatController] foreground,
 * [HeartbeatWorker] background) remain the fallback command drainer. Delivery is
 * at-most-once server-side, so a command is handed out on exactly one channel,
 * and every action in [DeviceCommandExecutor] is idempotent — so racing the two
 * channels is safe.
 *
 * Foreground-only by design: holding a persistent connection while backgrounded
 * would require a foreground service. Backgrounded, commands fall back to the
 * 15-min [HeartbeatWorker].
 */
@Singleton
class CommandChannelController @Inject constructor(
    private val device: FuziontvDeviceRepositoryImpl,
    private val commandExecutor: DeviceCommandExecutor,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            val deviceId = try { device.deviceId() } catch (_: Exception) { null }
            val token = try { device.deviceToken() } catch (_: Exception) { null }
            if (deviceId == null || token == null) {
                // Not activated yet — wait, don't hammer.
                delay(NOT_READY_RETRY_MS)
                continue
            }
            try {
                val res = device.api.awaitCommands(deviceId, token)
                if (res.commands.isNotEmpty()) {
                    commandExecutor.executeAll(res.commands)
                }
                // Healthy round (command or idle-timeout): reset backoff and
                // reconnect at once. An idle round already blocked ~25 s
                // server-side, so this is not a hot loop.
                backoffMs = INITIAL_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e // foreground lost / app stopping — exit cleanly
            } catch (e: Exception) {
                // Network down, server 5xx, CF hiccup… back off so we don't spin.
                Log.d(TAG, "command long-poll failed (non-fatal): ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    companion object {
        private const val TAG = "CmdChannel"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val NOT_READY_RETRY_MS = 15_000L
    }
}
