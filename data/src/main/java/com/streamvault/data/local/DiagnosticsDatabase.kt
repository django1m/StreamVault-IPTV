package com.streamvault.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.streamvault.data.local.dao.DiagnosticDao
import com.streamvault.data.local.entity.PendingDiagnosticEntity

/**
 * Tiny, isolated database for the device diagnostics upload queue.
 *
 * Deliberately SEPARATE from [StreamVaultDatabase] (v31, 30 explicit
 * migrations, user catalogue/history/favorites). Two Room databases have
 * independent SQLite connections and cannot deadlock with each other, so this
 * keeps the incident queue from ever touching — or risking — user data.
 */
@Database(
    entities = [PendingDiagnosticEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun diagnosticDao(): DiagnosticDao
}
