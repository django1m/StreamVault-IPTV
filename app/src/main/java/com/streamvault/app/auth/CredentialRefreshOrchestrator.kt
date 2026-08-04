package com.streamvault.app.auth

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamvault.app.vpn.VpnReadinessGate
import com.streamvault.app.vpn.allowsPanelCalls
import com.streamvault.data.remote.fuziontv.FuziontvApiException
import com.streamvault.data.remote.xtream.XtreamAuthInvalidationBus
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reacts to Xtream 401/403 signals published on [XtreamAuthInvalidationBus] by
 * fetching fresh credentials from the FuzionTV admin backend, persisting them
 * into the active provider, and — when the admin backend itself reports the
 * device as revoked — clearing the activation so AuthGate redirects to the
 * activation screen.
 *
 * Throttling: a single refresh runs at a time, and a 30s cool-down prevents
 * a 401 storm (zap mash, parallel sync) from hammering the admin backend. The
 * Xtream call site that triggered the refresh still fails (with the original
 * `XtreamAuthenticationException`) — the user sees one transient error notice;
 * the next user action picks up the refreshed credentials and succeeds.
 */
@Singleton
class CredentialRefreshOrchestrator(
    private val authInvalidationBus: XtreamAuthInvalidationBus,
    private val device: FuziontvDeviceRepositoryImpl,
    private val providerRepository: ProviderRepository,
    private val vpnGate: VpnReadinessGate,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        authInvalidationBus: XtreamAuthInvalidationBus,
        device: FuziontvDeviceRepositoryImpl,
        providerRepository: ProviderRepository,
        vpnGate: VpnReadinessGate,
    ) : this(
        authInvalidationBus = authInvalidationBus,
        device = device,
        providerRepository = providerRepository,
        vpnGate = vpnGate,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val mutex = Mutex()
    private var collectorJob: Job? = null

    @Volatile
    private var lastRunAt: Long = 0L

    override fun onStart(owner: LifecycleOwner) {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            authInvalidationBus.signals.collect {
                handleSignal()
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun handleSignal() {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < COOLDOWN_MS) {
            return
        }
        mutex.withLock {
            val freshNow = System.currentTimeMillis()
            if (freshNow - lastRunAt < COOLDOWN_MS) return@withLock
            // VPN requis mais pas prêt : rejouer loginXtream partirait sur l'IP
            // bannie (ou blackholerait) et ne ferait que brûler le rate-limit du
            // panel. On saute CE round SANS avancer lastRunAt, pour que le
            // premier 401/403 après tunnel vérifié relance sans être mangé par
            // le cooldown.
            val gate = vpnGate.awaitVpnForControlPlane(timeoutMs = 0)
            if (!gate.allowsPanelCalls()) {
                Log.i(TAG, "credential refresh skipped this round (vpn gate=$gate)")
                return@withLock
            }
            lastRunAt = freshNow
            refreshCredentialsOnce()
        }
    }

    private suspend fun refreshCredentialsOnce() {
        // If the device is not even activated, nothing to refresh — let
        // AuthGate route to the activation screen on its own.
        if (!device.isActivated()) return

        val response = try {
            device.fetchCredentials()
        } catch (e: FuziontvApiException) {
            if (e.httpCode == 401 || e.httpCode == 403) {
                Log.w(TAG, "Admin backend revoked device on credential refresh (${e.errorCode})")
                runCatching { device.clearActivation() }
            } else {
                Log.w(TAG, "Credential refresh failed: HTTP ${e.httpCode}", e)
            }
            return
        } catch (e: Exception) {
            Log.w(TAG, "Credential refresh failed", e)
            return
        }

        val xtream = response.xtream
        val active = try {
            providerRepository.getActiveProvider().first()
        } catch (e: Exception) {
            Log.w(TAG, "getActiveProvider failed during refresh", e)
            return
        }
        // We can't cheaply diff the password against the local copy (it's
        // encrypted at rest and the public-domain projection masks it), so
        // always replay loginXtream on a signal. The 30 s cooldown caps the
        // cost; loginXtream is the same call HeartbeatWorker uses on
        // forceSync.

        val result = providerRepository.loginXtream(
            serverUrl = xtream.host,
            username = xtream.username,
            password = xtream.password,
            name = active?.name?.takeIf { it.isNotBlank() } ?: "FuzionTV",
            xtreamFastSyncEnabled = false,
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            id = active?.id
        )
        when (result) {
            is Result.Success -> Log.i(TAG, "Xtream credentials refreshed from admin backend")
            is Result.Error -> Log.w(TAG, "loginXtream after refresh failed: ${result.message}")
            Result.Loading -> Unit
        }
    }

    companion object {
        private const val TAG = "CredRefreshOrch"
        private const val COOLDOWN_MS = 30_000L
    }
}
