package com.streamvault.app.startup

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamvault.app.R
import com.streamvault.app.update.AppUpdateChecker
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val FORTY_EIGHT_HOURS_MS = 48L * 60 * 60 * 1000L
private const val THROTTLE_MS = 30_000L

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val appUpdateChecker: AppUpdateChecker,
    private val preferencesRepository: PreferencesRepository,
    private val providerRepository: ProviderRepository,
    private val syncManager: SyncManager,
    private val snackbar: SyncSnackbarController,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : DefaultLifecycleObserver {

    @Volatile
    private var lastRunAt = 0L

    override fun onStart(owner: LifecycleOwner) {
        onAppStart()
    }

    fun onAppStart() {
        val now = clock()
        if (now - lastRunAt < THROTTLE_MS) return
        lastRunAt = now

        applicationScope.launch { checkForAppUpdate() }
        applicationScope.launch { checkForStaleSync() }
    }

    private suspend fun checkForAppUpdate() {
        val result = try {
            appUpdateChecker.fetchLatestRelease()
        } catch (e: Exception) {
            return
        }
        if (result is Result.Success) {
            val info = result.data
            preferencesRepository.setCachedAppUpdateRelease(
                versionName = info.versionName,
                versionCode = info.versionCode,
                releaseUrl = info.releaseUrl,
                downloadUrl = info.downloadUrl,
                releaseNotes = info.releaseNotes,
                publishedAt = info.publishedAt
            )
        }
    }

    private suspend fun checkForStaleSync() {
        val provider = try {
            providerRepository.getActiveProvider().first()
        } catch (e: Exception) {
            return
        } ?: return
        if (syncManager.isAnySyncActive()) return
        if (clock() - provider.lastSyncedAt < FORTY_EIGHT_HOURS_MS) return

        snackbar.emit(SyncSnackbarEvent.SyncStarted)
        val result = try {
            syncManager.sync(
                providerId = provider.id,
                force = false,
                epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND
            )
        } catch (e: Exception) {
            snackbar.emit(SyncSnackbarEvent.SyncFailed(R.string.startup_sync_failed))
            return
        }
        when {
            result is Result.Success -> snackbar.emit(SyncSnackbarEvent.SyncSuccess)
            result is Result.Error -> snackbar.emit(
                SyncSnackbarEvent.SyncFailed(R.string.startup_sync_failed)
            )
        }
    }
}
