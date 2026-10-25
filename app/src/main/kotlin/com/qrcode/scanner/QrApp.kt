package com.qrcode.scanner

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import com.qrcode.scanner.data.AppDatabase
import com.qrcode.scanner.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application entry point.
 *
 * - Reads the saved dark-mode preference from DataStore on a background thread, then applies
 *   it on the MAIN thread (AppCompatDelegate.setDefaultNightMode must be called from the
 *   main thread — calling it from a background thread can corrupt the delegate state and
 *   cause crashes on activity recreate).
 * - Warms up the Room database on a background thread so the first scan is fast.
 */
class QrApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Apply the saved theme: read on IO thread, apply on main thread.
        appScope.launch {
            val s = SettingsManager.get(this@QrApp)
            withContext(Dispatchers.Main) {
                SettingsManager.applyTheme(this@QrApp, s.themeMode)
            }
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
