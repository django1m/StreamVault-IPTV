package com.streamvault.app.network

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Tells [BackendDns] when a connection to OUR backend fails — refused, timed out,
 * or a TLS mismatch. That is how a filter answering a public block-page IP, which
 * no resolver-level check can recognise, still moves the backend onto DoH.
 * Connections to any other host are ignored. Stateless, so one instance serves
 * every call.
 */
class BackendConnectFailureListener(
    private val backendHost: String,
    private val onFailure: () -> Unit,
) : EventListener() {

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        if (backendHost.isNotEmpty() && call.request().url.host.equals(backendHost, ignoreCase = true)) {
            onFailure()
        }
    }
}
