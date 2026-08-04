package com.streamvault.app.ui.screens.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.BuildConfig
import com.streamvault.app.vpn.VpnDisplayPhase
import com.streamvault.app.vpn.VpnGateResult
import com.streamvault.app.vpn.VpnReadinessGate
import com.streamvault.app.vpn.allowsPanelCalls
import com.streamvault.data.remote.fuziontv.ActivateRequest
import com.streamvault.data.remote.fuziontv.FuziontvApiException
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Customer-facing failure buckets: the activation screen renders an
 * actionable French message per kind instead of a raw exception string —
 * the boxes are operated by non-technical customers.
 */
enum class ActivationFailureKind {
    NETWORK,
    SERVER,
    SUBSCRIPTION_EXPIRED,
    TOKEN_RESET_NEEDED,
    UNKNOWN,
}

sealed class ActivationState {
    data object Loading : ActivationState()
    data class WaitingForCode(val code: String) : ActivationState()
    data object Syncing : ActivationState()
    data object Ready : ActivationState()
    data class Disabled(val reason: String?) : ActivationState()
    data class Failed(
        val kind: ActivationFailureKind,
        val detail: String?,
        val retryInSeconds: Int? = null,
    ) : ActivationState()

    /** Attente du tunnel VPN requis avant tout contact avec le panel. */
    data class VpnPending(val phase: VpnDisplayPhase) : ActivationState()

    /** Consentement VPN Android requis ([denied] = l'utilisateur a refusé). */
    data class VpnConsent(val denied: Boolean) : ActivationState()

