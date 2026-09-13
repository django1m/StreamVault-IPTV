package com.streamvault.app.vpn

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.vpn.VpnRegionHealthPolicy.Decision
import com.streamvault.app.vpn.VpnRegionHealthPolicy.Event
import com.streamvault.app.vpn.VpnRegionHealthPolicy.FailureKind
import com.streamvault.app.vpn.VpnRegionHealthPolicy.State
import org.junit.Test

/**
 * Pure-logic tests for the conservative region-fallback state machine. The
 * clock is a mutable long so time-based hysteresis is exercised deterministically.
 */
class VpnRegionHealthPolicyTest {

    private class Clock(var now: Long = 1_000_000L) { val src: () -> Long = { now } }

    private fun policy(
        clock: Clock = Clock(),
        initial: PersistedGuardState = PersistedGuardState(),
    ) = VpnRegionHealthPolicy(clock.src, initial) to clock

    private val R = "nl_amsterdam"

    /** Bring the policy to HEALTHY on region R. */
    private fun VpnRegionHealthPolicy.healthy(region: String = "nl_amsterdam") {
        onEvent(Event.ServerAssignedRegion(region))
        onEvent(Event.VerifiedUp(region))
    }

    // ---- Live blackhole (2xx accepted, zero bytes delivered) ---------------

