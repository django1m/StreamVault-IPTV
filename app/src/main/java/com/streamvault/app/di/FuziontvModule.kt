package com.streamvault.app.di

import android.content.Context
import com.streamvault.app.BuildConfig
import com.streamvault.data.remote.fuziontv.FuziontvApi
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FuziontvModule {

    @Provides
    @Singleton
    fun provideFuziontvApi(okHttpClient: OkHttpClient): FuziontvApi =
        FuziontvApi(client = okHttpClient, baseUrl = BuildConfig.FUZIONTV_BASE_URL)

    @Provides
    @Singleton
    fun provideFuziontvDeviceRepository(
        @ApplicationContext context: Context,
        api: FuziontvApi
    ): FuziontvDeviceRepositoryImpl = FuziontvDeviceRepositoryImpl(context = context, api = api)
}
