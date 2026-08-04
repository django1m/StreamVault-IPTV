package com.streamvault.app.vpn

/**
 * Pure, deterministic state machine deciding when — very rarely — a device
 * should ask the server to move it off its assigned VPN region. No Android
 * imports; the clock is injected so it is fully unit-testable.
 *
 * The whole design is built so that a false fallback is structurally hard:
 *
 *  - NON-SIGNALS ARE UNREPRESENTABLE. There is no event for HTTP 513, 404,
 *    5xx, timeouts, DNS or parse errors. Call sites can only emit the two
 *    real signals ([Event.Enforced403], [Event.VerificationCycleFailed]) plus
 *    context events. "513 is not a ban signal" is enforced by the type, not a
 *    rule someone has to remember.
 *  - Class A (region/tunnel dead): [CLASS_A_CYCLES] consecutive failed
 *    provision+connect+verify cycles, spaced by [CLASS_A_RETRY_DELAYS_MS]
 *    (≥ ~10 min total), and only counted when the plain internet is proven up.
 *  - Class B (region banned for on-demand): [CLASS_B_DISTINCT] DISTINCT movie/
 *    series contents enforced-403 within [CLASS_B_WINDOW_MS], AND a live
 *    success in the same window proving the block is selective (a 403 on live
 *    too means a provider-wide refusal → evidence wiped, no fallback).
 *  - Hysteresis: evidence within [NETWORK_SETTLE_MS] of a connectivity change
 *    is dropped; cooldowns after grant/deny; a lifetime cap of
 *    [MAX_FALLBACKS_PER_WEEK] within a rolling week → [State.TERMINAL].
 *  - Server authority: a heartbeat-delivered region change or an admin vpn
 *    on/off cycle resets everything.
 */
