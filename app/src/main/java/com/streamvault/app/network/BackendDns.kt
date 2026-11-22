package com.streamvault.app.network

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * [Dns] of the shared control-plane client. It keeps OUR backend reachable when
 * the box's resolver filters it — a router "security" feature, or an ISP filter
 * such as Cogeco's « Protection en ligne », answering NXDOMAIN or a sinkhole
 * (0.0.0.0 / ::, seen in the field as "Failed to connect to …/[::]:443").
 *
 * - Every other host (provider, EPG, logos) goes straight to [system]: no change.
 * - The backend host goes to [system] FIRST. Only a failure or a filtered answer
 *   sends it to DNS-over-HTTPS (Cloudflare, then Google).
 * - A DoH rescue, or a failed connection to the backend ([onBackendConnectFailed] —
 *   which also catches a filter answering a public block-page IP), keeps the
 *   backend on DoH first for [STICKY_DOH_MS]. Answers are cached [DOH_CACHE_MS];
 *   a DoH failure is not retried for [DOH_FAILURE_BACKOFF_MS].
 * - When DoH cannot help either, the system answer or error is returned as is:
 *   the worst case is exactly the behaviour before DoH existed.
 *
 * A box whose resolver works never builds the DoH client and never sends a DoH
 * query — the healthy path costs one string comparison per lookup.
 */
class BackendDns(
    val backendHost: String,
    private val system: Dns,
    dohResolvers: () -> List<Dns>,
    private val clock: () -> Long = System::currentTimeMillis,
) : Dns {

    private val doh: List<Dns> by lazy { runCatching(dohResolvers).getOrDefault(emptyList()) }

    @Volatile private var preferDohUntilMs = 0L
    @Volatile private var dohBackoffUntilMs = 0L
    @Volatile private var cached: CachedAnswer? = null

    private class CachedAnswer(val addresses: List<InetAddress>, val expiresAtMs: Long)

    /** A connection to the backend failed (refused, timeout, TLS mismatch): try DoH first for a while. */
    fun onBackendConnectFailed() {
        preferDohUntilMs = clock() + STICKY_DOH_MS
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (backendHost.isEmpty() || !hostname.equals(backendHost, ignoreCase = true)) {
            return system.lookup(hostname)
        }
        if (clock() < preferDohUntilMs) {
            resolveOverDoh(hostname)?.let { return it }
        }

        var systemError: UnknownHostException? = null
        val systemAnswer = try {
            system.lookup(hostname)
        } catch (e: UnknownHostException) {
            systemError = e
            null
        }
        if (systemAnswer != null && systemAnswer.isNotEmpty() && systemAnswer.none(::isFilteredAnswer)) {
            return systemAnswer
        }

        resolveOverDoh(hostname)?.let {
            preferDohUntilMs = clock() + STICKY_DOH_MS
            return it
        }
        if (systemAnswer != null) return systemAnswer
        throw systemError ?: UnknownHostException(hostname)
    }

    private fun resolveOverDoh(hostname: String): List<InetAddress>? {
        val now = clock()
        cached?.let { if (now < it.expiresAtMs) return it.addresses }
        if (now < dohBackoffUntilMs) return null
        for (resolver in doh) {
            val answer = try {
                resolver.lookup(hostname)
            } catch (_: Exception) {
                continue
            }
            if (answer.isNotEmpty() && answer.none(::isFilteredAnswer)) {
                cached = CachedAnswer(answer, now + DOH_CACHE_MS)
                return answer
            }
        }
        dohBackoffUntilMs = now + DOH_FAILURE_BACKOFF_MS
        return null
    }

    companion object {
        const val STICKY_DOH_MS = 10 * 60_000L
        const val DOH_CACHE_MS = 5 * 60_000L
        const val DOH_FAILURE_BACKOFF_MS = 60_000L
        private const val DOH_TIMEOUT_SECONDS = 4L
        private const val DOH_CALL_TIMEOUT_SECONDS = 5L

        /**
         * An address our Cloudflare-fronted backend can never legitimately have:
         * the unspecified sinkhole (0.0.0.0 / ::), loopback, private, link-local,
         * multicast or unique-local IPv6 — what filters answer instead of the truth.
         */
        fun isFilteredAnswer(address: InetAddress): Boolean =
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress || isUniqueLocalIpv6(address)

        private fun isUniqueLocalIpv6(address: InetAddress): Boolean =
            address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC

        fun create(baseUrl: String, system: Dns = Dns.SYSTEM): BackendDns =
            BackendDns(baseUrl.toHttpUrlOrNull()?.host.orEmpty(), system, { buildDohResolvers() })

        private fun buildDohResolvers(): List<Dns> {
            val client = OkHttpClient.Builder()
                .connectTimeout(DOH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DOH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DOH_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Short-lived sockets: one opened before a VPN transition must not
                // be reused for long on the network it was bound to.
                .connectionPool(ConnectionPool(1, 1, TimeUnit.MINUTES))
                .build()
            return listOf(
                dohResolver(client, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
                dohResolver(client, "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
            )
        }

        private fun dohResolver(client: OkHttpClient, url: String, vararg bootstrapIps: String): Dns =
            DnsOverHttps.Builder()
                .client(client)
                .url(url.toHttpUrl())
                // Reached by IP: resolving the resolver must not depend on the filtered DNS.
                .bootstrapDnsHosts(bootstrapIps.map { InetAddress.getByName(it) })
                // The tunnel carries IPv4 only, and IPv4 is what the app uses today.
                .includeIPv6(false)
                // Skips OkHttp's public-suffix database (~200 KB of heap on a 2 GB
                // box); answers are vetted by isFilteredAnswer instead.
                .resolvePrivateAddresses(true)
                .build()
    }
}
