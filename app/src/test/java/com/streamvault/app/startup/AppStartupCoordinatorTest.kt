package com.streamvault.app.startup

import com.streamvault.app.update.AppUpdateChecker
import com.streamvault.app.update.AppUpdateInfo
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupCoordinatorTest {

    private val now = 1_700_000_000_000L
    private val fortyEightHoursMs = 48L * 60 * 60 * 1000L

    private fun newCoordinator(
        scope: TestScope,
        appUpdateChecker: AppUpdateChecker = mock(),
        preferencesRepository: PreferencesRepository = mock(),
        providerRepository: ProviderRepository = mock(),
        syncManager: SyncManager = mock(),
        snackbar: SyncSnackbarController = SyncSnackbarController(),
        clock: () -> Long = { now }
    ) = AppStartupCoordinator(
        appUpdateChecker = appUpdateChecker,
        preferencesRepository = preferencesRepository,
        providerRepository = providerRepository,
        syncManager = syncManager,
        snackbar = snackbar,
        applicationScope = scope,
        clock = clock
    )

    private fun provider(lastSyncedAt: Long) = Provider(
        id = 1L,
        name = "FuzionTV",
        type = ProviderType.XTREAM_CODES,
        serverUrl = "https://example.test",
        username = "u",
        password = "p",
        lastSyncedAt = lastSyncedAt
    )

    @Test
    fun `onStart triggers update check and sync when stale`() = runTest(StandardTestDispatcher()) {
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.success(
                AppUpdateInfo(
                    versionName = "4.2.0",
                    versionCode = 480,
                    releaseUrl = "https://example.test",
                    downloadUrl = "https://example.test/apk",
                    releaseNotes = "notes",
                    publishedAt = null
                )
            )
        }
        val prefs = mock<PreferencesRepository>()
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - fortyEightHoursMs - 1))
        }
        val sync = mock<SyncManager> {
            on { isAnySyncActive() } doReturn false
            onBlocking {
                sync(eq(1L), eq(false), anyOrNull(), eq(ProviderEpgSyncMode.BACKGROUND), anyOrNull())
            } doReturn Result.success(Unit)
        }

        val coord = newCoordinator(this, checker, prefs, provRepo, sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(checker).fetchLatestRelease()
        verify(prefs).setCachedAppUpdateRelease(
            versionName = "4.2.0",
            versionCode = 480,
            releaseUrl = "https://example.test",
            downloadUrl = "https://example.test/apk",
            releaseNotes = "notes",
            publishedAt = null
        )
        verify(sync).sync(
            providerId = eq(1L),
            force = eq(false),
            movieFastSyncOverride = anyOrNull(),
            epgSyncModeOverride = eq(ProviderEpgSyncMode.BACKGROUND),
            onProgress = anyOrNull()
        )
    }

    @Test
    fun `onStart skips sync when lastSyncedAt is fresh`() = runTest(StandardTestDispatcher()) {
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.error("offline")
        }
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - 1000L))
        }
        val sync = mock<SyncManager> { on { isAnySyncActive() } doReturn false }

        val coord = newCoordinator(this, checker, mock(), provRepo, sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(sync, never()).sync(any(), any(), any(), any(), any())
    }

    @Test
    fun `onStart skips sync when no active provider`() = runTest(StandardTestDispatcher()) {
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(null)
        }
        val sync = mock<SyncManager>()

        val coord = newCoordinator(this, providerRepository = provRepo, syncManager = sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(sync, never()).sync(any(), any(), any(), any(), any())
    }

    @Test
    fun `onStart skips sync when sync already active`() = runTest(StandardTestDispatcher()) {
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - fortyEightHoursMs - 1))
        }
        val sync = mock<SyncManager> { on { isAnySyncActive() } doReturn true }

        val coord = newCoordinator(this, providerRepository = provRepo, syncManager = sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(sync, never()).sync(any(), any(), any(), any(), any())
    }

    @Test
    fun `onStart applies 30s throttle`() = runTest(StandardTestDispatcher()) {
        var clockNow = now
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.error("ignore")
        }
        val coord = newCoordinator(
            scope = this,
            appUpdateChecker = checker,
            clock = { clockNow }
        )

        coord.onAppStart()
        advanceUntilIdle()

        clockNow += 10_000L
        coord.onAppStart()
        advanceUntilIdle()

        // Une seule exécution car le second onAppStart est dans la fenêtre des 30s.
        verify(checker, times(1)).fetchLatestRelease()

        clockNow += 25_000L
        coord.onAppStart()
        advanceUntilIdle()
        verify(checker, times(2)).fetchLatestRelease()
    }

    @Test
    fun `onStart emits Started then Success snackbar events`() = runTest(StandardTestDispatcher()) {
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - fortyEightHoursMs - 1))
        }
        val sync = mock<SyncManager> {
            on { isAnySyncActive() } doReturn false
            onBlocking {
                sync(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
            } doReturn Result.success(Unit)
        }
        val snackbar = SyncSnackbarController()
        val collected = mutableListOf<SyncSnackbarEvent>()
        val collectJob = launch {
            snackbar.events.toList(collected)
        }

        val coord = newCoordinator(
            scope = this,
            providerRepository = provRepo,
            syncManager = sync,
            snackbar = snackbar
        )
        coord.onAppStart()
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals(SyncSnackbarEvent.SyncStarted, collected.first())
        assertEquals(SyncSnackbarEvent.SyncSuccess, collected.last())
    }
}
