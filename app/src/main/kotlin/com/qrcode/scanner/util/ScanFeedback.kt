package com.qrcode.scanner.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.qrcode.scanner.util.SettingsManager.Settings as AppSettings

/**
 * Plays a short beep and/or a tiny vibration after a successful scan.
 * Respects the user's settings and the device's silent-mode toggle.
 */
object ScanFeedback {

    fun feedback(context: Context, settings: AppSettings) {
        if (settings.beepOnScan) beep()
        if (settings.vibrateOnScan) vibrate(context)
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            // ToneGenerator auto-releases; small leak is acceptable for a single beep.
        } catch (_: Exception) { /* ignore */ }
    }

    private fun vibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) { /* ignore */ }
    }
}
