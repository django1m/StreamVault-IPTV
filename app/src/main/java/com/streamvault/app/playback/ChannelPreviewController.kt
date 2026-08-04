package com.streamvault.app.playback

import android.content.Context
import com.streamvault.app.R
import com.streamvault.app.di.AuxiliaryPlayerEngine
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Plays a live channel into a small pane, and hands the still-playing engine over to the
 * fullscreen player when the viewer confirms.
 *
 * Extracted from HomeViewModel so the search screen can offer the same
 * first-OK-previews / second-OK-goes-fullscreen gesture. It is deliberately *not* a
 * ViewModel and *not* a singleton: an unqualified `@Inject constructor` yields a fresh
 * instance per injection point, so Home and Search each own their preview. A shared one
 * would mean [clear] on one screen killing the other's playback.
 *
 * Three behaviours here look incidental and are not — each one is a bug that was fixed
 * once already:
 *
 *  1. **Session versioning.** A cancelled preview's `getStreamInfo` can still land after
 *     the user has moved on. Every result is checked against the version that requested
 *     it, otherwise a stale stream starts playing under a different channel's name.
 *  2. **An ERRORed engine is never parked.** [PlayerEngineHandoffRegistry] hands the
 *     engine to PlayerViewModel with `skipEnginePrepare`; the replayed error is then
 *     dropped as pre-session, no recovery ever runs, and the screen stays frozen. Better
 *     to return false and let fullscreen do a real, fresh attempt.
 *  3. **A single slot.** [PlayerEngineHandoffRegistry.park] releases whatever was parked
 *     before, so parking twice cannot leak an engine.
 */
class ChannelPreviewController @Inject constructor(
    @AuxiliaryPlayerEngine private val engineProvider: Provider<PlayerEngine>,
    private val channelRepository: ChannelRepository,
    private val handoffRegistry: PlayerEngineHandoffRegistry,
    @ApplicationContext private val appContext: Context
) {

    data class State(
        val channelId: Long? = null,
        val engine: PlayerEngine? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var playbackJob: Job? = null
    private var errorJob: Job? = null
    private var engine: PlayerEngine? = null
    private var streamInfo: StreamInfo? = null
    private var channelProviderId: Long = -1L
    private var sessionVersion: Long = 0L

    /** Call once from the owning ViewModel's `init`, passing its `viewModelScope`. */
    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    fun preview(channel: Channel) {
        val scope = scope ?: return
        if (_state.value.channelId == channel.id && _state.value.engine != null) return

        val version = ++sessionVersion
        val engine = this.engine ?: engineProvider.get().also { this.engine = it }
        playbackJob?.cancel()
        errorJob?.cancel()

        _state.update {
            it.copy(
                channelId = channel.id,
                engine = engine,
                isLoading = true,
                errorMessage = null
            )
        }

        playbackJob = scope.launch {
            engine.playbackState.collectLatest { playbackState ->
                if (!isActiveSession(version, channel.id)) return@collectLatest
                _state.update { state ->
                    state.copy(
                        isLoading = playbackState == PlaybackState.IDLE ||
                            playbackState == PlaybackState.BUFFERING,
                        errorMessage = when {
                            playbackState == PlaybackState.ERROR && state.errorMessage.isNullOrBlank() ->
                                appContext.getString(R.string.live_preview_failed)

                            playbackState != PlaybackState.ERROR -> null
                            else -> state.errorMessage
                        }
                    )
                }
            }
        }

        errorJob = scope.launch {
            engine.error.collectLatest { error ->
                if (!isActiveSession(version, channel.id)) return@collectLatest
                if (error != null) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message.ifBlank {
                                appContext.getString(R.string.live_preview_failed)
                            }
                        )
                    }
                }
            }
        }

        scope.launch {
            when (val result = channelRepository.getStreamInfo(channel)) {
                is Result.Success -> {
                    if (!isActiveSession(version, channel.id)) return@launch
                    streamInfo = result.data
                    channelProviderId = channel.providerId
                    engine.stop()
                    engine.prepare(result.data)
                    engine.setVolume(1f)
                    engine.play()
                }

                is Result.Error -> {
                    if (!isActiveSession(version, channel.id)) return@launch
                    _state.update { it.copy(isLoading = false, errorMessage = result.message) }
                }

                Result.Loading -> Unit
            }
        }
    }

    fun clear() {
        sessionVersion++
        playbackJob?.cancel()
        errorJob?.cancel()
        playbackJob = null
        errorJob = null
        engine?.stop()
        engine?.release()
        engine = null
        streamInfo = null
        channelProviderId = -1L
        _state.value = State()
    }

    /**
     * Parks the live engine for the fullscreen player to adopt. Returns false — and falls
     * back to a plain [clear] — when there is nothing useful to hand over: no engine, no
     * resolved stream, a different channel, or an engine already in ERROR (see point 2 in
     * the class docs).
     *
     * Must be called in the same synchronous block as the navigation: the registry's
     * hand-off expires after 3 s.
     */
    fun handoffForFullscreen(channel: Channel): Boolean {
        val engine = this.engine
        val streamInfo = this.streamInfo
        if (engine == null ||
            streamInfo == null ||
            _state.value.channelId != channel.id ||
            engine.playbackState.value == PlaybackState.ERROR
        ) {
            clear()
            return false
        }
        sessionVersion++
        playbackJob?.cancel()
        errorJob?.cancel()
        playbackJob = null
        errorJob = null
        handoffRegistry.park(
            engine = engine,
            channelId = channel.id,
            providerId = channel.providerId.takeIf { it > 0L } ?: channelProviderId,
            streamInfo = streamInfo
        )
        this.engine = null
        this.streamInfo = null
        channelProviderId = -1L
        _state.value = State()
        return true
    }

    private fun isActiveSession(version: Long, channelId: Long): Boolean =
        version == sessionVersion && _state.value.channelId == channelId
}
