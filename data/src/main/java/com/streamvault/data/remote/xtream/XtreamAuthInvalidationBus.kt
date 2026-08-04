package com.streamvault.data.remote.xtream

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide signal channel for "an Xtream call just returned 401/403". Used
 * to trigger an out-of-band credential refresh (fetch fresh user/pass from the
 * FuzionTV admin backend, persist into the provider, so the *next* Xtream
 * request uses the new creds).
 *
 * Why a bus and not a synchronous replay-on-401:
 *   - Xtream URLs embed `username` / `password` either in query params
 *     (`player_api.php?username=…&password=…`) or in the path
 *     (`/live/<user>/<pass>/<stream_id>.ts`). Rebuilding the failed URL with
 *     new credentials requires non-trivial regex surgery duplicated across
 *     every call site.
 *   - The orchestrator on the receiving end is the single place that knows
 *     how to talk to the admin backend, diff against the current provider
 *     credentials, and update them — keeping that concern out of the HTTP
 *     layer is cleaner.
 *   - 401 from the panel typically signals one of two things: credentials
 *     rotated (next call succeeds after refresh) OR device revoked (next
 *     admin call also 401s, orchestrator clears activation, AuthGate
 *     redirects to ACTIVATION). The user sees at most one transient error
 *     followed by recovery — no infinite retry storms.
 */
@Singleton
class XtreamAuthInvalidationBus @Inject constructor() {

    private val _signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val signals: SharedFlow<Unit> = _signals.asSharedFlow()

    fun emit() {
        _signals.tryEmit(Unit)
    }
}
