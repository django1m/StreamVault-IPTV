package com.streamvault.app.vpn

/**
 * The peer half of a WireGuard config, as provisioned by the backend
 * (`POST /api/v1/device/vpn-config`). The device's own private key never
 * appears here — it stays in the local encrypted store and is combined with
 * this at tunnel-up time.
 */
data class VpnPeerConfig(
    val region: String,
    val endpoint: String,          // "ip:port"
    val serverPublicKey: String,   // base64
    val address: String,           // /32 assigned to this device, e.g. "10.13.0.5"
    val dns: List<String>,
    val persistentKeepalive: Int = 25,
)
