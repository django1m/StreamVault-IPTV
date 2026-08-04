package com.streamvault.app.playback

import android.content.Context
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Guards the three behaviours in [ChannelPreviewController] that each exist because of a
 * bug that already shipped once: stale-session results, parking an ERRORed engine, and
 * releasing the engine on clear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelPreviewControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val channelRepository: ChannelRepository = mock()
    private val context: Context = mock()
    private val registry = PlayerEngineHandoffRegistry()

    private val playbackState = MutableStateFlow(PlaybackState.IDLE)
    private val engine: PlayerEngine = mock()

    private val channel = Channel(id = 7L, name = "World Sports HD", providerId = 3L)
    private val streamInfo = StreamInfo(url = "http://example.test/live/7.ts")

    private fun controller() = ChannelPreviewController(
        engineProvider = Provider { engine },
        channelRepository = channelRepository,
        handoffRegistry = registry,
        appContext = context
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(engine.playbackState).thenReturn(playbackState)
        whenever(engine.error).thenReturn(emptyFlow())
        whenever(context.getString(any())).thenReturn("preview failed")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `preview prepares and plays the resolved stream`() = runTest(dispatcher) {
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        val controller = controller().apply { attach(TestScope(dispatcher)) }

        controller.preview(channel)
        advanceUntilIdle()

        inOrder(engine) {
            verify(engine).stop()
            verify(engine).prepare(streamInfo)
            verify(engine).setVolume(1f)
            verify(engine).play()
        }
        assertEquals(channel.id, controller.state.value.channelId)
    }

    @Test
    fun `previewing the same channel twice prepares once`() = runTest(dispatcher) {
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        val controller = controller().apply { attach(TestScope(dispatcher)) }

        controller.preview(channel)
        advanceUntilIdle()
        controller.preview(channel)
        advanceUntilIdle()

        verify(engine).prepare(streamInfo)
    }

    @Test
    fun `a stream resolved after clear is ignored`() = runTest(dispatcher) {
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        val controller = controller().apply { attach(TestScope(dispatcher)) }

        controller.preview(channel)
        // clear() before the coroutine that resolves the stream gets to run
        controller.clear()
        advanceUntilIdle()

        verify(engine, never()).prepare(any())
        assertNull(controller.state.value.channelId)
    }

    @Test
    fun `handoff parks the live engine and clears the state`() = runTest(dispatcher) {
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        val controller = controller().apply { attach(TestScope(dispatcher)) }
        controller.preview(channel)
        advanceUntilIdle()

        val handedOff = controller.handoffForFullscreen(channel)

        assertTrue(handedOff)
        val parked = registry.consume()
        assertNotNull(parked)
        assertEquals(channel.id, parked!!.channelId)
        assertEquals(channel.providerId, parked.providerId)
        assertEquals(streamInfo, parked.streamInfo)
        // The engine is handed over alive — never released on this path.
        verify(engine, never()).release()
        assertNull(controller.state.value.channelId)
    }

    @Test
    fun `an errored engine is never parked`() = runTest(dispatcher) {
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        val controller = controller().apply { attach(TestScope(dispatcher)) }
        controller.preview(channel)
        advanceUntilIdle()
        playbackState.value = PlaybackState.ERROR

        val handedOff = controller.handoffForFullscreen(channel)

        // Parking it would let PlayerViewModel adopt it with skipEnginePrepare; the
        // replayed error is dropped as pre-session and no recovery ever runs.
        assertFalse(handedOff)
        assertNull(registry.consume())
        verify(engine).release()
    }

    @Test
    fun `clear stops then releases the engine`() = runTest(dispatcher) {
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        val controller = controller().apply { attach(TestScope(dispatcher)) }
        controller.preview(channel)
        advanceUntilIdle()

        controller.clear()

        inOrder(engine) {
            verify(engine).stop()
            verify(engine).release()
        }
        assertNull(controller.state.value.engine)
    }
}
