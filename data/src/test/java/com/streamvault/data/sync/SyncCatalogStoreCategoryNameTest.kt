package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.StoredCategoryIdentity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Regression test for the bug where a failed `get_*_categories` request mid-sync replaced real
 * category names already stored in the DB with synthetic "Category <id>" placeholders, so the
 * user saw raw numeric ids instead of real labels.
 *
 * [SyncCatalogStore.stageCategories] is the single chokepoint all category persistence flows
 * through, so guarding it there covers live / VOD / series and every staged-import path.
 */
class SyncCatalogStoreCategoryNameTest {
    private val catalogSyncDao: CatalogSyncDao = mock()
    private val movieDao: MovieDao = mock()
    private val transactionRunner: DatabaseTransactionRunner = mock()

    private lateinit var store: SyncCatalogStore

    private val providerId = 7L
    private val sessionId = 99L

    @Before
    fun setUp() {
        store = SyncCatalogStore(movieDao, catalogSyncDao, transactionRunner)
    }

    private fun category(categoryId: Long, name: String, type: ContentType = ContentType.LIVE) =
        CategoryEntity(
            categoryId = categoryId,
            name = name,
            type = type,
            providerId = providerId
        )

    @Test
    fun `placeholder name does not overwrite stored real name`() = runTest {
        whenever(catalogSyncDao.storedCategoryIdentities(eq(providerId), eq("LIVE")))
            .thenReturn(
                listOf(
                    StoredCategoryIdentity(
                        categoryId = 513L,
                        name = "Sports FR",
                        parentId = null,
                        isAdult = false
                    )
                )
            )
        val captor = argumentCaptor<List<CategoryImportStageEntity>>()

        store.stageCategories(
            providerId,
            sessionId,
            listOf(category(513L, "Category 513"))
        )

        org.mockito.kotlin.verify(catalogSyncDao).insertCategoryStages(captor.capture())
        val staged = captor.allValues.flatten()
        assertThat(staged.single().categoryId).isEqualTo(513L)
        assertThat(staged.single().name).isEqualTo("Sports FR")
    }

    @Test
    fun `placeholder with no stored real name is kept as-is`() = runTest {
        whenever(catalogSyncDao.storedCategoryIdentities(eq(providerId), eq("LIVE")))
            .thenReturn(emptyList())
        val captor = argumentCaptor<List<CategoryImportStageEntity>>()

        store.stageCategories(
            providerId,
            sessionId,
            listOf(category(423L, "Category 423"))
        )

        org.mockito.kotlin.verify(catalogSyncDao).insertCategoryStages(captor.capture())
        val staged = captor.allValues.flatten()
        assertThat(staged.single().name).isEqualTo("Category 423")
    }

    @Test
    fun `real names skip the DB lookup and pass through unchanged`() = runTest {
        val captor = argumentCaptor<List<CategoryImportStageEntity>>()

        store.stageCategories(
            providerId,
            sessionId,
            listOf(category(1L, "Sports FR"), category(2L, "News"))
        )

        // No placeholder present => no stored-name lookup at all.
        org.mockito.kotlin.verify(catalogSyncDao, org.mockito.kotlin.never())
            .storedCategoryIdentities(any(), any())
        org.mockito.kotlin.verify(catalogSyncDao).insertCategoryStages(captor.capture())
        val staged = captor.allValues.flatten().sortedBy { it.categoryId }
        assertThat(staged.map { it.name }).containsExactly("Sports FR", "News")
    }

    @Test
    fun `real incoming name still overrides a stored placeholder (recovery)`() = runTest {
        whenever(catalogSyncDao.storedCategoryIdentities(eq(providerId), eq("LIVE")))
            .thenReturn(
                listOf(
                    StoredCategoryIdentity(
                        categoryId = 513L,
                        name = "Category 513",
                        parentId = null,
                        isAdult = false
                    )
                )
            )
        val captor = argumentCaptor<List<CategoryImportStageEntity>>()

        // 600 is a placeholder (triggers lookup); 513 recovers its real name this round.
        store.stageCategories(
            providerId,
            sessionId,
            listOf(category(513L, "Sports FR"), category(600L, "Category 600"))
        )

        org.mockito.kotlin.verify(catalogSyncDao).insertCategoryStages(captor.capture())
        val staged = captor.allValues.flatten().associateBy { it.categoryId }
        assertThat(staged.getValue(513L).name).isEqualTo("Sports FR")
        assertThat(staged.getValue(600L).name).isEqualTo("Category 600")
    }
}
