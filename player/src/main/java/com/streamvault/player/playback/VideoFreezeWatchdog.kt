package com.streamvault.player.playback

/**
 * Un échantillon de lecture LibVLC. [displayedPictures] = `Media.Stats.displayedPictures`
 * (null si les stats sont illisibles), [positionMs] = horloge de lecture (audio).
 */
data class PlaybackSample(val positionMs: Long, val displayedPictures: Int?, val isPlaying: Boolean)

/**
 * Détecte « l'audio avance, l'image est figée » : la position progresse d'au
 * moins [minMediaAdvanceMs] alors qu'aucune nouvelle image n'a été affichée
 * depuis [freezeWindowMs] (horloge murale). Ne s'arme qu'après la première image
 * (pas de faux positif sur un flux audio seul ou avant le démarrage), et une
 * pause / un rebuffer (position immobile) ne déclenchent rien.
 *
 * Renvoie true UNE fois par gel ; [reset] ré-arme (nouveau média, seek, repli).
 * Non thread-safe : un seul thread d'échantillonnage.
 */
class VideoFreezeDetector(
    private val freezeWindowMs: Long = 3_000L,
    private val minMediaAdvanceMs: Long = 2_000L
) {
    private var lastPictures = -1
    private var anchorWallMs = 0L
    private var anchorPositionMs = 0L
    private var latched = false

    fun reset() {
        lastPictures = -1
        latched = false
    }

    fun onSample(nowMs: Long, sample: PlaybackSample): Boolean {
        val pictures = sample.displayedPictures ?: return false
        if (pictures != lastPictures || !sample.isPlaying || sample.positionMs < anchorPositionMs) {
            lastPictures = pictures
            anchorWallMs = nowMs
            anchorPositionMs = sample.positionMs
            return false
        }
        if (latched || pictures <= 0) return false
        val stalledFor = nowMs - anchorWallMs
        val mediaAdvanced = sample.positionMs - anchorPositionMs
        if (stalledFor >= freezeWindowMs && mediaAdvanced >= minMediaAdvanceMs) {
            latched = true
            return true
        }
        return false
    }
}

enum class FreezeFallbackStep { LIBVLC_SOFTWARE, MEDIA3 }

interface FreezeFallbackTarget {
    /** Relance LibVLC en décodage logiciel à [positionMs]. */
    fun restartLibVlcSoftware(positionMs: Long)
    /** Bascule sur Media3 à [positionMs]. */
    fun switchToMedia3(positionMs: Long)
}

/**
 * Échelle de repli après un gel vidéo : LibVLC logiciel, puis Media3. Une seule
 * tentative par niveau et par média — au-delà on rend la main (pas de boucle).
 * [reset] à chaque nouveau média lancé par l'utilisateur.
 */
class FreezeFallbackCoordinator(private val target: FreezeFallbackTarget) {
    private val used = mutableSetOf<FreezeFallbackStep>()

    @Synchronized
    fun reset() = used.clear()

    @Synchronized
    fun onFreeze(positionMs: Long, libVlcAlreadySoftware: Boolean): FreezeFallbackStep? {
        if (libVlcAlreadySoftware) used += FreezeFallbackStep.LIBVLC_SOFTWARE
        val step = FreezeFallbackStep.entries.firstOrNull { it !in used } ?: return null
        used += step
        when (step) {
            FreezeFallbackStep.LIBVLC_SOFTWARE -> target.restartLibVlcSoftware(positionMs)
            FreezeFallbackStep.MEDIA3 -> target.switchToMedia3(positionMs)
        }
        return step
    }
}
