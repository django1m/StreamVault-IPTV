package com.streamvault.player.system

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies the device into a coarse performance tier so the player can pick
 * conservative buffers / cache sizes / connection pools on low-end Android TV
 * boxes (typical target: Amlogic S905X5M, 2 GB RAM).
 *
 * Decision is based on [ActivityManager.getMemoryClass] which reports the heap
 * budget Android grants the process — a much better proxy for "how much can we
 * cache without OOM" than raw RAM.
 */
@Singleton
class DevicePerformanceProfile @Inject constructor(
    @ApplicationContext context: Context
) {
    val tier: Tier
    val memoryClassMb: Int

    init {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        memoryClassMb = am.memoryClass
        tier = when {
            memoryClassMb <= 192 -> Tier.LOW
            memoryClassMb <= 384 -> Tier.MID
            else -> Tier.HIGH
        }
    }

    val isLowEnd: Boolean get() = tier == Tier.LOW

    enum class Tier { LOW, MID, HIGH }
}
