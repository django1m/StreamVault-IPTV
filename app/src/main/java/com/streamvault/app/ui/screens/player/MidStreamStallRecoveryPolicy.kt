package com.streamvault.app.ui.screens.player

/**
 * Decides how to react when a live stream silently stalls in BUFFERING after
 * having reached READY (panel stopped feeding bytes but socket still open, so
 * Media3 never raises onPlayerError and PlayerRetryPolicy is never invoked).
 *
 * The policy is intentionally a small state machine with no Android deps so it
 * can be unit-tested without an instrumentation harness.
 *
 * Lifecycle expected from the caller (PlayerViewModel mid-stream watchdog):
 *   - decide(networkAvailable) is called once per stall-detection tick
 *   - onRestart() is called every time the caller actually invokes
 *     playerEngine.restart() (so the cooldown timestamp + consecutive-restart
 *     counter advance in lockstep with reality)
 *   - onPlaybackRecovered() is called once the engine reaches READY again
 *     (clears the consecutive-restart counter so a long-running channel can
 *     recover from successive unrelated incidents)
 */
class MidStreamStallRecoveryPolicy(
    private val maxConsecutiveRestarts: Int,
    private val restartCooldownMs: Long,
    private val clock: () -> Long
) {
    private var consecutiveRestarts: Int = 0
    private var lastRestartAtMs: Long = Long.MIN_VALUE

    fun decide(networkAvailable: Boolean): Action {
        if (!networkAvailable) return Action.WaitForNetwork
        if (consecutiveRestarts >= maxConsecutiveRestarts) return Action.Fallback
        val now = clock()
        if (lastRestartAtMs != Long.MIN_VALUE &&
            now - lastRestartAtMs < restartCooldownMs
        ) {
            return Action.WaitForCooldown
        }
        return Action.Restart
    }

    fun onRestart() {
        consecutiveRestarts += 1
        lastRestartAtMs = clock()
    }

    fun onPlaybackRecovered() {
        consecutiveRestarts = 0
    }

    fun reset() {
        consecutiveRestarts = 0
        lastRestartAtMs = Long.MIN_VALUE
    }

    sealed class Action {
        /** Network is up + cooldown elapsed + budget left: call restart() now. */
        object Restart : Action()

        /** Network is up but a previous restart happened recently: keep waiting. */
        object WaitForCooldown : Action()

        /** ConnectivityManager reports no network: restart would just thrash. */
        object WaitForNetwork : Action()

        /** Restart budget exhausted: fall back to previous channel / notice. */
        object Fallback : Action()
    }
}
