package com.streamvault.app.update

import com.streamvault.app.BuildConfig
import com.streamvault.domain.model.Result
import kotlinx.coroutines.Dispatchers
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
    val publishedAt: String?
)

/**
 * Fetches the latest published APK from the FuzionTV admin backend
 * (`/api/v1/updates/latest`).
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
    }

    suspend fun fetchLatestRelease(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BuildConfig.FUZIONTV_BASE_URL.trimEnd('/')
            val url = "$baseUrl/api/v1/updates/latest?current=0"
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
                        publishedAt = null
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
