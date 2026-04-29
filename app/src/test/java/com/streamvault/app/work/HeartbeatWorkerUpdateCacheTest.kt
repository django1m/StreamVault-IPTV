package com.streamvault.app.work

import com.streamvault.app.update.AppUpdateChecker
import com.streamvault.app.update.AppUpdateInfo
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatWorkerUpdateCacheTest {

    @Test
    fun `refreshAppUpdateCache writes cache on success`() = runTest {
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

        HeartbeatWorker.refreshAppUpdateCache(checker, prefs)

        verify(prefs).setCachedAppUpdateRelease(
            versionName = "4.2.0",
            versionCode = 480,
            releaseUrl = "https://example.test",
            downloadUrl = "https://example.test/apk",
            releaseNotes = "notes",
            publishedAt = null
        )
    }

    @Test
    fun `refreshAppUpdateCache silent on failure`() = runTest {
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.error("offline")
        }
        val prefs = mock<PreferencesRepository>()

        HeartbeatWorker.refreshAppUpdateCache(checker, prefs)

        verify(prefs, never()).setCachedAppUpdateRelease(
            any(), any(), any(), any(), any(), any()
        )
    }
}
