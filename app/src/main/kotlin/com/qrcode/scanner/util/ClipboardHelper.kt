package com.qrcode.scanner.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.qrcode.scanner.R

/**
 * Thin wrapper around Android's [ClipboardManager]. Shows a translated toast on success.
 */
object ClipboardHelper {

    fun copy(context: Context, label: String, value: String, toastMessageRes: Int = R.string.toast_copied) {
        if (value.isEmpty()) {
            Toast.makeText(context, R.string.toast_invalid_input, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, toastMessageRes, Toast.LENGTH_SHORT).show()
    }

    fun copyPassword(context: Context, value: String) {
        copy(context, "Wi-Fi password", value, R.string.toast_copied_password)
    }

    fun copyName(context: Context, value: String) {
        copy(context, "Contact name", value, R.string.toast_copied_name)
    }
}
