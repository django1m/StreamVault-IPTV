package com.streamvault.domain.model

data class Movie(
    val id: Long,
    val name: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val streamUrl: String = "",
    val containerExtension: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val duration: String? = null,
    val durationSeconds: Int = 0,
    val rating: Float = 0f,
    val year: String? = null,
    val tmdbId: Long? = null,
    val youtubeTrailer: String? = null,
    val isFavorite: Boolean = false,
    val providerId: Long = 0,
    val watchProgress: Long = 0L,
    val lastWatchedAt: Long = 0L,
    val isAdult: Boolean = false,
    val isUserProtected: Boolean = false,
    val streamId: Long = 0L,
    /** Rang du film dans sa catégorie, dans l'ordre d'envoi du fournisseur (1 = premier, 0 = inconnu). */
    val providerOrder: Int = 0,
    /** Date d'ajout chez le fournisseur (champ `added` Xtream), en epoch ms ; 0 = inconnue. */
    val providerAddedAt: Long = 0L
) {
    init {
        require(durationSeconds >= 0) { "durationSeconds must be non-negative" }
        require(rating in 0f..10f) { "rating must be between 0 and 10" }
        require(watchProgress >= 0) { "watchProgress must be non-negative" }
        require(lastWatchedAt >= 0) { "lastWatchedAt must be non-negative" }
    }
}

/**
 * Tri « Le plus récent » : la date d'ajout chez le fournisseur d'abord — la liste Xtream la donne
 * pour chaque film, alors que la date de sortie n'arrive qu'avec la fiche détaillée. La date de
 * sortie (ou l'année) ne départage que les films sans date d'ajout.
 */
val MOVIE_NEWEST_FIRST: Comparator<Movie> =
    compareByDescending<Movie> { it.providerAddedAt }.thenByDescending { it.releaseScore() }

/** Date de sortie en AAAAMMJJ, ou l'année seule ; 0 si inconnue. */
private fun Movie.releaseScore(): Long =
    releaseDate?.filter { it.isDigit() }?.take(8)?.toLongOrNull()
        ?: year?.toLongOrNull()
        ?: 0L
