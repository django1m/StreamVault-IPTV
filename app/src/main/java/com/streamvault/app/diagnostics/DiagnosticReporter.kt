package com.streamvault.app.diagnostics

import com.streamvault.data.repository.DiagnosticsRepository
import com.streamvault.domain.model.DiagnosticCategory
import com.streamvault.domain.model.DiagnosticDraft
import com.streamvault.domain.model.DiagnosticSeverity
import com.streamvault.player.PlayerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-facing facade that turns incidents into clear, explicitly-worded French
 * diagnostics and enqueues them for best-effort upload. Messages are written so
 * the admin reads exactly what failed — never a fuzzy code.
 */
@Singleton
class DiagnosticReporter @Inject constructor(
    private val repository: DiagnosticsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun report(
        severity: DiagnosticSeverity,
        category: DiagnosticCategory,
        message: String,
        context: Map<String, String> = emptyMap(),
    ) {
        val draft = DiagnosticDraft(severity, category, message, context, System.currentTimeMillis())
        scope.launch { runCatching { repository.enqueue(draft) } }
    }

    /** Terminal playback failure (after every recovery attempt has failed). */
    fun reportPlaybackFailure(
        error: PlayerError,
        contentType: String,
        title: String,
        engine: String,
    ) {
        val detail = error.message.trim()
        val httpCode = HTTP_CODE.find(detail)?.value
        val (category, reason) = classify(error, httpCode)
        val label = contentLabel(contentType)
        val name = title.ifBlank { "sans titre" }
        val message = "Lecture $label « $name » échouée — $reason (moteur ${engineLabel(engine)})"
        val context = buildMap {
            put("type", label)
            if (title.isNotBlank()) put("titre", title)
            put("moteur", engineLabel(engine))
            if (httpCode != null) put("http", httpCode)
            if (detail.isNotBlank()) put("détail", detail.take(300))
        }
        report(DiagnosticSeverity.ERROR, category, message, context)
    }

    /**
     * Pre-playback probe rejected by the panel (no engine ever ran, so
     * [reportPlaybackFailure] never fires for these). WARNING when an
     * alternate container extension ended up working — fleet-wide, those
     * warnings confirm panels advertising the wrong/no extension.
     */
    fun reportStreamProbeRejected(
        httpCode: Int,
        contentType: String,
        title: String,
        requestedExtension: String?,
        triedExtensions: List<String>,
        recoveredExtension: String?,
        host: String?,
    ) {
        val label = contentLabel(contentType)
        val name = title.ifBlank { "sans titre" }
        val severity =
            if (recoveredExtension != null) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR
        val category = when (httpCode) {
            401, 403 -> DiagnosticCategory.PLAYBACK_AUTH
            404 -> DiagnosticCategory.PLAYBACK_SOURCE
            else -> DiagnosticCategory.PLAYBACK_SERVER
        }
        val message = if (recoveredExtension != null) {
            "Sonde $label « $name » : HTTP $httpCode sur .${requestedExtension ?: "?"}, " +
                "récupérée avec .$recoveredExtension"
        } else {
            "Sonde $label « $name » refusée par le fournisseur — HTTP $httpCode" +
                (triedExtensions.takeIf { it.isNotEmpty() }
                    ?.let { " (extensions testées : ${it.joinToString(", ")})" } ?: "")
        }
        val context = buildMap {
            put("type", label)
            if (title.isNotBlank()) put("titre", title)
            put("http", httpCode.toString())
            requestedExtension?.let { put("ext", it) }
            if (triedExtensions.isNotEmpty()) put("ext_testées", triedExtensions.joinToString(","))
            recoveredExtension?.let { put("ext_ok", it) }
            host?.let { put("host", it) }
        }
        report(severity, category, message, context)
    }

    private fun classify(error: PlayerError, httpCode: String?): Pair<DiagnosticCategory, String> = when {
        httpCode == "401" || httpCode == "403" ->
            DiagnosticCategory.PLAYBACK_AUTH to "HTTP $httpCode authentification refusée par le fournisseur"
        httpCode in SERVER_CODES ->
            DiagnosticCategory.PLAYBACK_SERVER to "HTTP $httpCode erreur côté fournisseur"
        error is PlayerError.NetworkError ->
            DiagnosticCategory.PLAYBACK_NETWORK to "problème de connexion réseau"
        error is PlayerError.DecoderError ->
            DiagnosticCategory.PLAYBACK_DECODER to "décodage vidéo impossible sur cette box"
        error is PlayerError.DrmError ->
            DiagnosticCategory.PLAYBACK_DRM to "contenu protégé (DRM) non lisible"
        error is PlayerError.SourceError ->
            DiagnosticCategory.PLAYBACK_SOURCE to "flux illisible ou interrompu"
        else ->
            DiagnosticCategory.PLAYBACK_UNKNOWN to "erreur de lecture inconnue"
    }

    private fun contentLabel(contentType: String): String = when (contentType.uppercase()) {
        "LIVE", "CHANNEL" -> "chaîne"
        "MOVIE", "VOD" -> "VOD"
        "SERIES_EPISODE", "SERIES" -> "épisode"
        else -> "contenu"
    }

    private fun engineLabel(engine: String): String = when (engine.uppercase()) {
        "LIBVLC" -> "LibVLC"
        "MEDIA3" -> "Media3"
        else -> engine
    }

    companion object {
        private val HTTP_CODE = Regex("""\b(401|403|408|429|500|502|503|504)\b""")
        private val SERVER_CODES = setOf("408", "429", "500", "502", "503", "504")
    }
}
