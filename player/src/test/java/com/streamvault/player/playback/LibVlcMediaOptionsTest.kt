package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DecoderMode
import org.junit.Test

class LibVlcMediaOptionsTest {

    @Test
    fun `software mode disables MediaCodec and forces avcodec`() {
        val cfg = LibVlcMediaOptions.build(DecoderMode.SOFTWARE)
        assertThat(cfg.hwDecoderEnabled).isFalse()
        assertThat(cfg.hwDecoderForced).isFalse()
        assertThat(cfg.options).contains(":codec=avcodec")
    }

    @Test
    fun `hardware only forces MediaCodec`() {
        val cfg = LibVlcMediaOptions.build(DecoderMode.HARDWARE)
        assertThat(cfg.hwDecoderEnabled).isTrue()
        assertThat(cfg.hwDecoderForced).isTrue()
        assertThat(cfg.options.none { it.startsWith(":codec=") }).isTrue()
    }

    @Test
    fun `auto keeps MediaCodec with software fallback`() {
        val cfg = LibVlcMediaOptions.build(DecoderMode.AUTO)
        assertThat(cfg.hwDecoderEnabled).isTrue()
        assertThat(cfg.hwDecoderForced).isFalse()
        assertThat(cfg.options.none { it.startsWith(":codec=") }).isTrue()
    }

    @Test
    fun `buffer duration is applied as network and file caching`() {
        for (mode in DecoderMode.entries) {
            val cfg = LibVlcMediaOptions.build(mode, cachingMs = 5_000)
            assertThat(cfg.options).containsAtLeast(":network-caching=5000", ":file-caching=5000")
        }
    }

    @Test
    fun `default caching is larger than the global 2000 ms that let pts_delay starve`() {
        val cfg = LibVlcMediaOptions.build(DecoderMode.AUTO)
        assertThat(cfg.options).contains(":network-caching=${LibVlcMediaOptions.DEFAULT_VOD_CACHING_MS}")
        assertThat(LibVlcMediaOptions.DEFAULT_VOD_CACHING_MS).isGreaterThan(2_000)
    }

    @Test
    fun `absurd caching values are clamped`() {
        assertThat(LibVlcMediaOptions.build(DecoderMode.AUTO, cachingMs = 0).options)
            .contains(":network-caching=${LibVlcMediaOptions.MIN_CACHING_MS}")
        assertThat(LibVlcMediaOptions.build(DecoderMode.AUTO, cachingMs = 600_000).options)
            .contains(":network-caching=${LibVlcMediaOptions.MAX_CACHING_MS}")
    }
}
