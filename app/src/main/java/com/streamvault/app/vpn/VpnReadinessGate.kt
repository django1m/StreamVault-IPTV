package com.streamvault.app.vpn

import kotlinx.coroutines.flow.StateFlow

/**
 * Terminal answer of one gate consultation. The gate never lies about the
 * tunnel: READY means "up + fresh handshake NOW", FAILED means the cycle ended
 * with the tunnel torn DOWN (blackhole invariant honored), TIMEOUT means the
 * cycle is still running and the tunnel state is indeterminate.
 */
enum class VpnGateResult {
    /** mode off, ou auto && !autoTriggered — le VPN n'est pas requis. */
    NOT_REQUIRED,

    /** Tunnel monté + handshake frais (sémantique verifiedNow). */
    READY,

    /** Provisioning impossible (device pas encore activé) — le trafic est
     *  backend-only tant qu'aucun token n'existe, donc procéder est sûr. */
    NO_DEVICE_TOKEN,

    /** Mode "auto" fail-open : le tunnel n'est pas vérifié mais l'IP
     *  résidentielle sert le plan de contrôle (seul l'on-demand VOD est
     *  bloqué) — procéder sans VPN est le comportement voulu. */
    PROCEED_WITHOUT_VPN,

    /** Consentement OS requis ; l'activity de consentement a été lancée si
     *  l'app est au premier plan. Le budget ne court pas pendant ce temps. */
    CONSENT_PENDING,

    /** L'utilisateur a refusé la demande Android. */
    CONSENT_DENIED,

    /** Guard TERMINAL et tunnel non vérifié — plus de retry automatique,
     *  intervention admin requise. */
    TERMINAL,

    /** Cycle terminé, tunnel DOWN. */
    FAILED,

    /** Cycle encore en cours après le budget — état indéterminé. */
    TIMEOUT,

    /** Aucun réseau actif — à mapper sur l'UX « pas de connexion » existante. */
    NO_NETWORK,
}

/**
 * Phase d'affichage du cycle VPN. AFFICHAGE UNIQUEMENT : ne JAMAIS baser une
 * décision de gating dessus — les décisions viennent du résultat awaité de
 * [VpnReadinessGate.awaitVpnReadyIfRequired] (un flow peut livrer une valeur
 * périmée juste après le kick d'un nouveau cycle).
 */
enum class VpnDisplayPhase { IDLE, CONNECTING, VERIFYING, CONSENT_WAIT, READY, FAILED }

/**
 * Gate consulté par tout chemin qui contacte le panel Xtream ou charge le
 * catalogue : quand le mode VPN de l'appareil exige le tunnel, l'appelant doit
 * attendre qu'il soit monté ET vérifié avant d'émettre la moindre requête —
 * sinon elle part sur l'IP résidentielle (bannie) ou blackhole dans un tunnel
 * pas encore handshaké.
 */
interface VpnReadinessGate {
    /** true quand le mode persisté exige le tunnel (on, ou auto+autoTriggered). */
    suspend fun isVpnRequired(): Boolean

    /**
     * Gate STRICT (fail-closed pour "on" ET "auto") : attend le tunnel vérifié.
     * À utiliser SEULEMENT là où le VPN est réellement indispensable — la
     * lecture on-demand (VOD/séries), qui échoue sur l'IP résidentielle.
     * N'annule JAMAIS le cycle de provisioning sous-jacent (le timeout ne coupe
     * que l'attente). null = budget par mode : 120 s pour "on", 45 s pour
     * "auto". timeoutMs = 0 vaut pur snapshot (workers qui ne dorment pas).
     */
    suspend fun awaitVpnReadyIfRequired(timeoutMs: Long? = null): VpnGateResult

    /**
     * Gate PLAN DE CONTRÔLE (activation, /credentials, heartbeat forceSync,
     * sync catalogue). Fail-closed en mode "on" (IP entièrement bannie), mais
     * fail-open en mode "auto" : l'IP résidentielle sert le plan de contrôle,
     * seul l'on-demand VOD est bloqué — on ne bloque jamais le boot / le
     * refresh sur le VPN. Renvoie READY si le tunnel est déjà vérifié.
     */
    suspend fun awaitVpnForControlPlane(timeoutMs: Long? = null): VpnGateResult

    /** Phase courante du cycle, pour l'UI d'attente (splash, notices player). */
    val displayPhase: StateFlow<VpnDisplayPhase>

    /**
     * Bouton « Réessayer » : efface un refus de consentement, bypasse le
     * throttle de convergence (un appui utilisateur ne doit jamais no-op en
     * silence) et relance connexion-ou-consentement.
     */
    fun userRetry()
}

/** true ⇒ le site d'appel peut contacter le panel / backend. */
fun VpnGateResult.allowsPanelCalls(): Boolean =
    this == VpnGateResult.NOT_REQUIRED ||
        this == VpnGateResult.READY ||
        this == VpnGateResult.NO_DEVICE_TOKEN ||
        this == VpnGateResult.PROCEED_WITHOUT_VPN
