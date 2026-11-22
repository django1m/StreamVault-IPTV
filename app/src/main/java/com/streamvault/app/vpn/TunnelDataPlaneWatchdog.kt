package com.streamvault.app.vpn

/**
 * Recognises a tunnel that LOOKS healthy — its WireGuard handshakes keep
 * succeeding, so every freshness check says "verified" — while nothing else gets
 * through it. The app is then blackholed behind a tunnel it trusts: heartbeats,
 * credentials and playback all fail, and nothing ever reconnects.
 *
 * It trips only when all of these hold, so the look-alikes never do:
 *  - backend calls kept failing at the transport level for [MIN_FAILURE_WINDOW_MS]
 *    ([MIN_FAILURES]+ failures, no success in between) — a blip is not a pattern;
 *  - a handshake COMPLETED after the failures began — proof that the box's link and
 *    the UDP path to the server work. A Wi-Fi hole stops handshakes too, and it is
 *    the link's fault, not the tunnel's (the 536 lesson of reportLiveBlackhole);
 *  - rx grew less than [MAX_RX_GROWTH_BYTES] meanwhile — playback still flowing
 *    means the data plane works and the problem lies elsewhere;
 *  - no recovery in the last [RECOVERY_COOLDOWN_MS] — never churn the tunnel.
 *
 * Pure (clock injected). The caller confirms with a probe through the tunnel
 * before acting on a trip.
 */
class TunnelDataPlaneWatchdog(private val clock: () -> Long) {

    private var failures = 0
    private var firstFailureAtMs = 0L
    private var rxBaseline = 0L
    private var lastRecoveryAtMs = 0L

    @Synchronized
    fun onBackendSuccess() = resetEvidence()

    /**
     * One backend call failed at the transport level while the tunnel was up.
     * @param handshakeAgeSec age of the latest WireGuard handshake, null if none ever completed.
     * @param rxBytes the peer's cumulative received bytes.
     * @return true when the tunnel should be confirmed dead and rebuilt.
     */
    @Synchronized
    fun onBackendTransportFailure(handshakeAgeSec: Long?, rxBytes: Long): Boolean {
        val now = clock()
        if (failures == 0) {
            firstFailureAtMs = now
            rxBaseline = rxBytes
        }
        failures++
        // WireGuard counters restart at 0 when the tunnel is rebuilt: rebase
        // instead of reading a huge drop.
        if (rxBytes < rxBaseline) rxBaseline = rxBytes

        if (failures < MIN_FAILURES || now - firstFailureAtMs < MIN_FAILURE_WINDOW_MS) return false
        val age = handshakeAgeSec ?: return false
        if (now - age * 1000 <= firstFailureAtMs) return false
        if (rxBytes - rxBaseline > MAX_RX_GROWTH_BYTES) return false
        if (lastRecoveryAtMs != 0L && now - lastRecoveryAtMs < RECOVERY_COOLDOWN_MS) return false
        return true
    }

    @Synchronized
    fun onRecoveryStarted() {
        lastRecoveryAtMs = clock()
        resetEvidence()
    }

    private fun resetEvidence() {
        failures = 0
        firstFailureAtMs = 0L
        rxBaseline = 0L
    }

    companion object {
        const val MIN_FAILURES = 3
        const val MIN_FAILURE_WINDOW_MS = 150_000L
        const val MAX_RX_GROWTH_BYTES = 64 * 1024L
        const val RECOVERY_COOLDOWN_MS = 10 * 60_000L
    }
}
