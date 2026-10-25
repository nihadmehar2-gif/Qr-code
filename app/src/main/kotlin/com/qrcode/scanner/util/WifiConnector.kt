package com.qrcode.scanner.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.qrcode.scanner.R
import com.qrcode.scanner.data.QrContent

/**
 * Wi-Fi connection helper.
 *
 * Behaviour per Android version:
 *  - API 29+ (Android 10+): use [WifiNetworkSpecifier] + [ConnectivityManager.requestNetwork].
 *    This launches the system Wi-Fi join dialog (the user must tap "Connect").
 *    Apps cannot silently join a network on modern Android — this is a documented platform
 *    limitation, not a missing feature.
 *  - API 26–28: try [WifiManager.addNetwork] / [WifiManager.enableNetwork]. This still works
 *    on those API levels because of the deprecated write-permission flow.
 *  - Below API 26 or when the call returns failure: open the system Wi-Fi settings screen
 *    and copy the password to the clipboard so the user can paste it into the system dialog.
 *
 * We never promise silent auto-connection.
 */
object WifiConnector {

    interface Callback {
        fun onSystemDialogShown()
        fun onDirectAttemptFailed()
    }

    fun connect(context: Context, wifi: QrContent.Wifi, callback: Callback) {
        val ssid = wifi.ssid
        if (ssid.isBlank()) {
            Toast.makeText(context, R.string.toast_invalid_input, Toast.LENGTH_SHORT).show()
            return
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> requestViaSuggestionApi(context, wifi, callback)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> addViaLegacyConfig(context, wifi, callback)
            else -> {
                // No platform API to write Wi-Fi config — fall back to opening Settings.
                openWifiSettings(context, callback)
            }
        }
    }

    /**
     * On Android 10+, the only way an app can prompt the user to join a Wi-Fi network is
     * via [ConnectivityManager.requestNetwork] with a [WifiNetworkSpecifier]. This pops up
     * the system "Connect to network" sheet — the user explicitly confirms by tapping
     * "Connect". There is no silent connection.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestViaSuggestionApi(context: Context, wifi: QrContent.Wifi, callback: Callback) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            openWifiSettings(context, callback); return
        }
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(wifi.ssid)

        when (wifi.security.uppercase()) {
            "WEP" -> {
                val pass = wifi.password ?: ""
                if (pass.isNotEmpty()) {
                    specifierBuilder.setWpa2Passphrase(pass) // WEP not supported on Q+; best-effort
                }
            }
            "WPA", "WPA2", "WPA3", "" -> {
                val pass = wifi.password
                if (pass.isNullOrEmpty()) {
                    // Open network
                } else {
                    specifierBuilder.setWpa2Passphrase(pass)
                }
            }
            "NOPASS" -> { /* open */ }
            else -> {
                val pass = wifi.password
                if (!pass.isNullOrEmpty()) specifierBuilder.setWpa2Passphrase(pass)
            }
        }
        if (wifi.hidden) {
            specifierBuilder.setIsHiddenSsid(true)
        }
        val specifier = specifierBuilder.build()

        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callbackObj = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {}
            override fun onUnavailable() {
                callback.onDirectAttemptFailed()
            }
        }
        try {
            cm.requestNetwork(request, callbackObj, 30_000)
            callback.onSystemDialogShown()
        } catch (e: SecurityException) {
            // Some OEMs require additional permission; fall back gracefully.
            openWifiSettings(context, callback)
        }
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
    private fun addViaLegacyConfig(context: Context, wifi: QrContent.Wifi, callback: Callback) {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            openWifiSettings(context, callback); return
        }
        val config = WifiConfiguration().apply {
            SSID = "\"" + wifi.ssid + "\""
            hiddenSSID = wifi.hidden
            when (wifi.security.uppercase()) {
                "NOPASS", "" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                "WEP" -> {
                    val pass = wifi.password.orEmpty()
                    wepKeys[0] = "\"" + pass + "\""
                    wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                }
                else -> {
                    val pass = wifi.password.orEmpty()
                    preSharedKey = "\"" + pass + "\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }
        }
        try {
            val netId = wm.addNetwork(config)
            if (netId == -1) {
                openWifiSettings(context, callback)
            } else {
                wm.disconnect()
                wm.enableNetwork(netId, true)
                wm.reconnect()
                callback.onSystemDialogShown()
            }
        } catch (e: SecurityException) {
            openWifiSettings(context, callback)
        }
    }

    private fun openWifiSettings(context: Context, callback: Callback) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        callback.onDirectAttemptFailed()
    }

    @Suppress("unused")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildSuggestion(wifi: QrContent.Wifi): WifiNetworkSuggestion? {
        val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid)
        when (wifi.security.uppercase()) {
            "NOPASS", "" -> builder.setIsAppInteractionRequired(false)
            "WEP" -> {
                val pass = wifi.password ?: return null
                builder.setWpa2Passphrase(pass)
            }
            else -> {
                val pass = wifi.password ?: return null
                builder.setWpa2Passphrase(pass)
            }
        }
        if (wifi.hidden) builder.setIsHiddenSsid(true)
        return builder.build()
    }

    @Suppress("unused")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun makePendingIntent(context: Context): PendingIntent {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
