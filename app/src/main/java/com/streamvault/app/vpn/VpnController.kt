package com.streamvault.app.vpn

import android.content.Context
import android.net.InetAddresses
import android.os.Build
import android.util.Log
import com.streamvault.app.network.ControlPlanePoolEvictor
import com.streamvault.player.system.DevicePerformanceProfile
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** One read of WireGuard peer stats. handshakeAgeSec is null when never handshaked. */
data class PeerStatsSnapshot(val handshakeAgeSec: Long?, val rxBytes: Long, val txBytes: Long)

/**
 * Owns the WireGuard tunnel (PIA) that routes FuzionTV's own traffic through an
 * egress IP the provider's on-demand edges accept. Only this app's packets go
 * through the tunnel ([Interface.Builder.includeApplication]); the LAN is
 * excluded from AllowedIPs so Cast / local discovery keep working.
 *
 * The tunnel service itself is provided by the wireguard-android AAR
 * (`GoBackend$VpnService`, already declared in its merged manifest) — we never
 * write our own VpnService.
 */
@Singleton
class VpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val performanceProfile: DevicePerformanceProfile,
    private val poolEvictor: ControlPlanePoolEvictor,
) {
    private val backend: Backend by lazy { GoBackend(context) }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    val isUp: Boolean get() = _state.value == VpnState.CONNECTED

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = when (newState) {
                Tunnel.State.UP -> VpnState.CONNECTED
                Tunnel.State.DOWN -> VpnState.DISCONNECTED
                Tunnel.State.TOGGLE -> _state.value
            }
        }
    }

    /**
     * Returns the OS consent Intent if the user has not yet authorized a VPN
     * for this app (must be launched for-result from an Activity), or null when
     * consent is already granted. Delegates to [android.net.VpnService.prepare].
     */
    fun prepareConsentIntent(): android.content.Intent? =
        android.net.VpnService.prepare(context)

    /** Generate a fresh WireGuard keypair. Returns (privateKeyBase64, publicKeyBase64). */
    fun generateKeyPair(): Pair<String, String> {
        val kp = KeyPair()
        return kp.privateKey.toBase64() to kp.publicKey.toBase64()
    }

    /** Bring the tunnel up with [peer] and the device's [privateKeyBase64]. */
    suspend fun connect(peer: VpnPeerConfig, privateKeyBase64: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                _state.value = VpnState.CONNECTING
                val config = buildConfig(peer, privateKeyBase64)
                backend.setState(tunnel, Tunnel.State.UP, config)
                // Kill idle sockets opened on the plain interface BEFORE the
                // verifier probes — else its HTTP check can reuse a pre-tunnel
                // socket and "verify" a tunnel it never traversed.
                poolEvictor.onTunnelTransition(up = true)
                Log.i(TAG, "tunnel up (region=${peer.region}, tier=${performanceProfile.tier})")
                true
            } catch (e: Exception) {
                Log.e(TAG, "connect failed: ${e.message}", e)
                _state.value = VpnState.ERROR
                false
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                .onFailure { Log.w(TAG, "disconnect failed: ${it.message}") }
            _state.value = VpnState.DISCONNECTED
            // The post-teardown httpReachable() probe must not reuse a socket
            // that was routed through the now-dead tunnel.
            poolEvictor.onTunnelTransition(up = false)
        }
    }

    /** True when the OS reports our tunnel as running (survives process death). */
    fun isTunnelRunning(): Boolean =
        runCatching { backend.runningTunnelNames.contains(TUNNEL_NAME) }.getOrDefault(false)

    /**
     * One-shot read of the peer's WireGuard statistics. `handshakeAgeSec` is
     * null when no handshake has ever completed (dead endpoint) or stats are
     * unavailable. Never throws — a failure returns null so callers treat it as
     * "unknown". Cheap enough to poll a few times per connect / once per
     * heartbeat on a 2 GB box.
     */
    suspend fun peerStats(serverPublicKeyBase64: String): PeerStatsSnapshot? =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = Key.fromBase64(serverPublicKeyBase64)
                val stats = backend.getStatistics(tunnel)
                val peer = stats.peer(key) ?: return@runCatching null
                val hs = peer.latestHandshakeEpochMillis
                PeerStatsSnapshot(
                    handshakeAgeSec = if (hs > 0) (System.currentTimeMillis() - hs) / 1000 else null,
                    rxBytes = peer.rxBytes,
                    txBytes = peer.txBytes,
                )
            }.getOrNull()
        }

    private fun buildConfig(peer: VpnPeerConfig, privateKeyBase64: String): Config {
        val iface = Interface.Builder()
            .setKeyPair(KeyPair(Key.fromBase64(privateKeyBase64)))
            .addAddress(InetNetwork.parse(ensureCidr(peer.address, 32)))
            .apply {
                peer.dns.forEach { addDnsServer(parseInet(it)) }
                // Route ONLY this app through the tunnel; the rest of the box
                // (YouTube, launcher, other apps) keeps its normal connection.
                includeApplication(context.packageName)
            }
            .build()

        // 0.0.0.0/0 minus RFC1918 LAN (keeps Cast / local discovery direct),
        // PLUS the PIA DNS servers as /32 — they live inside 10.0.0.0/8 which
        // we otherwise exclude, so without this the app can't resolve anything
        // through the tunnel (longest-prefix match routes these /32 in).
        val allowedIps = buildString {
            append(ALLOWED_IPS_EXCLUDING_LAN)
            peer.dns.forEach { append(", ").append(it).append("/32") }
        }
        val wgPeer = Peer.Builder()
            .setPublicKey(Key.fromBase64(peer.serverPublicKey))
            .setEndpoint(InetEndpoint.parse(peer.endpoint))
            .parseAllowedIPs(allowedIps)
            .setPersistentKeepalive(peer.persistentKeepalive)
            .build()

        return Config.Builder().setInterface(iface).addPeer(wgPeer).build()
    }

    private fun parseInet(ip: String): InetAddress =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) InetAddresses.parseNumericAddress(ip)
        else InetAddress.getByName(ip)

    private fun ensureCidr(addr: String, defaultPrefix: Int): String =
        if (addr.contains('/')) addr else "$addr/$defaultPrefix"

    private companion object {
        const val TAG = "VpnController"
        const val TUNNEL_NAME = "fuziontv"

        // Standard "0.0.0.0/0 except private ranges" split. Excludes 10/8,
        // 172.16/12, 192.168/16 so LAN-local traffic (Chromecast, mDNS/SSDP)
        // bypasses the tunnel. IPv6 is intentionally not routed (PIA WG peer is
        // IPv4); the app's own sockets are IPv4 for playback.
        const val ALLOWED_IPS_EXCLUDING_LAN =
            "0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, " +
            "64.0.0.0/2, 128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/6, 172.0.0.0/12, " +
            "172.32.0.0/11, 172.64.0.0/10, 172.128.0.0/9, 173.0.0.0/8, 174.0.0.0/7, " +
            "176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, 192.169.0.0/16, " +
            "192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, " +
            "193.0.0.0/8, 194.0.0.0/7, 196.0.0.0/6, 200.0.0.0/5, 208.0.0.0/4"
    }
}
