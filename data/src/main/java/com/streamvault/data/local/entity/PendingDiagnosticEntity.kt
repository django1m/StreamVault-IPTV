package com.streamvault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A diagnostic awaiting best-effort upload to the admin backend. Stored in the
 * dedicated DiagnosticsDatabase (NOT the user-data StreamVaultDatabase) so the
 * incident queue can never threaten catalogue/history/favorites.
 *
 * `contextJson` is the serialized context map (or null). App version is stamped
 * at upload time from BuildConfig, so it is not persisted here.
 */
@Entity(tableName = "pending_diagnostics")
data class PendingDiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val severity: String,
    val category: String,
    val message: String,
    val contextJson: String?,
    val occurredAtMs: Long,
)
