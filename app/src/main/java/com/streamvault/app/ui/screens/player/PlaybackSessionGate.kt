package com.streamvault.app.ui.screens.player

import com.streamvault.domain.model.ContentType

/**
 * Tracks which playback session history writes belong to, and whether the
 * engine actually started playing that session's media.
 *
 * Closes the resume-corruption race: PlayerViewModel.prepare() mutates the
 * now-playing identity synchronously while the engine loads the new media
 * asynchronously (URL resolution + network probe can take seconds). A history
 * write issued inside that window used to pair the NEW content id with the
 * OLD media's position — the persisted corruption behind "resume sent me back
 * to the first episode". The gate only releases a snapshot once the engine
 * was prepared for the current session AND reached READY afterwards, so a
 * stale READY emitted by the previous media can never arm it.
 *
 * Session versions can change without a full prepare() (engine fallback
 * Media3↔LibVLC, alternate live streams): [onNewSession] keeps the content
 * identity but disarms until the new prepare reaches READY, so same-content
 * retries keep persisting once recovered.
 *
 * Pure Kotlin/JVM on purpose (no Android imports) — unit-tested on the JVM.
 * All methods are called on the main thread; fields are @Volatile only so the
 * application-scope writer used for final saves reads a coherent reference
 * from its IO thread.
 */
class PlaybackSessionGate {

    data class Snapshot(
        val requestVersion: Long,
        val contentId: Long,
        val contentType: ContentType,
        val providerId: Long,
        val seriesId: Long?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String,
        val artworkUrl: String?,
        val streamUrl: String
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var enginePreparedVersion: Long = Long.MIN_VALUE

    @Volatile
    private var activated: Boolean = false

    /**
     * A new requestVersion was allocated (beginPlaybackSession) without a new
     * content identity yet. Disarms the gate; keeps the previous identity
     * rebound to the new version so same-content retries re-activate.
     */
    fun onNewSession(requestVersion: Long) {
        activated = false
        snapshot = snapshot?.copy(requestVersion = requestVersion)
    }

    /** prepare() captured a brand-new content identity for this session. */
    fun begin(newSnapshot: Snapshot) {
        activated = false
        snapshot = newSnapshot
    }

    /**
     * Series metadata resolved asynchronously — enrich the snapshot if it
     * still belongs to the same session.
     */
    fun enrich(
        requestVersion: Long,
        seriesId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        title: String?
    ) {
        val current = snapshot ?: return
        if (current.requestVersion != requestVersion) return
        snapshot = current.copy(
            seriesId = seriesId ?: current.seriesId,
            seasonNumber = seasonNumber ?: current.seasonNumber,
            episodeNumber = episodeNumber ?: current.episodeNumber,
            title = title ?: current.title
        )
    }

    /** The engine accepted prepare() for this session's media. */
    fun onEnginePrepared(requestVersion: Long) {
        if (snapshot?.requestVersion == requestVersion) {
            enginePreparedVersion = requestVersion
        }
    }

    /**
     * Engine reached READY. Arms the gate only when the READY follows this
     * session's own engine prepare.
     *
     * @return true on the disarmed → armed transition (used to trigger the
     * one-shot session-start history write).
     */
    fun onEngineReady(requestVersion: Long): Boolean {
        val current = snapshot ?: return false
        if (current.requestVersion != requestVersion) return false
        if (enginePreparedVersion != requestVersion) return false
        if (activated) return false
        activated = true
        return true
    }

    /** Snapshot to attribute history writes to, or null while disarmed. */
    fun activeSnapshotOrNull(): Snapshot? = if (activated) snapshot else null
}
