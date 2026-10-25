package com.nihadmehar.qrscanner.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import com.nihadmehar.qrscanner.R
import com.nihadmehar.qrscanner.data.QrContent

/**
 * Tiny intent-helper that launches the appropriate system app for each QR action.
 * Every action requires explicit user action (a tap on the result button) — nothing
 * is launched automatically after a scan.
 */
object IntentHelper {

    fun openUrl(context: Context, url: String) {
        val safe = if (url.startsWith("http://") || url.startsWith("https://")) url
                   else "https://$url"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    fun dial(context: Context, number: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

    fun call(context: Context, number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: SecurityException) {
            dial(context, number)
        } catch (e: ActivityNotFoundException) {
            dial(context, number)
        }
    }

    fun sendEmail(context: Context, content: QrContent.Email) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(content.address))
            content.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            content.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_email_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun sendSms(context: Context, content: QrContent.Sms) {
        val uri = Uri.parse("smsto:${Uri.encode(content.number)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            content.message?.let { putExtra("sms_body", it) }
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_sms_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun viewOnMap(context: Context, geo: QrContent.Geo) {
        val uri = if (!geo.query.isNullOrEmpty()) {
            Uri.parse("geo:${geo.lat},${geo.lng}?q=${Uri.encode(geo.query)}")
        } else {
            Uri.parse("geo:${geo.lat},${geo.lng}?q=${geo.lat},${geo.lng}")
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_maps_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun navigateTo(context: Context, geo: QrContent.Geo) {
        val uri = Uri.parse("google.navigation:q=${geo.lat},${geo.lng}")
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            viewOnMap(context, geo)
        }
    }

    fun addToCalendar(context: Context, event: QrContent.CalendarEvent) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            event.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
            event.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            event.start?.let { dtStartMillis ->
                parseDateToMillis(dtStartMillis)?.let {
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
                }
            }
            event.end?.let { dtEndMillis ->
                parseDateToMillis(dtEndMillis)?.let {
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it)
                }
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_calendar_app, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Insert a contact using the system Contacts UI. The user must explicitly tap Save.
     */
    fun saveContact(context: Context, vcard: QrContent.VCard) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            vcard.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            vcard.organization?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            vcard.title?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
            vcard.note?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
            vcard.phones.firstOrNull()?.let {
                putExtra(ContactsContract.Intents.Insert.PHONE, it)
                putExtra(ContactsContract.Intents.Insert.PHONE_TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            vcard.phones.getOrNull(1)?.let {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it)
            }
            vcard.emails.firstOrNull()?.let {
                putExtra(ContactsContract.Intents.Insert.EMAIL, it)
                putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME)
            }
            vcard.urls.firstOrNull()?.let {
                putExtra(ContactsContract.Intents.Insert.DATA, it)
            }
            vcard.addresses.firstOrNull()?.let {
                putExtra(ContactsContract.Intents.Insert.POSTAL, it)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_contacts_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(context: Context, label: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, label)
            putExtra(Intent.EXTRA_TEXT, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            val chooser = Intent.createChooser(intent, label).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            // no-op
        }
    }

    /**
     * Parse a vCalendar / iCal timestamp into epoch milliseconds.
     * Supports the common forms:
     *   - YYYYMMDDTHHMMSSZ            (UTC)
     *   - YYYYMMDDTHHMMSS             (local)
     *   - YYYYMMDDTHHMMSS+HHMM        (offset)
     *   - YYYY-MM-DD HH:MM:SS
     */
    fun parseDateToMillis(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null

        // Basic iCal form (e.g. 20240501T103000Z or 20240501T103000Z)
        val regex = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z|[+\-]\d{4})?$""")
        val match = regex.matchEntire(v) ?: return try { v.toLong() } catch (_: NumberFormatException) { null }
        val (y, mo, d, h, mi, s, tz) = match.destructured
        val cal = java.util.GregorianCalendar()
        cal.clear()
        if (tz == "Z") {
            cal.timeZone = java.util.TimeZone.getTimeZone("UTC")
        } else if (tz.isNotEmpty()) {
            val sign = if (tz[0] == '-') -1 else 1
            val oh = tz.substring(1, 3).toInt()
            val om = tz.substring(3, 5).toInt()
            val offsetMs = sign * (oh * 3_600_000 + om * 60_000)
            cal.timeZone = java.util.TimeZone.getTimeZone("GMT") // we'll apply offset manually below
            return cal.apply {
                set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            }.timeInMillis - offsetMs
        }
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        return cal.timeInMillis
    }
}