    @Test
    fun `a single starved live channel does not rotate`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:100"))).isEqualTo(Decision.None)
        assertThat(p.state()).isEqualTo(State.BLOCK_SUSPECT)
    }

    @Test
    fun `zapping the SAME starved channel twice does not rotate`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:100"))).isEqualTo(Decision.None)
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:100"))).isEqualTo(Decision.None)
    }

    @Test
    fun `two DISTINCT starved live channels rotate the egress and interrupt live`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:100"))).isEqualTo(Decision.None)
        val d = p.onEvent(Event.LiveBlackhole(R, "1:101"))
        assertThat(d).isInstanceOf(Decision.RequestEgressRotation::class.java)
        d as Decision.RequestEgressRotation
        assertThat(d.failedRegion).isEqualTo(R)
        assertThat(d.interruptLive).isTrue()
        assertThat(d.evidenceSummary).contains("blackholed")
    }

    @Test
    fun `a live success between two starved channels prevents the rotation`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveBlackhole(R, "1:100"))
        p.onEvent(Event.LiveSuccess(R))          // bytes flowed → the exit delivers
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:101"))).isEqualTo(Decision.None)
    }

    @Test
    fun `starved channels outside the window do not add up`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.LiveBlackhole(R, "1:100"))
        clock.now += VpnRegionHealthPolicy.BLACKHOLE_WINDOW_MS + 1
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:101"))).isEqualTo(Decision.None)
    }

    @Test
    fun `a blackhole never triggers a region fallback`() {
        val (p, clock) = policy(); p.healthy()
        // Far more evidence than CLASS_B_DISTINCT, spaced inside the window.
        repeat(6) { i ->
            p.onEvent(Event.LiveBlackhole(R, "1:20$i"))
            clock.now += 1_000
            // Keep the rotation budget from masking a fallback: acknowledge each
            // dispatched rotation, which also clears the evidence.
            p.onEvent(Event.EgressRotationResult(true))
        }
        assertThat(p.state()).isNotEqualTo(State.FALLBACK_PENDING)
    }

    @Test
    fun `blackhole evidence is dropped right after a network change`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.NetworkChanged)
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:100"))).isEqualTo(Decision.None)
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:101"))).isEqualTo(Decision.None)
    }

    @Test
    fun `a starved channel from a stale region is ignored`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveBlackhole("belgium", "1:100"))
        assertThat(p.onEvent(Event.LiveBlackhole("belgium", "1:101"))).isEqualTo(Decision.None)
    }

    @Test
    fun `an undispatched blackhole rotation refunds its slot`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.LiveBlackhole(R, "1:100"))
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:101")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
        p.onEvent(Event.EgressRotationResult(false)) // deferred / transport failure
        // Slot refunded AND evidence kept → the very next starved channel retries
        // instead of stranding the box on the blackholed exit.
        clock.now += VpnRegionHealthPolicy.EGRESS_ROTATE_MIN_SPACING_MS
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:102")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
    }

    @Test
    fun `the rotation budget caps repeated blackholes`() {
        val (p, clock) = policy(); p.healthy()
        repeat(VpnRegionHealthPolicy.MAX_EGRESS_ROTATES_PER_WINDOW) { round ->
            p.onEvent(Event.LiveBlackhole(R, "1:${round}0"))
            assertThat(p.onEvent(Event.LiveBlackhole(R, "1:${round}1")))
                .isInstanceOf(Decision.RequestEgressRotation::class.java)
            p.onEvent(Event.EgressRotationResult(true))
            clock.now += VpnRegionHealthPolicy.EGRESS_ROTATE_MIN_SPACING_MS
        }
        p.onEvent(Event.LiveBlackhole(R, "1:90"))
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:91"))).isEqualTo(Decision.None)
    }

    @Test
    fun `a tunnel reconnect between two starved channels prevents the rotation`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveBlackhole(R, "1:100"))
        p.onEvent(Event.VerifiedUp(R))           // link flapped, tunnel came back
        assertThat(p.onEvent(Event.LiveBlackhole(R, "1:101"))).isEqualTo(Decision.None)
    }

    @Test
    fun `a flapping link never rotates however long it flaps`() {
        val (p, clock) = policy(); p.healthy()
        repeat(12) { i ->
            p.onEvent(Event.VerifiedUp(R))
            assertThat(p.onEvent(Event.LiveBlackhole(R, "1:30$i"))).isEqualTo(Decision.None)
            clock.now += 30_000
        }
    }

    @Test
    fun `a tunnel reconnect does NOT clear on-demand 403 evidence`() {
        // The asymmetry that makes the blackhole clearing safe: a 403 is the
        // provider's decision about the exit and survives a reconnect.
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.VerifiedUp(R))
        assertThat(p.onEvent(Event.Enforced403(R, "1:11")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
    }

    // ---- Class B (region banned for on-demand) ----------------------------

    @Test
    fun `a single enforced 403 does nothing`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.Enforced403(R, "1:10"))).isEqualTo(Decision.None)
        assertThat(p.state()).isEqualTo(State.BLOCK_SUSPECT)
    }

    @Test
    fun `two 403s on the SAME content do not rotate`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.Enforced403(R, "1:10"))).isEqualTo(Decision.None)
        assertThat(p.onEvent(Event.Enforced403(R, "1:10"))).isEqualTo(Decision.None) // same key
    }

    @Test
    fun `two DISTINCT 403s without live proof rotate the egress (VOD-only escape)`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.Enforced403(R, "1:10"))).isEqualTo(Decision.None)
        val d = p.onEvent(Event.Enforced403(R, "1:11"))
        assertThat(d).isInstanceOf(Decision.RequestEgressRotation::class.java)
        assertThat((d as Decision.RequestEgressRotation).failedRegion).isEqualTo(R)
    }

    @Test
    fun `with live proof, two distinct 403s do NOT region-fallback (rotation gated off)`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))
        assertThat(p.onEvent(Event.Enforced403(R, "1:10"))).isEqualTo(Decision.None)
        assertThat(p.onEvent(Event.Enforced403(R, "1:11"))).isEqualTo(Decision.None) // needs 3
        assertThat(p.state()).isEqualTo(State.BLOCK_SUSPECT)
    }

    @Test
    fun `three distinct 403s plus live proof triggers BLOCKED fallback`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))                 // live works → selective block
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:11"))
        val d = p.onEvent(Event.Enforced403(R, "1:12"))
        assertThat(d).isInstanceOf(Decision.RequestFallback::class.java)
        assertThat((d as Decision.RequestFallback).kind).isEqualTo(FailureKind.BLOCKED)
        assertThat(d.failedRegion).isEqualTo(R)
        assertThat(p.state()).isEqualTo(State.FALLBACK_PENDING)
    }

    @Test
    fun `three 403s on the SAME content count once - no fallback`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:10"))
        val d = p.onEvent(Event.Enforced403(R, "1:10"))
        assertThat(d).isEqualTo(Decision.None)
        assertThat(p.state()).isEqualTo(State.BLOCK_SUSPECT)
    }

    @Test
    fun `egress rotation is spaced - a fresh burst too soon does not rotate again`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.Enforced403(R, "1:10"))
        assertThat(p.onEvent(Event.Enforced403(R, "1:11")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
        p.onEvent(Event.EgressRotationResult(true))          // manager dispatched → evidence cleared
        // A fresh burst 3 min later (< 5 min spacing): no rotation. Push to 3 distinct
        // so it also falls through to the confirmatory-live-probe path.
        clock.now += 3 * 60_000L
        p.onEvent(Event.Enforced403(R, "2:10"))
        p.onEvent(Event.Enforced403(R, "2:11"))
        val d = p.onEvent(Event.Enforced403(R, "2:12"))
        assertThat(d).isInstanceOf(Decision.RequestConfirmatoryLiveProbe::class.java)
    }

    @Test
    fun `egress rotation is capped per window`() {
        val (p, clock) = policy(); p.healthy()
        repeat(VpnRegionHealthPolicy.MAX_EGRESS_ROTATES_PER_WINDOW) { i ->
            p.onEvent(Event.Enforced403(R, "$i:10"))
            assertThat(p.onEvent(Event.Enforced403(R, "$i:11")))
                .isInstanceOf(Decision.RequestEgressRotation::class.java)
            p.onEvent(Event.EgressRotationResult(true))      // dispatched → slot kept, evidence cleared
            clock.now += VpnRegionHealthPolicy.EGRESS_ROTATE_MIN_SPACING_MS + 1_000L
        }
        // Cap reached (still spaced) → 3 distinct now falls back to waiting for live.
        p.onEvent(Event.Enforced403(R, "x:10"))
        p.onEvent(Event.Enforced403(R, "x:11"))
        assertThat(p.onEvent(Event.Enforced403(R, "x:12")))
            .isInstanceOf(Decision.RequestConfirmatoryLiveProbe::class.java)
    }

    @Test
    fun `an aborted rotation refunds the slot and keeps evidence`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.Enforced403(R, "1:10"))
        assertThat(p.onEvent(Event.Enforced403(R, "1:11")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)   // slot reserved
        p.onEvent(Event.EgressRotationResult(false))                    // couldn't execute → refund
        // Slot refunded + evidence kept → the next on-demand 403 re-triggers at once,
        // with no 5-min spacing wait (the reserved timestamp was popped).
        assertThat(p.onEvent(Event.Enforced403(R, "1:12")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
    }

    @Test
    fun `a dispatched rotation clears the stale evidence`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:11"))              // rotation reserved
        p.onEvent(Event.EgressRotationResult(true))          // dispatched → evidence cleared
        clock.now += 6 * 60_000L                             // past the 5-min spacing
        assertThat(p.onEvent(Event.Enforced403(R, "2:10"))).isEqualTo(Decision.None) // 1 fresh
        assertThat(p.onEvent(Event.Enforced403(R, "2:11")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)                 // 2 fresh → rotate
    }

    @Test
    fun `live proof present takes precedence over egress rotation`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))               // proof present before the burst
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:11"))       // no rotation (live proof gates it)
        val d = p.onEvent(Event.Enforced403(R, "1:12"))
        assertThat(d).isInstanceOf(Decision.RequestFallback::class.java)
        assertThat((d as Decision.RequestFallback).kind).isEqualTo(FailureKind.BLOCKED)
    }

    @Test
    fun `after a rotation, a fresh burst with live proof escalates to a region fallback`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.Enforced403(R, "1:10"))
        assertThat(p.onEvent(Event.Enforced403(R, "1:11")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
        p.onEvent(Event.EgressRotationResult(true))          // dispatched → evidence cleared
        clock.now += 25 * 60_000L
        p.onEvent(Event.LiveSuccess(R))               // now the box has live proof
        p.onEvent(Event.Enforced403(R, "2:10"))
        p.onEvent(Event.Enforced403(R, "2:11"))
        val d = p.onEvent(Event.Enforced403(R, "2:12"))
        assertThat(d).isInstanceOf(Decision.RequestFallback::class.java)
        assertThat((d as Decision.RequestFallback).kind).isEqualTo(FailureKind.BLOCKED)
    }

    @Test
    fun `region reassignment re-arms egress rotation`() {
        val (p, clock) = policy(); p.healthy()
        repeat(VpnRegionHealthPolicy.MAX_EGRESS_ROTATES_PER_WINDOW) { i ->
            p.onEvent(Event.Enforced403(R, "$i:10"))
            p.onEvent(Event.Enforced403(R, "$i:11"))
            p.onEvent(Event.EgressRotationResult(true))
            clock.now += VpnRegionHealthPolicy.EGRESS_ROTATE_MIN_SPACING_MS + 1_000L
        }
        // Server reassigns to a different region → egress history resets.
        p.onEvent(Event.ServerAssignedRegion("france"))
        p.onEvent(Event.VerifiedUp("france"))
        p.onEvent(Event.Enforced403("france", "9:10"))
        assertThat(p.onEvent(Event.Enforced403("france", "9:11")))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
    }

    @Test
    fun `live 403 through a verified tunnel rotates the egress (exit likely burned)`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:11"))
        // Live 403s too → not necessarily provider-wide: the current exit IP may be
        // burned (e.g. by a catalog-sync burst). A fresh exit is the fix, so rotate.
        val d = p.onEvent(Event.LiveProbe403)
        assertThat(d).isInstanceOf(Decision.RequestEgressRotation::class.java)
        assertThat((d as Decision.RequestEgressRotation).interruptLive).isTrue()
        assertThat(d.failedRegion).isEqualTo(R)
    }

    @Test
    fun `a second live 403 within the rotation spacing does nothing (budget spent)`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.LiveProbe403))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
        p.onEvent(Event.EgressRotationResult(true))   // dispatched → fresh exit pinned
        // Another live 403 immediately after: the 5-min min-spacing has not elapsed
        // → no rotation. Treat it as genuinely provider-wide and sit still.
        assertThat(p.onEvent(Event.LiveProbe403)).isEqualTo(Decision.None)
        assertThat(p.state()).isEqualTo(State.HEALTHY)
    }

    @Test
    fun `live 403 with no rotation budget left wipes evidence and returns to healthy`() {
        val (p, clock) = policy(); p.healthy()
        // Spend the whole per-window rotation budget on live 403s, 5 min apart.
        repeat(VpnRegionHealthPolicy.MAX_EGRESS_ROTATES_PER_WINDOW) {
            assertThat(p.onEvent(Event.LiveProbe403))
                .isInstanceOf(Decision.RequestEgressRotation::class.java)
            p.onEvent(Event.EgressRotationResult(true))
            clock.now += VpnRegionHealthPolicy.EGRESS_ROTATE_MIN_SPACING_MS + 1
        }
        // Budget exhausted → provider-wide conclusion: do nothing, back to healthy,
        // and any accumulated Class-B evidence is wiped.
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:11"))
        assertThat(p.onEvent(Event.LiveProbe403)).isEqualTo(Decision.None)
        assertThat(p.state()).isEqualTo(State.HEALTHY)
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.Enforced403(R, "1:10"))
        assertThat(p.state()).isEqualTo(State.BLOCK_SUSPECT)   // evidence was wiped
    }

    @Test
    fun `enforced 403 for a different region is ignored (stale)`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.Enforced403("other", "1:10"))
        p.onEvent(Event.Enforced403("other", "1:11"))
        val d = p.onEvent(Event.Enforced403("other", "1:12"))
        assertThat(d).isEqualTo(Decision.None)
    }

    @Test
    fun `evidence older than the window expires`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:11"))
        clock.now += VpnRegionHealthPolicy.CLASS_B_WINDOW_MS + 1  // both expire
        p.onEvent(Event.LiveSuccess(R))
        val d = p.onEvent(Event.Enforced403(R, "1:12"))
        assertThat(d).isEqualTo(Decision.None)                   // only 1 fresh in window
    }

    @Test
    fun `evidence during the network settle window is dropped`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.NetworkChanged)
        p.onEvent(Event.Enforced403(R, "1:10"))   // within settle window → dropped
        p.onEvent(Event.Enforced403(R, "1:11"))
        val d = p.onEvent(Event.Enforced403(R, "1:12"))
        assertThat(d).isEqualTo(Decision.None)
    }

    // ---- Class A (tunnel/region dead) -------------------------------------

    private val SPACE = VpnRegionHealthPolicy.CLASS_A_MIN_SPACING_MS + 1

    @Test
    fun `class A - three failed cycles trigger a TUNNEL fallback with pacing`() {
        val (p, clock) = policy(); p.healthy()
        val d1 = p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        assertThat(d1).isEqualTo(Decision.ScheduleRetryCycle(VpnRegionHealthPolicy.CLASS_A_RETRY_DELAYS_MS[0]))
        clock.now += SPACE
        val d2 = p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        assertThat(d2).isEqualTo(Decision.ScheduleRetryCycle(VpnRegionHealthPolicy.CLASS_A_RETRY_DELAYS_MS[1]))
        clock.now += SPACE
        val d3 = p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        assertThat(d3).isInstanceOf(Decision.RequestFallback::class.java)
        assertThat((d3 as Decision.RequestFallback).kind).isEqualTo(FailureKind.TUNNEL)
    }

    @Test
    fun `class A - a too-soon cycle is rescheduled, not counted`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))       // count=1
        // Two more within the min-spacing window: rescheduled, not counted.
        val d = p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        assertThat(d).isInstanceOf(Decision.ScheduleRetryCycle::class.java)
        assertThat(p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true)))
            .isInstanceOf(Decision.ScheduleRetryCycle::class.java)
        // Only after the spacing elapses does a cycle count toward the fallback.
        clock.now += SPACE
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))       // count=2
        clock.now += SPACE
        assertThat(p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true)))
            .isInstanceOf(Decision.RequestFallback::class.java)
    }

    @Test
    fun `class A cycle with plain network DOWN is not counted`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = false))
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = false))
        val d = p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = false))
        assertThat(d).isInstanceOf(Decision.ScheduleRetryCycle::class.java)  // never escalates
    }

    @Test
    fun `verified up mid-suspect resets the Class A counter`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        clock.now += SPACE
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        p.onEvent(Event.VerifiedUp(R))                        // recovered
        assertThat(p.state()).isEqualTo(State.HEALTHY)
        // Counter reset: it takes 3 fresh failures again.
        clock.now += SPACE
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        clock.now += SPACE
        val d = p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        assertThat(d).isEqualTo(Decision.ScheduleRetryCycle(VpnRegionHealthPolicy.CLASS_A_RETRY_DELAYS_MS[1]))
    }

    // ---- Cooldowns, terminal, resets --------------------------------------

    @Test
    fun `granted fallback enters a cooldown that blocks new fallbacks`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.FallbackResult(granted = true, newRegion = "france"))
        assertThat(p.state()).isEqualTo(State.COOLDOWN)
        // New Class B evidence during cooldown is ignored.
        p.onEvent(Event.LiveSuccess("france"))
        p.onEvent(Event.Enforced403("france", "1:10"))
        p.onEvent(Event.Enforced403("france", "1:11"))
        val d = p.onEvent(Event.Enforced403("france", "1:12"))
        assertThat(d).isEqualTo(Decision.None)
        // After the cooldown elapses, evidence accrues again.
        clock.now += VpnRegionHealthPolicy.FALLBACK_COOLDOWN_MS + 1
        p.onEvent(Event.VerifiedUp("france"))
        assertThat(p.state()).isEqualTo(State.HEALTHY)
    }

    @Test
    fun `denied fallback enters a longer cooldown`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.FallbackResult(granted = false, newRegion = null))
        assertThat(p.state()).isEqualTo(State.COOLDOWN)
        clock.now += VpnRegionHealthPolicy.FALLBACK_COOLDOWN_MS + 1  // 6h < 12h deny cooldown
        // Still cooling down at 6h+1: evidence ignored.
        p.onEvent(Event.LiveSuccess(R))
        p.onEvent(Event.Enforced403(R, "1:10"))
        p.onEvent(Event.Enforced403(R, "1:11"))
        assertThat(p.onEvent(Event.Enforced403(R, "1:12"))).isEqualTo(Decision.None)
    }

    @Test
    fun `third granted fallback in a week goes TERMINAL`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.FallbackResult(granted = true, newRegion = "a"))
        p.onEvent(Event.FallbackResult(granted = true, newRegion = "b"))
        val d = p.onEvent(Event.FallbackResult(granted = true, newRegion = "c"))
        assertThat(d).isInstanceOf(Decision.EnterTerminal::class.java)
        assertThat(p.state()).isEqualTo(State.TERMINAL)
        // TERMINAL absorbs further evidence...
        p.onEvent(Event.LiveSuccess("c"))
        p.onEvent(Event.Enforced403("c", "1:10"))
        p.onEvent(Event.Enforced403("c", "1:11"))
        assertThat(p.onEvent(Event.Enforced403("c", "1:12"))).isEqualTo(Decision.None)
    }

    @Test
    fun `restart adoption does NOT wipe persisted TERMINAL`() {
        // Fresh construction (currentRegion == null). The first heartbeat region
        // is an ADOPTION after restart, not a reassignment — TERMINAL must survive.
        val (p, _) = policy(clock = Clock(), initial = PersistedGuardState(terminal = true))
        assertThat(p.state()).isEqualTo(State.TERMINAL)
        val d = p.onEvent(Event.ServerAssignedRegion("nl_amsterdam"))
        assertThat(d).isEqualTo(Decision.None)
        assertThat(p.state()).isEqualTo(State.TERMINAL)
    }

    @Test
    fun `a genuine region change (after adoption) resets out of TERMINAL`() {
        val (p, _) = policy(clock = Clock(), initial = PersistedGuardState(terminal = true))
        p.onEvent(Event.ServerAssignedRegion("nl_amsterdam"))   // adopt (stays terminal)
        assertThat(p.state()).isEqualTo(State.TERMINAL)
        val d = p.onEvent(Event.ServerAssignedRegion("france"))  // real reassignment
        assertThat(d).isEqualTo(Decision.ResetToIdle)
        assertThat(p.state()).isEqualTo(State.IDLE)
    }

    @Test
    fun `fallback transport failure applies a short cooldown, not the 12h denial`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.FallbackAborted)
        assertThat(p.state()).isEqualTo(State.COOLDOWN)
        // Short cooldown elapses well before the 12h deny cooldown would.
        clock.now += VpnRegionHealthPolicy.FALLBACK_ABORT_COOLDOWN_MS + 1
        p.onEvent(Event.VerifiedUp(R))
        assertThat(p.state()).isEqualTo(State.HEALTHY)
    }

    @Test
    fun `admin vpn cycle resets everything`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.FallbackResult(granted = false, newRegion = null))  // cooldown
        val d = p.onEvent(Event.AdminVpnCycleReset)
        assertThat(d).isEqualTo(Decision.ResetToIdle)
        assertThat(p.state()).isEqualTo(State.IDLE)
    }

    @Test
    fun `persisted cooldown survives reconstruction`() {
        val clock = Clock()
        val until = clock.now + 5 * 3_600_000L
        val (p, _) = policy(clock = clock, initial = PersistedGuardState(cooldownUntilMs = until))
        assertThat(p.state()).isEqualTo(State.COOLDOWN)
        val snap = p.persisted()
        assertThat(snap.cooldownUntilMs).isEqualTo(until)
    }

    @Test
    fun `same-region server assignment does not reset`() {
        val (p, clock) = policy(); p.healthy()
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))  // count=1
        val d = p.onEvent(Event.ServerAssignedRegion(R))                    // same region
        assertThat(d).isEqualTo(Decision.None)
        // Counter preserved: two more (paced) failures escalate to fallback.
        clock.now += SPACE
        p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        clock.now += SPACE
        val d3 = p.onEvent(Event.VerificationCycleFailed(R, plainNetworkOk = true))
        assertThat(d3).isInstanceOf(Decision.RequestFallback::class.java)
    }

    // ---- Panel login 403 (box stuck on the activation screen) --------------

    @Test
    fun `a single panel login 403 rotates the egress immediately`() {
        val (p, _) = policy(); p.healthy()
        val d = p.onEvent(Event.PanelLogin403)
        assertThat(d).isInstanceOf(Decision.RequestEgressRotation::class.java)
        val r = d as Decision.RequestEgressRotation
        assertThat(r.failedRegion).isEqualTo(R)
        // Never tears a working live for a control-plane failure.
        assertThat(r.interruptLive).isFalse()
    }

    @Test
    fun `panel login 403 needs no live proof and no distinct-content evidence`() {
        val (p, _) = policy(); p.healthy()
        // No LiveSuccess, no Enforced403 beforehand — the login refusal stands alone.
        assertThat(p.onEvent(Event.PanelLogin403))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
    }

    @Test
    fun `panel login 403 ignores the region-fallback cooldown`() {
        // A device that just fell back sits in a 6 h cooldown. If its exit is burned
        // it cannot log in at all — honouring that cooldown would freeze it for 6 h.
        val clock = Clock()
        val (p, _) = policy(clock = clock, initial = PersistedGuardState(cooldownUntilMs = clock.now + 6 * 3_600_000L))
        p.onEvent(Event.ServerAssignedRegion(R))
        assertThat(p.state()).isEqualTo(State.COOLDOWN)
        assertThat(p.onEvent(Event.PanelLogin403))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
    }

    @Test
    fun `panel login 403 is ignored inside the network settle window`() {
        val (p, _) = policy(); p.healthy()
        p.onEvent(Event.NetworkChanged)
        // Right after a Wi-Fi transition a 403 proves nothing.
        assertThat(p.onEvent(Event.PanelLogin403)).isEqualTo(Decision.None)
    }

    @Test
    fun `panel login 403 respects the bounded rotation budget`() {
        val (p, clock) = policy(); p.healthy()
        repeat(VpnRegionHealthPolicy.MAX_EGRESS_ROTATES_PER_WINDOW) {
            assertThat(p.onEvent(Event.PanelLogin403))
                .isInstanceOf(Decision.RequestEgressRotation::class.java)
            p.onEvent(Event.EgressRotationResult(dispatched = true))
            clock.now += VpnRegionHealthPolicy.EGRESS_ROTATE_MIN_SPACING_MS + 1_000L
        }
        // Budget exhausted: several fresh exits still refuse the login → provider-wide.
        assertThat(p.onEvent(Event.PanelLogin403)).isEqualTo(Decision.None)
    }

    @Test
    fun `panel login 403 twice in a row does not double-spend the budget`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.PanelLogin403))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
        // The screen's auto-retry fires again before the manager reports back: the
        // 5-min spacing must swallow it instead of burning a second slot.
        assertThat(p.onEvent(Event.PanelLogin403)).isEqualTo(Decision.None)
    }

    @Test
    fun `an aborted panel-login rotation refunds its slot`() {
        val (p, _) = policy(); p.healthy()
        assertThat(p.onEvent(Event.PanelLogin403))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
        // Transport failure: the exit never changed, so the box must keep the budget
        // to try again rather than stay stranded on the burned exit.
        p.onEvent(Event.EgressRotationResult(dispatched = false))
        assertThat(p.onEvent(Event.PanelLogin403))
            .isInstanceOf(Decision.RequestEgressRotation::class.java)
    }

    @Test
    fun `panel login 403 before any region assignment does nothing`() {
        val (p, _) = policy()
        // Never provisioned → no exit to rotate (and the 403 is the residential block).
        assertThat(p.onEvent(Event.PanelLogin403)).isEqualTo(Decision.None)
    }
}
