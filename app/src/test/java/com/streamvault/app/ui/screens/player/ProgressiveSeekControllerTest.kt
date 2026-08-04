package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressiveSeekControllerTest {

    @Test
    fun `tickStepMs returns 10s for elapsed under 2s`() {
        assertThat(tickStepMs(0L)).isEqualTo(10_000L)
        assertThat(tickStepMs(1_000L)).isEqualTo(10_000L)
        assertThat(tickStepMs(1_999L)).isEqualTo(10_000L)
    }

    @Test
    fun `tickStepMs returns 30s between 2s and 5s elapsed`() {
        assertThat(tickStepMs(2_000L)).isEqualTo(30_000L)
        assertThat(tickStepMs(3_500L)).isEqualTo(30_000L)
        assertThat(tickStepMs(4_999L)).isEqualTo(30_000L)
    }

    @Test
    fun `tickStepMs returns 2min between 5s and 10s elapsed`() {
        assertThat(tickStepMs(5_000L)).isEqualTo(120_000L)
        assertThat(tickStepMs(7_500L)).isEqualTo(120_000L)
        assertThat(tickStepMs(9_999L)).isEqualTo(120_000L)
    }

    @Test
    fun `tickStepMs returns 5min beyond 10s elapsed`() {
        assertThat(tickStepMs(10_000L)).isEqualTo(300_000L)
        assertThat(tickStepMs(60_000L)).isEqualTo(300_000L)
        assertThat(tickStepMs(Long.MAX_VALUE)).isEqualTo(300_000L)
    }

    @Test
    fun `start emits ticks at the configured interval with the right step`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)
        advanceTimeBy(180L)
        runCurrent()

        assertThat(received).hasSize(1)
        assertThat(received[0]).isEqualTo(SeekTick(SeekDirection.FORWARD, 10_000L))

        controller.stop()
        collectJob.cancel()
    }

    @Test
    fun `step accelerates with elapsed hold time`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)

        // Run for ~12 seconds total. We should observe at least one step
        // from each palier as elapsed hold time grows.
        advanceTimeBy(12_000L)
        runCurrent()

        val steps = received.map { it.stepMs }.toSet()
        assertThat(steps).containsAtLeast(10_000L, 30_000L, 120_000L, 300_000L)

        controller.stop()
        collectJob.cancel()
    }

    @Test
    fun `stop halts further ticks and resets isActive`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)
        advanceTimeBy(360L)
        runCurrent()

        val countBeforeStop = received.size
        assertThat(countBeforeStop).isAtLeast(1)
        assertThat(controller.isActive.value).isTrue()

        controller.stop()
        assertThat(controller.isActive.value).isFalse()

        advanceTimeBy(2_000L)
        runCurrent()

        assertThat(received.size).isEqualTo(countBeforeStop)

        collectJob.cancel()
    }

    @Test
    fun `second start while active is a no-op`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)
        advanceTimeBy(180L)
        runCurrent()
        val firstCount = received.size

        // Second start should not spin a second job.
        controller.start(SeekDirection.BACKWARD)
        advanceTimeBy(180L)
        runCurrent()

        // Only one new tick should have been emitted, still in the original direction.
        assertThat(received.size).isEqualTo(firstCount + 1)
        assertThat(received.last().direction).isEqualTo(SeekDirection.FORWARD)

        controller.stop()
        collectJob.cancel()
    }
}
