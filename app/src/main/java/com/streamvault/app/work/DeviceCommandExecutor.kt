package com.streamvault.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import coil3.SingletonImageLoader
import com.streamvault.app.di.MainPlayerEngine
import com.streamvault.data.remote.fuziontv.DeviceCommandDto
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.player.PlayerEngine
import com.streamvault.player.RoutingPlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Executes the remote commands delivered in the heartbeat response. The
 * action set is a strict whitelist mirrored by the server (route + SQL CHECK):
 * an unknown action is acked `failed` and never executed. Every action is
 * idempotent — the server re-delivers commands whose ack was lost.
 */
@Singleton
class DeviceCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val device: FuziontvDeviceRepositoryImpl,
    private val providerRepository: ProviderRepository,
    private val syncManager: SyncManager,
    private val vpnManager: com.streamvault.app.vpn.VpnManager,
    @MainPlayerEngine private val playerEngine: PlayerEngine,
) {
    suspend fun executeAll(commands: List<DeviceCommandDto>) {
        for (command in commands) {
            execute(command)
        }
    }

    private suspend fun execute(command: DeviceCommandDto) {
        Log.i(TAG, "executing remote command ${command.action} (#${command.id})")
        if (command.action == "restart_app") {
            // Ack BEFORE killing the process — afterwards nothing runs to send it.
            ackSafely(command.id, ok = true, result = "redémarrage déclenché")
            restartApp()
            return
        }
        val (ok, result) = try {
            when (command.action) {
                "resync" -> runResync()
                "clear_cache" -> runClearCache()
                "force_engine" -> runForceEngine(command.params?.get("engine"))
                "vpn_on" -> { vpnManager.forceOn(); true to "VPN forcé ON" }
                "vpn_off" -> { vpnManager.forceOff(); true to "VPN forcé OFF" }
                else -> false to "action inconnue : ${command.action}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "remote command ${command.action} failed", e)
            false to (e.message ?: e.javaClass.simpleName).take(200)
        }
        ackSafely(command.id, ok, result)
    }

    private suspend fun runResync(): Pair<Boolean, String> {
        val provider = providerRepository.getActiveProvider().first()
            ?: return false to "aucun provider actif"
        val result = syncManager.sync(
            providerId = provider.id,
            force = true,
            epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND,
        )
        return when (result) {
            is com.streamvault.domain.model.Result.Success -> true to "catalogue resynchronisé"
            is com.streamvault.domain.model.Result.Error -> false to result.message.take(200)
            else -> false to "résultat inattendu"
        }
    }

    private suspend fun runClearCache(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val imageLoader = SingletonImageLoader.get(context)
        runCatching { imageLoader.memoryCache?.clear() }
        runCatching { imageLoader.diskCache?.clear() }
        true to "cache images vidé"
    }

    private fun runForceEngine(engine: String?): Pair<Boolean, String> {
        val routing = playerEngine as? RoutingPlayerEngine
            ?: return false to "engine routing indisponible"
        val preference = when (engine?.lowercase()) {
            "media3" -> RoutingPlayerEngine.EnginePreference.MEDIA3
            "libvlc" -> RoutingPlayerEngine.EnginePreference.LIBVLC
            else -> return false to "moteur inconnu : $engine"
        }
        // Next-prepare-only scope on purpose: a persistent override could
        // durably degrade a box if misused; the routing rules resume after
        // one playback.
        routing.forceEngineForNextPrepare(preference)
        return true to "moteur ${preference.name.lowercase()} forcé pour la prochaine lecture"
    }

    /**
     * Self-restart: schedule the (leanback) launcher activity via
     * AlarmManager, then terminate the process — the reliable pattern on
     * Android TV where no Play Services / Phoenix helper is available.
     */
    private fun restartApp() {
        val pm = context.packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: pm.getLaunchIntentForPackage(context.packageName)
            ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            RESTART_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pendingIntent)
        kotlin.system.exitProcess(0)
    }

    private suspend fun ackSafely(commandId: Long, ok: Boolean, result: String?) {
        runCatching {
            val deviceId = device.deviceId()
            val token = device.deviceToken() ?: return
            device.api.commandAck(deviceId, token, commandId, ok, result)
        }.onFailure { Log.w(TAG, "command ack failed for #$commandId: ${it.message}") }
    }

    private companion object {
        const val TAG = "DeviceCommandExec"
        const val RESTART_REQUEST_CODE = 4821
        const val RESTART_DELAY_MS = 800L
    }
}
