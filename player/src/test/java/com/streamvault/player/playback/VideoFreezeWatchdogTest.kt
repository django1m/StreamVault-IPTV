package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reproduit le gel observé sur kirkwood (c2.mtk.avc.decoder via LibVLC) :
 * l'audio continue (la position avance), mais plus aucune image n'est
 * affichée (displayedPictures figé) — « buffer deadlock prevented » en boucle.
 */
class VideoFreezeWatchdogTest {

    /**
     * Faux moteur : la position avance de 250 ms par tick (l'audio joue), les
     * images s'arrêtent d'avancer à [freezeAtMs]. Chaque relance (repli)
     * repart à la position demandée et, si [freezeAgainAfterRestart], regèle
     * aussitôt après une image.
     */
    private class FakeFreezingEngine(
        private val freezeAtMs: Long,
        private val freezeAgainAfterRestart: Boolean
    ) : FreezeFallbackTarget {
        var nowMs = 0L
        var positionMs = 0L
        var pictures = 0
        private var frozen = false
        private var restarted = false
        val calls = mutableListOf<Pair<String, Long>>()

        fun tick(): PlaybackSample {
            nowMs += 250
            positionMs += 250
            if (!restarted && positionMs >= freezeAtMs) frozen = true
            if (!frozen) pictures += 6
            return PlaybackSample(positionMs, pictures, isPlaying = true)
        }

        private fun restartAt(pos: Long) {
            restarted = true
            positionMs = pos
            pictures += 1 // une image après la relance
            frozen = freezeAgainAfterRestart
        }

        override fun restartLibVlcSoftware(positionMs: Long) {
            calls += "libvlc-sw" to positionMs
            restartAt(positionMs)
        }

        override fun switchToMedia3(positionMs: Long) {
            calls += "media3" to positionMs
            restartAt(positionMs)
        }
    }

    private fun run(engine: FakeFreezingEngine, ticks: Int, startsSoftware: Boolean = false) {
        val detector = VideoFreezeDetector()
        val coordinator = FreezeFallbackCoordinator(engine)
        var onSoftware = startsSoftware
        repeat(ticks) {
            val sample = engine.tick()
            if (detector.onSample(engine.nowMs, sample)) {
                val step = coordinator.onFreeze(sample.positionMs, onSoftware)
                if (step == FreezeFallbackStep.LIBVLC_SOFTWARE) onSoftware = true
                detector.reset()
            }
        }
    }

    @Test
    fun `audio advances but video frozen - restarts LibVLC in software at current position`() {
        val engine = FakeFreezingEngine(freezeAtMs = 15_000, freezeAgainAfterRestart = false)
        run(engine, ticks = 120) // 30 s
        assertThat(engine.calls).hasSize(1)
        val (step, pos) = engine.calls.single()
        assertThat(step).isEqualTo("libvlc-sw")
        // ~3 s après le gel, à la position courante (pas 0, pas la position du gel figée).
        assertThat(pos).isAtLeast(17_500L)
        assertThat(pos).isAtMost(19_000L)
    }

    @Test
    fun `software also freezes - falls back to Media3, then stops without looping`() {
        val engine = FakeFreezingEngine(freezeAtMs = 15_000, freezeAgainAfterRestart = true)
        run(engine, ticks = 400) // 100 s : plein d'occasions de boucler
        assertThat(engine.calls.map { it.first }).containsExactly("libvlc-sw", "media3").inOrder()
    }

    @Test
    fun `already in software mode - skips straight to Media3`() {
        val engine = FakeFreezingEngine(freezeAtMs = 15_000, freezeAgainAfterRestart = true)
        run(engine, ticks = 400, startsSoftware = true)
        assertThat(engine.calls.map { it.first }).containsExactly("media3")
    }

    @Test
    fun `healthy playback never triggers`() {
        val engine = FakeFreezingEngine(freezeAtMs = Long.MAX_VALUE, freezeAgainAfterRestart = false)
        run(engine, ticks = 400)
        assertThat(engine.calls).isEmpty()
    }

    @Test
    fun `audio-only or not yet started (zero pictures) never triggers`() {
        val detector = VideoFreezeDetector()
        var fired = false
        for (i in 1..100) {
            fired = fired || detector.onSample(i * 250L, PlaybackSample(i * 250L, 0, isPlaying = true))
        }
        assertThat(fired).isFalse()
    }

    @Test
    fun `paused or rebuffering (position not advancing) never triggers`() {
        val detector = VideoFreezeDetector()
        var fired = detector.onSample(0, PlaybackSample(10_000, 300, isPlaying = true))
        for (i in 1..100) {
            // pause : position et images figées
            fired = fired || detector.onSample(i * 250L, PlaybackSample(10_000, 300, isPlaying = false))
        }
        for (i in 101..200) {
            // rebuffer : isPlaying vrai mais position figée
            fired = fired || detector.onSample(i * 250L, PlaybackSample(10_000, 300, isPlaying = true))
        }
        assertThat(fired).isFalse()
    }

    @Test
    fun `stats unavailable never triggers`() {
        val detector = VideoFreezeDetector()
        var fired = false
        for (i in 1..100) {
            fired = fired || detector.onSample(i * 250L, PlaybackSample(i * 250L, null, isPlaying = true))
        }
        assertThat(fired).isFalse()
    }

    @Test
    fun `reset after new media re-arms every level`() {
        val calls = mutableListOf<String>()
        val coordinator = FreezeFallbackCoordinator(object : FreezeFallbackTarget {
            override fun restartLibVlcSoftware(positionMs: Long) { calls += "sw" }
            override fun switchToMedia3(positionMs: Long) { calls += "m3" }
        })
        coordinator.onFreeze(1_000, libVlcAlreadySoftware = false)
        coordinator.onFreeze(2_000, libVlcAlreadySoftware = true)
        assertThat(coordinator.onFreeze(3_000, libVlcAlreadySoftware = true)).isNull()
        coordinator.reset()
        assertThat(coordinator.onFreeze(4_000, libVlcAlreadySoftware = false))
            .isEqualTo(FreezeFallbackStep.LIBVLC_SOFTWARE)
        assertThat(calls).containsExactly("sw", "m3", "sw").inOrder()
    }
}
