package com.streamvault.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Observes the device activation token. When it transitions from authenticated
 * to unauthenticated (e.g. the heartbeat worker received a 401 and called
 * `clearActivation()`, or a background sync receives a token-revoked response),
 * [AppNavigation] reroutes the user to the activation screen so they don't sit
 * on a dead session staring at stale data.
 *
 * The state starts as `null` (loading) — callers must wait for a non-null
 * value before reacting to avoid a false reroute on cold start.
 */
@HiltViewModel
class AuthGateViewModel @Inject constructor(
    device: FuziontvDeviceRepositoryImpl
) : ViewModel() {

    enum class AuthState { LOADING, ACTIVATED, REVOKED }

    val authState: StateFlow<AuthState> = device.deviceTokenFlow
        .map { if (it.isNullOrBlank()) AuthState.REVOKED else AuthState.ACTIVATED }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AuthState.LOADING
        )
}
