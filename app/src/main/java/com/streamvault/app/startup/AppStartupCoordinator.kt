package com.streamvault.app.startup

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamvault.app.R
import com.streamvault.app.update.AppUpdateChecker
import com.streamvault.app.vpn.VpnReadinessGate
import com.streamvault.app.vpn.allowsPanelCalls
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppStartupCoord"
private const val FORTY_EIGHT_HOURS_MS = 48L * 60 * 60 * 1000L
private const val THROTTLE_MS = 30_000L
// Let the first screen settle before any startup IO: a stale-catalogue sync
// kicking off at t=0 competes with first composition + Room reads on the
// 2 GB box. Catalogue freshness can wait three seconds.
private const val STARTUP_WORK_DELAY_MS = 3_000L
// One full VPN double-verify cycle — same bound as the activation gate.
private const val STALE_SYNC_VPN_GATE_MS = 120_000L

@Singleton
class AppStartupCoordinator(
    private val appUpdateChecker: AppUpdateChecker,
    private val preferencesRepository: PreferencesRepository,
    private val providerRepository: ProviderRepository,
    private val syncManager: SyncManager,
    private val snackbar: SyncSnackbarController,
    private val vpnGate: VpnReadinessGate,
    private val applicationScope: CoroutineScope,
    private val clock: () -> Long
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        appUpdateChecker: AppUpdateChecker,
        preferencesRepository: PreferencesRepository,
        providerRepository: ProviderRepository,
        syncManager: SyncManager,
        snackbar: SyncSnackbarController,
        vpnGate: VpnReadinessGate,
    ) : this(
        appUpdateChecker = appUpdateChecker,
        preferencesRepository = preferencesRepository,
        providerRepository = providerRepository,
        syncManager = syncManager,
        snackbar = snackbar,
        vpnGate = vpnGate,
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = { System.currentTimeMillis() }
    )

    @Volatile
    private var lastRunAt = 0L

    override fun onStart(owner: LifecycleOwner) {
        onAppStart()
    }

    fun onAppStart() {
        val now = clock()
        if (now - lastRunAt < THROTTLE_MS) {
            Log.i(TAG, "onAppStart skipped: throttled (${(now - lastRunAt) / 1000}s since last run, need ${THROTTLE_MS / 1000}s)")
            return
        }
        lastRunAt = now
        Log.i(TAG, "onAppStart fired: checking app update + stale sync")

        applicationScope.launch {
            delay(STARTUP_WORK_DELAY_MS)
            checkForAppUpdate()
        }
        applicationScope.launch {
            delay(STARTUP_WORK_DELAY_MS)
            checkForStaleSync()
        }
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
            Log.w(TAG, "checkForStaleSync skipped: getActiveProvider threw", e)
            return
        }
        if (provider == null) {
            Log.i(TAG, "checkForStaleSync skipped: no active provider")
            return
        }
        if (syncManager.isAnySyncActive()) {
            Log.i(TAG, "checkForStaleSync skipped: another sync is already in progress")
            return
        }
        val ageMs = clock() - provider.lastSyncedAt
        if (ageMs < FORTY_EIGHT_HOURS_MS) {
            Log.i(TAG, "checkForStaleSync skipped: lastSyncedAt is fresh (${ageMs / 60_000}min ago, threshold ${FORTY_EIGHT_HOURS_MS / 60_000}min)")
            return
        }

        // Coroutine de fond : attendre le tunnel est gratuit, et un sync de
        // catalogue sur l'IP bannie est un échec multi-minutes garanti. Si le
        // VPN requis ne monte pas, on saute — retenté au prochain foreground.
        val gate = vpnGate.awaitVpnForControlPlane(timeoutMs = STALE_SYNC_VPN_GATE_MS)
        if (!gate.allowsPanelCalls()) {
            Log.i(TAG, "checkForStaleSync skipped: VPN gate=$gate")
            return
        }

        Log.i(TAG, "checkForStaleSync running: providerId=${provider.id} ageMs=$ageMs")
        snackbar.emit(SyncSnackbarEvent.SyncStarted)
        val result = try {
            syncManager.sync(
                providerId = provider.id,
                force = false,
                epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkForStaleSync threw", e)
            snackbar.emit(SyncSnackbarEvent.SyncFailed(R.string.startup_sync_failed))
            return
        }
        when {
            result is Result.Success -> {
                Log.i(TAG, "checkForStaleSync success")
                snackbar.emit(SyncSnackbarEvent.SyncSuccess)
            }
            result is Result.Error -> {
                Log.w(TAG, "checkForStaleSync failed: ${result.message}")
                snackbar.emit(SyncSnackbarEvent.SyncFailed(R.string.startup_sync_failed))
            }
        }
    }
}
