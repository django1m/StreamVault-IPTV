package com.streamvault.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.streamvault.app.di.MainPlayerEngine
import com.streamvault.data.remote.fuziontv.DeviceHealth
import com.streamvault.player.PlayerEngine
import com.streamvault.player.RoutingPlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Release-safe, allocation-light health capture for the heartbeat payload
 * (~300 bytes on the wire). Deliberately separate from
 * [RuntimeDiagnosticsManager], whose file logger is DEBUG-only and must stay
 * that way — customer boxes never write verbose logs to disk.
 */
@Singleton
class DeviceHealthSnapshot @Inject constructor(
    @ApplicationContext private val context: Context,
    @MainPlayerEngine private val playerEngine: PlayerEngine,
) {
    fun capture(): DeviceHealth {
        val memoryInfo = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(memoryInfo)
        }
        val engine = (playerEngine as? RoutingPlayerEngine)
            ?.currentEnginePreference()
            ?.name
            ?.lowercase()
        return DeviceHealth(
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000L,
            memFreeMb = (memoryInfo.availMem / BYTES_PER_MB).toInt().takeIf { it > 0 },
            memTotalMb = (memoryInfo.totalMem / BYTES_PER_MB).toInt().takeIf { it > 0 },
            playerEngine = engine,
        )
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
