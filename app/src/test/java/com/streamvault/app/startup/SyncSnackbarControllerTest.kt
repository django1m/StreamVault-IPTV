package com.streamvault.app.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSnackbarControllerTest {

    @Test
    fun `emits Started Success in order`() = runTest(UnconfinedTestDispatcher()) {
        val controller = SyncSnackbarController()
        val collected = mutableListOf<SyncSnackbarEvent>()
        val job = launch {
            controller.events.toList(collected)
        }
        controller.emit(SyncSnackbarEvent.SyncStarted)
        controller.emit(SyncSnackbarEvent.SyncSuccess)
        job.cancel()

        assertEquals(
            listOf(SyncSnackbarEvent.SyncStarted, SyncSnackbarEvent.SyncSuccess),
            collected
        )
    }

    @Test
    fun `emits Failed with messageRes`() = runTest(UnconfinedTestDispatcher()) {
        val controller = SyncSnackbarController()
        val collected = mutableListOf<SyncSnackbarEvent>()
        val job = launch {
            controller.events.toList(collected)
        }
        controller.emit(SyncSnackbarEvent.SyncFailed(messageRes = 12345))
        job.cancel()

        assertEquals(
            listOf(SyncSnackbarEvent.SyncFailed(messageRes = 12345)),
            collected
        )
    }

    @Test
    fun `late subscriber does not receive prior events`() = runTest(UnconfinedTestDispatcher()) {
        val controller = SyncSnackbarController()
        controller.emit(SyncSnackbarEvent.SyncStarted)

        val collected = mutableListOf<SyncSnackbarEvent>()
        val job = launch {
            controller.events.toList(collected)
        }
        controller.emit(SyncSnackbarEvent.SyncSuccess)
        job.cancel()

        // SharedFlow with replay=0: late collector misses Started.
        assertEquals(listOf(SyncSnackbarEvent.SyncSuccess), collected)
    }
}
