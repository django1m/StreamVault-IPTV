package com.streamvault.data.sync

import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series

/**
 * Numérote les films ou les séries de chaque catégorie dans l'ordre où le fournisseur les envoie
 * (1 = premier).
 *
 * Une instance couvre UNE synchro : la partager entre les lots ou les pages d'une même synchro
 * garde la numérotation continue. Le rang est compté par catégorie et non sur toute la liste :
 * un film ajouté en tête de « Films 2026 » ne décale que cette catégorie, alors qu'un rang global
 * décalerait tout le catalogue — et forcerait sa réécriture complète à chaque synchro.
 */
internal class ProviderOrderCounter {
    private val lastRankByCategory = HashMap<Long?, Int>()

    fun next(categoryId: Long?): Int {
        val rank = (lastRankByCategory[categoryId] ?: 0) + 1
        lastRankByCategory[categoryId] = rank
        return rank
    }

    fun rank(movie: Movie): Movie = movie.copy(providerOrder = next(movie.categoryId))

    fun rank(series: Series): Series = series.copy(providerOrder = next(series.categoryId))
}
