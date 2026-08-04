package com.streamvault.app.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Transparent, no-UI activity that runs the mandatory one-time Android VPN
 * consent dialog ([android.net.VpnService.prepare]). Launched by [VpnManager]
 * when the tunnel needs to come up but consent hasn't been granted yet. On
 * approval it kicks provisioning + connect, then finishes immediately.
 *
 * Android requires this consent per app; it cannot be bypassed. After the first
 * approval it never appears again.
 */
@AndroidEntryPoint
class VpnConsentActivity : ComponentActivity() {

    @Inject lateinit var vpnManager: VpnManager

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpnManager.onConsentGranted()
        } else {
            Log.w(TAG, "VPN consent denied by user")
            vpnManager.onConsentDenied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent: Intent? = runCatching { android.net.VpnService.prepare(this) }.getOrNull()
        if (intent == null) {
            // Consent already granted (race) — just proceed.
            vpnManager.onConsentGranted()
            finish()
        } else {
            runCatching { consentLauncher.launch(intent) }
                .onFailure { Log.e(TAG, "failed to launch consent: ${it.message}"); finish() }
        }
    }

    private companion object { const val TAG = "VpnConsentActivity" }
}
