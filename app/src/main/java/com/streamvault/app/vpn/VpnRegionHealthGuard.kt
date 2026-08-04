package com.streamvault.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.streamvault.app.di.ApplicationScope
import com.streamvault.app.diagnostics.DiagnosticReporter
import com.streamvault.domain.model.DiagnosticCategory
import com.streamvault.domain.model.DiagnosticSeverity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-facing wrapper around [VpnRegionHealthPolicy]. Serializes all policy
 * access on a mutex, persists the durable slice to [VpnStore] after every
 * event, exposes the policy's decisions as flows for [VpnManager] to act on,
 * and feeds it connectivity-change events. Uses the [XtreamAuthInvalidationBus]
 * bus pattern (flows, not a direct Manager reference) to avoid a Hilt cycle.
 */
@Singleton
class VpnRegionHealthGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: VpnStore,
    private val diagnosticReporter: DiagnosticReporter,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var policy: VpnRegionHealthPolicy? = null

    // Decisions the manager must act on. Buffered + drop-oldest so a burst can
    // never suspend the policy coroutine.
    private val _fallbackRequests = MutableSharedFlow<VpnRegionHealthPolicy.Decision.RequestFallback>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val fallbackRequests: SharedFlow<VpnRegionHealthPolicy.Decision.RequestFallback> = _fallbackRequests

    private val _retryCycles = MutableSharedFlow<Long>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Class A pacing: emitted delayMs before the manager should retry a cycle. */
    val retryCycles: SharedFlow<Long> = _retryCycles

    // In-region egress rotations the manager must execute (VOD-only escape hatch).
    private val _egressRotateRequests = MutableSharedFlow<VpnRegionHealthPolicy.Decision.RequestEgressRotation>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val egressRotateRequests: SharedFlow<VpnRegionHealthPolicy.Decision.RequestEgressRotation> = _egressRotateRequests

    /** What the box is currently playing ("live" | "movie" | "series" | null).
     *  Set by PlaybackReporter; read by the manager to never tear a live tunnel. */
    @Volatile var activePlayback: String? = null

    @Volatile private var cachedLabel: String = "idle"
    /** Cheap, lock-free snapshot for the heartbeat payload. */
    fun heartbeatLabel(): String = cachedLabel

    init {
        registerNetworkCallback()
    }

    // --- event surface (all fire-and-forget onto the serialized policy) ---
    fun onVerified(region: String) = submit(VpnRegionHealthPolicy.Event.VerifiedUp(region))
    fun onVerificationCycleFailed(region: String, plainNetworkOk: Boolean) =
        submit(VpnRegionHealthPolicy.Event.VerificationCycleFailed(region, plainNetworkOk))
    fun onEnforced403(region: String, providerId: Long, contentId: Long) =
        submit(VpnRegionHealthPolicy.Event.Enforced403(region, "$providerId:$contentId"))
    fun onLiveSuccess(region: String) = submit(VpnRegionHealthPolicy.Event.LiveSuccess(region))
    fun onLiveProbe403() = submit(VpnRegionHealthPolicy.Event.LiveProbe403)
    fun onServerAssignedRegion(region: String?) =
        submit(VpnRegionHealthPolicy.Event.ServerAssignedRegion(region))
    fun onAdminVpnCycle() = submit(VpnRegionHealthPolicy.Event.AdminVpnCycleReset)
    fun onFallbackResult(granted: Boolean, newRegion: String?) =
        submit(VpnRegionHealthPolicy.Event.FallbackResult(granted, newRegion))
    fun onFallbackAborted() = submit(VpnRegionHealthPolicy.Event.FallbackAborted)
    fun onEgressRotationResult(dispatched: Boolean) =
        submit(VpnRegionHealthPolicy.Event.EgressRotationResult(dispatched))

    private fun submit(event: VpnRegionHealthPolicy.Event) {
        scope.launch {
            mutex.withLock {
                val p = ensurePolicy()
                val decision = runCatching { p.onEvent(event) }.getOrElse {
                    Log.w(TAG, "policy event failed: ${it.message}"); return@withLock
                }
                runCatching { store.saveGuardState(p.persisted()) }
                cachedLabel = p.heartbeatLabel()
                route(decision)
            }
        }
    }

    private suspend fun ensurePolicy(): VpnRegionHealthPolicy {
        policy?.let { return it }
        val p = VpnRegionHealthPolicy({ System.currentTimeMillis() }, store.guardState())
        policy = p
        cachedLabel = p.heartbeatLabel()
        return p
    }

    private fun route(d: VpnRegionHealthPolicy.Decision) {
        when (d) {
            is VpnRegionHealthPolicy.Decision.ScheduleRetryCycle -> _retryCycles.tryEmit(d.delayMs)
            is VpnRegionHealthPolicy.Decision.RequestFallback -> _fallbackRequests.tryEmit(d)
            is VpnRegionHealthPolicy.Decision.RequestEgressRotation -> _egressRotateRequests.tryEmit(d)
            is VpnRegionHealthPolicy.Decision.RequestConfirmatoryLiveProbe ->
                Log.d(TAG, "class-B: awaiting organic live proof (spacing ${d.minSpacingMs}ms)")
            is VpnRegionHealthPolicy.Decision.EnterTerminal -> diagnosticReporter.report(
                DiagnosticSeverity.ERROR, DiagnosticCategory.VPN,
                "VPN : quota de bascules de région épuisé (${d.reason}) — appareil figé, intervention admin requise",
            )
            VpnRegionHealthPolicy.Decision.ResetToIdle,
            VpnRegionHealthPolicy.Decision.None -> Unit
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // Watch the UNDERLYING (non-VPN) internet transport, NOT the per-UID
        // default network. registerDefaultNetworkCallback would flip VPN↔underlying
        // every time WE bring our own tunnel up/down, firing spurious NetworkChanged
        // events whose 90 s settle window then suppresses the very Class-A cycle
        // failures the teardown is reporting — making TUNNEL fallback unreachable.
        // A NOT_VPN request only reacts to real Wi-Fi/Ethernet transitions.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var wasLost = false
            override fun onLost(network: Network) { wasLost = true }
            override fun onAvailable(network: Network) {
                // A real underlying-network transition (not our tunnel) opens the
                // evidence-suppression window: a reconnect can produce spurious
                // 403s / handshake gaps we must not count.
                if (wasLost) { wasLost = false; submit(VpnRegionHealthPolicy.Event.NetworkChanged) }
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onFailure { Log.w(TAG, "network callback register failed: ${it.message}") }
    }

    private companion object { const val TAG = "VpnRegionHealthGuard" }
}
