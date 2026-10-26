package com.nihadmehar.qrscanner.data

/**
 * Detected QR content types and parsed payloads.
 *
 * Parsing follows the de-facto QR conventions used by ZXing / iOS Camera:
 *   - URLs: schemes https/http/ftp/market/etc.
 *   - Wi-Fi:  WIFI:S:<ssid>;T:<auth>;P:<password>;H:<hidden>;;
 *   - vCard:  BEGIN:VCARD ... END:VCARD
 *   - mecard: MECARD:N:<name>;TEL:<tel>;EMAIL:<email>;;;
 *   - Phone:  tel:+1234567890
 *   - Email:  mailto:a@b.com?subject=...&body=...
 *   - SMS:    sms:+1234567890?body=...   or  smsto:number:message
 *   - Geo:    geo:lat,lng?q=...
 *   - Crypto: bitcoin:1Address?amount=0.001
 *   - Calendar:  BEGIN:VCALENDAR / VEVENT / END:VCALENDAR  (or BEGIN:VEVENT)
 *
 * Anything else falls back to plain text.
 *
 * NOTE: this class deliberately does NOT depend on android.net.Uri so that
 * the parsing logic can be unit-tested on the JVM (no Robolectric needed).
 * The [percentDecode] helper below is a tiny URL-decoder that mirrors
 * android.net.percentDecode() behavior (specifically: '+' is preserved as '+',
 * not converted to space — that matches the de-facto QR-scanner convention
 * for mailto / sms subjects).
 */
sealed class QrContent {
    abstract val raw: String
    abstract val typeLabelRes: Int

    data class Text(override val raw: String) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_text
    }

    data class Url(
        override val raw: String,
        val url: String,
        val isLikelySafe: Boolean
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_url
    }

    data class Wifi(
        override val raw: String,
        val ssid: String,
        val password: String?,
        val security: String, // WPA, WEP, nopass, or empty if unknown
        val hidden: Boolean
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_wifi
    }

    data class VCard(
        override val raw: String,
        val name: String?,
        val organization: String?,
        val title: String?,
        val phones: List<String>,
        val emails: List<String>,
        val urls: List<String>,
        val note: String?,
        val addresses: List<String>
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_vcard
    }

    data class Phone(
        override val raw: String,
        val number: String
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_phone
    }

    data class Email(
        override val raw: String,
        val address: String,
        val subject: String?,
        val body: String?
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_email
    }

    data class Sms(
        override val raw: String,
        val number: String,
        val message: String?
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_sms
    }

    data class Geo(
        override val raw: String,
        val lat: Double,
        val lng: Double,
        val query: String?
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_geo
    }

    data class CalendarEvent(
        override val raw: String,
        val title: String?,
        val description: String?,
        val location: String?,
        val start: String?,
        val end: String?
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_calendar
    }

    data class Crypto(
        override val raw: String,
        val scheme: String,
        val address: String,
        val amount: String?
    ) : QrContent() {
        override val typeLabelRes = com.nihadmehar.qrscanner.R.string.type_crypto
    }
}

object QrTypeDetector {

    private val URL_SCHEMES = setOf(
        "http", "https", "ftp", "ftps", "market", "intent", "geo", "tel", "mailto",
        "sms", "smsto", "wifi", "begin", "mecard", "bitcoin", "ethereum", "monero"
    )

    private val SUSPICIOUS_TLDS = setOf("zip", "mov", "xyz", "top", "click", "country", "stream", "gq", "tk", "ml")
    private val SUSPICIOUS_KEYWORDS = listOf(
        "login-", "verify-", "account-", "free-", "bonus-", "claim-", "gift-", "prize-",
        "-login", "-verify", "-secure", "xn--"
    )

