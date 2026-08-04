package com.streamvault.app.vpn

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VpnGateResultTest {

    @Test
    fun `seuls NOT_REQUIRED, READY, NO_DEVICE_TOKEN et PROCEED_WITHOUT_VPN autorisent les appels panel`() {
        val allowed = VpnGateResult.entries.filter { it.allowsPanelCalls() }
        assertThat(allowed).containsExactly(
            VpnGateResult.NOT_REQUIRED,
            VpnGateResult.READY,
            VpnGateResult.NO_DEVICE_TOKEN,
            VpnGateResult.PROCEED_WITHOUT_VPN,
        )
    }

    @Test
    fun `tous les etats d'echec ou d'attente bloquent les appels panel`() {
        val blocked = listOf(
            VpnGateResult.CONSENT_PENDING,
            VpnGateResult.CONSENT_DENIED,
            VpnGateResult.TERMINAL,
            VpnGateResult.FAILED,
            VpnGateResult.TIMEOUT,
            VpnGateResult.NO_NETWORK,
        )
        blocked.forEach { assertThat(it.allowsPanelCalls()).isFalse() }
    }
}
