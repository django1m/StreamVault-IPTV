package com.streamvault.app.startup

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SyncSnackbarHolderViewModel @Inject constructor(
    val controller: SyncSnackbarController
) : ViewModel()
