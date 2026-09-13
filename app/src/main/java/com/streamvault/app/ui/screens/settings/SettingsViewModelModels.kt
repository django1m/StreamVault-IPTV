package com.streamvault.app.ui.screens.settings

import android.app.Application
import com.streamvault.app.R
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.update.AppUpdateDownloadState
import com.streamvault.app.update.AppUpdateDownloadStatus
import com.streamvault.app.update.AppUpdateInfo
import com.streamvault.app.update.isAppUpdateNewer
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.VodSyncMode

enum class ProviderWarningAction {
    EPG,
    MOVIES,
    SERIES
}

enum class ProviderSyncSelection {
    ALL,
    FAST,
    TV,
    MOVIES,
    SERIES,
    EPG
}

internal data class SettingsPreferenceSnapshot(
    val providers: List<Provider>,
    val activeProviderId: Long?,
    val parentalControlLevel: Int,
    val hasParentalPin: Boolean,
    val appLanguage: String,
    val preferredAudioLanguage: String,
    val playerMediaSessionEnabled: Boolean,
    val playerDecoderMode: DecoderMode,
    val playerPlaybackSpeed: Float,
    val playerControlsTimeoutSeconds: Int,
    val playerLiveOverlayTimeoutSeconds: Int,
    val playerNoticeTimeoutSeconds: Int,
    val playerDiagnosticsTimeoutSeconds: Int,
    val subtitleTextScale: Float,
    val subtitleTextColor: Int,
    val subtitleBackgroundColor: Int,
    val wifiMaxVideoHeight: Int?,
    val ethernetMaxVideoHeight: Int?,
    val lastSpeedTestMegabits: Double?,
    val lastSpeedTestTimestamp: Long?,
    val lastSpeedTestTransport: String?,
    val lastSpeedTestRecommendedHeight: Int?,
    val lastSpeedTestEstimated: Boolean,
    val isIncognitoMode: Boolean,
    val useXtreamTextClassification: Boolean,
    val xtreamBase64TextCompatibility: Boolean,
    val liveTvChannelMode: LiveTvChannelMode,
    val showLiveSourceSwitcher: Boolean,
    val liveTvCategoryFilters: List<String>,
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode,
    val liveChannelNumberingMode: ChannelNumberingMode,
    val vodViewMode: VodViewMode,
    val guideDefaultCategoryId: Long,
    val guideDefaultCategoryOptions: List<Category>,
    val preventStandbyDuringPlayback: Boolean,
    val autoCheckAppUpdates: Boolean,
    val appUpdateBetaChannel: Boolean,
    val lastAppUpdateCheckAt: Long?,
    val cachedAppUpdateVersionName: String?,
    val cachedAppUpdateVersionCode: Int?,
    val cachedAppUpdateReleaseUrl: String?,
    val cachedAppUpdateDownloadUrl: String?,
    val cachedAppUpdateReleaseNotes: String,
    val cachedAppUpdatePublishedAt: String?
)

data class AppUpdateUiModel(
    val latestVersionName: String? = null,
    val latestVersionCode: Int? = null,
    val releaseUrl: String? = null,
    val downloadUrl: String? = null,
    val releaseNotes: String = "",
    val publishedAt: String? = null,
    val isUpdateAvailable: Boolean = false,
    val lastCheckedAt: Long? = null,
    val errorMessage: String? = null,
    val downloadStatus: AppUpdateDownloadStatus = AppUpdateDownloadStatus.Idle,
    val downloadedVersionName: String? = null
)

data class ProviderDiagnosticsUiModel(
    val lastSyncStatus: String = "NONE",
    val lastLiveSync: Long = 0L,
    val lastMovieSync: Long = 0L,
    val lastMovieAttempt: Long = 0L,
    val lastMovieSuccess: Long = 0L,
    val lastMoviePartial: Long = 0L,
    val lastSeriesSync: Long = 0L,
    val lastEpgSync: Long = 0L,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val epgCount: Int = 0,
    val movieSyncMode: VodSyncMode = VodSyncMode.UNKNOWN,
    val movieWarningsCount: Int = 0,
    val movieCatalogStale: Boolean = false,
    val capabilitySummary: String = "",
    val sourceLabel: String = "",
    val expirySummary: String = "",
    val connectionSummary: String = "",
    val archiveSummary: String = ""
)

