package com.streamvault.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.streamvault.app.di.ApplicationScope
import com.streamvault.app.diagnostics.DiagnosticReporter
import com.streamvault.data.remote.fuziontv.FuziontvApi
import com.streamvault.data.remote.fuziontv.VpnPeer
import com.streamvault.data.remote.fuziontv.VpnStateDto
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.domain.model.DiagnosticCategory
import com.streamvault.domain.model.DiagnosticSeverity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the VPN lifecycle above [VpnController]:
 *  - decides whether the tunnel should be up (mode on / auto-after-403),
 *  - provisions a peer from the backend cache-first (egress-IP stickiness) and
 *    VERIFIES it actually passes traffic ([VpnTunnelVerifier]),
 *  - feeds health/block signals to [VpnRegionHealthGuard] and executes the
 *    server-arbitrated region fallbacks it decides on,
 *  - reports tunnel state in heartbeats.
 *
 * Blackhole invariant: the tunnel is app-scoped ([VpnController] uses
 * includeApplication), so when it is UP but its endpoint is dead, THIS app's
 * calls to the backend blackhole too. Every re-provision / plain-network check
 * therefore runs with the tunnel DOWN, and the tunnel is left DOWN between
 * failed Class-A cycles so heartbeats keep reaching the server.
 */
@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: VpnController,
    private val store: VpnStore,
    private val deviceRepo: FuziontvDeviceRepositoryImpl,
    private val verifier: VpnTunnelVerifier,
    private val guard: VpnRegionHealthGuard,
    private val diagnosticReporter: DiagnosticReporter,
    @ApplicationScope private val scope: CoroutineScope,
) : VpnReadinessGate, VpnPanelBlockReporter {
    private val provisionMutex = Mutex()
    private val pendingAutoActivated = AtomicBoolean(false)

    // Cycle de provisioning partagé : tous les appelants non forcés (boot,
    // heartbeat, gate) rejoignent le MÊME Deferred. Il tourne sur le scope
    // application, donc un timeout d'appelant n'annule jamais le cycle — le
    // teardown-on-failure (invariant blackhole) va toujours au bout.
    private val cycleKickMutex = Mutex()
    @Volatile private var provisionCycle: Deferred<Unit>? = null

    @Volatile private var consentDenied = false

    // A tunnel "verified" by its handshakes while nothing else gets through — see
    // TunnelDataPlaneWatchdog. Fed by the heartbeat outcomes: no extra traffic.
    private val dataPlaneWatchdog = TunnelDataPlaneWatchdog(System::currentTimeMillis)
    private val dataPlaneRecoveryInFlight = AtomicBoolean(false)
    @Volatile private var lastBackendBlockedReportAtMs = 0L

    // Phase AFFICHAGE du cycle (splash / notices player). Les décisions de
    // gating n'en dépendent jamais — voir VpnDisplayPhase.
    private val _displayPhase = MutableStateFlow(VpnDisplayPhase.IDLE)
    override val displayPhase: StateFlow<VpnDisplayPhase> = _displayPhase.asStateFlow()

    @Volatile private var lastFailedCycleAtMs = 0L
    private fun failCycle(region: String, plainNetworkOk: Boolean) {
        if (plainNetworkOk) lastFailedCycleAtMs = System.currentTimeMillis()
        guard.onVerificationCycleFailed(region, plainNetworkOk)
    }

    val state get() = controller.state

    private val api: FuziontvApi get() = deviceRepo.api

    init {
        // React to the guard's decisions.
        scope.launch { guard.fallbackRequests.collect { runCatching { executeFallback(it) } } }
        scope.launch { guard.egressRotateRequests.collect { runCatching { executeEgressRotation(it) } } }
        scope.launch {
            guard.retryCycles.collect { delayMs ->
                // Own coroutine so the collector isn't blocked by the delay.
                scope.launch { delay(delayMs); runCatching { provisionAndConnect(forceFresh = true) } }
            }
        }
        // Tunnel killed outside our own lifecycle (user disabled the VPN in the
        // system settings): never leave a phantom READY on screen. Our own
        // teardown paths set FAILED/IDLE themselves; this only downgrades READY.
        scope.launch {
            controller.state.collect { s ->
                if ((s == VpnState.DISCONNECTED || s == VpnState.ERROR) &&
                    _displayPhase.value == VpnDisplayPhase.READY
                ) {
                    _displayPhase.value = VpnDisplayPhase.FAILED
                }
            }
        }
        registerUnderlyingNetworkCallback()
    }

    /**
     * Wi-Fi/Ethernet transitions under us (flap, standby-resume) leave the
     * tunnel nominally UP but dead: wireguard-android does NOT rebind its UDP
     * socket on a network change, so the old socket keeps sending to an
     * unreachable path and handshakes stop. Nothing else notices until the next
     * heartbeat (~90 s) or the next foreground. Re-validate immediately on a real
     * underlying transition so the box isn't stuck with a silently-dead tunnel —
     * exactly the window that made "restart the box" a folk fix.
     *
     * NOT_VPN so we ignore our OWN tunnel coming up/down (else every connect/
     * disconnect would self-trigger). The lost→available edge gates it to real
     * transitions; [triggerRevalidate] is throttled + deduped downstream.
     */
    private fun registerUnderlyingNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var wasLost = false
            override fun onLost(network: Network) { wasLost = true }
            override fun onAvailable(network: Network) {
                if (wasLost) { wasLost = false; triggerRevalidate() }
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onFailure { Log.w(TAG, "underlying network callback register failed: ${it.message}") }
    }

    // --- VpnReadinessGate ----------------------------------------------------

    override suspend fun isVpnRequired(): Boolean {
        val mode = store.mode()
        return mode == "on" || (mode == "auto" && store.autoTriggered())
    }

    override suspend fun awaitVpnReadyIfRequired(timeoutMs: Long?): VpnGateResult {
        val mode = store.mode()
        if (mode != "on" && !(mode == "auto" && store.autoTriggered())) {
            return VpnGateResult.NOT_REQUIRED
        }
        // Déjà vérifié — réponse instantanée, aucun cycle déclenché.
        store.cachedPeer()?.config?.let { if (verifiedNow(it)) return VpnGateResult.READY }

        if (consentDenied) return VpnGateResult.CONSENT_DENIED
        // TERMINAL ne durcit que l'échec : un tunnel qui vérifie encore est
        // retourné READY ci-dessus (TERMINAL = plus de fallback région, pas
        // « VPN mort »).
        if (store.guardState().terminal) return VpnGateResult.TERMINAL
        if (deviceRepo.deviceToken() == null) return VpnGateResult.NO_DEVICE_TOKEN
        if (!hasActiveNetwork()) return VpnGateResult.NO_NETWORK

        if (controller.prepareConsentIntent() != null) {
            launchConsentActivity()
            return VpnGateResult.CONSENT_PENDING
        }

        val budget = timeoutMs ?: if (mode == "on") GATE_BUDGET_ON_MS else GATE_BUDGET_AUTO_MS
        if (budget <= 0L) {
            // Pur snapshot (workers / Boucle B) : ne kicke rien, ne dort pas.
            return if (provisionCycle?.isActive == true) VpnGateResult.TIMEOUT else gateSnapshot()
        }
        val cycle = sharedProvisionCycle()
        if (withTimeoutOrNull(budget) { cycle.await() } == null) return VpnGateResult.TIMEOUT
        return gateSnapshot()
    }

    override suspend fun awaitVpnForControlPlane(timeoutMs: Long?): VpnGateResult {
        val mode = store.mode()
        if (mode != "on" && !(mode == "auto" && store.autoTriggered())) {
            return VpnGateResult.NOT_REQUIRED
        }
        // Tunnel déjà vérifié → l'utiliser.
        store.cachedPeer()?.config?.let { if (verifiedNow(it)) return VpnGateResult.READY }
        // Mode "auto" : l'IP résidentielle sert parfaitement le plan de contrôle
        // (activation, /credentials, heartbeat, sync) — seul l'on-demand VOD est
        // bloqué. On ne bloque JAMAIS le boot / le refresh sur le VPN : il monte
        // en arrière-plan (connectIfNeeded / onOnDemandBlocked) et le chemin VOD
        // attend le tunnel au moment de la lecture. Fenêtre rare : si le tunnel
        // est up-mais-mort à cet instant, l'appel blackhole → IOException →
        // retry, qui retombe sur l'IP claire (auto-guérison, identique au
        // comportement pré-gate d'une box auto). Ne PAS étendre ce fail-open au
        // mode "on" : là l'IP est bannie, procéder recréerait la boucle d'erreur.
        if (mode == "auto") return VpnGateResult.PROCEED_WITHOUT_VPN
        // Mode "on" : IP entièrement bannie → gate strict fail-closed.
        return awaitVpnReadyIfRequired(timeoutMs)
    }

    /** État réel post-cycle : READY seulement si le tunnel passe du trafic LÀ. */
    private suspend fun gateSnapshot(): VpnGateResult {
        val cached = store.cachedPeer()?.config
        return if (cached != null && verifiedNow(cached)) VpnGateResult.READY else VpnGateResult.FAILED
    }

    /** Rejoint le cycle actif ou en démarre un — jamais deux cycles non forcés. */
    private suspend fun sharedProvisionCycle(): Deferred<Unit> = cycleKickMutex.withLock {
        provisionCycle?.takeIf { it.isActive }
            ?: scope.async { runCatching { provisionAndConnect() }; Unit }.also { provisionCycle = it }
    }

    /** Bouton Réessayer : un appui utilisateur ne doit pas no-op sur le throttle. */
    override fun userRetry() {
        consentDenied = false
        lastFailedCycleAtMs = 0L
        scope.launch { ensureConnectedOrConsent() }
    }

    /** Refus du dialogue de consentement Android ([VpnConsentActivity]). */
    fun onConsentDenied() {
        consentDenied = true
        _displayPhase.value = VpnDisplayPhase.FAILED
    }

    private fun hasActiveNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return runCatching { cm.activeNetwork != null }.getOrDefault(true)
    }

    /** Apply the authoritative mode from the heartbeat: persist + converge. */
    fun applyMode(mode: String?) {
        val m = mode ?: return
        scope.launch {
            if (m !in ALLOWED_MODES) return@launch
            if (store.mode() != m) store.setMode(m)
            when (m) {
                "off" -> { controller.disconnect(); _displayPhase.value = VpnDisplayPhase.IDLE }
                "on" -> ensureConnectedOrConsent()
                "auto" -> if (store.autoTriggered()) ensureConnectedOrConsent()
            }
        }
    }

    /**
     * Apply the effective region assignment delivered in the heartbeat. null =
     * not assigned yet → keep the cached config, do NOT re-provision. A value
     * different from our cached region means the server reassigned us (admin or
     * a fallback we didn't drive) → drop the cache and reconnect fresh.
     */
    fun applyAssignedRegion(region: String?) {
        if (region == null) return
        scope.launch {
            guard.onServerAssignedRegion(region)
            val cachedRegion = store.cachedPeer()?.config?.region ?: return@launch
            if (cachedRegion == region) return@launch
            val mode = store.mode()
            store.clearPeer()
            controller.disconnect() // our API calls must reach the server to re-provision
            if (mode == "on" || (mode == "auto" && store.autoTriggered())) {
                provisionAndConnect(forceFresh = true)
            }
        }
    }

    /**
     * Called by the player when an on-demand stream is refused (403) while the
     * VPN is OFF: turns it on permanently (unless admin forced off). Distinct
     * from the region-fallback detector — this is the initial auto-activation.
     */
    suspend fun onOnDemandBlocked(): Boolean {
        if (store.mode() == "off") return false
        if (controller.isUp) return true
        if (!store.autoTriggered()) store.setAutoTriggered(true)
        pendingAutoActivated.set(true)
        ensureConnectedOrConsent()
        return true
    }

    /** Connect on process start if the persisted state says we should. */
    fun connectIfNeeded() {
        scope.launch {
            val mode = store.mode()
            if (mode == "off") return@launch
            if (mode == "on" || (mode == "auto" && store.autoTriggered())) {
                ensureConnectedOrConsent()
            }
        }
    }

    /**
     * App revenue au premier plan : (re)vérifie le tunnel MAINTENANT si le mode
     * l'exige, pour que la première lecture après reprise ne paie pas la latence
     * de handshake. Après un long arrière-plan (deep sleep de la box), le tunnel
     * peut rester nominalement UP mais avec un handshake périmé — cas que
     * [ensureConnectedOrConsent] ne rattrape PAS (il fait un early-return sur
     * controller.isUp sans tester la fraîcheur). Ici on teste explicitement
     * verifiedNow et on relance un cycle si le handshake n'est plus frais.
     */
    fun onAppForegrounded() = triggerRevalidate()

    /**
     * (Re)vérifie le tunnel MAINTENANT si le mode l'exige. Appelé au retour au
     * premier plan ET sur un changement de réseau sous-jacent. Après un long
     * arrière-plan (deep sleep) ou un flap Wi-Fi, le tunnel peut rester
     * nominalement UP mais avec un handshake périmé / un socket mort — cas que
     * [ensureConnectedOrConsent] ne rattrape PAS (early-return sur controller.isUp
     * sans tester la fraîcheur). Ici on teste explicitement verifiedNow et on
     * relance un cycle si le handshake n'est plus frais.
     */
    private fun triggerRevalidate() {
        scope.launch {
            val mode = store.mode()
            if (mode == "off") return@launch
            if (mode != "on" && !store.autoTriggered()) return@launch
            // Déjà frais → ne rien perturber (une lecture en cours a un handshake
            // frais par le trafic, donc jamais reconnectée ici).
            val cached = store.cachedPeer()?.config
            if (cached != null && verifiedNow(cached)) return@launch
            // Down, jamais monté, ou up-mais-périmé → (re)provision + verify tout
            // de suite. Le cycle partagé dédoublonne avec connectIfNeeded/heartbeat,
            // et le throttle de convergence évite de spammer pendant une panne PIA
            // (le retry programmé du guard prend alors le relais).
            sharedProvisionCycle()
        }
    }

    /** Invoked by [VpnConsentActivity] after the user grants VPN permission. */
    fun onConsentGranted() {
        consentDenied = false
        scope.launch { sharedProvisionCycle() }
    }

    /** Drain the "auto-activated" flag for the heartbeat payload. */
    fun consumePendingAutoActivated(): Boolean = pendingAutoActivated.getAndSet(false)

    // --- region-health signals from the player -----------------------------

    /** A movie/series 403 — only counts as ban evidence if it happened through a
     *  VERIFIED tunnel (else it's the pre-VPN residential block, handled by
     *  onOnDemandBlocked). */
    fun reportEnforcedOnDemand403(providerId: Long, contentId: Long) {
        scope.launch {
            val cached = store.cachedPeer()?.config ?: return@launch
            if (!verifiedNow(cached)) return@launch
            guard.onEnforced403(cached.region, providerId, contentId)
        }
    }

    /**
     * A LIVE stream was accepted by the panel (2xx) but the origin sent no bytes,
     * through a verified tunnel: the classic blackholed-exit signature. Unlike every
     * 403 signal there is no refusal to key on — the panel says yes and the stream
     * stays empty — so without this report the box waits forever on a black screen
     * and no guard ever fires (the failure mode seen on 2026-09-05).
     *
     * Same verified-tunnel guard as the other signals: with no fresh handshake a
     * starved stream says nothing about the exit IP.
     */
    fun reportLiveBlackhole(providerId: Long, contentId: Long) {
        scope.launch {
            val cached = store.cachedPeer()?.config ?: return@launch
            if (!verifiedNow(cached)) return@launch
            // A blackhole is an ABSENCE of bytes, so anything that stops bytes
            // flowing looks identical to it — including the box's own link
            // dropping. A 403 needs no such check (the provider had to be REACHED
            // to refuse), but here the exit can only be blamed once we know the
            // path still works: probe the backend through the tunnel right now.
            // Measured 2026-09-07: a box on a flapping Wi-Fi link (60-90 s holes,
            // every minute or two) reported two "blackholes" on a region whose
            // servers all tested healthy, and each rotation tore the tunnel down
            // again — the detector was feeding the very problem it saw.
            if (!verifier.tunnelHttpReachable()) {
                Log.d(TAG, "live starved but backend unreachable — blaming the link, not the exit")
                return@launch
            }
            guard.onLiveBlackhole(cached.region, providerId, contentId)
        }
    }

    /** A live channel reached READY through the tunnel — proof any on-demand
     *  block is selective (region-specific), not a provider-wide outage. */
    fun reportLivePlaybackSuccess() {
        scope.launch {
            val cached = store.cachedPeer()?.config ?: return@launch
            if (!verifiedNow(cached)) return@launch
            guard.onLiveSuccess(cached.region)
        }
    }

    /** A LIVE stream refused with 403 through a verified tunnel: the provider is
     *  refusing everything (provider-wide outage), NOT banning this region for
     *  on-demand — wipe any accumulating Class-B evidence so it can't fabricate a
     *  spurious region fallback. */
    fun reportLive403() {
        scope.launch {
            val cached = store.cachedPeer()?.config ?: return@launch
            if (!verifiedNow(cached)) return@launch
            guard.onLiveProbe403()
        }
    }

    /**
     * The provider refused the panel LOGIN with a 403 through a verified tunnel:
     * this exit IP is burned for everything, not just on-demand. Unlike the three
     * signals above this one fires OUTSIDE playback — it is the only escape for a
     * box that never gets past activation (see [VpnPanelBlockReporter]).
     *
     * Same verified-tunnel guard as the others: without a fresh handshake a 403 is
     * just the expected residential block, which the VPN gate already handles.
     */
    override fun reportPanelLogin403() {
        scope.launch {
            val cached = store.cachedPeer()?.config ?: return@launch
            if (!verifiedNow(cached)) return@launch
            guard.onPanelLogin403()
        }
    }

    // --- data-plane watchdog (fed by heartbeat outcomes) ---------------------

    /** A backend call got an answer — any answer: the path works. */
    fun onBackendCallSucceeded() = dataPlaneWatchdog.onBackendSuccess()

    /**
     * A backend call failed. An HTTP error still proves the path works; only a
     * transport failure while the tunnel is up feeds the watchdog, and only a
     * confirmed trip rebuilds the tunnel.
     */
    fun onBackendCallFailed(error: Throwable) {
        if (!TunnelTrafficRules.isTransportFailure(error)) {
            dataPlaneWatchdog.onBackendSuccess()
            return
        }
        scope.launch {
            if (!controller.isUp && !controller.isTunnelRunning()) return@launch
            val cached = store.cachedPeer()?.config ?: return@launch
            val stats = controller.peerStats(cached.serverPublicKey) ?: return@launch
            if (dataPlaneWatchdog.onBackendTransportFailure(stats.handshakeAgeSec, stats.rxBytes)) {
                recoverDeadDataPlane(cached)
            }
        }
    }

    /**
     * The tunnel handshakes but carries nothing. Confirm with a probe through it —
     * any HTTP answer, even a 5xx, clears it — then rebuild it on a fresh
     * registration with the tunnel DOWN (blackhole invariant), the way a scheduled
     * Class-A retry would.
     */
    private suspend fun recoverDeadDataPlane(peer: VpnPeerConfig) {
        if (!dataPlaneRecoveryInFlight.compareAndSet(false, true)) return
        try {
            if (verifier.tunnelCarriesBackendTraffic()) {
                dataPlaneWatchdog.onBackendSuccess()
                return
            }
            dataPlaneWatchdog.onRecoveryStarted()
            diagnosticReporter.report(
                DiagnosticSeverity.WARNING, DiagnosticCategory.VPN,
                "VPN : tunnel connecté mais aucun trafic ne passe (${peer.region}) — reconnexion sur une configuration fraîche",
            )
            provisionAndConnect(forceFresh = true, tearDownFirst = true)
        } finally {
            dataPlaneRecoveryInFlight.set(false)
        }
    }

    /**
     * Our backend is unreachable with the tunnel DOWN. When Android itself says the
     * internet works (validated network), this is not "no internet": something on
     * the box's network filters us — typically an ISP or router security filter.
     * The guard keeps its retry pacing either way (a region change needs the backend
     * too); this only leaves the admin an explicit trace, at most once an hour.
     */
    private fun noteBackendUnreachableOffVpn() {
        if (!internetValidated()) return
        val now = System.currentTimeMillis()
        if (now - lastBackendBlockedReportAtMs < BACKEND_BLOCKED_REPORT_INTERVAL_MS) return
        lastBackendBlockedReportAtMs = now
        diagnosticReporter.report(
            DiagnosticSeverity.WARNING, DiagnosticCategory.CONNECTION,
            "Serveur FuzionTV injoignable hors VPN alors qu'Internet fonctionne — filtre du fournisseur Internet ou du routeur probable",
        )
    }

    private fun internetValidated(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.getOrDefault(false)
    }

    private suspend fun verifiedNow(peer: VpnPeerConfig): Boolean {
        if (!controller.isUp && !controller.isTunnelRunning()) return false
        val hs = controller.peerStats(peer.serverPublicKey)?.handshakeAgeSec
        return hs != null && hs <= HANDSHAKE_FRESH_SEC
    }

    // --- heartbeat payload --------------------------------------------------

    suspend fun vpnStateForHeartbeat(): VpnStateDto? {
        val mode = store.mode()
        val up = controller.isUp || controller.isTunnelRunning()
        if (mode == "off" && !up) return null
        val cached = store.cachedPeer()?.config
        val hsAge = if (up && cached != null) controller.peerStats(cached.serverPublicKey)?.handshakeAgeSec else null
        // Opportunistic re-verification: an up tunnel whose handshake is very
        // stale — OR that never handshaked at all (hsAge == null while up, i.e.
        // an up-but-dead tunnel) — is worse than being down. Reconnect to
        // recover (or start a Class-A cycle if it's truly dead).
        if (up && cached != null && (hsAge == null || hsAge > STALE_HANDSHAKE_SEC)) {
            scope.launch { runCatching { provisionAndConnect(forceFresh = false) } }
        }
        return VpnStateDto(
            up = up,
            region = cached?.region,
            handshakeAgeSec = hsAge,
            guardState = guard.heartbeatLabel(),
        )
    }

    // --- remote command surface --------------------------------------------
    fun forceOn() {
        scope.launch {
            val wasOff = store.mode() == "off"
            store.setMode("on")
            if (wasOff) guard.onAdminVpnCycle()
            ensureConnectedOrConsent()
        }
    }
    fun forceOff() {
        scope.launch {
            store.setMode("off"); controller.disconnect()
            _displayPhase.value = VpnDisplayPhase.IDLE
        }
    }

    // --- internals ----------------------------------------------------------

    private suspend fun ensureConnectedOrConsent() {
        if (controller.isUp) return
        if (controller.isTunnelRunning()) {
            // Survived process death — trust it only if the handshake is fresh.
            val cached = store.cachedPeer()?.config
            if (cached != null && verifiedNow(cached)) {
                guard.onVerified(cached.region)
                _displayPhase.value = VpnDisplayPhase.READY
                return
            }
            // stale/unknown → fall through and reconnect cleanly.
        }
        val consent: Intent? = controller.prepareConsentIntent()
        if (consent != null) {
            launchConsentActivity()
        } else {
            sharedProvisionCycle().await()
        }
    }

    /**
     * Lance l'activity de consentement seulement quand l'app est au premier
     * plan — un startActivity depuis un worker headless échoue silencieusement
     * sur Android moderne ; le prochain passage au premier plan relancera.
     */
    private fun launchConsentActivity() {
        val foreground = runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(true)
        if (!foreground) {
            Log.w(TAG, "consent needed but app is backgrounded — deferring to next foreground")
            return
        }
        _displayPhase.value = VpnDisplayPhase.CONSENT_WAIT
        val i = Intent(context, VpnConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
            .onFailure { Log.w(TAG, "cannot launch consent activity: ${it.message}") }
    }

    /**
     * Cache-first connect. Reuses the cached peer for egress-IP stickiness;
     * re-provisions only when forced, when there is no cache, or when the cache
     * is stale. On a verification failure it does ONE in-cycle fresh
     * re-provision (covers an expired PIA key without counting a Class-A cycle);
     * a further failure reports exactly one Class-A cycle to the guard.
     */
    private suspend fun provisionAndConnect(
        forceFresh: Boolean = false,
        tearDownFirst: Boolean = false,
    ) = provisionMutex.withLock {
        // Respect the admin escape hatch and the auto-not-yet-triggered state on
        // EVERY path (a scheduled Class-A retry must not resurrect an OFF tunnel).
        val mode = store.mode()
        if (mode == "off") {
            controller.disconnect(); _displayPhase.value = VpnDisplayPhase.IDLE; return@withLock
        }
        if (mode == "auto" && !store.autoTriggered()) {
            _displayPhase.value = VpnDisplayPhase.IDLE; return@withLock
        }

        // Un doublon en file d'attente derrière un cycle qui vient de vérifier
        // ne doit pas démonter/remonter un tunnel frais.
        if (!forceFresh) {
            store.cachedPeer()?.config?.let { c ->
                if (controller.isUp && verifiedNow(c)) {
                    _displayPhase.value = VpnDisplayPhase.READY; return@withLock
                }
            }
        }

        val now = System.currentTimeMillis()
        // Throttle: don't let 90 s heartbeat-driven converges run a full ~70 s
        // dead-tunnel cycle each. Only the guard's scheduled retry (forceFresh)
        // drives cycles while a recent one failed — this preserves Class-A pacing.
        if (!forceFresh && now - lastFailedCycleAtMs < CONVERGE_THROTTLE_MS) return@withLock

        // Hors ligne : répondre en millisecondes au lieu de brûler un cycle
        // mort de ~50 s ET un cycle Class-A compté.
        if (!hasActiveNetwork()) {
            Log.w(TAG, "no active network — skipping VPN cycle")
            _displayPhase.value = VpnDisplayPhase.FAILED
            return@withLock
        }

        val token = deviceRepo.deviceToken()
        if (token == null) { Log.w(TAG, "no device token yet — skipping VPN provision"); return@withLock }
        // A tunnel known to carry nothing would blackhole the vpn-config request below.
        if (tearDownFirst) controller.disconnect()
        val (priv, pub) = ensureKeyPair()
        val cached = store.cachedPeer()
        val stale = cached != null && now - cached.provisionedAtMs > PEER_REFRESH_AFTER_MS
        val useFresh = forceFresh || cached == null || stale

        var peer: VpnPeerConfig
        var haveFresh: Boolean
        if (useFresh) {
            val f = provisionFresh(pub)
            when {
                f != null -> { peer = f; haveFresh = true }
                cached != null -> { peer = cached.config; haveFresh = false }
                else -> {
                    Log.w(TAG, "provision failed and no cache — cannot connect")
                    _displayPhase.value = VpnDisplayPhase.FAILED
                    return@withLock
                }
            }
        } else {
            peer = cached!!.config; haveFresh = false
        }

        _displayPhase.value = VpnDisplayPhase.CONNECTING
        if (!controller.connect(peer, priv)) {
            controller.disconnect()
            _displayPhase.value = VpnDisplayPhase.FAILED
            val plainNetworkOk = verifier.httpReachable()
            if (!plainNetworkOk) noteBackendUnreachableOffVpn()
            failCycle(peer.region, plainNetworkOk = plainNetworkOk)
            return@withLock
        }
        val rxStart = controller.peerStats(peer.serverPublicKey)?.rxBytes ?: 0L
        _displayPhase.value = VpnDisplayPhase.VERIFYING
        if (verifier.verify(peer) == VpnTunnelVerifier.Result.VERIFIED) {
            guard.onVerified(peer.region)
            _displayPhase.value = VpnDisplayPhase.READY
            return@withLock
        }
        // Anti-flap: the probe can miss a working tunnel (cold DNS…), so real payload
        // bytes still count as proof it is alive. WireGuard's own handshake and
        // keepalive bytes do NOT: counting them validated tunnels that carried
        // nothing, and the box then blackholed itself behind a tunnel it trusted.
        val rxNow = controller.peerStats(peer.serverPublicKey)?.rxBytes ?: rxStart
        if (TunnelTrafficRules.rxProvesTraffic(rxStart, rxNow)) {
            guard.onVerified(peer.region)
            _displayPhase.value = VpnDisplayPhase.READY
            return@withLock
        }

        // Verification failed. Tear down so our own API/heartbeats reach the server.
        controller.disconnect()
        _displayPhase.value = VpnDisplayPhase.FAILED
        if (!verifier.httpReachable()) {
            noteBackendUnreachableOffVpn()
            failCycle(peer.region, plainNetworkOk = false); return@withLock
        }
        if (haveFresh) {
            // This cycle already used a fresh config that failed → one Class-A cycle.
            failCycle(peer.region, plainNetworkOk = true); return@withLock
        }
        // Used the cache → try one fresh re-provision in the same cycle.
        val fresh = provisionFresh(pub)
        if (fresh == null) { failCycle(peer.region, plainNetworkOk = true); return@withLock }
        if (connectAndVerify(fresh, priv)) {
            guard.onVerified(fresh.region)
        } else {
            // connectAndVerify left the tunnel DOWN between cycles.
            failCycle(fresh.region, plainNetworkOk = true)
        }
    }

    /** POST /vpn-config (normal). Caches the result. Returns null on failure. */
    private suspend fun provisionFresh(pub: String): VpnPeerConfig? = runCatching {
        val id = deviceRepo.deviceId()
        val token = deviceRepo.deviceToken() ?: return null
        val resp = api.vpnConfig(id, token, pub)
        resp.vpn.toPeerConfig().also { store.savePeer(it, System.currentTimeMillis()) }
    }.getOrElse { Log.w(TAG, "provisionFresh failed: ${it.message}"); null }

    /**
     * Execute a guard-decided fallback. Never tears a live tunnel for a BLOCKED
     * fallback (the block is on-demand-only; live keeps working) — it defers and
     * the next on-demand 403 re-triggers. A denial still applies the returned
     * (current-region, possibly pin-rotated) config so the box is never stranded.
     */
    private suspend fun executeFallback(req: VpnRegionHealthPolicy.Decision.RequestFallback) = provisionMutex.withLock {
        // Admin escape hatch wins even mid-fallback.
        if (store.mode() == "off") { controller.disconnect(); return@withLock }
        if (req.kind == VpnRegionHealthPolicy.FailureKind.BLOCKED && guard.activePlayback == "live") {
            Log.d(TAG, "deferring blocked fallback while live is playing"); return@withLock
        }
        // Drop a stale queued request: if we no longer sit on the region it names
        // (a prior fallback already moved us), acting on it would re-request an
        // old region and clobber the fresh cooldown. cachedPeer==null = tunnel
        // never provisioned yet (TUNNEL kind pre-disconnect), so allow it.
        val cachedRegion = store.cachedPeer()?.config?.region
        if (cachedRegion != null && cachedRegion != req.failedRegion) {
            Log.d(TAG, "dropping stale fallback for ${req.failedRegion} (now on $cachedRegion)"); return@withLock
        }
        val id = deviceRepo.deviceId()
        val token = deviceRepo.deviceToken() ?: return@withLock
        val (priv, pub) = ensureKeyPair()
        // A dead tunnel would blackhole this very request.
        if (req.kind == VpnRegionHealthPolicy.FailureKind.TUNNEL) controller.disconnect()

        val resp = runCatching {
            api.vpnConfig(id, token, pub, req.failedRegion, req.kind.wire, req.evidenceSummary)
        }.getOrElse {
            // Transport failure (not a server denial) — a 12 h deny cooldown would
            // strand a genuinely-dead pinned server. Use a short abort cooldown so
            // the normal retry pacing resumes soon; leave the tunnel DOWN.
            Log.w(TAG, "fallback request transport failure: ${it.message}")
            controller.disconnect()
            guard.onFallbackAborted()
            return@withLock
        }
        val cfg = resp.vpn.toPeerConfig()
        store.savePeer(cfg, System.currentTimeMillis())

        // Both paths: connect + verify, and CRITICALLY disconnect if verify fails
        // so an up-but-dead tunnel never blackholes the app's own heartbeats.
        val verified = connectAndVerify(cfg, priv)
        if (resp.fallbackGranted == true) {
            guard.onFallbackResult(granted = true, newRegion = cfg.region)
            if (verified) guard.onVerified(cfg.region)
            diagnosticReporter.report(
                if (verified) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR,
                DiagnosticCategory.VPN,
                "VPN : région basculée ${req.failedRegion} → ${cfg.region} (${req.kind.wire})" +
                    if (!verified) " — nouvelle région non vérifiée" else "",
            )
        } else {
            guard.onFallbackResult(granted = false, newRegion = null)
            if (verified) guard.onVerified(cfg.region)
            diagnosticReporter.report(
                DiagnosticSeverity.WARNING, DiagnosticCategory.VPN,
                "VPN : bascule refusée par le serveur (${resp.fallbackReason ?: "?"}) — maintenu sur ${req.failedRegion}",
            )
        }
    }

    /**
     * Rotate the exit IP within the CURRENT region (the guard saw repeated
     * on-demand 403 through a verified tunnel but has no live proof for a region
     * change — a VOD-only box). Unlike a TUNNEL fallback the tunnel is healthy, so
     * we do NOT tear it before the request (it reaches the backend fine); like a
     * BLOCKED fallback the block is on-demand-only, so we defer while live plays
     * rather than interrupt it. Never changes region → no fallback budget, no
     * guard result event. Blackhole invariant honored: connectAndVerify leaves the
     * tunnel DOWN if the rotated config fails to verify.
     */
    private suspend fun executeEgressRotation(req: VpnRegionHealthPolicy.Decision.RequestEgressRotation) = provisionMutex.withLock {
        // Every path that does NOT actually re-pin a fresh exit reports
        // dispatched=false so the guard refunds the reserved rotation slot (and keeps
        // the block evidence) — a decided-but-unexecuted rotation must never burn the
        // box's bounded budget and strand it on the burned exit.
        if (store.mode() == "off") {
            controller.disconnect(); guard.onEgressRotationResult(false); return@withLock
        }
        // A live-burned rotation (req.interruptLive) MUST proceed even though live
        // is the active playback: live is the thing that's 403-ing, so a fresh exit
        // is the fix — not something the current (already-black) live must be
        // protected from. A VOD-only rotation still defers to keep live untouched.
        if (guard.activePlayback == "live" && !req.interruptLive) {
            Log.d(TAG, "deferring egress rotation while live is playing")
            guard.onEgressRotationResult(false); return@withLock
        }
        // Drop a stale request: a prior move already took us off the named region.
        val cachedRegion = store.cachedPeer()?.config?.region
        if (cachedRegion != null && cachedRegion != req.failedRegion) {
            Log.d(TAG, "dropping stale egress rotation for ${req.failedRegion} (now on $cachedRegion)")
            guard.onEgressRotationResult(false); return@withLock
        }
        val id = deviceRepo.deviceId()
        val token = deviceRepo.deviceToken()
        if (token == null) { guard.onEgressRotationResult(false); return@withLock }
        val (priv, pub) = ensureKeyPair()
        val resp = runCatching {
            api.vpnConfig(id, token, pub, req.failedRegion, "egress", req.evidenceSummary)
        }.getOrElse {
            Log.w(TAG, "egress rotation request failed: ${it.message}")
            // Blackhole invariant: an up-but-dead tunnel must be torn down so our own
            // heartbeats/API reach the server (mirrors executeFallback).
            controller.disconnect()
            guard.onEgressRotationResult(false); return@withLock
        }
        // Region exposes a single PIA server → the server couldn't change the exit IP.
        // Not a real rotation: refund the slot instead of pretending it worked.
        if (resp.fallbackReason == "egress_no_alt_server") {
            Log.w(TAG, "egress rotation: no alternate server in ${req.failedRegion}")
            guard.onEgressRotationResult(false); return@withLock
        }
        // A live playback may have STARTED during the request round-trip. Never tear a
        // live tunnel for an on-demand rotation: abort (refund + keep the current
        // exit); the next on-demand 403 after live ends re-triggers it.
        if (guard.activePlayback == "live" && !req.interruptLive) {
            Log.d(TAG, "live started during egress rotation — aborting to preserve it")
            guard.onEgressRotationResult(false); return@withLock
        }
        val cfg = resp.vpn.toPeerConfig()
        store.savePeer(cfg, System.currentTimeMillis())
        // Dispatched: a fresh exit is pinned → the old-exit evidence is now stale.
        guard.onEgressRotationResult(true)
        val verified = connectAndVerify(cfg, priv)
        if (verified) guard.onVerified(cfg.region)
        diagnosticReporter.report(
            if (verified) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR,
            DiagnosticCategory.VPN,
            "VPN : rotation de l'IP de sortie (${cfg.region})" +
                if (!verified) " — nouvelle sortie non vérifiée" else "",
        )
    }

    /** connect + verify; leave the tunnel DOWN on failure (blackhole invariant). */
    private suspend fun connectAndVerify(cfg: VpnPeerConfig, priv: String): Boolean {
        controller.disconnect()
        _displayPhase.value = VpnDisplayPhase.CONNECTING
        if (!controller.connect(cfg, priv)) {
            controller.disconnect()
            _displayPhase.value = VpnDisplayPhase.FAILED
            return false
        }
        _displayPhase.value = VpnDisplayPhase.VERIFYING
        if (verifier.verify(cfg) == VpnTunnelVerifier.Result.VERIFIED) {
            _displayPhase.value = VpnDisplayPhase.READY
            return true
        }
        controller.disconnect()
        _displayPhase.value = VpnDisplayPhase.FAILED
        return false
    }

    private suspend fun ensureKeyPair(): Pair<String, String> {
        store.keyPair()?.let { return it }
        val (priv, pub) = controller.generateKeyPair()
        store.saveKeyPair(priv, pub)
        return priv to pub
    }

    private fun VpnPeer.toPeerConfig() = VpnPeerConfig(
        region = region,
        endpoint = endpoint,
        serverPublicKey = serverPublicKey,
        address = address,
        dns = dns,
        persistentKeepalive = persistentKeepalive,
    )

    private companion object {
        const val TAG = "VpnManager"
        val ALLOWED_MODES = setOf("auto", "on", "off")
        const val PEER_REFRESH_AFTER_MS = 24 * 3_600_000L
        const val HANDSHAKE_FRESH_SEC = 190L
        const val STALE_HANDSHAKE_SEC = 300L
        // Suppress non-forced (heartbeat-driven) reconnect attempts this long
        // after a failed cycle, so the guard's scheduled retry sets the pace.
        const val CONVERGE_THROTTLE_MS = 150_000L
        const val BACKEND_BLOCKED_REPORT_INTERVAL_MS = 3_600_000L

        // Budgets du gate STRICT (awaitVpnReadyIfRequired, chemin VOD) : "on"
        // couvre un cycle complet provision + double-vérification (~110-120 s) ;
        // "auto" couvre une seule tentative de vérification. Le plan de contrôle
        // en mode "auto" ne passe PAS par ces budgets — il fail-open direct
        // (awaitVpnForControlPlane).
        const val GATE_BUDGET_ON_MS = 120_000L
        const val GATE_BUDGET_AUTO_MS = 45_000L
    }
}
