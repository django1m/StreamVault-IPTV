package com.streamvault.app.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Call
import okhttp3.Request
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy

class BackendConnectFailureListenerTest {

    private fun callTo(url: String): Call = mock { on { request() } doReturn Request.Builder().url(url).build() }

    private val address = InetSocketAddress.createUnresolved("unused", 443)

    @Test
    fun `a failed connection to the backend is reported`() {
        var reported = 0
        val listener = BackendConnectFailureListener("admin.fuziontv.app") { reported++ }

        listener.connectFailed(
            callTo("https://admin.fuziontv.app/api/v1/device/heartbeat"),
            address, Proxy.NO_PROXY, null, ConnectException("refused"),
        )

        assertThat(reported).isEqualTo(1)
    }

    @Test
    fun `a failed connection to any other host is ignored`() {
        var reported = 0
        val listener = BackendConnectFailureListener("admin.fuziontv.app") { reported++ }

        listener.connectFailed(
            callTo("http://vpn.fuziontv.net/player_api.php"),
            address, Proxy.NO_PROXY, null, ConnectException("refused"),
        )

        assertThat(reported).isEqualTo(0)
    }
}
