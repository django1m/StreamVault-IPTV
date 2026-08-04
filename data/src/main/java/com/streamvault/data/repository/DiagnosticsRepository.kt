package com.streamvault.data.repository

import com.streamvault.data.local.dao.DiagnosticDao
import com.streamvault.data.local.entity.PendingDiagnosticEntity
import com.streamvault.domain.model.DiagnosticDraft
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local persistence for the diagnostics upload queue. Enqueue is best-effort
 * and capped ([MAX_PENDING]); the oldest rows are dropped past the cap so a
 * permanently-offline box never grows the DB without bound.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    private val dao: DiagnosticDao,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun enqueue(draft: DiagnosticDraft) {
        dao.insert(
            PendingDiagnosticEntity(
                severity = draft.severity.wireName,
                category = draft.category.wireName,
                message = draft.message,
                contextJson = draft.context.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) },
                occurredAtMs = draft.occurredAtMs,
            )
        )
        dao.trimTo(MAX_PENDING)
    }

    suspend fun pending(limit: Int): List<PendingDiagnosticEntity> = dao.pending(limit)

    suspend fun delete(ids: List<Long>) = dao.deleteByIds(ids)

    /** Parse a stored entity's context back into a map, or null if absent/corrupt. */
    fun contextOf(entity: PendingDiagnosticEntity): Map<String, String>? =
        entity.contextJson?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    companion object {
        const val MAX_PENDING = 200
    }
}
