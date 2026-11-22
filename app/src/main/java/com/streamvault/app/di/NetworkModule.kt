package com.streamvault.app.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamvault.data.remote.NetworkTimeoutConfig
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.player.system.DevicePerformanceProfile
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.parser.XmltvParser
import com.streamvault.app.BuildConfig
import com.streamvault.app.network.BackendConnectFailureListener
import com.streamvault.app.network.BackendDns
import com.streamvault.data.remote.fuziontv.FuziontvApi
import com.streamvault.data.repository.FuziontvDeviceRepositoryImpl
import com.streamvault.player.LibVlcPlayerEngine
import com.streamvault.player.Media3PlayerEngine
import com.streamvault.player.PlayerEngine
import com.streamvault.player.RoutingPlayerEngine
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

    /**
     * Resolver of the control-plane client: the system DNS for every host, plus a
     * DNS-over-HTTPS rescue for OUR backend when the box's resolver filters it.
     * The playback client is untouched.
     */
    @Provides
    @Singleton
    fun provideBackendDns(): BackendDns = BackendDns.create(BuildConfig.FUZIONTV_BASE_URL)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        deviceProfile: DevicePerformanceProfile,
        backendDns: BackendDns,
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

        // Low-end boxes (2 GB RAM Amlogic) saturate quickly with the Multi-View
        // tuned defaults; cap concurrency to what the heap can actually serve.
        val (maxReq, maxReqHost, idle) = when (deviceProfile.tier) {
            DevicePerformanceProfile.Tier.LOW -> Triple(16, 4, 6)
            DevicePerformanceProfile.Tier.MID -> Triple(32, 6, 8)
            DevicePerformanceProfile.Tier.HIGH -> Triple(64, 10, 10)
        }

        return OkHttpClient.Builder()
            .connectTimeout(NetworkTimeoutConfig.CONNECT_TIMEOUT_SECONDS, SECONDS)
            .readTimeout(NetworkTimeoutConfig.READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.WRITE_TIMEOUT_SECONDS, SECONDS)
            .addInterceptor(httpLogger)
            .dns(backendDns)
            .eventListener(BackendConnectFailureListener(backendDns.backendHost, backendDns::onBackendConnectFailed))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(idle, 5, java.util.concurrent.TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = maxReq
                maxRequestsPerHost = maxReqHost
            })
            .build()
    }

    /**
     * Dedicated playback client — its OWN Dispatcher + ConnectionPool (built
     * from a fresh [OkHttpClient.Builder], NOT [OkHttpClient.newBuilder] which
     * would share the parent's dispatcher/pool by reference). Isolating playback
     * from the control-plane means stuck live sockets can never starve the
     * catalogue/heartbeat — the root cause of the "nothing plays until reboot"
     * bug. No logging interceptor (privacy of stream URLs). Per-stream timeouts
     * are still refined downstream by PlayerTimeoutProfile.
     */
    @Provides
    @Singleton
    @PlaybackHttpClient
    fun providePlaybackOkHttpClient(deviceProfile: DevicePerformanceProfile): OkHttpClient {
        val (maxReq, maxReqHost, idle, keepAliveMin) = when (deviceProfile.tier) {
            DevicePerformanceProfile.Tier.LOW -> Quad(12, 8, 8, 2L)
            DevicePerformanceProfile.Tier.MID -> Quad(24, 10, 10, 3L)
            DevicePerformanceProfile.Tier.HIGH -> Quad(48, 12, 12, 5L)
        }
        return OkHttpClient.Builder()
            .connectTimeout(NetworkTimeoutConfig.CONNECT_TIMEOUT_SECONDS, SECONDS)
            .readTimeout(NetworkTimeoutConfig.READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.WRITE_TIMEOUT_SECONDS, SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(idle, keepAliveMin, java.util.concurrent.TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = maxReq
                maxRequestsPerHost = maxReqHost
            })
            .build()
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Long)

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
    fun provideXtreamApiService(
        okHttpClient: OkHttpClient,
        xtreamJson: Json,
        authInvalidationBus: com.streamvault.data.remote.xtream.XtreamAuthInvalidationBus
    ): XtreamApiService =
        OkHttpXtreamApiService(okHttpClient, xtreamJson, authInvalidationBus)

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

    // Main engine : routage stream-aware. Les VOD progressives (MP4/MKV)
    // passent par libVLC pour contourner les moov/stco cassés que Media3
    // mis-parse ; tout le reste (HLS, DASH, MPEG-TS live, inconnu) reste sur
    // Media3 — libVLC produit un écran vert sur certaines chaînes live via
    // le décodeur matériel Amlogic.
    @Provides
    @Singleton
    @MainPlayerEngine
    fun provideMainPlayerEngine(
        @ApplicationContext context: Context,
        @PlaybackHttpClient playbackClient: OkHttpClient,
        libVlcEngine: LibVlcPlayerEngine,
        deviceProfile: DevicePerformanceProfile
    ): PlayerEngine = RoutingPlayerEngine(
        // SurfaceView (scaler matériel Amlogic) seulement pour le lecteur plein
        // écran. L'AuxiliaryPlayerEngine (mosaïque + preview) reste en TextureView.
        media3 = Media3PlayerEngine(context, playbackClient, deviceProfile).apply { useSurfaceView = true },
        libvlc = libVlcEngine
    )

    /**
     * Factory binding for preview and multiview playback.
     * Each Provider.get() call returns a fresh engine instance.
     */
    @Provides
    @AuxiliaryPlayerEngine
    fun provideAuxiliaryPlayerEngine(
        @ApplicationContext context: Context,
        @PlaybackHttpClient playbackClient: OkHttpClient,
        deviceProfile: DevicePerformanceProfile
    ): PlayerEngine = Media3PlayerEngine(context, playbackClient, deviceProfile).apply {
        enableMediaSession = false
        bypassAudioFocus = true
    }
}