    /** Tunnel inconnectable ([terminal] = guard figé, intervention admin). */
    data class VpnFailed(val terminal: Boolean) : ActivationState()
}

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val device: FuziontvDeviceRepositoryImpl,
    private val providerRepository: ProviderRepository,
    private val diagnosticReporter: com.streamvault.app.diagnostics.DiagnosticReporter,
    private val vpnGate: VpnReadinessGate,
) : ViewModel() {

    private val _state = MutableStateFlow<ActivationState>(ActivationState.Loading)
    val state: StateFlow<ActivationState> = _state

    private var activationJob: Job? = null
    private var autoRetryJob: Job? = null

    /** One-shot « Continuer sans VPN » — session-scoped, jamais persisté. */
    private var skipVpnGateOnce = false

    init {
        // Auto-reprise : quand un retry programmé du guard (+3/+7 min) ou un
        // consentement accordé finit par vérifier le tunnel pendant qu'un écran
        // d'échec VPN est affiché, on repart tout seul — le pacing du guard EST
        // la boucle de retry, on n'en invente pas une deuxième.
        viewModelScope.launch {
            vpnGate.displayPhase.collect { phase ->
                val s = _state.value
                if (phase == VpnDisplayPhase.READY &&
                    (s is ActivationState.VpnFailed || s is ActivationState.VpnConsent)
                ) {
                    retry()
                }
            }
        }
    }

    fun start() {
        activationJob?.cancel()
        autoRetryJob?.cancel()
        activationJob = viewModelScope.launch {
            try {
                if (!awaitVpnGate()) return@launch
                if (device.isActivated()) {
                    // Server is SoT. Fetch /credentials and compare to the
                    // local provider before showing Ready. If the host or
                    // credentials have rotated (e.g. transition from direct
                    // Xtream creds to proxy creds), do a blocking sync so the
                    // user never sees stale stream URLs that would 404 on the
                    // upstream.
                    refreshIfServerConfigChanged()
                    return@launch
                }
                activateLoop()
            } catch (e: Exception) {
                fail(classify(e), e.message)
            }
        }
    }

    /** Re-runs the whole activation flow — wired to the on-screen button. */
    fun retry() {
        _state.value = ActivationState.Loading
        start()
    }

    /** Bouton Réessayer des écrans VPN : relance le tunnel PUIS le flux. */
    fun retryVpn() {
        vpnGate.userRetry()
        retry()
    }

    /** Bouton « Continuer sans VPN » (échappatoire support téléphonique). */
    fun continueWithoutVpn() {
        diagnosticReporter.report(
            severity = com.streamvault.domain.model.DiagnosticSeverity.WARNING,
            category = com.streamvault.domain.model.DiagnosticCategory.VPN,
            message = "Activation : poursuite sans VPN choisie par l'utilisateur",
        )
        skipVpnGateOnce = true
        retry()
    }

    /**
     * Gate VPN : quand le mode de l'appareil exige le tunnel, attend qu'il soit
     * monté ET vérifié avant de laisser partir la moindre requête panel — sinon
     * elle sort sur l'IP résidentielle (bannie) ou blackhole dans un tunnel pas
     * encore handshaké, et l'écran boucle sur des erreurs réseau.
     * @return true = poursuivre le flux d'activation.
     */
    private suspend fun awaitVpnGate(): Boolean {
        if (skipVpnGateOnce) { skipVpnGateOnce = false; return true }
        if (!vpnGate.isVpnRequired()) return true

        _state.value = ActivationState.VpnPending(VpnDisplayPhase.CONNECTING)
        val phaseJob = viewModelScope.launch {
            vpnGate.displayPhase.collect { phase ->
                _state.value = ActivationState.VpnPending(
                    if (phase == VpnDisplayPhase.VERIFYING) VpnDisplayPhase.VERIFYING
                    else VpnDisplayPhase.CONNECTING
                )
            }
        }
        val result = try {
            vpnGate.awaitVpnForControlPlane()
        } finally {
            phaseJob.cancel()
        }
        if (result.allowsPanelCalls()) {
            _state.value = ActivationState.Loading
            return true
        }
        when (result) {
            VpnGateResult.NO_NETWORK ->
                fail(ActivationFailureKind.NETWORK, "réseau indisponible (gate VPN)")
            VpnGateResult.CONSENT_PENDING -> _state.value = ActivationState.VpnConsent(denied = false)
            VpnGateResult.CONSENT_DENIED -> _state.value = ActivationState.VpnConsent(denied = true)
            VpnGateResult.TERMINAL -> _state.value = ActivationState.VpnFailed(terminal = true)
            else -> _state.value = ActivationState.VpnFailed(terminal = false)
        }
        return false
    }

    private fun classify(error: Throwable): ActivationFailureKind = when {
        error is FuziontvApiException && (error.httpCode == 401 || error.httpCode == 403) &&
            (error.errorCode == "user_expired" || error.errorCode == "user_disabled") ->
            ActivationFailureKind.SUBSCRIPTION_EXPIRED
        error is FuziontvApiException && error.httpCode in 500..599 -> ActivationFailureKind.SERVER
        error is FuziontvApiException -> ActivationFailureKind.SERVER
        error is java.io.IOException -> ActivationFailureKind.NETWORK
        else -> ActivationFailureKind.UNKNOWN
    }

    private fun fail(kind: ActivationFailureKind, detail: String?) {
        diagnosticReporter.report(
            severity = com.streamvault.domain.model.DiagnosticSeverity.ERROR,
            category = com.streamvault.domain.model.DiagnosticCategory.ACTIVATION,
            message = "Activation échouée — ${kind.name.lowercase()}",
            context = buildMap { detail?.let { put("détail", it.take(300)) } },
        )
        if (kind == ActivationFailureKind.NETWORK) {
            scheduleNetworkAutoRetry(detail)
        } else {
            _state.value = ActivationState.Failed(kind, detail)
        }
    }

    /**
     * Network failures self-heal (cable plugged back in, Wi-Fi back up):
     * count down 30 s on screen, then retry automatically. The user can still
     * press the Réessayer button at any point.
     */
    private fun scheduleNetworkAutoRetry(detail: String?) {
        autoRetryJob?.cancel()
        autoRetryJob = viewModelScope.launch {
            for (remaining in NETWORK_AUTO_RETRY_SECONDS downTo 1) {
                _state.value = ActivationState.Failed(ActivationFailureKind.NETWORK, detail, remaining)
                delay(1_000)
            }
            retry()
        }
    }

    private companion object {
        const val NETWORK_AUTO_RETRY_SECONDS = 30
    }

    private suspend fun refreshIfServerConfigChanged() {
        // Try to fetch authoritative credentials. If the network is down we
        // fall back to local providers so the user can still play cached
        // content.
        val creds = runCatching { device.fetchCredentials() }.getOrNull()
        val providers = providerRepository.getProviders().first()
        if (creds == null) {
            if (providers.isNotEmpty()) {
                _state.value = ActivationState.Ready
            } else {
                fail(ActivationFailureKind.NETWORK, "credentials unreachable")
            }
            return
        }

        // Compare the server-returned creds with what we have locally. If the
        // active provider already matches, skip the (slow) full sync.
        val active = providers.firstOrNull { it.isActive }
        val matchesLocal = active != null &&
            active.serverUrl.trimEnd('/').equals(creds.xtream.host.trimEnd('/'), ignoreCase = true) &&
            active.username == creds.xtream.username
        if (matchesLocal) {
            _state.value = ActivationState.Ready
            return
        }

        // Either no provider, or server config changed (host rotated, token
        // rotated, transition from direct to proxy). Block on loginWithCreds
        // so the user never sees stale catalog entries.
        loginWithCreds(creds.xtream.host, creds.xtream.username, creds.xtream.password, creds.epgOverrideUrl)
    }

    private suspend fun loginWithCreds(host: String, username: String, password: String, epgOverrideUrl: String? = null) {
        _state.value = ActivationState.Syncing
        val result = providerRepository.loginXtream(
            serverUrl = host,
            username = username,
            password = password,
            name = "FuzionTV",
            xtreamFastSyncEnabled = false,
            epgSyncMode = if (epgOverrideUrl.isNullOrBlank()) ProviderEpgSyncMode.BACKGROUND else ProviderEpgSyncMode.UPFRONT,
        )
        when (result) {
            is com.streamvault.domain.model.Result.Success -> {
                // The FuzionTV webapp is the single source of truth: every
                // other provider on this device (manually added test playlists,
                // old proxy creds from a previous setup, etc.) is removed so
                // the user only ever sees one playlist.
                pruneOtherProviders(keepId = result.data.id)
                _state.value = ActivationState.Ready
            }
            is com.streamvault.domain.model.Result.Error -> fail(
                result.exception?.let(::classify) ?: ActivationFailureKind.UNKNOWN,
                result.exception?.message ?: result.message
            )
            is com.streamvault.domain.model.Result.Loading -> Unit
        }
    }

    private suspend fun pruneOtherProviders(keepId: Long) {
        val all = providerRepository.getProviders().first()
        all.filter { it.id != keepId }.forEach { p ->
            runCatching { providerRepository.deleteProvider(p.id) }
        }
    }

    private suspend fun activateLoop() {
        val deviceId = device.deviceId()
        val activate = device.api.activate(
            ActivateRequest(
                deviceId = deviceId,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                // Recorded as devices.platform; the admin uses it to compare each
                // device against the right APK channel ("androidtv"/"androidmobile").
                platform = BuildConfig.APP_PLATFORM,
            )
        )
        if (activate.managed) {
            fail(ActivationFailureKind.TOKEN_RESET_NEEDED, "managed device without token")
            return
        }
        val code = activate.activationCode
            ?: return run { fail(ActivationFailureKind.SERVER, "activation code missing") }
        _state.value = ActivationState.WaitingForCode(code)
        val pollMs = activate.pollIntervalMs ?: 5000L

        while (viewModelScope.isActive) {
            delay(pollMs)
            val status = try {
                device.api.status(deviceId)
            } catch (e: FuziontvApiException) {
                if (e.httpCode == 403) {
                    _state.value = ActivationState.Disabled(e.errorCode)
                    return
                }
                continue
            } catch (_: Exception) {
                continue
            }
            if (status.status == "disabled") {
                _state.value = ActivationState.Disabled(null)
                return
            }
            val token = status.deviceToken
            if (status.managed && token != null) {
                device.saveDeviceToken(token)
                // Le token vient d'apparaître : c'est LE moment où le VPN
                // devient provisionnable (ré-activation d'une box au mode "on"
                // persisté) — gater AVANT le premier login panel. Sur un device
                // vierge (mode auto non déclenché) le gate est un no-op.
                if (!awaitVpnGate()) return
                val creds = device.fetchCredentials()
                loginWithCreds(creds.xtream.host, creds.xtream.username, creds.xtream.password, creds.epgOverrideUrl)
                return
            }
        }
    }
}
