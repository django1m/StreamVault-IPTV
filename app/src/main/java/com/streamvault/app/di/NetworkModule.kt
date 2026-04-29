package com.streamvault.app.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamvault.data.remote.NetworkTimeoutConfig
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.parser.XmltvParser
import com.streamvault.app.BuildConfig
import com.streamvault.data.remote.fuziontv.FuziontvApi
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.player.LibVlcPlayerEngine
import com.streamvault.player.Media3PlayerEngine
import com.streamvault.player.PlayerEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit.SECONDS
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val loggingLevel = if (isDebuggable) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }

        val httpLogger = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", XtreamUrlFactory.sanitizeLogMessage(message))
        }.apply {
            level = loggingLevel
        }

        return OkHttpClient.Builder()
            .connectTimeout(NetworkTimeoutConfig.CONNECT_TIMEOUT_SECONDS, SECONDS)
            .readTimeout(NetworkTimeoutConfig.READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.WRITE_TIMEOUT_SECONDS, SECONDS)
            .addInterceptor(httpLogger)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES)) // Allow more idle connections
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 10 // Increase host limit for Multi-View
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideFuziontvApi(okHttpClient: OkHttpClient): FuziontvApi =
        FuziontvApi(okHttpClient, BuildConfig.FUZIONTV_BASE_URL.trimEnd('/'))

    @Provides
    @Singleton
    fun provideFuziontvDeviceRepository(
        @ApplicationContext context: Context,
        api: FuziontvApi,
    ): FuziontvDeviceRepositoryImpl = FuziontvDeviceRepositoryImpl(context, api)

    @Provides
    @Singleton
    fun provideXtreamApiService(okHttpClient: OkHttpClient, xtreamJson: Json): XtreamApiService =
        OkHttpXtreamApiService(okHttpClient, xtreamJson)

    @Provides
    @Singleton
    fun provideXtreamJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideXmltvParser(): XmltvParser = XmltvParser()

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    // POC libVLC : on force libVLC sur le main engine pour valider qu'il lit
    // les MP4 où Mp4Extractor de Media3 plante (Tulsa King S02E01-E04).
    // Si OK, phase 2 = router PROGRESSIVE→libVLC, autres→Media3.
    @Provides
    @Singleton
    @MainPlayerEngine
    fun provideMainPlayerEngine(
        @ApplicationContext context: Context,
        @Suppress("UNUSED_PARAMETER") okHttpClient: OkHttpClient
    ): PlayerEngine = LibVlcPlayerEngine(context)

    /**
     * Factory binding for preview and multiview playback.
     * Each Provider.get() call returns a fresh engine instance.
     */
    @Provides
    @AuxiliaryPlayerEngine
    fun provideAuxiliaryPlayerEngine(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): PlayerEngine = Media3PlayerEngine(context, okHttpClient).apply {
        enableMediaSession = false
        bypassAudioFocus = true
    }
}
