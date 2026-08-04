package com.streamvault.app.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamvault.app.BuildConfig
import com.streamvault.app.diagnostics.CrashReporter
import com.streamvault.data.remote.fuziontv.DiagnosticItem
import com.streamvault.data.remote.fuziontv.FuziontvApiException
import com.streamvault.data.repository.DiagnosticsRepository
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsUploadWorkerEntryPoint {
    fun fuziontvDevice(): FuziontvDeviceRepositoryImpl
    fun diagnosticsRepository(): DiagnosticsRepository
    fun crashReporter(): CrashReporter
}

/**
 * Best-effort upload of the local diagnostics queue. Drains persisted crashes
 * into the queue first, then flushes the queue in batches. Same wiring style as
 * [HeartbeatWorker] (CoroutineWorker + EntryPointAccessors, not @HiltWorker).
 */
class DiagnosticsUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(
        appContext, DiagnosticsUploadWorkerEntryPoint::class.java
    )
    private val device = entryPoint.fuziontvDevice()
    private val repository = entryPoint.diagnosticsRepository()
    private val crashReporter = entryPoint.crashReporter()

    override suspend fun doWork(): Result {
        runCatching { crashReporter.drainPendingCrashes() }

        val deviceId = device.deviceId()
        val token = device.deviceToken() ?: return Result.success()

        return try {
            while (true) {
                val batch = repository.pending(BATCH_SIZE)
                if (batch.isEmpty()) break
                val items = batch.map { e ->
                    DiagnosticItem(
                        severity = e.severity,
                        category = e.category,
                        message = e.message,
                        context = repository.contextOf(e),
                        appVersionCode = BuildConfig.VERSION_CODE,
                        appVersionName = BuildConfig.VERSION_NAME,
                        occurredAtMs = e.occurredAtMs,
                    )
                }
                device.api.reportDiagnostics(deviceId, token, items)
                repository.delete(batch.map { it.id })
                if (batch.size < BATCH_SIZE) break
            }
            Result.success()
        } catch (e: FuziontvApiException) {
            // Token revoked / device disabled — drop silently (the queue will be
            // re-activated later); other HTTP errors are transient, so retry.
            if (e.httpCode == 401 || e.httpCode == 403) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.d(TAG, "diagnostics upload failed (will retry): ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DiagnosticsUpload"
        private const val UNIQUE_PERIODIC = "fuziontv_diagnostics_upload"
        private const val UNIQUE_ONESHOT = "fuziontv_diagnostics_upload_now"
        private const val BATCH_SIZE = 50

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DiagnosticsUploadWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<DiagnosticsUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT, ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
