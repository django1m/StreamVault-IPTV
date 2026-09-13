package com.streamvault.app.update

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.BuildConfig
import org.junit.Test

/**
 * Régression : une bêta (versionCode 525, versionName "1.3.37-beta1") se voyait
 * proposer la stable ANTÉRIEURE 524 / "1.3.36", parce que l'ancien comparateur
 * lisait "37-beta1" comme 0 et retombait dessus dès que le versionCode disait non.
 */
class AppVersionCompareTest {

    // ── isAppUpdateNewer : le versionCode fait autorité ──────────────────────

    @Test
    fun `a lower versionCode is never an update, whatever the version name says`() {
        // Le cas signalé : stable 1.3.36 (524) offerte à la bêta 1.3.37-beta1 (525).
        assertThat(isAppUpdateNewer(BuildConfig.VERSION_CODE - 1, "9.9.9")).isFalse()
    }

    @Test
    fun `the same versionCode is not an update`() {
        assertThat(isAppUpdateNewer(BuildConfig.VERSION_CODE, "9.9.9")).isFalse()
    }

    @Test
    fun `a higher versionCode is an update, even with an older-looking name`() {
        assertThat(isAppUpdateNewer(BuildConfig.VERSION_CODE + 1, "0.0.1")).isTrue()
    }

    @Test
    fun `a null versionCode falls back to the name comparison`() {
        assertThat(isAppUpdateNewer(null, "0.0.1")).isFalse()
        assertThat(isAppUpdateNewer(null, "999.0.0")).isTrue()
        assertThat(isAppUpdateNewer(null, null)).isFalse()
    }

    // ── compareVersionNames : tolérant aux suffixes ──────────────────────────

    @Test
    fun `a beta suffix no longer zeroes its component`() {
        // L'ancien parseur lisait "1.3.37-beta1" comme 1.3.0, donc 1.3.36 gagnait.
        assertThat(compareVersionNames("1.3.36", "1.3.37-beta1")).isLessThan(0)
        assertThat(compareVersionNames("1.3.38", "1.3.37-beta1")).isGreaterThan(0)
    }

    @Test
    fun `the mobile versionNameSuffix is parsed too`() {
        // Tous les builds mobile portent -m<N> : le bug ne touchait pas que les bêtas.
        assertThat(compareVersionNames("1.3.37-m535", "1.3.36-m534")).isGreaterThan(0)
        assertThat(compareVersionNames("1.3.37-m535", "1.3.37-m534")).isEqualTo(0)
    }

    @Test
    fun `a pre-release ranks below the same release without a suffix`() {
        assertThat(compareVersionNames("1.3.37-beta1", "1.3.37")).isLessThan(0)
        assertThat(compareVersionNames("1.3.37", "1.3.37-beta1")).isGreaterThan(0)
        assertThat(compareVersionNames("1.3.37-beta1", "1.3.37-beta2")).isEqualTo(0)
    }

    @Test
    fun `plain semver still compares as before`() {
        assertThat(compareVersionNames("1.3.37", "1.3.36")).isGreaterThan(0)
        assertThat(compareVersionNames("1.3.37", "1.3.37")).isEqualTo(0)
        assertThat(compareVersionNames("1.4.0", "1.3.99")).isGreaterThan(0)
        assertThat(compareVersionNames("2.0.0", "1.99.99")).isGreaterThan(0)
    }

    @Test
    fun `a leading v and missing components are tolerated`() {
        assertThat(compareVersionNames("v1.3.37", "1.3.37")).isEqualTo(0)
        assertThat(compareVersionNames("1.4", "1.3.99")).isGreaterThan(0)
        assertThat(compareVersionNames("1.3", "1.3.0")).isEqualTo(0)
        assertThat(compareVersionNames("", "0.0.0")).isEqualTo(0)
    }
}
