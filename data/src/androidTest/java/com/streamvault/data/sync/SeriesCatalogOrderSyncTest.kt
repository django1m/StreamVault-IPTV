package com.streamvault.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sur une vraie base : le rang du fournisseur traverse la table de staging des séries, et une série
 * qui a seulement changé de place chez le fournisseur est bien réécrite.
 */
@RunWith(AndroidJUnit4::class)
class SeriesCatalogOrderSyncTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var store: SyncCatalogStore

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        val transactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
        store = SyncCatalogStore(db.movieDao(), db.catalogSyncDao(), transactionRunner)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun resyncRewritesSeriesThatOnlyMovedInTheProviderList() = runTest {
        db.providerDao().insert(
            ProviderEntity(id = 1L, name = "Xtream", type = ProviderType.XTREAM_CODES, serverUrl = "http://example.com")
        )

        store.replaceSeriesCatalog(
            providerId = 1L,
            categories = null,
            series = sequenceOf(series(1L, "Alpha", providerOrder = 1), series(2L, "Bravo", providerOrder = 2))
        )
        store.replaceSeriesCatalog(
            providerId = 1L,
            categories = null,
            series = sequenceOf(series(2L, "Bravo", providerOrder = 1), series(1L, "Alpha", providerOrder = 2))
        )

        assertThat(db.seriesDao().getByCategory(1L, 10L).first().map { it.name to it.providerOrder })
            .containsExactly("Bravo" to 1, "Alpha" to 2)
            .inOrder()
    }

    private fun series(seriesId: Long, name: String, providerOrder: Int) = SeriesEntity(
        seriesId = seriesId,
        name = name,
        categoryId = 10L,
        providerId = 1L,
        providerOrder = providerOrder
    )
}
