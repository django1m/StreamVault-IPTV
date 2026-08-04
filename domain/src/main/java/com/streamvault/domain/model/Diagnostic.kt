package com.streamvault.domain.model

/**
 * Severity of a device-reported incident. `wireName` is the exact string the
 * webapp endpoint whitelists (POST /api/v1/device/diagnostics).
 *
 * ERROR and CRASH drive the "N incidents" badge on the admin device list;
 * WARNING/INFO are visible on the detail page but do not raise the badge.
 */
enum class DiagnosticSeverity(val wireName: String) {
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    CRASH("crash"),
}

/**
 * Category of a device-reported incident. The PLAYBACK_* values map 1:1 onto
 * the player's PlaybackErrorCategory; the rest cover connection/heartbeat and
 * process crashes. `wireName` is sent verbatim to the webapp.
 */
enum class DiagnosticCategory(val wireName: String) {
    PLAYBACK_NETWORK("playback_network"),
    PLAYBACK_AUTH("playback_auth"),
    PLAYBACK_SERVER("playback_server"),
    PLAYBACK_DECODER("playback_decoder"),
    PLAYBACK_DRM("playback_drm"),
    PLAYBACK_FORMAT("playback_format"),
    PLAYBACK_SOURCE("playback_source"),
    PLAYBACK_LIVE_WINDOW("playback_live_window"),
    PLAYBACK_CLEARTEXT("playback_cleartext"),
    PLAYBACK_SSL("playback_ssl"),
    PLAYBACK_UNKNOWN("playback_unknown"),
    CONNECTION("connection"),
    HEARTBEAT("heartbeat"),
    ACTIVATION("activation"),
    UPDATE("update"),
    VPN("vpn"),
    CRASH("crash"),
}

/**
 * An incident captured locally, queued for best-effort upload to the admin.
 * `context` carries structured, non-fuzzy extras (title, kind, stream_id,
 * engine, http_code, …) rendered as `key: value` on the admin detail page.
 * `occurredAtMs` is the client-side epoch millis of the incident.
 */
data class DiagnosticDraft(
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val message: String,
    val context: Map<String, String> = emptyMap(),
    val occurredAtMs: Long,
)
