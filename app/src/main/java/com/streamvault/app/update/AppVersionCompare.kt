package com.streamvault.app.update

import com.streamvault.app.BuildConfig

/**
 * Le versionCode fait autorité, point final.
 *
 * Le PackageManager refuse d'installer un versionCode inférieur à celui déjà en
 * place : proposer une release « plus récente par son nom » mais plus ancienne par
 * son code ne produit qu'un téléchargement qui échoue à l'installation. C'est
 * exactement ce qui offrait la stable 1.3.36 (524) à une bêta 1.3.37-beta1 (525) —
 * l'ancien code ne se servait du versionCode que pour dire OUI, puis retombait sur
 * une comparaison de noms que le suffixe `-beta1` faisait mentir.
 *
 * Le garde anti-downgrade vit ici et NON côté serveur : [AppUpdateChecker] envoie
 * délibérément `current=0` pour que l'app connaisse toujours la dernière version
 * publiée, même quand elle n'est pas plus récente — sans quoi l'écran Réglages
 * afficherait une erreur au lieu de « vous êtes à jour ».
 */
internal fun isAppUpdateNewer(remoteVersionCode: Int?, remoteVersionName: String?): Boolean {
    if (remoteVersionCode != null) return remoteVersionCode > BuildConfig.VERSION_CODE
    // Le backend rejette déjà une réponse sans versionCode (AppUpdateChecker), et
    // le parseur JSON refuse de construire un AppUpdateInfo sans lui. Ce repli ne
    // sert donc qu'aux caches DataStore écrits par une version antérieure de l'app.
    return compareVersionNames(remoteVersionName.orEmpty(), BuildConfig.VERSION_NAME) > 0
}

/**
 * Comparaison de type semver, tolérante aux suffixes.
 *
 * « 1.3.37-beta1 » et « 1.3.37-m535 » valent tous deux 1.3.37 — l'ancien parseur
 * les lisait 1.3.0, parce que `"37-beta1".toIntOrNull()` vaut null. Le suffixe `-m<N>`
 * est présent sur TOUS les builds mobile (versionNameSuffix dans build.gradle.kts),
 * donc le défaut ne touchait pas que les bêtas.
 *
 * À égalité numérique, un nom suffixé est considéré ANTÉRIEUR au même nom sans
 * suffixe : 1.3.37-beta1 < 1.3.37, comme le veut la convention pré-release semver.
 */
internal fun compareVersionNames(left: String, right: String): Int {
    val leftParts = splitVersionName(left)
    val rightParts = splitVersionName(right)
    val length = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val leftValue = leftParts.getOrElse(index) { 0 }
        val rightValue = rightParts.getOrElse(index) { 0 }
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }
    // Numériquement identiques : celui qui porte un suffixe pré-release perd.
    val leftPre = hasPreReleaseSuffix(left)
    val rightPre = hasPreReleaseSuffix(right)
    return when {
        leftPre == rightPre -> 0
        leftPre -> -1
        else -> 1
    }
}

private fun splitVersionName(name: String): List<Int> =
    name.trim().removePrefix("v").split('.').map { part ->
        part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
    }

private fun hasPreReleaseSuffix(name: String): Boolean =
    name.trim().removePrefix("v").split('.').any { part ->
        part.isNotEmpty() && part.dropWhile(Char::isDigit).isNotEmpty()
    }
