package com.streamvault.app.update

import com.streamvault.app.BuildConfig
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String?,
    val releaseNotes: String,
    val publishedAt: String?,
    val sha256: String? = null
)

/**
 * Fetches the latest published APK from the FuzionTV admin backend
 * (`/api/v1/updates/latest`).
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val preferencesRepository: PreferencesRepository
) {
    private companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
        private const val CHANNEL_STABLE = "stable"
        private const val CHANNEL_BETA = "beta"
    }

    /**
     * Resolves the update this device should be offered.
     *
     * Opting into the beta channel means "give me whichever build is newest",
     * NOT "give me betas only": we query both channels and keep the higher
     * versionCode. Querying beta alone would strand a tester on an old build
     * during any period where no beta is published but stable has moved on —
     * and it would also make turning the setting on look like a downgrade.
     */
    suspend fun fetchLatestRelease(): Result<AppUpdateInfo> {
        val betaOptIn = try {
            preferencesRepository.appUpdateBetaChannel.first()
        } catch (_: IOException) {
            // Unreadable DataStore must not block the update check; stable is
            // the safe default. Cancellation deliberately still propagates.
            false
        }
        val stable = fetchChannel(CHANNEL_STABLE)
        if (!betaOptIn) return stable

        val beta = fetchChannel(CHANNEL_BETA)
        val stableInfo = stable.getOrNull()
        val betaInfo = beta.getOrNull()
        return when {
            stableInfo == null -> beta
            betaInfo == null -> stable
            // Ties can't happen (version_code is globally UNIQUE backend-side),
            // but prefer stable on equality anyway — it is the safer build.
            (betaInfo.versionCode ?: 0) > (stableInfo.versionCode ?: 0) -> beta
            else -> stable
        }
    }

    private suspend fun fetchChannel(channel: String): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BuildConfig.FUZIONTV_BASE_URL.trimEnd('/')
            // `variant` scopes the lookup to this build's form factor. Without
            // it the backend defaults to "tv", which would offer a TV APK to a
            // phone (and, since mobile versionCodes are offset by +100000, would
            // otherwise let a phone release reach the whole box fleet).
            // `channel` is orthogonal: stable releases vs opt-in test builds.
            // `current=0` est DÉLIBÉRÉ : on veut connaître la dernière version publiée
            // même quand elle n’est pas plus récente que le build installé, sinon le
            // backend répondrait updateAvailable:false (updates.js) et l’écran Réglages
            // afficherait une erreur au lieu de « vous êtes à jour ». Le garde
            // anti-downgrade vit donc côté client, dans isAppUpdateNewer — et lui seul.
            val url = "$baseUrl/api/v1/updates/latest?current=0" +
                "&variant=${BuildConfig.APP_VARIANT}&channel=$channel"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "FuzionTV-Update-Checker")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.error("Update check failed: HTTP ${response.code}")
                }

                val body = when (val bodyResult = response.body?.let(::readResponseBodyCapped)) {
                    is Result.Success -> bodyResult.data
                    is Result.Error -> return@withContext Result.error(bodyResult.message, bodyResult.exception)
                    null,
                    Result.Loading -> ""
                }
                if (body.isBlank()) {
                    return@withContext Result.error("Update check failed: empty release response")
                }

                val json = JSONObject(body)
                val updateAvailable = json.optBoolean("updateAvailable", false)

                if (!updateAvailable) {
                    return@withContext if (json.has("versionCode")) {
                        Result.error("Update check failed: no newer release available")
                    } else {
                        Result.error("Update check failed: no published release on server")
                    }
                }

                val versionCode = if (json.has("versionCode") && !json.isNull("versionCode")) {
                    json.optInt("versionCode")
                } else {
                    null
                }
                val versionName = json.optString("versionName").trim()
                val downloadUrl = json.optString("url").trim()
                val releaseNotes = if (json.has("releaseNotes") && !json.isNull("releaseNotes")) {
                    json.optString("releaseNotes")
                } else {
                    null
                }
                val sha256 = if (json.has("sha256") && !json.isNull("sha256")) {
                    json.optString("sha256").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }

                if (versionName.isBlank() || versionCode == null) {
                    return@withContext Result.error("Update check failed: malformed update response")
                }
                if (!isHttpsUrl(downloadUrl)) {
                    return@withContext Result.error("Update check failed: download URL is not HTTPS")
                }

                return@withContext Result.success(
                    AppUpdateInfo(
                        versionName = versionName,
                        versionCode = versionCode,
                        releaseUrl = downloadUrl,
                        downloadUrl = downloadUrl,
                        releaseNotes = releaseNotes ?: "",
                        publishedAt = null,
                        sha256 = sha256
                    )
                )
            }
        } catch (error: IOException) {
            Result.error("Update check failed: network error", error)
        } catch (error: Exception) {
            Result.error("Update check failed: ${error.message}", error)
        }
    }

    private fun readResponseBodyCapped(body: ResponseBody): Result<String> {
        val contentLength = body.contentLength()
        if (contentLength > MAX_RESPONSE_BYTES) {
            return Result.error("Update check failed: response exceeded 512 KB")
        }

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytesRead = 0L

        body.byteStream().use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                totalBytesRead += bytesRead
                if (totalBytesRead > MAX_RESPONSE_BYTES) {
                    return Result.error("Update check failed: response exceeded 512 KB")
                }

                output.write(buffer, 0, bytesRead)
            }
        }

        return Result.success(output.toString(charset.name()))
    }

    private fun isHttpsUrl(url: String): Boolean {
        val normalized = url.trim()
        if (normalized.isBlank()) return false
        return runCatching {
            val parsed = URI(normalized)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
