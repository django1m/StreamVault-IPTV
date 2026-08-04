package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MidStreamStallRecoveryPolicyTest {

    private class FakeClock(initial: Long = 0L) {
        var now: Long = initial
        fun advance(ms: Long) { now += ms }
        val source: () -> Long = { now }
    }

    private fun policy(
        maxRestarts: Int = 2,
        cooldownMs: Long = 60_000L,
        clock: FakeClock = FakeClock()
    ): Pair<MidStreamStallRecoveryPolicy, FakeClock> {
        return MidStreamStallRecoveryPolicy(
            maxConsecutiveRestarts = maxRestarts,
            restartCooldownMs = cooldownMs,
            clock = clock.source
        ) to clock
    }

    @Test
    fun `first stall on a healthy network triggers Restart`() {
        val (p, _) = policy()
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.Restart)
    }

    @Test
    fun `no network always returns WaitForNetwork regardless of state`() {
        val (p, _) = policy()
        assertThat(p.decide(networkAvailable = false))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.WaitForNetwork)
        // Even after onRestart() the policy still defers when offline.
        p.onRestart()
        assertThat(p.decide(networkAvailable = false))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.WaitForNetwork)
    }

    @Test
    fun `second decide within cooldown returns WaitForCooldown`() {
        val (p, clock) = policy(cooldownMs = 60_000L)
        p.onRestart()
        clock.advance(30_000L)
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.WaitForCooldown)
    }

    @Test
    fun `decide allows Restart once cooldown elapsed`() {
        val (p, clock) = policy(cooldownMs = 60_000L)
        p.onRestart()
        clock.advance(60_000L)
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.Restart)
    }

    @Test
    fun `reaching maxConsecutiveRestarts returns Fallback`() {
        val (p, clock) = policy(maxRestarts = 2, cooldownMs = 1_000L)
        p.onRestart()
        clock.advance(2_000L)
        p.onRestart()
        clock.advance(2_000L)
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.Fallback)
    }

    @Test
    fun `onPlaybackRecovered clears the consecutive-restart counter`() {
        val (p, clock) = policy(maxRestarts = 2, cooldownMs = 1_000L)
        p.onRestart()
        clock.advance(2_000L)
        p.onRestart()
        clock.advance(2_000L)
        // Without recovery: Fallback.
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.Fallback)
        p.onPlaybackRecovered()
        // After recovery, the budget is restored.
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.Restart)
    }

    @Test
    fun `onPlaybackRecovered does not reset the cooldown timer`() {
        val (p, clock) = policy(maxRestarts = 5, cooldownMs = 60_000L)
        p.onRestart()
        clock.advance(10_000L)
        p.onPlaybackRecovered()
        // Stream recovered briefly, but if it stalls again right away the
        // cooldown from the previous restart should still apply — we do not
        // want a "READY for 50ms then BUFFERING again" oscillation to bypass
        // the 60s cooldown and thrash the panel.
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.WaitForCooldown)
    }

    @Test
    fun `reset clears both counter and cooldown timer`() {
        val (p, _) = policy(maxRestarts = 2, cooldownMs = 60_000L)
        p.onRestart()
        p.onRestart()
        p.reset()
        // After a full reset (e.g. user manually changed channel) the policy
        // behaves like a fresh instance.
        assertThat(p.decide(networkAvailable = true))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.Restart)
    }

    @Test
    fun `network priority beats cooldown and budget`() {
        val (p, clock) = policy(maxRestarts = 2, cooldownMs = 60_000L)
        p.onRestart()
        p.onRestart()
        clock.advance(120_000L)
        // Even though cooldown elapsed AND budget would say Fallback, no
        // network must short-circuit first so we do not announce failure
        // while the user is just temporarily offline.
        assertThat(p.decide(networkAvailable = false))
            .isEqualTo(MidStreamStallRecoveryPolicy.Action.WaitForNetwork)
    }
}
