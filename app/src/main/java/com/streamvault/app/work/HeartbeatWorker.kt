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

    override suspend fun doWork(): Result {
        refreshAppUpdateCache(appUpdateChecker, preferencesRepository)

        val deviceId = device.deviceId()
        val token = device.deviceToken() ?: return Result.success()
        return try {
            val res = device.api.heartbeat(deviceId, token, BuildConfig.VERSION_CODE)
            if (res.forceSync) {
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
            Result.success()
        } catch (e: FuziontvApiException) {
            if (e.httpCode == 401 || e.httpCode == 403) {
                Log.w(TAG, "Device disabled or token revoked (${e.errorCode})")
                device.clearActivation()
                Result.success()
            } else {
                Log.w(TAG, "Heartbeat failed (${e.httpCode})", e)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat error", e)
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
