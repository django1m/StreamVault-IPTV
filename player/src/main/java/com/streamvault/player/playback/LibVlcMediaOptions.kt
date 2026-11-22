package com.streamvault.player.playback

import com.streamvault.domain.model.DecoderMode

/**
 * Ce que LibVLC doit appliquer à un `Media` : [hwDecoderEnabled]/[hwDecoderForced]
 * vont dans `media.setHWDecoderEnabled(enabled, force)`, puis chaque entrée de
 * [options] dans `media.addOption(...)` (dans cet ordre : la dernière `:codec=`
 * l'emporte sur celle posée par setHWDecoderEnabled).
 */
data class LibVlcDecoderConfig(
    val hwDecoderEnabled: Boolean,
    val hwDecoderForced: Boolean,
    val options: List<String>
)

/**
 * Traduit le réglage « Mode décodeur » et la mise en cache en options LibVLC.
 * S'applique au chemin LibVLC uniquement (Media3 : `buildRenderersFactory`).
 *
 * - AUTO     : MediaCodec, repli logiciel autorisé par LibVLC.
 * - HARDWARE : MediaCodec forcé.
 * - SOFTWARE : MediaCodec coupé + `:codec=avcodec` (libavcodec, CPU). C'est le
 *   contournement du gel `c2.mtk.avc.decoder` (« buffer deadlock prevented »).
 */
object LibVlcMediaOptions {
    /**
     * 3 s : le global `--network-caching=2000` laissait LibVLC remonter
     * pts_delay à 1500 ms en pleine lecture (« PCR is called too late »).
     */
    const val DEFAULT_VOD_CACHING_MS = 3_000
    const val MIN_CACHING_MS = 500
    const val MAX_CACHING_MS = 30_000

    fun build(mode: DecoderMode, cachingMs: Int = DEFAULT_VOD_CACHING_MS): LibVlcDecoderConfig {
        val caching = cachingMs.coerceIn(MIN_CACHING_MS, MAX_CACHING_MS)
        val cachingOptions = listOf(":network-caching=$caching", ":file-caching=$caching")
        return when (mode) {
            DecoderMode.SOFTWARE -> LibVlcDecoderConfig(
                hwDecoderEnabled = false,
                hwDecoderForced = false,
                options = listOf(":codec=avcodec") + cachingOptions
            )
            DecoderMode.HARDWARE -> LibVlcDecoderConfig(true, true, cachingOptions)
            DecoderMode.AUTO -> LibVlcDecoderConfig(true, false, cachingOptions)
        }
    }
}
