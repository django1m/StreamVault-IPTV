package com.streamvault.data.remote.fuziontv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class FuziontvApiException(
    val httpCode: Int,
    val errorCode: String?,
    message: String,
) : Exception(message)

class FuziontvApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * A clone of the shared client with a read timeout longer than the server's
     * 25 s long-poll hold, so [awaitCommands] waits for a pushed command instead
     * of tripping the default 30 s read timeout mid-hold. Reuses the shared
     * connection pool/dispatcher via [OkHttpClient.newBuilder].
     */
    private val longPollClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun Request.Builder.auth(deviceId: String?, deviceToken: String?): Request.Builder {
        if (deviceId != null) header("X-Device-Id", deviceId)
        if (deviceToken != null) header("X-Device-Token", deviceToken)
        return this
    }

    private inline fun <reified T> exec(request: Request): T {
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val code = runCatching { json.decodeFromString<Map<String, String>>(body)["error"] }.getOrNull()
            throw FuziontvApiException(response.code, code, "HTTP ${response.code}: ${code ?: body}")
        }
        return json.decodeFromString(body)
    }

    suspend fun activate(req: ActivateRequest): ActivateResponse = withContext(Dispatchers.IO) {
        exec(
            Request.Builder()
                .url("$baseUrl/api/v1/device/activate")
                .post(json.encodeToString(req).toRequestBody(jsonMedia))
                .build()
        )
    }

    suspend fun status(deviceId: String): StatusResponse = withContext(Dispatchers.IO) {
        exec(
            Request.Builder()
                .url("$baseUrl/api/v1/device/status?deviceId=$deviceId")
                .build()
        )
    }

    suspend fun credentials(deviceId: String, deviceToken: String): CredentialsResponse =
        withContext(Dispatchers.IO) {
            exec(
                Request.Builder()
                    .url("$baseUrl/api/v1/device/credentials")
                    .auth(deviceId, deviceToken)
                    .build()
            )
        }

    /**
     * Provision a WireGuard peer for this device's public key. The backend
     * calls PIA server-side; PIA account creds never reach the device.
     */
    suspend fun vpnConfig(
        deviceId: String,
        deviceToken: String,
        publicKey: String,
        failedRegion: String? = null,
        failureKind: String? = null,
        evidenceSummary: String? = null,
    ): VpnConfigResponse =
        withContext(Dispatchers.IO) {
            val payload = VpnConfigRequest(publicKey, failedRegion, failureKind, evidenceSummary)
            exec(
                Request.Builder()
                    .url("$baseUrl/api/v1/device/vpn-config")
                    .post(json.encodeToString(payload).toRequestBody(jsonMedia))
                    .auth(deviceId, deviceToken)
                    .build()
            )
        }

    suspend fun heartbeat(
        deviceId: String,
        deviceToken: String,
        appVersionCode: Int,
        appVersionName: String? = null,
        health: DeviceHealth? = null,
        vpnAutoActivated: Boolean? = null,
        vpn: VpnStateDto? = null,
    ): HeartbeatResponse =
        withContext(Dispatchers.IO) {
            val payload = HeartbeatRequest(
                appVersionCode = appVersionCode,
                appVersionName = appVersionName,
                uptimeSeconds = health?.uptimeSeconds,
                memFreeMb = health?.memFreeMb,
                memTotalMb = health?.memTotalMb,
                playerEngine = health?.playerEngine,
                vpnAutoActivated = vpnAutoActivated,
                vpn = vpn,
            )
            exec(
                Request.Builder()
                    .url("$baseUrl/api/v1/device/heartbeat")
                    .post(json.encodeToString(payload).toRequestBody(jsonMedia))
                    .auth(deviceId, deviceToken)
                    .build()
            )
        }

    suspend fun heartbeatWithPlayback(
        deviceId: String,
        deviceToken: String,
        appVersionCode: Int,
        playingKind: String?,
        playingStreamId: Long?,
        appVersionName: String? = null,
        health: DeviceHealth? = null,
        vpn: VpnStateDto? = null,
    ): HeartbeatResponse = withContext(Dispatchers.IO) {
        val payload = HeartbeatWithPlaybackRequest(
            appVersionCode = appVersionCode,
            appVersionName = appVersionName,
            playingKind = playingKind,
            playingStreamId = playingStreamId,
            uptimeSeconds = health?.uptimeSeconds,
            memFreeMb = health?.memFreeMb,
            memTotalMb = health?.memTotalMb,
            playerEngine = health?.playerEngine,
            vpn = vpn,
        )
        exec(
            Request.Builder()
                .url("$baseUrl/api/v1/device/heartbeat")
                .post(json.encodeToString(payload).toRequestBody(jsonMedia))
                .auth(deviceId, deviceToken)
                .build()
        )
    }

    /**
     * Real-time command channel (long-poll). The server holds this GET open
     * until a command is queued for this device (delivered in ~1 s) or its
     * ~25 s window elapses (returns empty → caller reconnects). Claim semantics
     * are shared with the heartbeat (at-most-once), so a command arrives via
     * exactly one channel.
     */
    suspend fun awaitCommands(
        deviceId: String,
        deviceToken: String,
    ): CommandWaitResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/device/commands/wait")
            .auth(deviceId, deviceToken)
            .build()
        val response = longPollClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val code = runCatching { json.decodeFromString<Map<String, String>>(body)["error"] }.getOrNull()
            throw FuziontvApiException(response.code, code, "HTTP ${response.code}: ${code ?: body}")
        }
        json.decodeFromString(body)
    }

    suspend fun commandAck(
        deviceId: String,
        deviceToken: String,
        commandId: Long,
        ok: Boolean,
        result: String? = null,
    ): CommandAckResponse = withContext(Dispatchers.IO) {
        val payload = CommandAckRequest(commandId, ok, result)
        exec(
            Request.Builder()
                .url("$baseUrl/api/v1/device/command-ack")
                .post(json.encodeToString(payload).toRequestBody(jsonMedia))
                .auth(deviceId, deviceToken)
                .build()
        )
    }

    suspend fun reportDiagnostics(
        deviceId: String,
        deviceToken: String,
        items: List<DiagnosticItem>,
    ): DiagnosticsResponse = withContext(Dispatchers.IO) {
        val payload = DiagnosticsRequest(items)
        exec(
            Request.Builder()
                .url("$baseUrl/api/v1/device/diagnostics")
                .post(json.encodeToString(payload).toRequestBody(jsonMedia))
                .auth(deviceId, deviceToken)
                .build()
        )
    }

    suspend fun syncAck(deviceId: String, deviceToken: String) {
        withContext(Dispatchers.IO) {
            exec<Map<String, Boolean>>(
                Request.Builder()
                    .url("$baseUrl/api/v1/device/sync-ack")
                    .post("{}".toRequestBody(jsonMedia))
                    .auth(deviceId, deviceToken)
                    .build()
            )
        }
    }

    /**
     * @param variant APK update channel — "tv" or "mobile". Defaults to "tv" so
     *   any caller not yet aware of form factors keeps the historical behaviour;
     *   the backend applies the same default when the parameter is absent.
     */
    suspend fun checkUpdate(
        currentVersionCode: Int,
        variant: String = "tv",
    ): UpdateLatestResponse =
        withContext(Dispatchers.IO) {
            exec(
                Request.Builder()
                    .url("$baseUrl/api/v1/updates/latest?current=$currentVersionCode&variant=$variant")
                    .build()
            )
        }
}
