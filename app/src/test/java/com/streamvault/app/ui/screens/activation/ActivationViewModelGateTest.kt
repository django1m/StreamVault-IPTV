package com.streamvault.app.ui.screens.activation

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.diagnostics.DiagnosticReporter
import com.streamvault.app.vpn.FakeVpnGate
import com.streamvault.app.vpn.VpnDisplayPhase
import com.streamvault.app.vpn.VpnGateResult
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ProviderRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class ActivationViewModelGateTest {

    private val testDispatcher = StandardTestDispatcher()
    private val diagnosticReporter: DiagnosticReporter = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Device activé dont le fetch /credentials échoue réseau (IP bannie). */
    private fun activatedDeviceNetworkDown() = mock<FuziontvDeviceRepositoryImpl> {
        onBlocking { isActivated() } doReturn true
        onBlocking { fetchCredentials() } doAnswer { throw IOException("connexion refusée") }
    }

    private fun emptyProviders() = mock<ProviderRepository> {
        on { getProviders() } doReturn flowOf(emptyList())
    }

    /**
     * Un provider local en cache : le flux qui poursuit malgré un fetch en
     * échec termine sur Ready (état SANS timer). Indispensable en temps
     * virtuel — le chemin providers-vides part dans le countdown 30 s + retry
     * à l'infini et advanceUntilIdle ne converge jamais.
     */
    private fun providersWithCache() = mock<ProviderRepository> {
        on { getProviders() } doReturn flowOf(listOf(cachedProvider()))
    }

    private fun cachedProvider() = Provider(
        id = 1L,
        name = "FuzionTV",
        type = ProviderType.XTREAM_CODES,
        serverUrl = "https://panel.test",
        username = "u",
        password = "p",
    )

    @Test
    fun `gate en échec = écran VpnFailed et AUCUN contact panel ou backend`() = runTest(testDispatcher) {
        val device = activatedDeviceNetworkDown()
        val gate = FakeVpnGate(result = VpnGateResult.FAILED, required = true)
        val vm = ActivationViewModel(device, emptyProviders(), diagnosticReporter, gate)

        vm.start()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ActivationState.VpnFailed(terminal = false))
        verify(device, never()).fetchCredentials()
    }

    @Test
    fun `gate TERMINAL = écran VpnFailed terminal`() = runTest(testDispatcher) {
        val device = activatedDeviceNetworkDown()
        val gate = FakeVpnGate(result = VpnGateResult.TERMINAL, required = true)
        val vm = ActivationViewModel(device, emptyProviders(), diagnosticReporter, gate)

        vm.start()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ActivationState.VpnFailed(terminal = true))
        verify(device, never()).fetchCredentials()
    }

    @Test
    fun `consentement requis = écran VpnConsent`() = runTest(testDispatcher) {
        val gate = FakeVpnGate(result = VpnGateResult.CONSENT_PENDING, required = true)
        val vm = ActivationViewModel(activatedDeviceNetworkDown(), emptyProviders(), diagnosticReporter, gate)

        vm.start()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ActivationState.VpnConsent(denied = false))
    }

    @Test
    fun `VpnPending est affiché PENDANT l'attente, puis le flux contacte le backend une fois READY`() =
        runTest(testDispatcher) {
            val device = activatedDeviceNetworkDown()
            val gate = FakeVpnGate(result = VpnGateResult.READY, required = true, awaitDelayMs = 10_000L)
            val vm = ActivationViewModel(device, providersWithCache(), diagnosticReporter, gate)

            vm.start()
            advanceTimeBy(5_000L)
            runCurrent()
            // Toujours en attente du tunnel : spinner VPN à l'écran, zéro requête.
            assertThat(vm.state.value).isInstanceOf(ActivationState.VpnPending::class.java)
            verify(device, never()).fetchCredentials()

            advanceUntilIdle()
            // Tunnel vérifié → le flux normal reprend et frappe le backend.
            verify(device).fetchCredentials()
            assertThat(vm.state.value).isEqualTo(ActivationState.Ready)
        }

    @Test
    fun `VPN non requis = jamais d'état VPN, flux normal direct`() = runTest(testDispatcher) {
        val device = mock<FuziontvDeviceRepositoryImpl> {
            onBlocking { isActivated() } doReturn true
            onBlocking { fetchCredentials() } doAnswer { throw IOException("hors ligne") }
        }
        val providers = mock<ProviderRepository> {
            on { getProviders() } doReturn flowOf(listOf(cachedProvider()))
        }
        val gate = FakeVpnGate(required = false)
        val vm = ActivationViewModel(device, providers, diagnosticReporter, gate)

        val seen = mutableListOf<ActivationState>()
        backgroundScope.launch { vm.state.collect { seen.add(it) } }

        vm.start()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ActivationState.Ready)
        assertThat(seen.filterIsInstance<ActivationState.VpnPending>()).isEmpty()
    }

    @Test
    fun `reprise automatique quand le tunnel finit par monter sur un écran VpnFailed`() =
        runTest(testDispatcher) {
            val device = activatedDeviceNetworkDown()
            val gate = FakeVpnGate(result = VpnGateResult.FAILED, required = true)
            val vm = ActivationViewModel(device, providersWithCache(), diagnosticReporter, gate)

            vm.start()
            advanceUntilIdle()
            assertThat(vm.state.value).isEqualTo(ActivationState.VpnFailed(terminal = false))

            // Un retry programmé du guard (+3 min) réussit : phase READY publiée.
            gate.result = VpnGateResult.READY
            gate.phaseFlow.value = VpnDisplayPhase.READY
            advanceUntilIdle()

            // Le collector d'auto-reprise a relancé le flux tout seul.
            verify(device).fetchCredentials()
            assertThat(vm.state.value).isEqualTo(ActivationState.Ready)
        }

    @Test
    fun `Continuer sans VPN saute le gate UNE fois`() = runTest(testDispatcher) {
        val device = activatedDeviceNetworkDown()
        val gate = FakeVpnGate(result = VpnGateResult.FAILED, required = true)
        val vm = ActivationViewModel(device, providersWithCache(), diagnosticReporter, gate)

        vm.start()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(ActivationState.VpnFailed(terminal = false))
        val callsBefore = gate.controlPlaneCalls

        vm.continueWithoutVpn()
        advanceUntilIdle()

        // Le flux a contacté le backend sans re-consulter le gate.
        verify(device).fetchCredentials()
        assertThat(gate.controlPlaneCalls).isEqualTo(callsBefore)
        assertThat(vm.state.value).isEqualTo(ActivationState.Ready)
    }

    @Test
    fun `mode auto fail-open (PROCEED_WITHOUT_VPN) = le flux poursuit sans écran VPN`() =
        runTest(testDispatcher) {
            val device = mock<FuziontvDeviceRepositoryImpl> {
                onBlocking { isActivated() } doReturn true
                onBlocking { fetchCredentials() } doAnswer { throw IOException("hors ligne") }
            }
            val gate = FakeVpnGate(result = VpnGateResult.PROCEED_WITHOUT_VPN, required = true)
            val vm = ActivationViewModel(device, providersWithCache(), diagnosticReporter, gate)

            vm.start()
            advanceUntilIdle()

            // Fail-open : le backend est contacté et la box atterrit sur Ready
            // (cache local), jamais bloquée sur VpnFailed.
            verify(device).fetchCredentials()
            assertThat(vm.state.value).isEqualTo(ActivationState.Ready)
        }
}
