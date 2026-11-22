package com.streamvault.app.vpn

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.data.remote.fuziontv.FuziontvApiException
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class TunnelTrafficRulesTest {

    // WireGuard counts its own control packets in rx_bytes: a handshake response
    // is 92 bytes, a keepalive 32. None of that proves the tunnel carries traffic.

    @Test
    fun `a WireGuard handshake alone does not prove the tunnel carries traffic`() {
        assertThat(TunnelTrafficRules.rxProvesTraffic(rxStart = 0, rxNow = 92)).isFalse()
    }

    @Test
    fun `handshake plus keepalives do not prove traffic`() {
        assertThat(TunnelTrafficRules.rxProvesTraffic(rxStart = 1_000, rxNow = 1_000 + 92 + 4 * 32)).isFalse()
    }

    @Test
    fun `a few DNS answers do not prove traffic`() {
        assertThat(TunnelTrafficRules.rxProvesTraffic(rxStart = 0, rxNow = 900)).isFalse()
    }

    @Test
    fun `a TLS handshake worth of bytes proves traffic`() {
        assertThat(TunnelTrafficRules.rxProvesTraffic(rxStart = 0, rxNow = 3_500)).isTrue()
    }

    @Test
    fun `a counter that went backwards does not prove traffic`() {
        assertThat(TunnelTrafficRules.rxProvesTraffic(rxStart = 50_000, rxNow = 100)).isFalse()
    }

    @Test
    fun `socket-level failures are transport failures`() {
        listOf(
            IOException("canceled"), UnknownHostException("x"), SocketTimeoutException("timeout"),
            ConnectException("refused"), SSLHandshakeException("bad cert"),
        ).forEach { assertWithMessage(it.toString()).that(TunnelTrafficRules.isTransportFailure(it)).isTrue() }
    }

    @Test
    fun `an HTTP error answer is not a transport failure - the path worked`() {
        assertThat(TunnelTrafficRules.isTransportFailure(FuziontvApiException(502, null, "HTTP 502"))).isFalse()
    }

    @Test
    fun `programming errors are not transport failures`() {
        assertThat(TunnelTrafficRules.isTransportFailure(IllegalStateException("bug"))).isFalse()
    }
}
