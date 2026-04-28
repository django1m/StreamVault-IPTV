package com.streamvault.app.startup

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncSnackbarEvent {
    data object SyncStarted : SyncSnackbarEvent()
    data object SyncSuccess : SyncSnackbarEvent()
    data class SyncFailed(@StringRes val messageRes: Int) : SyncSnackbarEvent()
}

@Singleton
class SyncSnackbarController @Inject constructor() {
    private val _events = MutableSharedFlow<SyncSnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val events: SharedFlow<SyncSnackbarEvent> = _events.asSharedFlow()

    suspend fun emit(event: SyncSnackbarEvent) {
        _events.emit(event)
    }
}
