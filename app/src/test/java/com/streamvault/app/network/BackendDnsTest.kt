package com.streamvault.app.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import okhttp3.Dns
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolution of OUR backend host when the box's DNS (router filter, ISP
 * "Protection en ligne") blocks it. Every other host must keep the system
 * resolver untouched — the provider and playback paths are not ours to change.
 */
class BackendDnsTest {

    private class Clock(var now: Long = 1_000_000L)

    /** A resolver scripted per test, counting how often it is asked. */
    private class ScriptedDns(var answer: (String) -> List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return answer(hostname)
        }
    }

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    private val backend = "admin.fuziontv.app"
    private val publicAnswer = listOf(ip("104.21.55.36"))
    private val dohAnswer = listOf(ip("172.64.80.1"))
    private val sinkhole = listOf(ip("0.0.0.0"), ip("::"))

    private fun blocked(): List<InetAddress> = throw UnknownHostException("blocked by resolver")

    private fun backendDns(
        system: ScriptedDns,
        vararg doh: ScriptedDns,
        clock: Clock = Clock(),
        factoryCalls: IntArray = IntArray(1),
    ) = BackendDns(backend, system, { factoryCalls[0]++; doh.toList() }, { clock.now })

    // ---- the healthy path must not change -----------------------------------

    @Test
    fun `other hosts always use the system resolver and never build DoH`() {
        val system = ScriptedDns { publicAnswer }
        val doh = ScriptedDns { dohAnswer }
        val factoryCalls = IntArray(1)
        val dns = backendDns(system, doh, factoryCalls = factoryCalls)

        assertThat(dns.lookup("vpn.fuziontv.net")).isEqualTo(publicAnswer)
        assertThat(doh.calls).isEqualTo(0)
        assertThat(factoryCalls[0]).isEqualTo(0)
    }

    @Test
    fun `a healthy backend answer is returned as is without building DoH`() {
        val system = ScriptedDns { publicAnswer }
        val doh = ScriptedDns { dohAnswer }
        val factoryCalls = IntArray(1)
        val dns = backendDns(system, doh, factoryCalls = factoryCalls)

        assertThat(dns.lookup(backend)).isEqualTo(publicAnswer)
        assertThat(doh.calls).isEqualTo(0)
        assertThat(factoryCalls[0]).isEqualTo(0)
    }

    @Test
    fun `the backend host is matched without regard to case`() {
        val system = ScriptedDns { blocked() }
        val doh = ScriptedDns { dohAnswer }
        val dns = backendDns(system, doh)

        assertThat(dns.lookup("ADMIN.FuzionTV.app")).isEqualTo(dohAnswer)
    }

    // ---- rescue by DoH -------------------------------------------------------

    @Test
    fun `a backend lookup refused by the resolver is rescued by DoH`() {
        val dns = backendDns(ScriptedDns { blocked() }, ScriptedDns { dohAnswer })

        assertThat(dns.lookup(backend)).isEqualTo(dohAnswer)
    }

    @Test
    fun `a sinkholed backend answer is rescued by DoH`() {
        val dns = backendDns(ScriptedDns { sinkhole }, ScriptedDns { dohAnswer })

        assertThat(dns.lookup(backend)).isEqualTo(dohAnswer)
    }

    @Test
    fun `one filtered address in an otherwise public answer still means filtering`() {
        val dns = backendDns(ScriptedDns { listOf(ip("203.0.113.7"), ip("::")) }, ScriptedDns { dohAnswer })

        assertThat(dns.lookup(backend)).isEqualTo(dohAnswer)
    }

    @Test
    fun `the second DoH resolver rescues the first`() {
        val dns = backendDns(
            ScriptedDns { blocked() },
            ScriptedDns { throw UnknownHostException("cloudflare unreachable") },
            ScriptedDns { dohAnswer },
        )

        assertThat(dns.lookup(backend)).isEqualTo(dohAnswer)
    }

    @Test
    fun `a DoH answer that is itself filtered is not trusted`() {
        val dns = backendDns(
            ScriptedDns { blocked() },
            ScriptedDns { listOf(ip("0.0.0.0")) },
            ScriptedDns { dohAnswer },
        )

        assertThat(dns.lookup(backend)).isEqualTo(dohAnswer)
    }

    // ---- when DoH cannot help, behave exactly as before ----------------------

    @Test
    fun `when DoH also fails the original system answer is kept`() {
        val dns = backendDns(
            ScriptedDns { sinkhole },
            ScriptedDns { throw UnknownHostException("no DoH either") },
        )

        assertThat(dns.lookup(backend)).isEqualTo(sinkhole)
    }

    @Test
    fun `when both fail the original resolver error propagates`() {
        val dns = backendDns(
            ScriptedDns { blocked() },
            ScriptedDns { throw UnknownHostException("no DoH either") },
        )

        try {
            dns.lookup(backend)
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertThat(e.message).contains("blocked by resolver")
        }
    }

    // ---- sticky DoH, cache and backoff --------------------------------------

    @Test
    fun `after a DoH rescue the backend stays on DoH first for the sticky window`() {
        val system = ScriptedDns { blocked() }
        val dns = backendDns(system, ScriptedDns { dohAnswer })

        dns.lookup(backend)
        assertThat(dns.lookup(backend)).isEqualTo(dohAnswer)
        assertThat(system.calls).isEqualTo(1)
    }

    @Test
    fun `after the sticky window the system resolver is tried first again`() {
        val clock = Clock()
        val system = ScriptedDns { blocked() }
        val dns = backendDns(system, ScriptedDns { dohAnswer }, clock = clock)

        dns.lookup(backend)
        system.answer = { publicAnswer }
        clock.now += BackendDns.STICKY_DOH_MS + 1

        assertThat(dns.lookup(backend)).isEqualTo(publicAnswer)
        assertThat(system.calls).isEqualTo(2)
    }

    @Test
    fun `a failed connection to the backend switches its lookups to DoH first`() {
        val system = ScriptedDns { publicAnswer }
        val doh = ScriptedDns { dohAnswer }
        val dns = backendDns(system, doh)

        dns.onBackendConnectFailed()

        assertThat(dns.lookup(backend)).isEqualTo(dohAnswer)
        assertThat(system.calls).isEqualTo(0)
    }

    @Test
    fun `sticky DoH falls back to the system resolver when DoH is unreachable`() {
        val dns = backendDns(
            ScriptedDns { publicAnswer },
            ScriptedDns { throw UnknownHostException("no DoH") },
        )

        dns.onBackendConnectFailed()

        assertThat(dns.lookup(backend)).isEqualTo(publicAnswer)
    }

    @Test
    fun `other hosts keep the system resolver while the backend is on DoH`() {
        val system = ScriptedDns { if (it == backend) blocked() else publicAnswer }
        val doh = ScriptedDns { dohAnswer }
        val dns = backendDns(system, doh)

        dns.lookup(backend)
        assertThat(dns.lookup("vpn.fuziontv.net")).isEqualTo(publicAnswer)
        assertThat(doh.calls).isEqualTo(1)
    }

    @Test
    fun `DoH answers are cached so repeated lookups cost one query`() {
        val doh = ScriptedDns { dohAnswer }
        val dns = backendDns(ScriptedDns { publicAnswer }, doh)

        dns.onBackendConnectFailed()
        dns.lookup(backend)
        dns.lookup(backend)

        assertThat(doh.calls).isEqualTo(1)
    }

    @Test
    fun `a failed DoH attempt is not retried during the backoff window`() {
        val clock = Clock()
        val doh = ScriptedDns { throw UnknownHostException("no DoH") }
        val dns = backendDns(ScriptedDns { sinkhole }, doh, clock = clock)

        dns.lookup(backend)
        assertThat(dns.lookup(backend)).isEqualTo(sinkhole)
        assertThat(doh.calls).isEqualTo(1)

        clock.now += BackendDns.DOH_FAILURE_BACKOFF_MS + 1
        dns.lookup(backend)
        assertThat(doh.calls).isEqualTo(2)
    }

    @Test
    fun `DoH resolvers are built once, on first need`() {
        val factoryCalls = IntArray(1)
        val dns = backendDns(ScriptedDns { blocked() }, ScriptedDns { dohAnswer }, factoryCalls = factoryCalls)

        dns.lookup(backend)
        dns.onBackendConnectFailed()
        dns.lookup(backend)

        assertThat(factoryCalls[0]).isEqualTo(1)
    }

    // ---- what counts as a filtered answer ------------------------------------

    @Test
    fun `sinkhole, loopback, private and link-local answers are filtered`() {
        listOf("0.0.0.0", "::", "127.0.0.1", "::1", "10.0.0.243", "172.16.0.1",
            "192.168.1.1", "169.254.1.1", "fe80::1", "fd00::1")
            .forEach { assertWithMessage(it).that(BackendDns.isFilteredAnswer(ip(it))).isTrue() }
    }

    @Test
    fun `public Cloudflare addresses are not filtered`() {
        listOf("104.21.55.36", "172.67.144.107", "172.64.80.1", "2606:4700:3033::ac43:906b")
            .forEach { assertWithMessage(it).that(BackendDns.isFilteredAnswer(ip(it))).isFalse() }
    }
}