class VpnRegionHealthPolicy(
    private val clock: () -> Long,
    initial: PersistedGuardState,
) {
    enum class State { IDLE, HEALTHY, TUNNEL_SUSPECT, BLOCK_SUSPECT, FALLBACK_PENDING, COOLDOWN, TERMINAL }

    enum class FailureKind(val wire: String) { TUNNEL("tunnel"), BLOCKED("blocked") }

    sealed interface Event {
        /** Tunnel came up AND passed health verification on [region]. */
        data class VerifiedUp(val region: String) : Event
        /** A full provision+connect+verify cycle failed. plainNetworkOk=false
         *  means the box's own internet is down (tunnel torn) → not the region's
         *  fault, cycle not counted. */
        data class VerificationCycleFailed(val region: String, val plainNetworkOk: Boolean) : Event
        /** A movie/series was refused 403 through a VERIFIED tunnel (caller-guaranteed).
         *  contentKey identifies the content so repeats don't over-count. */
        data class Enforced403(val region: String, val contentKey: String) : Event
        /** A live channel played successfully through the tunnel (organic). */
        data class LiveSuccess(val region: String) : Event
        /** Live was ALSO 403 → provider-wide refusal, not a region ban. */
        data object LiveProbe403 : Event
        /** Network connectivity changed (open evidence-suppression window). */
        data object NetworkChanged : Event
        /** Server's authoritative region assignment from the heartbeat. */
        data class ServerAssignedRegion(val region: String?) : Event
        /** Server replied to a fallback request. */
        data class FallbackResult(val granted: Boolean, val newRegion: String?) : Event
        /** The fallback request could not reach the server (transport failure).
         *  Distinct from a server denial — punished with a SHORT cooldown, not 12h. */
        data object FallbackAborted : Event
        /** The manager reports the outcome of a [Decision.RequestEgressRotation].
         *  dispatched=true: a new exit was actually provisioned → clear the now-stale
         *  block evidence. dispatched=false: the rotation could not be executed
         *  (transport failure / deferred / stale) → refund the reserved slot so the
         *  box can retry instead of burning its bounded rotation budget for nothing. */
        data class EgressRotationResult(val dispatched: Boolean) : Event
        /** Admin toggled the VPN off→on (a manual reset). */
        data object AdminVpnCycleReset : Event
    }

    sealed interface Decision {
        data object None : Decision
        /** Class A pacing: retry a fresh provision+connect+verify after delayMs. */
        data class ScheduleRetryCycle(val delayMs: Long) : Decision
        /** Class B: N distinct 403s but no live proof yet — one spaced confirmatory
         *  live check would help (v1 leaves this to organic playback). */
        data class RequestConfirmatoryLiveProbe(val minSpacingMs: Long) : Decision
        /** Ask the server to move off [failedRegion]. */
        data class RequestFallback(
            val failedRegion: String,
            val kind: FailureKind,
            val evidenceSummary: String,
        ) : Decision
        /** On-demand egress looks burned (N distinct 403 through a verified tunnel)
         *  but there is NO live proof to justify a region change: ask the server to
         *  rotate the exit IP WITHIN [failedRegion]. Lighter + safer than a region
         *  fallback (never abandons a region, so it needs no live-proof guard), and
         *  the only escape for a VOD-only box that never produces a live success. */
        data class RequestEgressRotation(
            val failedRegion: String,
            val evidenceSummary: String,
            /** True when the rotation was triggered by LIVE itself refusing (the
             *  exit IP is burned for everything). The manager must then rotate even
             *  though live is the active playback — the usual "defer while live
             *  plays" guard exists to protect a WORKING live during a VOD-only
             *  rotation, which does not apply when live is the thing that's broken. */
            val interruptLive: Boolean = false,
        ) : Decision
        /** Lifetime cap hit — report once and stay put until an admin reset. */
        data class EnterTerminal(val reason: String) : Decision
        /** Region reassigned / admin reset — counters cleared. */
        data object ResetToIdle : Decision
    }

    // --- persisted (survives restart) ---
    private var cooldownUntilMs = initial.cooldownUntilMs
    private var fallbackHistoryMs = initial.fallbackHistoryMs.toMutableList()
    private var terminal = initial.terminal
    private var lastReason = initial.lastReason

    // --- in-memory (a restart at worst delays a fallback — never causes one) ---
    private var state: State =
        if (terminal) State.TERMINAL
        else if (cooldownUntilMs > clock()) State.COOLDOWN
        else State.IDLE
    private var currentRegion: String? = null
    private var classACycleCount = 0
    private var lastCountedCycleMs = 0L
    private val blockEvidence = LinkedHashMap<String, Long>() // contentKey -> ts
    private var liveProofAtMs = 0L
    private var lastNetworkChangeMs = 0L
    // Timestamps of recent in-region egress rotations (per-window cap + min spacing,
    // see canRotateEgress). Deliberately in-memory, unlike the persisted region
    // fallbackHistoryMs: a restart refills the cap, but an in-region rotation is cheap
    // and never abandons a region, so a few extra rotations after a reboot are
    // harmless — not worth the persistence surface a region fallback warrants.
    private val egressRotateHistoryMs = mutableListOf<Long>()

    fun state(): State = state

    fun persisted(): PersistedGuardState = PersistedGuardState(
        cooldownUntilMs = cooldownUntilMs,
        fallbackHistoryMs = fallbackHistoryMs.toList(),
        terminal = terminal,
        lastReason = lastReason,
    )

    fun heartbeatLabel(): String = when (state) {
        State.IDLE -> "idle"
        State.HEALTHY -> "healthy"
        State.TUNNEL_SUSPECT -> "tunnel_suspect($classACycleCount/$CLASS_A_CYCLES)"
        State.BLOCK_SUSPECT -> "block_suspect(${blockEvidence.size}/$CLASS_B_DISTINCT)"
        State.FALLBACK_PENDING -> "fallback_pending"
        State.COOLDOWN -> "cooldown"
        State.TERMINAL -> "terminal"
    }

    /** The single mutation entry point. */
    fun onEvent(e: Event): Decision {
        // Global overrides, valid in any state including TERMINAL.
        when (e) {
            is Event.ServerAssignedRegion -> return onServerRegion(e.region)
            Event.AdminVpnCycleReset -> { resetAll(); state = State.IDLE; return Decision.ResetToIdle }
            Event.NetworkChanged -> { lastNetworkChangeMs = clock(); return Decision.None }
            else -> {}
        }
        if (terminal) return Decision.None
        // Lazily leave cooldown once it has elapsed.
        if (state == State.COOLDOWN && clock() >= cooldownUntilMs) state = State.HEALTHY

        return when (e) {
            is Event.VerifiedUp -> onVerifiedUp(e.region)
            is Event.VerificationCycleFailed -> onCycleFailed(e.region, e.plainNetworkOk)
            is Event.Enforced403 -> onEnforced403(e.region, e.contentKey)
            is Event.LiveSuccess -> onLiveSuccess(e.region)
            Event.LiveProbe403 -> onLiveProbe403()
            is Event.FallbackResult -> onFallbackResult(e.granted, e.newRegion)
            Event.FallbackAborted -> onFallbackAborted()
            is Event.EgressRotationResult -> onEgressRotationResult(e.dispatched)
            else -> Decision.None
        }
    }

    private fun onFallbackAborted(): Decision {
        // Transport failure reaching the server — a genuinely dead region should
        // be retried soon, so back off only briefly instead of the 12h denial.
        classACycleCount = 0
        lastCountedCycleMs = 0L
        cooldownUntilMs = clock() + FALLBACK_ABORT_COOLDOWN_MS
        state = State.COOLDOWN
        return Decision.None
    }

    private fun onServerRegion(region: String?): Decision {
        if (region == null) return Decision.None
        if (currentRegion == null) {
            // First region seen after (re)construction. A restart clears the
            // in-memory currentRegion; adopting the heartbeat region must NOT be
            // treated as a reassignment, or a persisted cooldown / TERMINAL /
            // fallback-history would be wiped on the very first heartbeat.
            currentRegion = region
            return Decision.None
        }
        if (region != currentRegion) {
            // Genuine authoritative reassignment (admin or a fallback we didn't
            // drive) → full reset, adopt the new region.
            resetAll()
            currentRegion = region
            state = State.IDLE
            return Decision.ResetToIdle
        }
        return Decision.None
    }

    private fun onVerifiedUp(region: String): Decision {
        currentRegion = region
        classACycleCount = 0
        lastCountedCycleMs = 0L
        // A verified tunnel does NOT clear Class B evidence: a healthy tunnel can
        // still be region-banned for on-demand. Evidence expires by its window.
        if (state == State.COOLDOWN && clock() < cooldownUntilMs) return Decision.None
        state = State.HEALTHY
        return Decision.None
    }

    private fun onCycleFailed(region: String, plainNetworkOk: Boolean): Decision {
        if (isInCooldown() || inSettleWindow()) return Decision.None
        if (region != (currentRegion ?: region)) return Decision.None
        if (!plainNetworkOk) {
            // Box offline, not the region — don't count, just keep pacing retries.
            val idx = classACycleCount.coerceIn(0, CLASS_A_RETRY_DELAYS_MS.size - 1)
            return Decision.ScheduleRetryCycle(CLASS_A_RETRY_DELAYS_MS[idx])
        }
        // Minimum spacing between COUNTED cycles: back-to-back failures (e.g. a
        // 90 s heartbeat-driven reconnect on a briefly-down server) must not
        // race to the fallback in a few minutes. Reschedule without counting.
        if (lastCountedCycleMs > 0 && clock() - lastCountedCycleMs < CLASS_A_MIN_SPACING_MS) {
            val idx = classACycleCount.coerceIn(0, CLASS_A_RETRY_DELAYS_MS.size - 1)
            return Decision.ScheduleRetryCycle(CLASS_A_RETRY_DELAYS_MS[idx])
        }
        lastCountedCycleMs = clock()
        classACycleCount++
        if (classACycleCount >= CLASS_A_CYCLES) {
            state = State.FALLBACK_PENDING
            return Decision.RequestFallback(
                currentRegion ?: region, FailureKind.TUNNEL,
                "tunnel dead: $classACycleCount failed connect+verify cycles",
            )
        }
        state = State.TUNNEL_SUSPECT
        val idx = (classACycleCount - 1).coerceIn(0, CLASS_A_RETRY_DELAYS_MS.size - 1)
        return Decision.ScheduleRetryCycle(CLASS_A_RETRY_DELAYS_MS[idx])
    }

    private fun onEnforced403(region: String, contentKey: String): Decision {
        if (isInCooldown() || inSettleWindow()) return Decision.None
        if (region != currentRegion) return Decision.None // stale evidence
        pruneBlockEvidence()
        blockEvidence[contentKey] = clock()
        // Strong path (unchanged): a live success in-window proves the block is
        // selective → conservative, budget-capped region change once CLASS_B_DISTINCT
        // distinct contents are refused. A live-watcher keeps this exact behaviour.
        maybeRequestBlockFallback()?.let { return it }
        // No live proof — a VOD-only box that never plays live can't justify a region
        // change AND never produces the live success the strong path waits for, so it
        // would sit stranded on a burned exit IP. Rotate the exit IP within the SAME
        // region after only EGRESS_ROTATE_DISTINCT (=2) distinct on-demand 403: two
        // different contents both refused points at the EXIT, not the content, far
        // more than at a single 403 — and a client should not have to sit through
        // three failures (then call the reseller) before the box heals itself.
        // Cheap + bounded (spacing + per-window cap) and safe (never abandons a
        // region). Gated on "no live proof" so it never pre-empts a live-watcher's
        // region fallback.
        val hasLiveProof = liveProofAtMs > 0 && clock() - liveProofAtMs <= CLASS_B_WINDOW_MS
        if (!hasLiveProof && blockEvidence.size >= EGRESS_ROTATE_DISTINCT && canRotateEgress()) {
            val n = blockEvidence.size
            // Reserve a rotation slot NOW — its timestamp is what stops a second
            // request firing (via the spacing in canRotateEgress) before the manager
            // reports back. Evidence is NOT cleared here: the manager confirms the
            // outcome via [onEgressRotationResult]. A DISPATCHED rotation clears the
            // now-stale evidence (it was against the old exit); an ABORTED one
            // (transport failure / deferred-for-live / stale) refunds the slot so a
            // decided-but-unexecuted rotation never strands the box on the burned exit
            // — the very cul-de-sac this path exists to eliminate.
            egressRotateHistoryMs.add(clock())
            state = State.BLOCK_SUSPECT
            return Decision.RequestEgressRotation(
                currentRegion ?: region,
                "egress burned: $n distinct on-demand 403, no live proof",
            )
        }
        state = State.BLOCK_SUSPECT
        return if (blockEvidence.size >= CLASS_B_DISTINCT) {
            Decision.RequestConfirmatoryLiveProbe(LIVE_PROBE_MIN_SPACING_MS)
        } else {
            Decision.None
        }
    }

    /** At most [MAX_EGRESS_ROTATES_PER_WINDOW] rotations per rolling window, spaced
     *  by at least [EGRESS_ROTATE_MIN_SPACING_MS]. Self-heals: old rotations age out
     *  so a fresh burn later can rotate again. */
    private fun canRotateEgress(): Boolean {
        val now = clock()
        egressRotateHistoryMs.retainAll { now - it <= EGRESS_ROTATE_WINDOW_MS }
        if (egressRotateHistoryMs.size >= MAX_EGRESS_ROTATES_PER_WINDOW) return false
        val last = egressRotateHistoryMs.maxOrNull() ?: return true
        return now - last >= EGRESS_ROTATE_MIN_SPACING_MS
    }

    /** Manager reported a [Decision.RequestEgressRotation] outcome. dispatched=true →
     *  the exit IP actually changed, so evidence gathered against the OLD exit is
     *  stale → drop it. dispatched=false → the rotation never executed → refund the
     *  reserved slot (the most recent timestamp; the 5-min spacing guarantees it is
     *  the one just reserved) so a transient failure doesn't burn bounded budget. */
    private fun onEgressRotationResult(dispatched: Boolean): Decision {
        if (dispatched) {
            blockEvidence.clear()
        } else if (egressRotateHistoryMs.isNotEmpty()) {
            egressRotateHistoryMs.removeAt(egressRotateHistoryMs.size - 1)
        }
        return Decision.None
    }

    private fun onLiveSuccess(region: String): Decision {
        if (region != currentRegion) return Decision.None
        liveProofAtMs = clock()
        if (isInCooldown() || inSettleWindow()) return Decision.None
        pruneBlockEvidence()
        return maybeRequestBlockFallback() ?: Decision.None
    }

    private fun onLiveProbe403(): Decision {
        // A live 403 through a VERIFIED tunnel was historically read as a
        // provider-wide refusal ("nothing we can do"). That is wrong when the
        // current EXIT IP has been burned — e.g. a catalog-sync request burst
        // tripped the provider's abuse ban on this exit: live 403s too, yet a
        // FRESH exit IP recovers everything (exactly what a manual region change
        // does). So try a bounded in-region egress rotation FIRST — the same
        // escape hatch as the VOD-only burn path, rate-limited by canRotateEgress
        // (≥5 min apart, ≤3 per 6 h) so it can't rotate-storm. Only once that
        // budget is exhausted (several fresh exits still 403 live) is it genuinely
        // provider-wide → clear evidence and sit still.
        liveProofAtMs = 0L
        val region = currentRegion
        if (region != null && !isInCooldown() && !inSettleWindow() && canRotateEgress()) {
            egressRotateHistoryMs.add(clock())
            state = State.BLOCK_SUSPECT
            // Evidence (if any) is cleared by onEgressRotationResult once the
            // rotation is confirmed dispatched — it was gathered against the exit
            // we are about to abandon.
            return Decision.RequestEgressRotation(
                region,
                "live 403 through verified tunnel: exit IP likely burned",
                interruptLive = true,
            )
        }
        blockEvidence.clear()
        if (state == State.BLOCK_SUSPECT) state = State.HEALTHY
        return Decision.None
    }

    /** Returns a RequestFallback decision if Class B is fully satisfied, else null. */
    private fun maybeRequestBlockFallback(): Decision? {
        if (blockEvidence.size < CLASS_B_DISTINCT) return null
        val liveProof = liveProofAtMs > 0 && clock() - liveProofAtMs <= CLASS_B_WINDOW_MS
        if (!liveProof) return null
        val region = currentRegion ?: return null
        state = State.FALLBACK_PENDING
        return Decision.RequestFallback(
            region, FailureKind.BLOCKED,
            "region banned: ${blockEvidence.size} distinct on-demand 403 + live ok in ${CLASS_B_WINDOW_MS / 60000}m",
        )
    }

    private fun onFallbackResult(granted: Boolean, newRegion: String?): Decision {
        classACycleCount = 0
        lastCountedCycleMs = 0L
        blockEvidence.clear()
        liveProofAtMs = 0L
        if (granted) {
            if (newRegion != null) currentRegion = newRegion
            fallbackHistoryMs.add(clock())
            fallbackHistoryMs.retainAll { clock() - it <= WEEK_MS }
            lastReason = "granted"
            if (fallbackHistoryMs.size > MAX_FALLBACKS_PER_WEEK) {
                terminal = true
                state = State.TERMINAL
                return Decision.EnterTerminal("max_fallbacks_per_week")
            }
            cooldownUntilMs = clock() + FALLBACK_COOLDOWN_MS
            state = State.COOLDOWN
            return Decision.None
        }
        // Denied — respect harder.
        lastReason = "denied"
        cooldownUntilMs = clock() + DENIED_COOLDOWN_MS
        state = State.COOLDOWN
        return Decision.None
    }

    private fun pruneBlockEvidence() {
        val now = clock()
        val it = blockEvidence.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > CLASS_B_WINDOW_MS) it.remove()
    }

    private fun isInCooldown() = clock() < cooldownUntilMs
    private fun inSettleWindow() = lastNetworkChangeMs > 0 && clock() - lastNetworkChangeMs < NETWORK_SETTLE_MS

    private fun resetAll() {
        cooldownUntilMs = 0L
        fallbackHistoryMs.clear()
        terminal = false
        lastReason = null
        classACycleCount = 0
        lastCountedCycleMs = 0L
        blockEvidence.clear()
        liveProofAtMs = 0L
        egressRotateHistoryMs.clear()
    }

    companion object {
        const val CLASS_A_CYCLES = 3
        val CLASS_A_RETRY_DELAYS_MS = longArrayOf(180_000, 420_000) // cycle2 +3min, cycle3 +7min
        const val CLASS_A_MIN_SPACING_MS = 150_000L                 // min gap between COUNTED cycles
        const val FALLBACK_ABORT_COOLDOWN_MS = 5 * 60_000L          // transport failure backoff
        const val CLASS_B_DISTINCT = 3
        const val CLASS_B_WINDOW_MS = 30 * 60_000L
        const val NETWORK_SETTLE_MS = 90_000L
        const val FALLBACK_COOLDOWN_MS = 6 * 3_600_000L
        const val DENIED_COOLDOWN_MS = 12 * 3_600_000L
        const val MAX_FALLBACKS_PER_WEEK = 2
        const val WEEK_MS = 7 * 24 * 3_600_000L
        const val LIVE_PROBE_MIN_SPACING_MS = 60_000L
        // In-region egress rotation (no live proof required, never abandons a
        // region): fires fast (2 distinct on-demand 403) so a client isn't left
        // through 3 failures, but rate-bounded so a fully-burned region can't
        // rotate-storm. Short spacing lets it try the region's next exit quickly if
        // the first rotation didn't help; the per-window cap stops it there.
        const val EGRESS_ROTATE_DISTINCT = 2
        const val MAX_EGRESS_ROTATES_PER_WINDOW = 3
        const val EGRESS_ROTATE_WINDOW_MS = 6 * 3_600_000L
        const val EGRESS_ROTATE_MIN_SPACING_MS = 5 * 60_000L
    }
}

/** The subset of guard state that must survive process death (see policy KDoc). */
data class PersistedGuardState(
    val cooldownUntilMs: Long = 0L,
    val fallbackHistoryMs: List<Long> = emptyList(),
    val terminal: Boolean = false,
    val lastReason: String? = null,
)