    /**
     * Detect and parse the QR content type. Falls back to plain Text when no
     * recognised scheme is found.
     */
    fun detect(rawInput: String): QrContent {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return QrContent.Text(raw)

        val upper = raw.uppercase()
        when {
            upper.startsWith("WIFI:") -> return parseWifi(raw)
            upper.startsWith("BEGIN:VCARD") -> return parseVCard(raw)
            upper.startsWith("BEGIN:VEVENT") || upper.startsWith("BEGIN:VCALENDAR") ->
                return parseCalendar(raw)
            upper.startsWith("MECARD:") -> return parseMecard(raw)
            upper.startsWith("GEO:") -> return parseGeo(raw)
            upper.startsWith("MATMSG:") -> return parseMatmsg(raw)
            upper.startsWith("SMTP:") || upper.startsWith("SMTPS:") -> return parseSmtp(raw)
            upper.startsWith("BITCOIN:") || upper.startsWith("BITCOINCASH:") ||
                upper.startsWith("ETHEREUM:") || upper.startsWith("MONERO:") ||
                upper.startsWith("LITECOIN:") || upper.startsWith("DASH:") ->
                return parseCrypto(raw)
            upper.startsWith("SMSTO:") || upper.startsWith("SMSTOI:") -> return parseSmsTo(raw)
            upper.startsWith("MMSTO:") -> return parseMmsTo(raw)
        }

        val lower = raw.lowercase()
        when {
            lower.startsWith("tel:") -> {
                val num = percentDecode(raw.substring(4).trim())
                return QrContent.Phone(raw, num)
            }
            lower.startsWith("mailto:") -> return parseMailto(raw)
            lower.startsWith("sms:") -> return parseSmsScheme(raw)
            lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("ftp://") || lower.startsWith("ftps://") ||
                lower.startsWith("market://") -> return parseUrl(raw)
            lower.startsWith("intent://") -> return parseUrl(raw)
        }

        // Bare URL heuristic (e.g. example.com/path) — treat as URL when host has a dot and no spaces
        if (looksLikeBareUrl(raw)) return parseUrl(raw)

        return QrContent.Text(raw)
    }

    private fun looksLikeBareUrl(s: String): Boolean {
        if (s.contains(' ')) return false
        if (s.contains('\n')) return false
        val first = s.substringBefore('/')
        if (!first.contains('.')) return false
        val tld = first.substringAfterLast('.').lowercase()
        if (tld.length < 2) return false
        if (!tld.all { it.isLetter() }) return false
        return true
    }

    private fun parseUrl(raw: String): QrContent.Url {
        val isSafe = !isSuspiciousUrl(raw)
        return QrContent.Url(raw = raw, url = raw, isLikelySafe = isSafe)
    }

