package com.streamvault.app.playback

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which container extension actually worked for Xtream episodes and
 * movies after the probe had to fall back (panel advertised no/wrong
 * `container_extension`). Process-memory only, on purpose: the Room-persisted
 * extension is overwritten on every catalog re-sync with the same wrong value
 * the panel keeps advertising, so persisting the correction there would be
 * silently undone. A cold cache merely costs one extra probe (~1s) on the
 * first episode after app start.
 */
@Singleton
class XtreamContainerExtensionMemory @Inject constructor() {
    private val bySeries = ConcurrentHashMap<String, String>()

    // Panels that serve MKV do so uniformly, so the last winning extension for
    // a provider is a good first guess for its other series and movies.
    private val byProvider = ConcurrentHashMap<Long, String>()

    fun remember(providerId: Long, seriesId: Long?, extension: String) {
        if (bySeries.size >= MAX_ENTRIES) bySeries.clear()
        seriesId?.let { bySeries[key(providerId, it)] = extension }
        byProvider[providerId] = extension
    }

    fun preferred(providerId: Long, seriesId: Long?): String? =
        seriesId?.let { bySeries[key(providerId, it)] } ?: byProvider[providerId]

    private fun key(providerId: Long, seriesId: Long) = "$providerId:$seriesId"

    private companion object {
        const val MAX_ENTRIES = 256
    }
}