data class SettingsUiState(
    val providers: List<Provider> = emptyList(),
    val combinedProfiles: List<CombinedM3uProfile> = emptyList(),
    val availableM3uProviders: List<Provider> = emptyList(),
    val activeProviderId: Long? = null,
    val activeLiveSource: ActiveLiveSource? = null,
    val isSyncing: Boolean = false,
    val syncProgress: String? = null,
    val syncingProviderName: String? = null,
    val userMessage: String? = null,
    val syncWarningsByProvider: Map<Long, List<String>> = emptyMap(),
    val diagnosticsByProvider: Map<Long, ProviderDiagnosticsUiModel> = emptyMap(),
    val parentalControlLevel: Int = 0,
    val hasParentalPin: Boolean = false,
    val appLanguage: String = "system",
    val preferredAudioLanguage: String = "auto",
    val playerMediaSessionEnabled: Boolean = true,
    val playerDecoderMode: DecoderMode = DecoderMode.AUTO,
    val playerPlaybackSpeed: Float = 1f,
    val playerControlsTimeoutSeconds: Int = 5,
    val playbackInactivityTimeoutSeconds: Int = 0, // 0 = disabled ("are you still watching?")
    val playerLiveOverlayTimeoutSeconds: Int = 4,
    val playerNoticeTimeoutSeconds: Int = 6,
    val playerDiagnosticsTimeoutSeconds: Int = 15,
    val subtitleTextScale: Float = 1f,
    val subtitleTextColor: Int = 0xFFFFFFFF.toInt(),
    val subtitleBackgroundColor: Int = 0x80000000.toInt(),
    val wifiMaxVideoHeight: Int? = null,
    val ethernetMaxVideoHeight: Int? = null,
    val lastSpeedTest: InternetSpeedTestUiModel? = null,
    val isRunningInternetSpeedTest: Boolean = false,
    val backupPreview: BackupPreview? = null,
    val pendingBackupUri: String? = null,
    val backupImportPlan: BackupImportPlan = BackupImportPlan(),
    val recordingItems: List<RecordingItem> = emptyList(),
    val recordingStorageState: RecordingStorageState = RecordingStorageState(),
    val isIncognitoMode: Boolean = false,
    val useXtreamTextClassification: Boolean = true,
    val xtreamBase64TextCompatibility: Boolean = false,
    val liveTvChannelMode: LiveTvChannelMode = LiveTvChannelMode.PRO,
    val showLiveSourceSwitcher: Boolean = false,
    val liveTvCategoryFilters: List<String> = emptyList(),
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
    val liveChannelNumberingMode: ChannelNumberingMode = ChannelNumberingMode.GROUP,
    val vodViewMode: VodViewMode = VodViewMode.CLASSIC,
    val guideDefaultCategoryId: Long = com.streamvault.domain.model.VirtualCategoryIds.FAVORITES,
    val guideDefaultCategoryOptions: List<Category> = emptyList(),
    val preventStandbyDuringPlayback: Boolean = true,
    val categorySortModes: Map<ContentType, CategorySortMode> = emptyMap(),
    val hiddenCategories: List<Category> = emptyList(),
    val epgSources: List<com.streamvault.domain.model.EpgSource> = emptyList(),
    val epgSourceAssignments: Map<Long, List<com.streamvault.domain.model.ProviderEpgSourceAssignment>> = emptyMap(),
    val epgResolutionSummaries: Map<Long, EpgResolutionSummary> = emptyMap(),
    val autoCheckAppUpdates: Boolean = true,
    val appUpdateBetaChannel: Boolean = false,
    val isCheckingForUpdates: Boolean = false,
    val appUpdate: AppUpdateUiModel = AppUpdateUiModel()
)

internal fun AppUpdateUiModel.toReleaseInfoOrNull(): AppUpdateInfo? {
    val versionName = latestVersionName ?: return null
    val releaseUrl = releaseUrl ?: return null
    return AppUpdateInfo(
        versionName = versionName,
        versionCode = latestVersionCode,
        releaseUrl = releaseUrl,
        downloadUrl = downloadUrl,
        releaseNotes = releaseNotes,
        publishedAt = publishedAt
    )
}

internal fun AppUpdateUiModel.withDownloadState(downloadState: AppUpdateDownloadState): AppUpdateUiModel {
    return copy(
        downloadStatus = downloadState.status,
        downloadedVersionName = downloadState.versionName
    )
}

internal fun AppUpdateUiModel.toDownloadState(): AppUpdateDownloadState {
    return AppUpdateDownloadState(
        status = downloadStatus,
        versionName = downloadedVersionName
    )
}

internal fun SettingsPreferenceSnapshot.toCachedAppUpdateUiModel(): AppUpdateUiModel {
    val versionName = cachedAppUpdateVersionName
    return AppUpdateUiModel(
        latestVersionName = versionName,
        latestVersionCode = cachedAppUpdateVersionCode,
        releaseUrl = cachedAppUpdateReleaseUrl,
        downloadUrl = cachedAppUpdateDownloadUrl,
        releaseNotes = cachedAppUpdateReleaseNotes,
        publishedAt = cachedAppUpdatePublishedAt,
        // Le versionCode fait autorité (isAppUpdateNewer) : le cache peut parfaitement
        // contenir une stable ANTÉRIEURE au build installé — c’est le cas normal sur
        // une bêta dont le canal n’est pas activé. Le nom reste requis : sans lui la
        // ligne « Dernière version » n’aurait rien à afficher.
        isUpdateAvailable = !versionName.isNullOrBlank() &&
            isAppUpdateNewer(cachedAppUpdateVersionCode, versionName),
        lastCheckedAt = lastAppUpdateCheckAt
    )
}

internal fun ProviderSyncSelection.label(application: Application): String = when (this) {
    ProviderSyncSelection.ALL -> application.getString(R.string.settings_sync_option_all)
    ProviderSyncSelection.FAST -> application.getString(R.string.settings_sync_option_fast)
    ProviderSyncSelection.TV -> application.getString(R.string.settings_sync_option_tv)
    ProviderSyncSelection.MOVIES -> application.getString(R.string.settings_sync_option_movies)
    ProviderSyncSelection.SERIES -> application.getString(R.string.settings_sync_option_series)
    ProviderSyncSelection.EPG -> application.getString(R.string.settings_sync_option_epg)
}

data class InternetSpeedTestUiModel(
    val megabitsPerSecond: Double,
    val measuredAtMs: Long,
    val transportLabel: String,
    val recommendedMaxVideoHeight: Int?,
    val isEstimated: Boolean
)

internal data class CategoryManagementSnapshot(
    val categorySortModes: Map<ContentType, CategorySortMode> = emptyMap(),
    val hiddenCategories: List<Category> = emptyList()
)
