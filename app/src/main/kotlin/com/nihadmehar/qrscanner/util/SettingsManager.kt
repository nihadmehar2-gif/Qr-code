package com.nihadmehar.qrscanner.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Lightweight settings store backed by Preferences DataStore. Holds the user's
 * dark-mode preference, scan beep / vibrate / save-history toggles and the
 * "open result automatically" toggle. Defaults are conservative (history on,
 * beep off, vibrate on, auto-open on).
 */
private val Context.dataStore by preferencesDataStore(name = "qr_settings")

object SettingsManager {

    enum class ThemeMode(val storageKey: String, val delegateMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        ON("on", AppCompatDelegate.MODE_NIGHT_YES),
        OFF("off", AppCompatDelegate.MODE_NIGHT_NO);

        companion object {
            fun fromKey(key: String?): ThemeMode =
                values().firstOrNull { it.storageKey == key } ?: SYSTEM
        }
    }

    data class Settings(
        val themeMode: ThemeMode,
        val beepOnScan: Boolean,
        val vibrateOnScan: Boolean,
        val openResultAuto: Boolean,
        val saveHistory: Boolean
    )

    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_BEEP = booleanPreferencesKey("beep_on_scan")
    private val KEY_VIBRATE = booleanPreferencesKey("vibrate_on_scan")
    private val KEY_OPEN_AUTO = booleanPreferencesKey("open_result_auto")
    private val KEY_SAVE_HISTORY = booleanPreferencesKey("save_history")

    suspend fun get(context: Context): Settings {
        val prefs = context.dataStore.data.first()
        return Settings(
            themeMode = ThemeMode.fromKey(prefs[KEY_THEME]),
            beepOnScan = prefs[KEY_BEEP] ?: false,
            vibrateOnScan = prefs[KEY_VIBRATE] ?: true,
            openResultAuto = prefs[KEY_OPEN_AUTO] ?: true,
            saveHistory = prefs[KEY_SAVE_HISTORY] ?: true
        )
    }

    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.storageKey }
        applyTheme(context, mode)
    }

    fun applyTheme(context: Context, mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode)
    }

    suspend fun setBeep(context: Context, on: Boolean) {
        context.dataStore.edit { it[KEY_BEEP] = on }
    }

    suspend fun setVibrate(context: Context, on: Boolean) {
        context.dataStore.edit { it[KEY_VIBRATE] = on }
    }

    suspend fun setOpenResultAuto(context: Context, on: Boolean) {
        context.dataStore.edit { it[KEY_OPEN_AUTO] = on }
    }

    suspend fun setSaveHistory(context: Context, on: Boolean) {
        context.dataStore.edit { it[KEY_SAVE_HISTORY] = on }
    }
}
