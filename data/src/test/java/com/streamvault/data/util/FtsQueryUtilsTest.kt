package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FtsQueryUtilsTest {

    /**
     * Régression : les jetons sont séparés par une espace (AND implicite), jamais par le mot-clé
     * `AND`. Le SQLite d'Android utilise la *standard query syntax*, où « AND » est un terme
     * ordinaire — `jason* AND bourne*` n'y remontait donc aucun film.
     */
    @Test
    fun `multi word query uses implicit AND, never the AND keyword`() {
        val ftsQuery = "jason bourne".toFtsPrefixQuery()

        assertThat(ftsQuery).isEqualTo("jason* bourne*")
        assertThat(ftsQuery).doesNotContain("AND")
    }

    @Test
    fun `query is trimmed, lowercased and collapsed`() {
        assertThat("  Jason   BOURNE  ".toFtsPrefixQuery()).isEqualTo("jason* bourne*")
    }

    @Test
    fun `single token query stays a prefix query`() {
        assertThat("jason".toFtsPrefixQuery()).isEqualTo("jason*")
    }

    /** L'apostrophe sépare les termes dans l'index : « l'été » doit chercher `ete`, pas `lete`. */
    @Test
    fun `punctuation splits tokens like the fts tokenizer does`() {
        assertThat("l'été".toFtsPrefixQuery()).isEqualTo("été*")
        assertThat("harry potter et l'ordre".toFtsPrefixQuery())
            .isEqualTo("harry* potter* et* ordre*")
    }

    @Test
    fun `one letter token is dropped when a longer token carries the search`() {
        assertThat("jason b".toFtsPrefixQuery()).isEqualTo("jason*")
    }

    /** Sinon « 9-1-1 » et « S.W.A.T. » deviendraient des recherches vides. */
    @Test
    fun `titles made only of initials stay searchable`() {
        assertThat("9-1-1".toFtsPrefixQuery()).isEqualTo("9* 1* 1*")
        assertThat("S.W.A.T.".toFtsPrefixQuery()).isEqualTo("s* w* a* t*")
    }

    @Test
    fun `queries without any searchable token return null`() {
        assertThat("".toFtsPrefixQuery()).isNull()
        assertThat("   ".toFtsPrefixQuery()).isNull()
        assertThat("!!!".toFtsPrefixQuery()).isNull()
        assertThat("a".toFtsPrefixQuery()).isNull()
    }

    @Test
    fun `relaxed query ORs the tokens together`() {
        assertThat("jason bourne".toFtsAnyPrefixQuery()).isEqualTo("jason* OR bourne*")
        assertThat("harry potter azkaban".toFtsAnyPrefixQuery())
            .isEqualTo("harry* OR potter* OR azkaban*")
    }

    /** Un seul jeton : le repli serait identique à la requête stricte, inutile de le lancer. */
    @Test
    fun `relaxed query is null when there is nothing to relax`() {
        assertThat("jason".toFtsAnyPrefixQuery()).isNull()
        assertThat("jason b".toFtsAnyPrefixQuery()).isNull()
        assertThat("!!!".toFtsAnyPrefixQuery()).isNull()
    }
}
