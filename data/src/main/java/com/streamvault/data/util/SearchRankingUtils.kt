package com.streamvault.data.util

/**
 * Reclasse les résultats FTS par pertinence perçue : SQLite les renvoie par ordre alphabétique,
 * ce qui enterre le titre exact au milieu des autres.
 *
 * Les comparaisons se font sur le titre replié (minuscules, accents retirés) pour rester alignées
 * sur le tokenizer `unicode61` de l'index : « amelie » doit être reconnu comme préfixe d'« Amélie
 * Poulain », sinon le résultat que l'utilisateur visait se retrouve classé dernier.
 */
private class RankedEntry<T>(
    val item: T,
    val folded: String,
    val length: Int,
    val bucket: Int,
    val firstMatch: Int
)

internal fun <T> List<T>.rankSearchResults(rawQuery: String, nameSelector: (T) -> String): List<T> {
    val foldedQuery = rawQuery.trim().foldForSearch()
    if (foldedQuery.isBlank()) return this

    val queryTokens = rawQuery.searchTokens().map { it.foldForSearch() }
    if (queryTokens.isEmpty()) return this

    // Décoration puis tri : les replis Unicode sont calculés une fois par élément, pas à chaque
    // comparaison (jusqu'à 300 résultats par section sur un boîtier 2 Go).
    return map { item ->
        val name = nameSelector(item).trim()
        val folded = name.foldForSearch()
        RankedEntry(
            item = item,
            folded = folded,
            length = name.length,
            bucket = relevanceBucket(folded, foldedQuery, queryTokens),
            firstMatch = firstMatchPosition(folded, queryTokens)
        )
    }.sortedWith(
        compareBy<RankedEntry<T>> { it.bucket }
            .thenBy { it.firstMatch }
            .thenBy { it.length }
            .thenBy { it.folded }
    ).map { it.item }
}

private fun relevanceBucket(foldedName: String, foldedQuery: String, queryTokens: List<String>): Int {
    val matchedTokens = queryTokens.count { token -> foldedName.contains(token) }
    return when {
        foldedName == foldedQuery -> 0
        foldedName.startsWith(foldedQuery) -> 1
        queryTokens.all { token -> foldedName.startsWith(token) || foldedName.contains(" $token") } -> 2
        matchedTokens == queryTokens.size -> 3
        // Repli permissif (OR) : plus il manque de mots, plus le résultat descend.
        matchedTokens > 0 -> 3 + (queryTokens.size - matchedTokens)
        else -> Int.MAX_VALUE
    }
}

private fun firstMatchPosition(foldedName: String, queryTokens: List<String>): Int {
    return queryTokens.minOfOrNull { token ->
        foldedName.indexOf(token).takeIf { it >= 0 } ?: Int.MAX_VALUE
    } ?: Int.MAX_VALUE
}
