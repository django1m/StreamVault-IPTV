package com.streamvault.app.auth

import com.streamvault.app.vpn.FakeVpnGate
import com.streamvault.app.vpn.VpnGateResult
import com.streamvault.data.remote.fuziontv.CredentialsResponse
import com.streamvault.data.remote.fuziontv.XtreamCreds
import com.streamvault.data.remote.xtream.XtreamAuthInvalidationBus
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Teste la logique de [CredentialRefreshOrchestrator.handleSignal] en direct
 * (le câblage bus→collector est trivial et vérifié à l'usage) : skip du round
 * quand le VPN requis n'est pas prêt, SANS consommer le cooldown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialRefreshOrchestratorTest {

    private val creds = CredentialsResponse(XtreamCreds("https://panel.test", "u", "p"))

    private fun activatedDevice() = mock<FuziontvDeviceRepositoryImpl> {
        onBlocking { isActivated() } doReturn true
        onBlocking { fetchCredentials() } doReturn creds
    }

    private fun providerRepo() = mock<ProviderRepository> {
        on { getActiveProvider() } doReturn flowOf(null)
        onBlocking {
            loginXtream(any(), any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())
        } doReturn Result.error("test")
    }

    private fun newOrchestrator(
        device: FuziontvDeviceRepositoryImpl,
        gate: FakeVpnGate,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = CredentialRefreshOrchestrator(
        authInvalidationBus = XtreamAuthInvalidationBus(),
        device = device,
        providerRepository = providerRepo(),
        vpnGate = gate,
        scope = scope,
    )

    @Test
    fun `un signal saute le round quand le VPN requis n'est pas prêt, sans consommer le cooldown`() = runTest {
        val gate = FakeVpnGate(result = VpnGateResult.TIMEOUT, required = true)
        val device = activatedDevice()
        val orch = newOrchestrator(device, gate, backgroundScope)

        orch.handleSignal()
        // Gate non passant : aucun appel backend/panel.
        verify(device, never()).fetchCredentials()

        // Le tunnel devient prêt : le signal suivant doit passer IMMÉDIATEMENT
        // (lastRunAt non avancé par le round sauté, sinon le cooldown 30 s
        // mangerait la première relance post-VPN).
        gate.result = VpnGateResult.READY
        orch.handleSignal()
        verify(device).fetchCredentials()
    }

    @Test
    fun `un signal passe normalement quand le VPN n'est pas requis`() = runTest {
        val gate = FakeVpnGate(required = false)
        val device = activatedDevice()
        val orch = newOrchestrator(device, gate, backgroundScope)

        orch.handleSignal()
        verify(device).fetchCredentials()
    }

    @Test
    fun `aucun refresh quand le device n'est pas activé`() = runTest {
        val gate = FakeVpnGate(required = false)
        val device = mock<FuziontvDeviceRepositoryImpl> {
            onBlocking { isActivated() } doReturn false
        }
        val orch = newOrchestrator(device, gate, backgroundScope)

        orch.handleSignal()
        verify(device, never()).fetchCredentials()
    }

    @Test
    fun `le cooldown bloque bien un second signal APRÈS un refresh réussi`() = runTest {
        val gate = FakeVpnGate(required = false)
        val device = activatedDevice()
        val orch = newOrchestrator(device, gate, backgroundScope)

        orch.handleSignal()
        orch.handleSignal() // dans la fenêtre de 30 s réelle → absorbé
        verify(device).fetchCredentials() // une seule fois
    }
}
