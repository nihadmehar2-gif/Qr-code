package com.nihadmehar.qrscanner.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.nihadmehar.qrscanner.R

/**
 * Single source of truth for scan history. Converts a parsed [QrContent] into a
 * [ScanHistoryEntity] (with a short title / subtitle for the list UI) and persists it
 * via Room.
 */
class ScanHistoryRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).scanHistoryDao()

    fun observeAll(): LiveData<List<ScanHistoryEntity>> = dao.observeAll()
    fun observeFavorites(): LiveData<List<ScanHistoryEntity>> = dao.observeFavorites()

    suspend fun insert(content: QrContent): Long {
        val entity = ScanHistoryEntity(
            rawContent = content.raw,
            typeLabel = typeLabelKey(content),
            title = titleFor(content),
            subtitle = subtitleFor(content)
        )
        return dao.insert(entity)
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun clearAll() = dao.clearAll()
    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)
    suspend fun getById(id: Long): ScanHistoryEntity? = dao.getById(id)

    /** Stable type-key string (used for filtering / migration); the UI uses [typeLabelRes]. */
    private fun typeLabelKey(content: QrContent): String = when (content) {
        is QrContent.Text -> "text"
        is QrContent.Url -> "url"
        is QrContent.Wifi -> "wifi"
        is QrContent.VCard -> "vcard"
        is QrContent.Phone -> "phone"
        is QrContent.Email -> "email"
        is QrContent.Sms -> "sms"
        is QrContent.Geo -> "geo"
        is QrContent.CalendarEvent -> "calendar"
        is QrContent.Crypto -> "crypto"
    }

    fun typeLabelRes(typeKey: String): Int = when (typeKey) {
        "text" -> R.string.type_text
        "url" -> R.string.type_url
        "wifi" -> R.string.type_wifi
        "vcard" -> R.string.type_vcard
        "phone" -> R.string.type_phone
        "email" -> R.string.type_email
        "sms" -> R.string.type_sms
        "geo" -> R.string.type_geo
        "calendar" -> R.string.type_calendar
        "crypto" -> R.string.type_crypto
        else -> R.string.type_unknown
    }

    /** Returns a short human-readable label (translated) for the entity. */
    fun typeLabelString(ctx: Context, typeKey: String): String =
        ctx.getString(typeLabelRes(typeKey))

    private fun titleFor(content: QrContent): String = when (content) {
        is QrContent.Text -> content.raw.take(80)
        is QrContent.Url -> content.url
        is QrContent.Wifi -> content.ssid.ifBlank { "Wi-Fi" }
        is QrContent.VCard -> content.name ?: content.phones.firstOrNull() ?: content.emails.firstOrNull() ?: "Contact"
        is QrContent.Phone -> content.number
        is QrContent.Email -> content.address
        is QrContent.Sms -> content.number
        is QrContent.Geo -> "%.5f, %.5f".format(content.lat, content.lng)
        is QrContent.CalendarEvent -> content.title ?: "Calendar event"
        is QrContent.Crypto -> content.address
    }

    private fun subtitleFor(content: QrContent): String = when (content) {
        is QrContent.Text -> ""
        is QrContent.Url -> ""
        is QrContent.Wifi -> content.security.ifBlank { "WPA" }
        is QrContent.VCard -> listOfNotNull(
            content.phones.firstOrNull(),
            content.emails.firstOrNull(),
            content.organization
        ).joinToString(" · ")
        is QrContent.Phone -> ""
        is QrContent.Email -> content.subject ?: ""
        is QrContent.Sms -> content.message?.take(60) ?: ""
        is QrContent.Geo -> content.query ?: ""
        is QrContent.CalendarEvent -> content.start ?: ""
        is QrContent.Crypto -> "${content.scheme.uppercase()} · ${content.amount ?: ""}"
    }.take(120)
}
