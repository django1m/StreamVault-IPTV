package com.streamvault.data.remote.fuziontv

import kotlinx.serialization.Serializable

@Serializable
data class ActivateRequest(
    val deviceId: String,
    val appVersionCode: Int,
    val appVersionName: String,
    val platform: String = "androidtv",
)

@Serializable
data class ActivateResponse(
    val managed: Boolean = false,
    val activationCode: String? = null,
    val pollIntervalMs: Long? = null,
    val needsReactivation: Boolean = false,
)

@Serializable
data class StatusResponse(
    val managed: Boolean,
    val status: String,
    val activationCode: String? = null,
    val credentialsReady: Boolean = false,
    val deviceToken: String? = null,
)

@Serializable
data class XtreamCreds(
    val host: String,
    val username: String,
    val password: String,
)

@Serializable
data class CredentialsResponse(
    val xtream: XtreamCreds,
    val epgOverrideUrl: String? = null,
)

/**
 * Bounded health telemetry attached to heartbeats. Every field is optional —
 * an old server simply ignores unknown JSON keys, an old APK never sends them.
 */
@Serializable
data class DeviceHealth(
    val uptimeSeconds: Long? = null,
    val memFreeMb: Int? = null,
    val memTotalMb: Int? = null,
    val playerEngine: String? = null, // 'media3' | 'libvlc'
)

/** Tunnel state attached to a heartbeat. Nullable end-to-end: an old server
 *  ignores it, an old APK never sends it. Purely observational (admin view). */
@Serializable
data class VpnStateDto(
    val up: Boolean,
    val region: String? = null,
    val handshakeAgeSec: Long? = null,
    val guardState: String? = null, // "healthy" | "tunnel_suspect(2/3)" | "cooldown" | "terminal"
)

@Serializable
data class HeartbeatRequest(
    val appVersionCode: Int,
    val appVersionName: String? = null,
    val uptimeSeconds: Long? = null,
    val memFreeMb: Int? = null,
    val memTotalMb: Int? = null,
    val playerEngine: String? = null,
    // Set once when the box auto-activated its VPN after an on-demand 403.
    val vpnAutoActivated: Boolean? = null,
    val vpn: VpnStateDto? = null,
)

@Serializable
data class HeartbeatWithPlaybackRequest(
    val appVersionCode: Int,
    val appVersionName: String? = null,
    val playingKind: String?,     // 'live' | 'movie' | 'series', or null = stop signal
    val playingStreamId: Long?,   // upstream stream_id / series_id, or null = stop signal
    val uptimeSeconds: Long? = null,
    val memFreeMb: Int? = null,
    val memTotalMb: Int? = null,
    val playerEngine: String? = null,
    val vpnAutoActivated: Boolean? = null,
    val vpn: VpnStateDto? = null,
)

/** Peer half of a WireGuard config, provisioned server-side from a public key. */
@Serializable
data class VpnPeer(
    val region: String = "",
    val endpoint: String,
    val serverPublicKey: String,
    val address: String,
    val dns: List<String> = emptyList(),
    val allowedIps: String = "0.0.0.0/0",
    val persistentKeepalive: Int = 25,
    val serverIp: String? = null,
    val serverCn: String? = null,
)

@Serializable
data class VpnConfigRequest(
    val publicKey: String,
    // Present only on a fallback request: the region the device believes is
    // broken/banned, plus why. The server arbitrates and may deny.
    val failedRegion: String? = null,
    val failureKind: String? = null,     // 'tunnel' | 'blocked'
    val evidenceSummary: String? = null, // short free text, server-clamped
)

@Serializable
data class VpnConfigResponse(
    val vpn: VpnPeer,
    // Present only in reply to a fallback request. null on a normal provision.
    val fallbackGranted: Boolean? = null,
    val fallbackReason: String? = null,  // deny reason when fallbackGranted == false
)

/** Remote command delivered in the heartbeat response (admin-queued). */
@Serializable
data class DeviceCommandDto(
    val id: Long,
    val action: String,
    val params: Map<String, String>? = null,
)

@Serializable
data class HeartbeatResponse(
    val ok: Boolean = false,
    val forceSync: Boolean = false,
    // Source of truth for VPN behaviour: 'auto' | 'on' | 'off'. Absent on old
    // servers → treated as 'auto' by the app.
    val vpnMode: String? = null,
    // Effective region assignment. null = not yet assigned (app keeps its
    // cached config); a value different from the app's cached region means the
    // server reassigned (admin / fallback) → app re-provisions on next connect.
    val vpnRegion: String? = null,
    val commands: List<DeviceCommandDto> = emptyList(),
)

/** Response of the GET /commands/wait long-poll (real-time command channel). */
@Serializable
data class CommandWaitResponse(
    val ok: Boolean = false,
    val commands: List<DeviceCommandDto> = emptyList(),
)

@Serializable
data class CommandAckRequest(
    val commandId: Long,
    val ok: Boolean,
    val result: String? = null,
)

@Serializable
data class CommandAckResponse(val ok: Boolean = false, val updated: Boolean = false)

@Serializable
data class DiagnosticItem(
    val severity: String,
    val category: String,
    val message: String,
    val context: Map<String, String>? = null,
    val appVersionCode: Int? = null,
    val appVersionName: String? = null,
    val occurredAtMs: Long,
)

@Serializable
data class DiagnosticsRequest(val items: List<DiagnosticItem>)

@Serializable
data class DiagnosticsResponse(val ok: Boolean = false, val stored: Int = 0)

@Serializable
data class UpdateLatestResponse(
    val updateAvailable: Boolean,
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val mandatory: Boolean = false,
    val releaseNotes: String? = null,
    /** Channel this release belongs to. Older backends omit it; "tv" is the historical one. */
    val variant: String = "tv",
)
