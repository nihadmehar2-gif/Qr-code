package com.nihadmehar.qrscanner.util

import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nihadmehar.qrscanner.data.QrContent

/**
 * Builds the QR-encodable string for each QR content type (Wi-Fi, vCard, SMS, etc.)
 * and renders it to a Bitmap with custom foreground/background colors.
 */
object QrGenerator {

    /** Default 512px QR bitmap. */
    fun render(content: String, sizePx: Int = 512, fgColor: Int, bgColor: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = bitMatrix.width
        val h = bitMatrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (bitMatrix[x, y]) fgColor else bgColor
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Plain text payload (used for any text/URL). */
    fun textPayload(text: String): String = text

    fun wifiPayload(ssid: String, password: String, security: String, hidden: Boolean): String {
        val t = when (security.uppercase()) {
            "WPA", "WPA2", "WPA3" -> "WPA"
            "WEP" -> "WEP"
            else -> "nopass"
        }
        val p = if (security.equals("nopass", true)) "" else escapeWifi(password)
        val sb = StringBuilder("WIFI:")
        sb.append("S:").append(escapeWifi(ssid)).append(';')
        sb.append("T:").append(t).append(';')
        if (p.isNotEmpty()) sb.append("P:").append(p).append(';')
        if (hidden) sb.append("H:true;")
        sb.append(';')
        return sb.toString()
    }

    private fun escapeWifi(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\"", "\\\"").replace(":", "\\:")

    fun phonePayload(number: String): String = "tel:${number.trim()}"

    fun emailPayload(email: String, subject: String?, body: String?): String {
        val sb = StringBuilder("mailto:").append(Uri.encode(email.trim()))
        val params = mutableListOf<Pair<String, String>>()
        subject?.let { params += "subject" to it }
        body?.let { params += "body" to it }
        if (params.isNotEmpty()) {
            sb.append('?').append(params.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" })
        }
        return sb.toString()
    }

    fun smsPayload(number: String, message: String?): String {
        return if (message.isNullOrEmpty()) "smsto:${number.trim()}"
               else "smsto:${number.trim()}:${message}"
    }

    fun vCardPayload(
        name: String,
        organization: String?,
        title: String?,
        phone: String?,
        email: String?,
        url: String?,
        note: String?
    ): String {
        val sb = StringBuilder("BEGIN:VCARD\nVERSION:3.0\n")
        if (name.isNotBlank()) sb.append("FN:").append(escapeVCard(name)).append('\n')
        if (organization?.isNotBlank() == true) sb.append("ORG:").append(escapeVCard(organization)).append('\n')
        if (title?.isNotBlank() == true) sb.append("TITLE:").append(escapeVCard(title)).append('\n')
        if (phone?.isNotBlank() == true) sb.append("TEL;TYPE=CELL:").append(escapeVCard(phone)).append('\n')
        if (email?.isNotBlank() == true) sb.append("EMAIL:").append(escapeVCard(email)).append('\n')
        if (url?.isNotBlank() == true) sb.append("URL:").append(escapeVCard(url)).append('\n')
        if (note?.isNotBlank() == true) sb.append("NOTE:").append(escapeVCard(note)).append('\n')
        sb.append("END:VCARD")
        return sb.toString()
    }

    private fun escapeVCard(s: String): String =
        s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

    /** Convenience wrapper so existing callers can pass a [QrContent] if needed. */
    fun toPayload(content: QrContent): String = when (content) {
        is QrContent.Text -> content.raw
        is QrContent.Url -> content.url
        is QrContent.Wifi -> wifiPayload(
            content.ssid, content.password.orEmpty(), content.security, content.hidden
        )
        is QrContent.VCard -> vCardPayload(
            name = content.name.orEmpty(),
            organization = content.organization,
            title = content.title,
            phone = content.phones.firstOrNull(),
            email = content.emails.firstOrNull(),
            url = content.urls.firstOrNull(),
            note = content.note
        )
        is QrContent.Phone -> phonePayload(content.number)
        is QrContent.Email -> emailPayload(content.address, content.subject, content.body)
        is QrContent.Sms -> smsPayload(content.number, content.message)
        is QrContent.Geo -> "geo:${content.lat},${content.lng}"
        is QrContent.CalendarEvent -> content.raw
        is QrContent.Crypto -> content.raw
    }
}
