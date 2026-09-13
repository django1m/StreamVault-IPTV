package com.streamvault.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sur une vraie base : le rang et la date d'ajout du fournisseur traversent la table de staging,
 * et un film qui a seulement changé de place chez le fournisseur est bien réécrit.
 */
@RunWith(AndroidJUnit4::class)
class MovieCatalogOrderSyncTest {
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
    fun resyncRewritesMoviesThatOnlyMovedInTheProviderList() = runTest {
        db.providerDao().insert(
            ProviderEntity(id = 1L, name = "Xtream", type = ProviderType.XTREAM_CODES, serverUrl = "http://example.com")
        )

        store.replaceMovieCatalog(
            providerId = 1L,
            categories = null,
            movies = sequenceOf(
                movie(1L, "Alpha", providerOrder = 1, addedAt = 1_000L),
                movie(2L, "Bravo", providerOrder = 2, addedAt = 2_000L)
            )
        )
        store.replaceMovieCatalog(
            providerId = 1L,
            categories = null,
            movies = sequenceOf(
                movie(2L, "Bravo", providerOrder = 1, addedAt = 2_000L),
                movie(1L, "Alpha", providerOrder = 2, addedAt = 1_000L)
            )
        )

        val stored = db.movieDao().getByCategory(1L, 10L).first()
        assertThat(stored.map { it.name to it.providerOrder })
            .containsExactly("Bravo" to 1, "Alpha" to 2)
            .inOrder()
        assertThat(stored.map { it.providerAddedAt }).containsExactly(2_000L, 1_000L).inOrder()
    }

    private fun movie(streamId: Long, name: String, providerOrder: Int, addedAt: Long) = MovieEntity(
        streamId = streamId,
        name = name,
        categoryId = 10L,
        providerId = 1L,
        providerOrder = providerOrder,
        providerAddedAt = addedAt
    )
}
