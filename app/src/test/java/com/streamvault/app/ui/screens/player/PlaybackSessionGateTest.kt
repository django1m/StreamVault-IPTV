package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import org.junit.Test

class PlaybackSessionGateTest {

    private fun snapshot(
        version: Long,
        contentId: Long = 11L,
        contentType: ContentType = ContentType.SERIES_EPISODE
    ) = PlaybackSessionGate.Snapshot(
        requestVersion = version,
        contentId = contentId,
        contentType = contentType,
        providerId = 1L,
        seriesId = 7L,
        seasonNumber = 1,
        episodeNumber = 2,
        title = "S1E2",
        artworkUrl = null,
        streamUrl = "https://example.com/$contentId"
    )

    @Test
    fun `no persistence before the engine is prepared and ready`() {
        val gate = PlaybackSessionGate()
        gate.begin(snapshot(version = 1L))

        // prepare() ran, but the engine has not loaded the media yet —
        // this is exactly the window where the old media's position used to
        // be written under the new episode's id.
        assertThat(gate.activeSnapshotOrNull()).isNull()

        // A READY without onEnginePrepared (stale READY from the previous
        // media re-emitted by a transient stall) must NOT arm the gate.
        assertThat(gate.onEngineReady(1L)).isFalse()
        assertThat(gate.activeSnapshotOrNull()).isNull()
    }

    @Test
    fun `arms at the first ready following this session's prepare`() {
        val gate = PlaybackSessionGate()
        gate.begin(snapshot(version = 1L))
        gate.onEnginePrepared(1L)

        assertThat(gate.onEngineReady(1L)).isTrue()
        assertThat(gate.activeSnapshotOrNull()?.contentId).isEqualTo(11L)
        // Transition is one-shot: a second READY does not re-trigger the
        // session-start write.
        assertThat(gate.onEngineReady(1L)).isFalse()
        assertThat(gate.activeSnapshotOrNull()).isNotNull()
    }

    @Test
    fun `ready for a stale version never arms the gate`() {
        val gate = PlaybackSessionGate()
        gate.begin(snapshot(version = 1L))
        gate.onEnginePrepared(1L)
        gate.begin(snapshot(version = 2L, contentId = 12L))

        // READY of version 1's media arriving after version 2 began.
        assertThat(gate.onEngineReady(1L)).isFalse()
        assertThat(gate.activeSnapshotOrNull()).isNull()
    }

    @Test
    fun `next episode disarms until the new media is ready`() {
        val gate = PlaybackSessionGate()
        gate.begin(snapshot(version = 1L, contentId = 11L))
        gate.onEnginePrepared(1L)
        gate.onEngineReady(1L)

        // playEpisode(next) → beginPlaybackSession + prepare with new identity.
        gate.onNewSession(2L)
        gate.begin(snapshot(version = 2L, contentId = 12L))
        assertThat(gate.activeSnapshotOrNull()).isNull()

        gate.onEnginePrepared(2L)
        assertThat(gate.onEngineReady(2L)).isTrue()
        assertThat(gate.activeSnapshotOrNull()?.contentId).isEqualTo(12L)
    }

    @Test
    fun `same content retry (engine fallback) keeps identity and re-arms at next ready`() {
        val gate = PlaybackSessionGate()
        gate.begin(snapshot(version = 1L, contentId = 11L))
        gate.onEnginePrepared(1L)
        gate.onEngineReady(1L)

        // Media3 fails on a broken MP4 → tryAlternateEngineInternal calls
        // beginPlaybackSession (new version) WITHOUT a new prepare() identity.
        gate.onNewSession(2L)
        assertThat(gate.activeSnapshotOrNull()).isNull()

        gate.onEnginePrepared(2L)
        assertThat(gate.onEngineReady(2L)).isTrue()
        // Identity survived the retry: writes keep going to the same episode.
        assertThat(gate.activeSnapshotOrNull()?.contentId).isEqualTo(11L)
    }

    @Test
    fun `enrich updates metadata only for the active session`() {
        val gate = PlaybackSessionGate()
        gate.begin(snapshot(version = 1L).copy(seriesId = null, seasonNumber = null, episodeNumber = null))
        gate.onEnginePrepared(1L)
        gate.onEngineReady(1L)

        gate.enrich(requestVersion = 1L, seriesId = 7L, seasonNumber = 2, episodeNumber = 5, title = "S2E5")
        val enriched = gate.activeSnapshotOrNull()
        assertThat(enriched?.seriesId).isEqualTo(7L)
        assertThat(enriched?.seasonNumber).isEqualTo(2)
        assertThat(enriched?.episodeNumber).isEqualTo(5)
        assertThat(enriched?.title).isEqualTo("S2E5")

        // Stale enrichment (old session resolving late) must be dropped.
        gate.enrich(requestVersion = 99L, seriesId = 8L, seasonNumber = 9, episodeNumber = 9, title = "stale")
        assertThat(gate.activeSnapshotOrNull()?.seriesId).isEqualTo(7L)
    }
}
