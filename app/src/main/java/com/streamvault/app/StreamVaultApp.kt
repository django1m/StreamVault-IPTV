package com.streamvault.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import androidx.lifecycle.ProcessLifecycleOwner
import com.streamvault.app.diagnostics.RuntimeDiagnosticsManager
import com.streamvault.app.startup.AppStartupCoordinator
import com.streamvault.app.ui.accessibility.isReducedMotionEnabled
import com.streamvault.player.system.DevicePerformanceProfile
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.streamvault.data.manager.recording.RecordingReconcileWorker
import javax.inject.Inject

@HiltAndroidApp
class StreamVaultApp : Application(), SingletonImageLoader.Factory {
    private val runtimeDiagnosticsManager by lazy { RuntimeDiagnosticsManager(this) }

    @Inject
    lateinit var appStartupCoordinator: AppStartupCoordinator

    @Inject
    lateinit var devicePerformanceProfile: DevicePerformanceProfile

    @Inject
    lateinit var foregroundHeartbeatController: com.streamvault.app.work.ForegroundHeartbeatController

    @Inject
    lateinit var commandChannelController: com.streamvault.app.work.CommandChannelController

    @Inject
    lateinit var credentialRefreshOrchestrator: com.streamvault.app.auth.CredentialRefreshOrchestrator

    @Inject
    lateinit var playerEngineHandoffRegistry: com.streamvault.app.playback.PlayerEngineHandoffRegistry

    @Inject
    lateinit var playbackConnectionRecovery: com.streamvault.app.network.PlaybackConnectionRecovery

    @Inject
    lateinit var crashReporter: com.streamvault.app.diagnostics.CrashReporter

    @Inject
    lateinit var vpnManager: com.streamvault.app.vpn.VpnManager

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        runtimeDiagnosticsManager.start()
        playbackConnectionRecovery.start()
        // Bring the VPN tunnel back up if this box already needs it (forced on,
        // or auto-activated after a past 403). Done before playback can start.
        vpnManager.connectIfNeeded()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appStartupCoordinator)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundHeartbeatController)
        ProcessLifecycleOwner.get().lifecycle.addObserver(commandChannelController)
        ProcessLifecycleOwner.get().lifecycle.addObserver(credentialRefreshOrchestrator)
        ProcessLifecycleOwner.get().lifecycle.addObserver(playerEngineHandoffRegistry)

        // Schedule daily data maintenance: EPG pruning, stale-favorite cleanup, and DB compaction checks.
        // BLD-H02: Require network + device idle so the worker doesn't drain battery.
        //
        // On the mobile build this additionally requires an UNMETERED network.
        // A catalog + EPG refresh moves a lot of data, and a client on a metered
        // plan should never pay for background maintenance they did not ask for.
        // A TV box is on Wi-Fi or Ethernet by definition, so CONNECTED there is
        // both correct and unchanged.
        //
        // Deliberately NOT applied to HeartbeatWorker: that is the control
        // channel (force-sync, remote commands) and must keep running on any
        // connection, metered or not.
        val gcConstraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (BuildConfig.APP_VARIANT == "mobile") NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val gcWorkRequest = PeriodicWorkRequestBuilder<com.streamvault.data.sync.SyncWorker>(24, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(gcConstraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DataMaintenanceWorker",
            // UPDATE, not KEEP: WorkManager freezes constraints at enqueue time,
            // so a KEEP would leave phones that already scheduled this work under
            // the old metered-allowed constraint forever.
            ExistingPeriodicWorkPolicy.UPDATE,
            gcWorkRequest
        )

        RecordingReconcileWorker.enqueuePeriodic(this)
        RecordingReconcileWorker.enqueueOneShot(this)

        // Diagnostics upload: drains crashes + flushes the incident queue.
        com.streamvault.app.work.DiagnosticsUploadWorker.schedulePeriodic(this)
        com.streamvault.app.work.DiagnosticsUploadWorker.enqueueOneShot(this)
    }

    override fun onTerminate() {
        runtimeDiagnosticsManager.stop()
        super.onTerminate()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Tighten budgets on low-end Android TV boxes (2 GB RAM Amlogic) where
        // a hundred channel logos in EPG can OOM the process otherwise.
        val (memPercent, diskBytes, fetchers, decoders) = when (devicePerformanceProfile.tier) {
            // 0.12 (~23 MB at memoryClass 192) instead of 0.08 (~15 MB): the
            // EPG channel-logo working set alone overflows 15 MB and causes
            // redecode churn while scrolling. The hero backdrop no longer eats
            // the cache (capped + RGB_565 in HeroBackdrop), so the headroom
            // genuinely goes to logos/posters.
            DevicePerformanceProfile.Tier.LOW -> Quad(0.12, 50L * 1024 * 1024, 4, 2)
            DevicePerformanceProfile.Tier.MID -> Quad(0.12, 80L * 1024 * 1024, 6, 3)
            DevicePerformanceProfile.Tier.HIGH -> Quad(0.15, 100L * 1024 * 1024, 6, 4)
        }
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, memPercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(diskBytes)
                    .build()
            }
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(fetchers))
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(decoders))
            .crossfade(!isReducedMotionEnabled(context))
            .build()
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
