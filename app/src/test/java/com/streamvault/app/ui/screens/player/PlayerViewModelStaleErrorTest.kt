package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.cast.CastManager
import com.streamvault.app.diagnostics.DiagnosticReporter
import com.streamvault.app.playback.PlayerEngineHandoffRegistry
import com.streamvault.app.playback.PlaybackReporter
import com.streamvault.app.playback.XtreamContainerExtensionMemory
import com.streamvault.app.playback.XtreamPlaybackProber
import com.streamvault.app.util.NetworkAvailability
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.MarkAsWatched
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Regression test for the prod v517 crash: the singleton engines' error
 * SharedFlow (replay = 1) replayed the PREVIOUS session's failure into a
 * freshly constructed PlayerViewModel, which handled it before its own
 * fields were initialized (NPE) and, at best, showed a phantom notice for a
 * playback this ViewModel never started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelStaleErrorTest {

    private val dispatcher = StandardTestDispatcher()

    private val engineError = MutableSharedFlow<PlayerError?>(replay = 1)
    private val playerEngine: PlayerEngine = mock()
    private val diagnosticReporter: DiagnosticReporter = mock()
    private val handoffRegistry: PlayerEngineHandoffRegistry = mock()
    private val castManager: CastManager = mock()
    private val recordingManager: RecordingManager = mock()
    private val preferencesRepository: PreferencesRepository = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)

        whenever(playerEngine.error).thenReturn(engineError)
        whenever(playerEngine.playbackState).thenReturn(MutableStateFlow(PlaybackState.IDLE))
        whenever(playerEngine.retryStatus).thenReturn(MutableStateFlow(null))
        whenever(playerEngine.isPlaying).thenReturn(MutableStateFlow(false))
        whenever(playerEngine.currentPosition).thenReturn(MutableStateFlow(0L))
        whenever(playerEngine.duration).thenReturn(MutableStateFlow(0L))
        whenever(playerEngine.videoFormat).thenReturn(MutableStateFlow(VideoFormat(0, 0)))
        whenever(playerEngine.playerStats).thenReturn(MutableStateFlow(PlayerStats()))
        whenever(playerEngine.availableAudioTracks).thenReturn(MutableStateFlow(emptyList()))
        whenever(playerEngine.availableSubtitleTracks).thenReturn(MutableStateFlow(emptyList()))
        whenever(playerEngine.availableVideoTracks).thenReturn(MutableStateFlow(emptyList()))
        whenever(playerEngine.playbackSpeed).thenReturn(MutableStateFlow(1f))
        whenever(playerEngine.mediaTitle).thenReturn(MutableStateFlow(null))
        whenever(playerEngine.isMuted).thenReturn(MutableStateFlow(false))

        whenever(handoffRegistry.consume()).thenReturn(null)
        whenever(castManager.connectionState)
            .thenReturn(MutableStateFlow(CastConnectionState.UNAVAILABLE))
        whenever(recordingManager.observeRecordingItems()).thenReturn(flowOf(emptyList()))

        whenever(preferencesRepository.playerSubtitleTextScale).thenReturn(flowOf(1f))
        whenever(preferencesRepository.playerSubtitleTextColor).thenReturn(flowOf(0))
        whenever(preferencesRepository.playerSubtitleBackgroundColor).thenReturn(flowOf(0))
        whenever(preferencesRepository.playerControlsTimeoutSeconds).thenReturn(flowOf(5))
        whenever(preferencesRepository.playerLiveOverlayTimeoutSeconds).thenReturn(flowOf(4))
        whenever(preferencesRepository.playerNoticeTimeoutSeconds).thenReturn(flowOf(6))
        whenever(preferencesRepository.playerDiagnosticsTimeoutSeconds).thenReturn(flowOf(15))
        whenever(preferencesRepository.playbackInactivityTimeoutSeconds).thenReturn(flowOf(0))
        whenever(preferencesRepository.playerMediaSessionEnabled).thenReturn(flowOf(false))
        whenever(preferencesRepository.playerDecoderMode).thenReturn(flowOf(DecoderMode.AUTO))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): PlayerViewModel = PlayerViewModel(
        injectedPlayerEngine = playerEngine,
        playerEngineHandoffRegistry = handoffRegistry,
        epgRepository = mock<EpgRepository>(),
        channelRepository = mock<ChannelRepository>(),
        movieRepository = mock<MovieRepository>(),
        seriesRepository = mock<SeriesRepository>(),
        favoriteRepository = mock(),
        playbackHistoryRepository = mock<PlaybackHistoryRepository>(),
        providerRepository = mock(),
        preferencesRepository = preferencesRepository,
        getCustomCategories = mock<GetCustomCategories>(),
        markAsWatched = mock<MarkAsWatched>(),
        scheduleRecordingUseCase = mock<ScheduleRecording>(),
        recordingManager = recordingManager,
        watchNextManager = mock(),
        launcherRecommendationsManager = mock(),
        castManager = castManager,
        xtreamStreamUrlResolver = mock<XtreamStreamUrlResolver>(),
        seekThumbnailProvider = mock(),
        playbackHttpClient = OkHttpClient(),
        xtreamPlaybackProber = XtreamPlaybackProber(OkHttpClient()),
        extensionMemory = XtreamContainerExtensionMemory(),
        playbackReporter = mock<PlaybackReporter>(),
        networkAvailability = mock<NetworkAvailability>(),
        diagnosticReporter = diagnosticReporter,
        vpnManager = mock(),
        applicationScope = CoroutineScope(SupervisorJob() + dispatcher),
    )

    @Test
    fun `replayed error from a previous session is ignored at construction`() =
        runTest(dispatcher.scheduler) {
            // The singleton engine still replays the previous screen's failure.
            engineError.tryEmit(PlayerError.SourceError("stale failure from previous session"))

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertThat(viewModel.playerNotice.value).isNull()
            verifyNoInteractions(diagnosticReporter)
            verify(playerEngine, org.mockito.kotlin.never()).prepare(org.mockito.kotlin.any())
            verify(playerEngine, org.mockito.kotlin.never()).play()
        }

    @Test
    fun `legitimate error after a session started is still handled`() =
        runTest(dispatcher.scheduler) {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.beginPlaybackSession()
            engineError.tryEmit(PlayerError.SourceError("real failure"))
            // runCurrent, not advanceUntilIdle: the notice auto-hides after a
            // virtual-time delay that advanceUntilIdle would fast-forward.
            dispatcher.scheduler.runCurrent()

            // Terminal path for non-live content without a channel: a notice
            // must be shown and the failure reported.
            assertThat(viewModel.playerNotice.value).isNotNull()
            verify(diagnosticReporter).reportPlaybackFailure(
                error = org.mockito.kotlin.any(),
                contentType = org.mockito.kotlin.any(),
                title = org.mockito.kotlin.any(),
                engine = org.mockito.kotlin.any(),
            )
        }
}
