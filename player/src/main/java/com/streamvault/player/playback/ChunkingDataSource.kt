package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlin.math.min

// Évite les coupures du CDN Xtream sur les longues connexions HTTP : chaque
// open() open-ended est servi en série de Range requests bornées que ExoPlayer
// consomme comme un flux continu.
@UnstableApi
class ChunkingDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val chunkSize: Long
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0L
    private var currentPosition: Long = 0L
    private var currentChunk: DataSource? = null
    private var currentChunkBytesRemaining: Long = 0L
    // Taille totale de la ressource conservée entre les open() pour borner les
    // chunks aux limites réelles du fichier (évite Range au-delà de EOF).
    private var totalSize: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        currentPosition = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize != C.LENGTH_UNSET.toLong() -> (totalSize - currentPosition).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        transferInitializing(dataSpec)
        if (bytesRemaining != 0L) {
            openCurrentChunk()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openCurrentChunk() {
        currentChunk?.close()
        currentChunk = null
        currentChunkBytesRemaining = 0L

        val chunkLength = when {
            totalSize != C.LENGTH_UNSET.toLong() -> {
                val remaining = totalSize - currentPosition
                if (remaining <= 0L) return
                min(remaining, chunkSize)
            }
            bytesRemaining != C.LENGTH_UNSET.toLong() -> {
                if (bytesRemaining <= 0L) return
                min(bytesRemaining, chunkSize)
            }
            else -> chunkSize
        }
        val spec = dataSpec ?: throw IOException("ChunkingDataSource: openCurrentChunk before open()")
        val chunkSpec = spec.buildUpon()
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()
        val chunk = upstreamFactory.createDataSource()
        val openResult = chunk.open(chunkSpec)
        currentChunk = chunk

        // OkHttpDataSource.open() retourne dataSpec.length sans vérifier ce que le
        // serveur a livré. On utilise Content-Length de la réponse pour la vraie
        // taille servie (évite d'attendre des bytes que le serveur n'enverra pas).
        val responseContentLength = chunk.responseHeaders["Content-Length"]
            ?.firstOrNull()
            ?.toLongOrNull()
        val effectiveLength = responseContentLength ?: openResult
        currentChunkBytesRemaining = if (effectiveLength == C.LENGTH_UNSET.toLong()) {
            chunkLength
        } else {
            effectiveLength
        }

        if (totalSize == C.LENGTH_UNSET.toLong()) {
            val total = chunk.responseHeaders["Content-Range"]
                ?.firstOrNull()
                ?.substringAfterLast('/')
                ?.toLongOrNull()
            if (total != null && total > 0) {
                totalSize = total
                if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                    bytesRemaining = total - currentPosition
                }
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (currentChunkBytesRemaining == 0L) {
            openCurrentChunk()
            if (currentChunkBytesRemaining == 0L) {
                bytesRemaining = 0L
                return C.RESULT_END_OF_INPUT
            }
        }
        val toRead = if (currentChunkBytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), currentChunkBytesRemaining).toInt()
        }
        val chunk = currentChunk ?: throw IOException("ChunkingDataSource: read with no current chunk")
        val bytesRead = chunk.read(buffer, offset, toRead)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            currentChunkBytesRemaining = 0L
            return if (bytesRemaining == 0L) C.RESULT_END_OF_INPUT else read(buffer, offset, length)
        }
        currentChunkBytesRemaining -= bytesRead
        currentPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun close() {
        try {
            currentChunk?.close()
        } finally {
            currentChunk = null
            currentChunkBytesRemaining = 0L
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = currentChunk?.responseHeaders ?: emptyMap()

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkSize: Long = DEFAULT_CHUNK_SIZE
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkingDataSource(upstreamFactory, chunkSize)
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE: Long = 4L * 1024 * 1024
    }
}
