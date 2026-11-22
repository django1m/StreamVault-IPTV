package com.streamvault.app.vpn

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.vpn.TunnelDataPlaneWatchdog.Companion.MIN_FAILURE_WINDOW_MS
import com.streamvault.app.vpn.TunnelDataPlaneWatchdog.Companion.RECOVERY_COOLDOWN_MS
import org.junit.Test

/**
 * The watchdog must recognise ONE situation: a tunnel whose WireGuard handshakes
 * keep succeeding while nothing else gets through (the app blackholed behind a
 * tunnel it believes healthy). Everything that merely looks similar — a Wi-Fi
 * hole, an outage of our backend, playback still flowing — must never trip it.
 */
class TunnelDataPlaneWatchdogTest {

    private class Clock(var now: Long = 10_000_000L)

    private val clock = Clock()
    private val watchdog = TunnelDataPlaneWatchdog { clock.now }
    private val t0 = clock.now

    /** One failed backend call at [atMs]; the last handshake happened at [handshakeAtMs] (null = never). */
    private fun failAt(atMs: Long, handshakeAtMs: Long?, rx: Long): Boolean {
        clock.now = atMs
        val age = handshakeAtMs?.let { (atMs - it) / 1000 }
        return watchdog.onBackendTransportFailure(handshakeAgeSec = age, rxBytes = rx)
    }

    /**
     * Foreground cadence: a failure every 10 s for [seconds]. WireGuard re-handshakes
     * about every 2 min while it sends, so a working UDP path shows a handshake at
     * t0+120 s. Returns the first elapsed second at which the watchdog tripped, or null.
     */
    private fun failEvery10s(
        seconds: Int,
        handshakeAt: (elapsedMs: Long) -> Long? = { if (it >= 120_000) t0 + 120_000 else t0 - 30_000 },
        rx: (elapsedMs: Long) -> Long = { 5_000L + it / 10_000 * 32 },
    ): Int? {
        var elapsed = 0L
        while (elapsed <= seconds * 1000L) {
            if (failAt(t0 + elapsed, handshakeAt(elapsed), rx(elapsed))) return (elapsed / 1000).toInt()
            elapsed += 10_000
        }
        return null
    }

    @Test
    fun `a single transport failure never trips`() {
        assertThat(failAt(t0, handshakeAtMs = t0, rx = 5_000)).isFalse()
    }

    @Test
    fun `failures inside the minimum window never trip even with a fresh handshake`() {
        assertThat(failAt(t0, t0 - 1_000, 5_000)).isFalse()
        assertThat(failAt(t0 + 10_000, t0 + 5_000, 5_000)).isFalse()
        assertThat(failAt(t0 + 20_000, t0 + 15_000, 5_000)).isFalse()
    }

    @Test
    fun `handshakes that keep succeeding while nothing gets through trip the watchdog`() {
        val trippedAt = failEvery10s(seconds = 300)

        assertThat(trippedAt).isNotNull()
        assertThat(trippedAt!! * 1000L).isAtLeast(MIN_FAILURE_WINDOW_MS)
    }

    @Test
    fun `a link outage with no handshake since the failures began never trips`() {
        assertThat(failEvery10s(seconds = 600, handshakeAt = { t0 - 30_000 })).isNull()
    }

    @Test
    fun `a tunnel that never handshaked is left to the stale-handshake path`() {
        assertThat(failEvery10s(seconds = 600, handshakeAt = { null })).isNull()
    }

    @Test
    fun `real traffic still flowing through the tunnel prevents tripping`() {
        assertThat(failEvery10s(seconds = 600, rx = { 5_000L + it * 20 })).isNull()
    }

    @Test
    fun `a success wipes the accumulated evidence`() {
        failEvery10s(seconds = 140)
        watchdog.onBackendSuccess()

        // Evidence restarts at the next failure: 20 s later is far inside the window.
        assertThat(failAt(t0 + 150_000, t0 + 120_000, 6_000)).isFalse()
        assertThat(failAt(t0 + 160_000, t0 + 120_000, 6_000)).isFalse()
    }

    @Test
    fun `after a recovery the watchdog stays quiet for the cooldown then may trip again`() {
        assertThat(failEvery10s(seconds = 300)).isNotNull()
        watchdog.onRecoveryStarted()

        // Same dead tunnel right after the recovery: evidence builds again but the cooldown holds.
        val restart = clock.now + 10_000
        var tripped = false
        var elapsed = 0L
        while (elapsed <= 300_000) {
            val at = restart + elapsed
            if (failAt(at, if (elapsed >= 120_000) restart + 120_000 else restart - 30_000, 9_000)) tripped = true
            elapsed += 10_000
        }
        assertThat(tripped).isFalse()

        // Past the cooldown, a still-dead tunnel is recognised again.
        val later = restart + RECOVERY_COOLDOWN_MS
        elapsed = 0L
        tripped = false
        while (elapsed <= 300_000 && !tripped) {
            val at = later + elapsed
            tripped = failAt(at, if (elapsed >= 120_000) later + 120_000 else later - 30_000, 9_000)
            elapsed += 10_000
        }
        assertThat(tripped).isTrue()
    }

    @Test
    fun `an rx counter reset after a reconnect is rebased instead of read as a huge drop`() {
        val trippedAt = failEvery10s(seconds = 300, rx = { if (it < 60_000) 1_000_000L else 200L + it / 10_000 * 32 })

        assertThat(trippedAt).isNotNull()
    }
}
