package com.streamvault.app.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SeekDirection { FORWARD, BACKWARD }

data class SeekTick(val direction: SeekDirection, val stepMs: Long)

internal fun tickStepMs(elapsedMs: Long): Long = when {
    elapsedMs < 2_000L  -> 10_000L
    elapsedMs < 5_000L  -> 30_000L
    elapsedMs < 10_000L -> 120_000L
    else                -> 300_000L
}

class ProgressiveSeekController(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
) {
    private val _ticks = MutableSharedFlow<SeekTick>(extraBufferCapacity = 16)
    val ticks: SharedFlow<SeekTick> = _ticks.asSharedFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var job: Job? = null

    fun start(direction: SeekDirection) {
        if (_isActive.value) return
        val startedAt = clock()
        _isActive.value = true
        job = scope.launch {
            while (true) {
                delay(tickIntervalMs)
                val elapsed = clock() - startedAt
                _ticks.tryEmit(SeekTick(direction, tickStepMs(elapsed)))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _isActive.value = false
    }

    companion object {
        const val DEFAULT_TICK_INTERVAL_MS: Long = 180L
    }
}