    /**
     * Heuristic safety check: flagspunycode homograph attacks, suspicious TLDs,
     * and very long URLs that hide the real destination in path/query params.
     */
    private fun isSuspiciousUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("xn--")) return true // punycode IDN — could be homograph
        if (lower.contains("@") && !lower.startsWith("http")) return true
        val hostPart = lower.substringAfter("://", lower).substringBefore('/')
        val tld = hostPart.substringAfterLast('.').substringBefore(':')
        if (tld in SUSPICIOUS_TLDS) return true
        SUSPICIOUS_KEYWORDS.forEach { if (hostPart.contains(it)) return true }
        // Many redirects / open-redirect params
        if (lower.contains("url=") || lower.contains("redirect=") || lower.contains("next=")) return true
        if (url.length > 200) return true
        return false
    }

    /** WIFI:S:<ssid>;T:<auth>;P:<password>;H:<hidden>;; */
    private fun parseWifi(raw: String): QrContent.Wifi {
        val body = raw.substring(5).trimEnd(';')
        val parts = mutableMapOf<String, String>()
        var i = 0
        while (i < body.length) {
            val colon = body.indexOf(':', i)
            if (colon == -1) break
            val key = body.substring(i, colon).uppercase()
            // find the next unescaped ; that ends this field
            var j = colon + 1
            val sb = StringBuilder()
            while (j < body.length) {
                val c = body[j]
                if (c == '\\' && j + 1 < body.length) {
                    sb.append(body[j + 1]); j += 2; continue
                }
                if (c == ';') break
                sb.append(c); j++
            }
            parts[key] = sb.toString()
            i = j + 1
            if (j >= body.length) break
        }
        val ssid = parts["S"].orEmpty()
        val auth = parts["T"]?.uppercase() ?: ""
        val pass = parts["P"]
        val hidden = parts["H"]?.equals("true", ignoreCase = true) == true
        return QrContent.Wifi(
            raw = raw,
            ssid = ssid,
            password = pass,
            security = auth,
            hidden = hidden
        )
    }

    /** Minimal vCard parser: handles 2.1 / 3.0 / 4.0 line folding and common properties. */
    private fun parseVCard(raw: String): QrContent {
        // Unfold: line breaks followed by space/tab should be joined.
        val unfolded = raw.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "")
        val lines = unfolded.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var name: String? = null
        var organization: String? = null
        var title: String? = null
        var note: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val addresses = mutableListOf<String>()
        if (!lines.firstOrNull().equals("BEGIN:VCARD", true)) {
            // Not a real vCard — fall back to text.
            return QrContent.Text(raw)
        }
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val property = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val propName = property.substringBefore(';').uppercase()
            val decoded = unescapeVCard(value)
            when (propName) {
                "FN" -> name = decoded
                "N" -> if (name == null) {
                    // Family;Given;Additional;Prefix;Suffix
                    val pieces = decoded.split(';')
                    val fam = pieces.getOrNull(0).orEmpty()
                    val giv = pieces.getOrNull(1).orEmpty()
                    name = listOf(giv, fam).filter { it.isNotBlank() }.joinToString(" ")
                }
                "ORG" -> organization = decoded.replace(";", ", ").trim().trimEnd(',')
                "TITLE" -> title = decoded
                "NOTE" -> note = decoded
                "TEL" -> phones.add(decoded)
                "EMAIL" -> emails.add(decoded)
                "URL" -> urls.add(decoded)
                "ADR" -> addresses.add(decoded.split(';').filter { it.isNotBlank() }.joinToString(", "))
            }
        }
        return QrContent.VCard(
            raw = raw,
            name = name,
            organization = organization,
            title = title,
            phones = phones,
            emails = emails,
            urls = urls,
            note = note,
            addresses = addresses
        )
    }

    private fun unescapeVCard(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    ',' -> sb.append(',')
                    ';' -> sb.append(';')
                    '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** MECARD:N:<name>;TEL:<tel>;EMAIL:<email>;;; */
    private fun parseMecard(raw: String): QrContent.VCard {
        val body = raw.substringAfter("MECARD:").trimEnd(';')
        val parts = body.split(';').mapNotNull { field ->
            val i = field.indexOf(':')
            if (i <= 0) null else field.substring(0, i).uppercase() to field.substring(i + 1)
        }.toMap()
        val name = parts["N"]
        val phone = parts["TEL"]
        val email = parts["EMAIL"]
        val note = parts["NOTE"]
        val sound = parts["SOUND"]
        return QrContent.VCard(
            raw = raw,
            name = name,
            organization = null,
            title = null,
            phones = listOfNotNull(phone),
            emails = listOfNotNull(email),
            urls = emptyList(),
            note = note,
            addresses = emptyList()
        ).also { /* suppress sound */ sound?.let { } }
    }

    /** geo:lat,lng?q=... */
    private fun parseGeo(raw: String): QrContent.Geo {
        val body = raw.substring(4)
        val q = if (body.contains("?q=")) body.substringAfter("?q=") else null
        val coord = if (body.contains("?")) body.substringBefore("?") else body
        val parts = coord.split(',').map { it.trim() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return QrContent.Geo(raw = raw, lat = lat, lng = lng, query = q)
    }

    /** mailto:a@b.com?subject=...&body=... */
    private fun parseMailto(raw: String): QrContent.Email {
        val body = raw.substring(7)
        val toAndQuery = if (body.contains('?')) body.substringBefore('?') else body
        val address = percentDecode(toAndQuery).trim()
        val params = parseQuery(body.substringAfter('?', ""))
        return QrContent.Email(
            raw = raw,
            address = address,
            subject = params["subject"],
            body = params["body"]
        )
    }

    /** MATMSG:TO:<email>;SUB:<subject>;BODY:<body>;;  (rare legacy) */
    private fun parseMatmsg(raw: String): QrContent.Email {
        val body = raw.substringAfter("MATMSG:").trimEnd(';')
        val parts = body.split(';').mapNotNull { field ->
            val i = field.indexOf(':')
            if (i <= 0) null else field.substring(0, i).uppercase() to field.substring(i + 1)
        }.toMap()
        return QrContent.Email(
            raw = raw,
            address = parts["TO"].orEmpty(),
            subject = parts["SUB"],
            body = parts["BODY"]
        )
    }

    /** SMTP:a@b.com:subject:body  (very rare) */
    private fun parseSmtp(raw: String): QrContent.Email {
        val body = raw.substringAfter(':')
        val pieces = body.split(':', limit = 3)
        return QrContent.Email(
            raw = raw,
            address = pieces.getOrNull(0).orEmpty(),
            subject = pieces.getOrNull(1),
            body = pieces.getOrNull(2)
        )
    }

    private fun parseSmsScheme(raw: String): QrContent.Sms {
        val body = raw.substring(4)
        val to = if (body.contains('?')) body.substringBefore('?') else body
        val params = parseQuery(body.substringAfter('?', ""))
        val number = percentDecode(to).trim()
        return QrContent.Sms(raw = raw, number = number, message = params["body"])
    }

    /** SMSTO:number:message  */
    private fun parseSmsTo(raw: String): QrContent.Sms {
        val body = raw.substringAfter(':')
        val pieces = body.split(':', limit = 2)
        return QrContent.Sms(
            raw = raw,
            number = percentDecode(pieces.getOrNull(0).orEmpty()).trim(),
            message = pieces.getOrNull(1)
        )
    }

    private fun parseMmsTo(raw: String): QrContent.Sms {
        val body = raw.substringAfter(':')
        val pieces = body.split(':', limit = 2)
        return QrContent.Sms(
            raw = raw,
            number = percentDecode(pieces.getOrNull(0).orEmpty()).trim(),
            message = pieces.getOrNull(1)
        )
    }

    private fun parseCrypto(raw: String): QrContent.Crypto {
        val colon = raw.indexOf(':')
        val scheme = raw.substring(0, colon).lowercase()
        val body = raw.substring(colon + 1)
        val addr = body.substringBefore('?').ifEmpty { body }
        val params = parseQuery(body.substringAfter('?', ""))
        return QrContent.Crypto(raw = raw, scheme = scheme, address = addr, amount = params["amount"])
    }

    private fun parseCalendar(raw: String): QrContent.CalendarEvent {
        val unfolded = raw.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "")
        val lines = unfolded.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var title: String? = null
        var desc: String? = null
        var location: String? = null
        var start: String? = null
        var end: String? = null
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val property = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val propName = property.substringBefore(';').uppercase()
            when (propName) {
                "SUMMARY" -> title = value
                "DESCRIPTION" -> desc = value
                "LOCATION" -> location = value
                "DTSTART" -> start = value
                "DTEND" -> end = value
            }
        }
        return QrContent.CalendarEvent(
            raw = raw, title = title, description = desc, location = location, start = start, end = end
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        query.split('&').forEach { kv ->
            val eq = kv.indexOf('=')
            if (eq > 0) {
                val k = kv.substring(0, eq)
                val v = kv.substring(eq + 1)
                result[k] = percentDecode(v)
            } else if (kv.isNotEmpty()) {
                result[kv] = ""
            }
        }
        return result
    }

    /**
     * Minimal RFC 3986 percent-decoder. Mirrors android.net.Uri.decode()
     * behavior:
     *   - '+' is preserved as '+' (NOT converted to space — that's
     *     application/x-www-form-urlencoded, which is different from URI-decode).
     *   - Multi-byte UTF-8 sequences (e.g. %C3%A9 → é) are decoded correctly
     *     by collecting decoded bytes into a buffer and converting them as
     *     UTF-8 at the end. This works because UTF-8 is self-synchronizing:
     *     ASCII literal chars (0x00-0x7F) appear as themselves in the byte
     *     stream, and multi-byte sequences are distinguished by their leading
     *     byte (0xC0+).
     *
     * Implemented here (rather than using android.net.Uri) so the parser can
     * be unit-tested on plain JVM without Robolectric.
     *
     * Malformed sequences (incomplete '%' or non-hex digits) are passed through
     * literally — same behavior as Uri.decode().
     */
    internal fun percentDecode(s: String): String {
        if (s.isEmpty() || '%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // Need at least 2 chars after '%' to read the hex pair.
            if (c == '%' && i + 2 < s.length) {
                val hi = s[i + 1].hexDigitOrNull()
                val lo = s[i + 2].hexDigitOrNull()
                if (hi != null && lo != null) {
                    // Write the decoded BYTE — multi-byte UTF-8 sequences will
                    // accumulate in the buffer and be decoded together when we
                    // convert to String below.
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            // Literal char (including literal '%' or '+'): encode as UTF-8 bytes
            // and append to the same buffer. ASCII chars are single-byte in UTF-8,
            // so they mix cleanly with the percent-decoded bytes above.
            out.write(c.toString().toByteArray(charset("UTF-8")))
            i++
        }
        return out.toString("UTF-8")
    }

    private fun Char.hexDigitOrNull(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }
}
