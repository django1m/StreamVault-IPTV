package com.streamvault.app.ui.screens.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.BuildConfig
import com.streamvault.app.update.AppUpdateDownloadState
import com.streamvault.app.update.AppUpdateDownloadStatus
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.AppUpdateInfo
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingAppUpdate(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val installReady: Boolean
)

@HiltViewModel
class AppUpdateStartupViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val appUpdateInstaller: AppUpdateInstaller
) : ViewModel() {

    private val suppressedThisSession = MutableStateFlow(false)

    private data class UpdateSnapshot(
        val versionName: String?,
        val versionCode: Int?,
        val releaseNotes: String,
        val downloadedVersionName: String?
    )

    private val updateSnapshot: Flow<UpdateSnapshot> = combine(
        preferencesRepository.cachedAppUpdateVersionName,
        preferencesRepository.cachedAppUpdateVersionCode,
        preferencesRepository.cachedAppUpdateReleaseNotes,
        preferencesRepository.downloadedAppUpdateVersionName
    ) { versionName, versionCode, releaseNotes, downloadedVersionName ->
        UpdateSnapshot(versionName, versionCode, releaseNotes, downloadedVersionName)
    }

    val pendingUpdate: StateFlow<PendingAppUpdate?> = combine(
        updateSnapshot,
        preferencesRepository.dismissedUpdateVersionCode,
        suppressedThisSession
    ) { snapshot, dismissedVersionCode, suppressed ->
        if (suppressed) return@combine null
        val latestVersionName = snapshot.versionName
        val latestVersionCode = snapshot.versionCode
        if (latestVersionName.isNullOrBlank() || latestVersionCode == null) return@combine null
        if (latestVersionCode <= BuildConfig.VERSION_CODE) return@combine null
        if (dismissedVersionCode != null && dismissedVersionCode >= latestVersionCode) return@combine null

        PendingAppUpdate(
            versionName = latestVersionName,
            versionCode = latestVersionCode,
            releaseNotes = snapshot.releaseNotes,
            installReady = snapshot.downloadedVersionName == latestVersionName
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val downloadState: StateFlow<AppUpdateDownloadState> = appUpdateInstaller.downloadState

    fun dismissForThisVersion(versionCode: Int) {
        suppressedThisSession.value = true
        viewModelScope.launch {
            preferencesRepository.setDismissedUpdateVersionCode(versionCode)
        }
    }

    fun install() {
        viewModelScope.launch {
            val state = appUpdateInstaller.refreshState()
            if (state.status == AppUpdateDownloadStatus.Downloaded) {
                suppressedThisSession.value = true
                appUpdateInstaller.installDownloadedUpdate()
                return@launch
            }
            val versionName = preferencesRepository.cachedAppUpdateVersionName.first() ?: return@launch
            val info = AppUpdateInfo(
                versionName = versionName,
                versionCode = preferencesRepository.cachedAppUpdateVersionCode.first(),
                releaseUrl = preferencesRepository.cachedAppUpdateReleaseUrl.first().orEmpty(),
                downloadUrl = preferencesRepository.cachedAppUpdateDownloadUrl.first(),
                releaseNotes = preferencesRepository.cachedAppUpdateReleaseNotes.first(),
                publishedAt = null
            )
            if (appUpdateInstaller.startDownload(info) is Result.Error) return@launch
            val finalState = appUpdateInstaller.downloadState.first {
                it.status == AppUpdateDownloadStatus.Downloaded ||
                    it.status == AppUpdateDownloadStatus.Failed
            }
            if (finalState.status == AppUpdateDownloadStatus.Downloaded) {
                suppressedThisSession.value = true
                appUpdateInstaller.installDownloadedUpdate()
            }
            // Si Failed: le dialog reste affiché avec l'état d'erreur, l'utilisateur
            // peut réessayer via le bouton Installer ou fermer via Plus tard.
        }
    }
}
