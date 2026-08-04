package com.streamvault.app.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamvault.app.BuildConfig
import com.streamvault.app.vpn.allowsPanelCalls
import com.streamvault.data.remote.fuziontv.FuziontvApiException
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HeartbeatWorkerEntryPoint {
    fun fuziontvDevice(): FuziontvDeviceRepositoryImpl
    fun providerRepository(): ProviderRepository
    fun appUpdateChecker(): com.streamvault.app.update.AppUpdateChecker
    fun preferencesRepository(): com.streamvault.data.preferences.PreferencesRepository
    fun diagnosticsRepository(): com.streamvault.data.repository.DiagnosticsRepository
    fun deviceHealthSnapshot(): com.streamvault.app.diagnostics.DeviceHealthSnapshot
    fun deviceCommandExecutor(): DeviceCommandExecutor
    fun vpnManager(): com.streamvault.app.vpn.VpnManager
}

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(
        appContext, HeartbeatWorkerEntryPoint::class.java
    )
    private val device = entryPoint.fuziontvDevice()
    private val providerRepository = entryPoint.providerRepository()
    private val appUpdateChecker = entryPoint.appUpdateChecker()
    private val preferencesRepository = entryPoint.preferencesRepository()
    private val diagnostics = entryPoint.diagnosticsRepository()
    private val healthSnapshot = entryPoint.deviceHealthSnapshot()
    private val commandExecutor = entryPoint.deviceCommandExecutor()
    private val vpnManager = entryPoint.vpnManager()

    private suspend fun reportConnectionIssue(message: String) {
        runCatching {
            diagnostics.enqueue(
                com.streamvault.domain.model.DiagnosticDraft(
                    severity = com.streamvault.domain.model.DiagnosticSeverity.WARNING,
                    category = com.streamvault.domain.model.DiagnosticCategory.CONNECTION,
                    message = message,
                    occurredAtMs = System.currentTimeMillis(),
                )
            )
        }
    }

    override suspend fun doWork(): Result {
        refreshAppUpdateCache(appUpdateChecker, preferencesRepository)

        val deviceId = device.deviceId()
        val token = device.deviceToken() ?: return Result.success()
        return try {
            val res = device.api.heartbeat(
                deviceId, token, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME,
                health = runCatching { healthSnapshot.capture() }.getOrNull(),
                vpnAutoActivated = vpnManager.consumePendingAutoActivated().takeIf { it },
                vpn = runCatching { vpnManager.vpnStateForHeartbeat() }.getOrNull(),
            )
            // Apply the authoritative VPN mode + region assignment from the server.
            vpnManager.applyMode(res.vpnMode)
            vpnManager.applyAssignedRegion(res.vpnRegion)
            if (res.forceSync) {
                // Le applyMode() ci-dessus a peut-être lancé la connexion VPN à
                // l'instant : snapshot sans attente (un worker ne dort pas). Si
                // le tunnel requis n'est pas prêt, on N'ACQUITTE PAS — le
                // serveur re-livre forceSync au prochain heartbeat.
                val gate = vpnManager.awaitVpnForControlPlane(timeoutMs = 0)
                if (!gate.allowsPanelCalls()) {
                    Log.i(TAG, "forceSync deferred (vpn gate=$gate) — no syncAck, redelivered next beat")
                } else {
                    val creds = device.api.credentials(deviceId, token)
                    providerRepository.loginXtream(
                        serverUrl = creds.xtream.host,
                        username = creds.xtream.username,
                        password = creds.xtream.password,
                        name = "FuzionTV",
                        xtreamFastSyncEnabled = false,
                        epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                    )
                    device.api.syncAck(deviceId, token)
                }
            }
            if (res.commands.isNotEmpty()) {
                commandExecutor.executeAll(res.commands)
            }
            Result.success()
        } catch (e: FuziontvApiException) {
            if (e.httpCode == 401 || e.httpCode == 403) {
                Log.w(TAG, "Device disabled or token revoked (${e.errorCode})")
                device.clearActivation()
                Result.success()
            } else {
                Log.w(TAG, "Heartbeat failed (${e.httpCode})", e)
                reportConnectionIssue("Backend FuzionTV injoignable — erreur HTTP ${e.httpCode}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat error", e)
            reportConnectionIssue("Connexion au backend FuzionTV échouée — ${e.message ?: "réseau indisponible"}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val UNIQUE_NAME = "fuziontv_heartbeat"

        suspend fun refreshAppUpdateCache(
            checker: com.streamvault.app.update.AppUpdateChecker,
            prefs: com.streamvault.data.preferences.PreferencesRepository
        ) {
            when (val result = checker.fetchLatestRelease()) {
                is com.streamvault.domain.model.Result.Success -> {
                    val info = result.data
                    prefs.setCachedAppUpdateRelease(
                        versionName = info.versionName,
                        versionCode = info.versionCode,
                        releaseUrl = info.releaseUrl,
                        downloadUrl = info.downloadUrl,
                        releaseNotes = info.releaseNotes,
                        publishedAt = info.publishedAt
                    )
                }
                is com.streamvault.domain.model.Result.Error,
                com.streamvault.domain.model.Result.Loading -> Unit
            }
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
