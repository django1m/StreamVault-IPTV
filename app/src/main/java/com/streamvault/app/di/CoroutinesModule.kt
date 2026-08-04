package com.streamvault.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime coroutine scope for work that must outlive a ViewModel —
 * e.g. the final playback-position save when the player screen is destroyed:
 * a viewModelScope.launch in onCleared() never runs because super.onCleared()
 * already cancelled the scope. Backed by Dispatchers.IO (the writes are Room
 * transactions) and a SupervisorJob so one failed write cannot kill the scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
