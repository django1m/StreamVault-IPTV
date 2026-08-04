package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.streamvault.data.local.entity.PendingDiagnosticEntity

@Dao
interface DiagnosticDao {

    @Insert
    suspend fun insert(entity: PendingDiagnosticEntity): Long

    @Query("SELECT * FROM pending_diagnostics ORDER BY id ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<PendingDiagnosticEntity>

    @Query("DELETE FROM pending_diagnostics WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_diagnostics")
    suspend fun count(): Int

    /** Keep only the newest [cap] rows; drop the oldest beyond that. */
    @Query(
        "DELETE FROM pending_diagnostics WHERE id NOT IN " +
            "(SELECT id FROM pending_diagnostics ORDER BY id DESC LIMIT :cap)"
    )
    suspend fun trimTo(cap: Int)
}
