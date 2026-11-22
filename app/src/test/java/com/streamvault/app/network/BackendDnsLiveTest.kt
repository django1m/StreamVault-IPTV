package com.streamvault.app.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Real-network check of the DoH configuration (bootstrap IPs, TLS, parsing,
 * IPv4-only). Skipped by default; run by hand with
 * `FUZIONTV_LIVE_NETWORK_TESTS=1 ./gradlew :app:testTvDebugUnitTest --tests '*BackendDnsLiveTest*'`.
 */
class BackendDnsLiveTest {

    @Test
    fun `with the system resolver blocked, the real DoH resolvers answer with public IPv4 only`() {
        assumeTrue(System.getenv("FUZIONTV_LIVE_NETWORK_TESTS") == "1")
        val blockedSystem = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw UnknownHostException("simulated ISP block")
        }
        val dns = BackendDns.create("https://admin.fuziontv.app", system = blockedSystem)

        val answer = dns.lookup("admin.fuziontv.app")

        assertThat(answer).isNotEmpty()
        assertThat(answer.all { it is Inet4Address }).isTrue()
        assertThat(answer.none { BackendDns.isFilteredAnswer(it) }).isTrue()
    }
}
