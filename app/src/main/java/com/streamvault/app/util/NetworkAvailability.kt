package com.streamvault.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small abstraction over [ConnectivityManager] so the mid-stream stall
 * recovery policy can be unit-tested without an instrumentation harness.
 *
 * On a hard network drop (cable yanked, wifi off, captive portal stuck), the
 * policy uses this to skip restart attempts that would only thrash DNS + TLS.
 */
interface NetworkAvailability {
    fun isConnected(): Boolean
}

@Singleton
class AndroidNetworkAvailability @Inject constructor(
    @param:ApplicationContext private val context: Context
) : NetworkAvailability {
    override fun isConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
