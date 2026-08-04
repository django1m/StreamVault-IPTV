package com.streamvault.app.vpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vpnStore: DataStore<Preferences> by preferencesDataStore("fuziontv_vpn")

/**
 * Isolated store for VPN state (kept out of user_preferences so the WireGuard
 * private key never mixes with UI prefs). The keypair is stable and reused:
 * PIA lets us re-register the same public key on every (re)connect, so we only
 * ever generate it once per install.
 */
@Singleton
class VpnStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val PRIVATE_KEY = stringPreferencesKey("wg_private_key")
        val PUBLIC_KEY = stringPreferencesKey("wg_public_key")
        val VPN_MODE = stringPreferencesKey("vpn_mode")          // auto | on | off
        val AUTO_TRIGGERED = stringPreferencesKey("auto_triggered") // "1" once triggered

        // Cached peer config (egress-IP stickiness + offline-webapp resilience):
        // reuse this instead of re-provisioning on every reconnect. The server
        // pins the PIA server per device, so a refresh returns the same endpoint.
        val PEER_REGION = stringPreferencesKey("peer_region")
        val PEER_ENDPOINT = stringPreferencesKey("peer_endpoint")
        val PEER_SERVER_PUBKEY = stringPreferencesKey("peer_server_pubkey")
        val PEER_ADDRESS = stringPreferencesKey("peer_address")
        val PEER_DNS = stringPreferencesKey("peer_dns")           // CSV
        val PEER_KEEPALIVE = stringPreferencesKey("peer_keepalive")
        val PEER_PROVISIONED_AT = stringPreferencesKey("peer_provisioned_at") // epoch ms

        // Persisted region-health guard state (must survive restarts so a
        // cooldown / terminal / fallback-history isn't lost on reboot).
        val GUARD_COOLDOWN_UNTIL = stringPreferencesKey("guard_cooldown_until")
        val GUARD_FALLBACK_HISTORY = stringPreferencesKey("guard_fallback_history") // CSV epoch ms
        val GUARD_TERMINAL = stringPreferencesKey("guard_terminal")   // "1"
        val GUARD_LAST_REASON = stringPreferencesKey("guard_last_reason")
    }

    val vpnModeFlow = context.vpnStore.data.map { it[VPN_MODE] ?: "auto" }

    suspend fun keyPair(): Pair<String, String>? {
        val p = context.vpnStore.data.first()
        val priv = p[PRIVATE_KEY]; val pub = p[PUBLIC_KEY]
        return if (priv != null && pub != null) priv to pub else null
    }

    suspend fun saveKeyPair(privateKey: String, publicKey: String) {
        context.vpnStore.edit { it[PRIVATE_KEY] = privateKey; it[PUBLIC_KEY] = publicKey }
    }

    suspend fun mode(): String = context.vpnStore.data.first()[VPN_MODE] ?: "auto"

    suspend fun setMode(mode: String) {
        context.vpnStore.edit { it[VPN_MODE] = mode }
    }

    suspend fun autoTriggered(): Boolean = context.vpnStore.data.first()[AUTO_TRIGGERED] == "1"

    suspend fun setAutoTriggered(value: Boolean) {
        context.vpnStore.edit { it[AUTO_TRIGGERED] = if (value) "1" else "0" }
    }

    // --- cached peer config -----------------------------------------------

    suspend fun cachedPeer(): CachedPeer? {
        val p = context.vpnStore.data.first()
        val endpoint = p[PEER_ENDPOINT] ?: return null
        val serverKey = p[PEER_SERVER_PUBKEY] ?: return null
        val address = p[PEER_ADDRESS] ?: return null
        val dns = p[PEER_DNS]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return CachedPeer(
            config = VpnPeerConfig(
                region = p[PEER_REGION] ?: "",
                endpoint = endpoint,
                serverPublicKey = serverKey,
                address = address,
                dns = dns,
                persistentKeepalive = p[PEER_KEEPALIVE]?.toIntOrNull() ?: 25,
            ),
            provisionedAtMs = p[PEER_PROVISIONED_AT]?.toLongOrNull() ?: 0L,
        )
    }

    suspend fun savePeer(config: VpnPeerConfig, provisionedAtMs: Long) {
        context.vpnStore.edit {
            it[PEER_REGION] = config.region
            it[PEER_ENDPOINT] = config.endpoint
            it[PEER_SERVER_PUBKEY] = config.serverPublicKey
            it[PEER_ADDRESS] = config.address
            it[PEER_DNS] = config.dns.joinToString(",")
            it[PEER_KEEPALIVE] = config.persistentKeepalive.toString()
            it[PEER_PROVISIONED_AT] = provisionedAtMs.toString()
        }
    }

    suspend fun clearPeer() {
        context.vpnStore.edit {
            it.remove(PEER_REGION); it.remove(PEER_ENDPOINT); it.remove(PEER_SERVER_PUBKEY)
            it.remove(PEER_ADDRESS); it.remove(PEER_DNS); it.remove(PEER_KEEPALIVE)
            it.remove(PEER_PROVISIONED_AT)
        }
    }

    // --- guard state (persisted subset; counters stay in-memory) ----------

    suspend fun guardState(): PersistedGuardState {
        val p = context.vpnStore.data.first()
        val history = p[GUARD_FALLBACK_HISTORY]
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
        return PersistedGuardState(
            cooldownUntilMs = p[GUARD_COOLDOWN_UNTIL]?.toLongOrNull() ?: 0L,
            fallbackHistoryMs = history,
            terminal = p[GUARD_TERMINAL] == "1",
            lastReason = p[GUARD_LAST_REASON],
        )
    }

    suspend fun saveGuardState(s: PersistedGuardState) {
        context.vpnStore.edit {
            it[GUARD_COOLDOWN_UNTIL] = s.cooldownUntilMs.toString()
            it[GUARD_FALLBACK_HISTORY] = s.fallbackHistoryMs.joinToString(",")
            it[GUARD_TERMINAL] = if (s.terminal) "1" else "0"
            if (s.lastReason != null) it[GUARD_LAST_REASON] = s.lastReason else it.remove(GUARD_LAST_REASON)
        }
    }
}

/** A cached [VpnPeerConfig] plus when it was provisioned (epoch ms). */
data class CachedPeer(val config: VpnPeerConfig, val provisionedAtMs: Long)
