package com.streamvault.app.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Gate contrôlable pour les tests JVM des sites d'appel. */
class FakeVpnGate(
    @Volatile var result: VpnGateResult = VpnGateResult.NOT_REQUIRED,
    @Volatile var required: Boolean = false,
    /** Délai virtuel simulant un cycle en cours (runTest l'avance). */
    @Volatile var awaitDelayMs: Long = 0L,
) : VpnReadinessGate {

    val phaseFlow = MutableStateFlow(VpnDisplayPhase.IDLE)
    override val displayPhase: StateFlow<VpnDisplayPhase> = phaseFlow

    var awaitCalls = 0
        private set
    var controlPlaneCalls = 0
        private set
    var userRetryCalls = 0
        private set

    override suspend fun isVpnRequired(): Boolean = required

    override suspend fun awaitVpnReadyIfRequired(timeoutMs: Long?): VpnGateResult {
        awaitCalls++
        if (!required) return VpnGateResult.NOT_REQUIRED
        if (timeoutMs != null && timeoutMs <= 0L) return result
        if (awaitDelayMs > 0L) delay(awaitDelayMs)
        return result
    }

    override suspend fun awaitVpnForControlPlane(timeoutMs: Long?): VpnGateResult {
        controlPlaneCalls++
        if (!required) return VpnGateResult.NOT_REQUIRED
        if (timeoutMs != null && timeoutMs <= 0L) return result
        if (awaitDelayMs > 0L) delay(awaitDelayMs)
        return result
    }

    override fun userRetry() {
        userRetryCalls++
    }
}
