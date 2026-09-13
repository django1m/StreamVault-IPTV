package com.streamvault.app.ui.screens.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.BuildConfig
import com.streamvault.app.vpn.VpnDisplayPhase
import com.streamvault.app.vpn.VpnGateResult
import com.streamvault.app.vpn.VpnPanelBlockReporter
import com.streamvault.app.vpn.VpnReadinessGate
import com.streamvault.app.vpn.allowsPanelCalls
import com.streamvault.data.remote.fuziontv.ActivateRequest
import com.streamvault.data.remote.fuziontv.FuziontvApiException
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamRequestException
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
    private val panelBlockReporter: VpnPanelBlockReporter,
) : ViewModel() {

    private val _state = MutableStateFlow<ActivationState>(ActivationState.Loading)
    val state: StateFlow<ActivationState> = _state

    private var activationJob: Job? = null
    private var autoRetryJob: Job? = null

    /** Échecs transitoires consécutifs — indexe [TRANSIENT_RETRY_BACKOFF_SECONDS]. */
    private var transientFailures = 0

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
        val retrySeconds = nextAutoRetrySeconds(kind)
        if (retrySeconds == null) {
            _state.value = ActivationState.Failed(kind, detail)
        } else {
            scheduleAutoRetry(kind, detail, retrySeconds)
        }
    }

    /**
     * Combien de secondes avant la reprise automatique, ou null quand réessayer ne
     * peut rien réparer (il faut un humain : abonnement expiré, appareil à
     * réassocier) — boucler afficherait un compte à rebours menteur.
     *
     * NETWORK se répare vite et tout seul (câble rebranché, Wi-Fi revenu) → 30 s
     * fixes, comportement historique. SERVER et UNKNOWN couvrent le cas qui laissait
     * des box mortes à vie : un 403 du panel sur une IP de sortie brûlée. Le VPN
     * demande alors une rotation d'exit en parallèle (voir [VpnPanelBlockReporter]),
     * et ce backoff lui laisse le temps d'aboutir (~20 s) avant de rejouer le login.
     *
     * Le backoff s'allonge au lieu de rester à 30 s pour une raison précise : le
     * panel limite à ~1 requête par IP, donc une boucle serrée re-brûlerait la
     * sortie fraîche à peine obtenue. Le compteur ne retombe qu'en atteignant Ready
     * ([becomeReady]) : un appui répété sur « Réessayer » ne doit pas servir de
     * contournement au rythme.
     */
    private fun nextAutoRetrySeconds(kind: ActivationFailureKind): Int? = when (kind) {
        ActivationFailureKind.NETWORK -> NETWORK_AUTO_RETRY_SECONDS
        ActivationFailureKind.SERVER, ActivationFailureKind.UNKNOWN -> {
            val idx = transientFailures.coerceIn(0, TRANSIENT_RETRY_BACKOFF_SECONDS.lastIndex)
            transientFailures++
            TRANSIENT_RETRY_BACKOFF_SECONDS[idx]
        }
        ActivationFailureKind.SUBSCRIPTION_EXPIRED,
        ActivationFailureKind.TOKEN_RESET_NEEDED -> null
    }

    /**
     * Compte à rebours à l'écran puis relance du flux. L'utilisateur peut toujours
     * appuyer sur « Réessayer » avant la fin — [retry] annule ce job.
     */
    private fun scheduleAutoRetry(kind: ActivationFailureKind, detail: String?, seconds: Int) {
        autoRetryJob?.cancel()
        autoRetryJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.value = ActivationState.Failed(kind, detail, remaining)
                delay(1_000)
            }
            retry()
        }
    }

    /** Seul chemin vers Ready : réarme le backoff des échecs transitoires. */
    private fun becomeReady() {
        transientFailures = 0
        _state.value = ActivationState.Ready
    }

    /**
     * Un 403 du PANEL (pas du backend FuzionTV) prouve que l'IP de sortie est
     * refusée. Les exceptions Xtream sont ré-emballées en cours de route, d'où le
     * parcours borné de la chaîne de causes.
     */
    private fun isPanelForbidden(error: Throwable?): Boolean {
        var e = error
        var depth = 0
        while (e != null && depth++ < CAUSE_CHAIN_MAX_DEPTH) {
            val status = when (e) {
                is XtreamAuthenticationException -> e.statusCode
                is XtreamRequestException -> e.statusCode
                else -> null
            }
            if (status == 403) return true
            e = e.cause
        }
        return false
    }

    private companion object {
        const val NETWORK_AUTO_RETRY_SECONDS = 30
        /** Échecs transitoires (SERVER/UNKNOWN) : 1 min, 2 min, puis 5 min. */
        val TRANSIENT_RETRY_BACKOFF_SECONDS = intArrayOf(60, 120, 300)
        const val CAUSE_CHAIN_MAX_DEPTH = 5
    }

    private suspend fun refreshIfServerConfigChanged() {
        // Try to fetch authoritative credentials. If the network is down we
        // fall back to local providers so the user can still play cached
        // content.
        val creds = runCatching { device.fetchCredentials() }.getOrNull()
        val providers = providerRepository.getProviders().first()
        if (creds == null) {
            if (providers.isNotEmpty()) {
                becomeReady()
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
            becomeReady()
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
                becomeReady()
            }
            is com.streamvault.domain.model.Result.Error -> {
                // Seul endroit d'où une box PAS ENCORE activée peut signaler une IP
                // de sortie brûlée : tous les autres signaux 403 viennent de la
                // lecture, qui n'arrive jamais si le login échoue. Sans ça, la box
                // boucle ici à vie sans jamais demander une rotation d'exit.
                if (isPanelForbidden(result.exception)) panelBlockReporter.reportPanelLogin403()
                fail(
                    result.exception?.let(::classify) ?: ActivationFailureKind.UNKNOWN,
                    result.exception?.message ?: result.message
                )
            }
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
