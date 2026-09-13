package com.streamvault.data.util

import java.text.Normalizer
import java.util.Locale

/**
 * Découpe une saisie utilisateur en jetons de recherche, de la même façon que le tokenizer
 * FTS `unicode61` découpe les titres indexés : tout ce qui n'est ni lettre ni chiffre est un
 * séparateur. C'est ce qui permet à `l'été` de trouver « L'Été dernier » (le titre est indexé
 * sous les termes `l` et `ete`, pas sous `lete`).
 *
 * Les jetons d'une seule lettre sont écartés — ils feraient scanner tout le catalogue pour rien —
 * SAUF quand la requête n'est faite que de ceux-là (`9-1-1`, `S.W.A.T.`), auquel cas les écarter
 * reviendrait à ne rien chercher du tout.
 */
private val TOKEN_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
private val DIACRITICS = Regex("\\p{Mn}+")
private const val MIN_TOKEN_LENGTH = 2

internal fun String.searchTokens(): List<String> {
    val tokens = lowercase(Locale.ROOT)
        .split(TOKEN_SEPARATORS)
        .filter { it.isNotEmpty() }

    val meaningful = tokens.filter { it.length >= MIN_TOKEN_LENGTH }
    return when {
        meaningful.isNotEmpty() -> meaningful
        tokens.size >= 2 -> tokens
        else -> emptyList()
    }
}

/** Minuscules + accents retirés, pour comparer côté Kotlin comme `unicode61` compare côté SQLite. */
internal fun String.foldForSearch(): String =
    Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(DIACRITICS, "")

/**
 * Requête FTS stricte : tous les jetons doivent être présents, chacun en préfixe.
 *
 * Exemple : `"jason bourne"` → `"jason* bourne*"`.
 *
 * ⚠️ Les jetons sont séparés par une espace, **jamais** par `AND`. Le SQLite système d'Android est
 * compilé sans `SQLITE_ENABLE_FTS3_PARENTHESIS` (cf. AOSP `external/sqlite/dist/Android.bp`), donc
 * FTS3/FTS4 y utilise la *standard query syntax*, où « AND » n'est pas un opérateur mais un terme
 * ordinaire : https://sqlite.org/fts3.html#section_3_2 — « Specifying the string "AND" [...] is
 * interpreted as a term query for the set of documents containing the term "and" ». Émettre
 * `jason* AND bourne*` exigeait donc un titre contenant le mot « and » et ne remontait rien.
 * L'espace vaut AND implicite dans les deux syntaxes : ne pas « re-clarifier » avec un AND.
 */
internal fun String.toFtsPrefixQuery(): String? =
    searchTokens().takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }

/**
 * Requête FTS permissive : au moins un jeton présent (`"jason* OR bourne*"`).
 *
 * Sert uniquement de repli quand la requête stricte ne remonte rien, pour qu'une saisie du type
 * « matrix reloaded vf » affiche quand même les Matrix au lieu d'un écran vide. `OR` est un vrai
 * opérateur dans les deux syntaxes FTS. `null` s'il n'y a qu'un jeton : le repli serait alors
 * identique à la requête stricte.
 */
internal fun String.toFtsAnyPrefixQuery(): String? =
    searchTokens().takeIf { it.size >= 2 }?.joinToString(" OR ") { "$it*" }
