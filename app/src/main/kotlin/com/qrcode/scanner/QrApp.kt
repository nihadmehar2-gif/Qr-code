package com.qrcode.scanner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.qrcode.scanner.data.AppDatabase
import com.qrcode.scanner.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Initializes the dark-mode preference (live-updates when the user
 * changes it from Settings) and warms up the Room database on a background thread.
 */
class QrApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Apply the saved theme synchronously by reading from DataStore on a background thread.
        appScope.launch {
            val s = SettingsManager.get(this@QrApp)
            SettingsManager.applyTheme(this@QrApp, s.themeMode)
        }
        // Warm up the DB on a background thread so the first scan is fast.
        AppDatabase.getInstance(this)
    }

    companion object {
        @Volatile
        private var instance: QrApp? = null
        fun get(): QrApp = instance ?: error("QrApp not initialized")
    }
}
