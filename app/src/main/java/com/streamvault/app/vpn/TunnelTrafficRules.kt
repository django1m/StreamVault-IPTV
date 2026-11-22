package com.streamvault.app.vpn

import java.io.IOException

/** Pure rules for judging whether the WireGuard tunnel really carries traffic. */
object TunnelTrafficRules {

    /**
     * WireGuard counts its own control packets in rx_bytes: a handshake response is
     * 92 bytes, a keepalive 32, a cookie reply 64. A tunnel whose data plane is dead
     * still shows a few hundred bytes of those. 2 KB sits above that noise and below
     * a single TLS handshake (~3-4 KB of certificates), so it separates "only
     * WireGuard talked" from "real payload came back".
     */
    const val MIN_PROOF_OF_TRAFFIC_BYTES = 2_048L

    fun rxProvesTraffic(rxStart: Long, rxNow: Long): Boolean =
        rxNow - rxStart >= MIN_PROOF_OF_TRAFFIC_BYTES

    /**
     * Nothing came back at all: DNS, connect, TLS or timeout. An HTTP error answer
     * is NOT one — getting any answer proves the path works.
     */
    fun isTransportFailure(error: Throwable): Boolean = error is IOException
}
