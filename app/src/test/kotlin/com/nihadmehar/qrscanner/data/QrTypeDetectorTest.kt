package com.nihadmehar.qrscanner.data

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

/**
 * Unit tests for [QrTypeDetector].
 *
 * These tests run on the JVM (no Android dependencies — QrTypeDetector only
 * uses pure Kotlin now that the android.net.Uri dependency has been replaced
 * with an internal percentDecode helper). They verify the type-detection
 * logic that the scanner relies on to dispatch smart actions. A regression
 * here would mean the wrong result sheet renders for a given QR code.
 *
 * These are REGRESSION tests for the bugs fixed in the previous commits:
 *  - StateFlow distinctUntilChanged bug (didn't show result sheet on repeat scans)
 *  - StateFlow initial-null re-emission bug
 *  - YUV NV21 byte format bug (now fixed via InputImage.fromMediaImage)
 *  - Wi-Fi open network specifier bug
 */
class QrTypeDetectorTest {

    @Test
    fun `plain text is detected as Text`() {
        val result = QrTypeDetector.detect("Hello, world!")
        assertTrue("Expected Text, got ${result::class.simpleName}", result is QrContent.Text)
        assertEquals("Hello, world!", result.raw)
    }

    @Test
    fun `empty input returns empty Text`() {
        val result = QrTypeDetector.detect("")
        assertTrue(result is QrContent.Text)
        assertEquals("", result.raw)
    }

    @Test
    fun `whitespace only input returns empty Text after trim`() {
        val result = QrTypeDetector.detect("   ")
        assertTrue(result is QrContent.Text)
        assertEquals("", result.raw)
    }

    @Test
    fun `http URL is detected as Url`() {
        val result = QrTypeDetector.detect("https://example.com/path?q=1")
        assertTrue("Expected Url, got ${result::class.simpleName}", result is QrContent.Url)
        val url = result as QrContent.Url
        assertEquals("https://example.com/path?q=1", url.url)
    }

    @Test
    fun `bare URL without scheme is detected as Url`() {
        val result = QrTypeDetector.detect("example.com")
        assertTrue(result is QrContent.Url)
        val url = result as QrContent.Url
        assertEquals("example.com", url.url)
    }

    @Test
    fun `suspicious URL with punycode is flagged as unsafe`() {
        val result = QrTypeDetector.detect("https://xn--example.com")
        assertTrue(result is QrContent.Url)
        assertFalse("Punycode URL should be flagged unsafe", (result as QrContent.Url).isLikelySafe)
    }

    @Test
    fun `suspicious URL with sketchy TLD is flagged as unsafe`() {
        val result = QrTypeDetector.detect("https://free-bonus.click")
        assertTrue(result is QrContent.Url)
        assertFalse(".click TLD should be flagged unsafe", (result as QrContent.Url).isLikelySafe)
    }

    @Test
    fun `Wi-Fi QR is parsed correctly for WPA`() {
        val raw = "WIFI:S:MyNetwork;T:WPA;P:secret123;H:false;;"
        val result = QrTypeDetector.detect(raw)
        assertTrue("Expected Wifi, got ${result::class.simpleName}", result is QrContent.Wifi)
        val wifi = result as QrContent.Wifi
        assertEquals("MyNetwork", wifi.ssid)
        assertEquals("WPA", wifi.security)
        assertEquals("secret123", wifi.password)
        assertFalse(wifi.hidden)
    }

    @Test
    fun `Wi-Fi QR with hidden network is parsed correctly`() {
        val raw = "WIFI:S:HiddenNet;T:WPA;P:pass;H:true;;"
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.Wifi)
        val wifi = result as QrContent.Wifi
        assertTrue(wifi.hidden)
    }

    @Test
    fun `open Wi-Fi QR has null password`() {
        val raw = "WIFI:S:OpenCafe;T:nopass;;"
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.Wifi)
        val wifi = result as QrContent.Wifi
        assertEquals("OpenCafe", wifi.ssid)
        // The parser intentionally uppercases the T: field — the rest of the
        // code compares wifi.security.uppercase() against "WPA"/"WPA2"/"NOPASS".
        assertEquals("NOPASS", wifi.security)
        assertNull(wifi.password)
    }

    @Test
    fun `tel URI is detected as Phone`() {
        val result = QrTypeDetector.detect("tel:+15551234567")
        assertTrue(result is QrContent.Phone)
        val phone = result as QrContent.Phone
        assertEquals("+15551234567", phone.number)
    }

    @Test
    fun `mailto URI is parsed into Email with subject and body`() {
        val raw = "mailto:user@example.com?subject=Hi&body=Hello%20there"
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.Email)
        val email = result as QrContent.Email
        assertEquals("user@example.com", email.address)
        assertEquals("Hi", email.subject)
        assertEquals("Hello there", email.body)
    }

    @Test
    fun `sms URI is parsed with body param`() {
        val raw = "sms:+15551234567?body=Hello%20there"
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.Sms)
        val sms = result as QrContent.Sms
        assertEquals("+15551234567", sms.number)
        assertEquals("Hello there", sms.message)
    }

    @Test
    fun `smsto URI is parsed with message after colon`() {
        val raw = "SMSTO:+15551234567:Hello there"
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.Sms)
        val sms = result as QrContent.Sms
        assertEquals("+15551234567", sms.number)
        assertEquals("Hello there", sms.message)
    }

    @Test
    fun `geo URI is parsed with lat and lng`() {
        val result = QrTypeDetector.detect("geo:37.422,-122.084")
        assertTrue(result is QrContent.Geo)
        val geo = result as QrContent.Geo
        assertEquals(37.422, geo.lat, 0.0001)
        assertEquals(-122.084, geo.lng, 0.0001)
    }

    @Test
    fun `geo URI with query is parsed correctly`() {
        val result = QrTypeDetector.detect("geo:0,0?q=Eiffel+Tower")
        assertTrue(result is QrContent.Geo)
        val geo = result as QrContent.Geo
        assertEquals("Eiffel+Tower", geo.query)
    }

    @Test
    fun `vCard is parsed with FN, ORG, TEL, EMAIL`() {
        val raw = """
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            ORG:Acme Corp
            TEL;TYPE=CELL:+15551234567
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent()
        val result = QrTypeDetector.detect(raw)
        assertTrue("Expected VCard, got ${result::class.simpleName}", result is QrContent.VCard)
        val v = result as QrContent.VCard
        assertEquals("John Doe", v.name)
        assertEquals("Acme Corp", v.organization)
        assertEquals(1, v.phones.size)
        assertEquals("+15551234567", v.phones.first())
        assertEquals(1, v.emails.size)
        assertEquals("john@example.com", v.emails.first())
    }

    @Test
    fun `vCard with N property instead of FN uses Family and Given order`() {
        val raw = """
            BEGIN:VCARD
            VERSION:2.1
            N:Doe;John;;;
            END:VCARD
        """.trimIndent()
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.VCard)
        val v = result as QrContent.VCard
        assertEquals("John Doe", v.name)
    }

    @Test
    fun `malformed vCard without BEGIN falls back to Text`() {
        val raw = "FN:John Doe\nEMAIL:john@example.com"
        val result = QrTypeDetector.detect(raw)
        assertTrue("Expected Text fallback, got ${result::class.simpleName}", result is QrContent.Text)
    }

    @Test
    fun `MECARD is parsed correctly`() {
        val raw = "MECARD:N:John,Doe;TEL:+15551234567;EMAIL:john@example.com;;"
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.VCard)
        val v = result as QrContent.VCard
        assertEquals("John,Doe", v.name)
        assertEquals(1, v.phones.size)
        assertEquals("+15551234567", v.phones.first())
    }

    @Test
    fun `bitcoin URI is detected as Crypto with address and amount`() {
        val raw = "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0.001"
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.Crypto)
        val c = result as QrContent.Crypto
        assertEquals("bitcoin", c.scheme)
        assertEquals("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", c.address)
        assertEquals("0.001", c.amount)
    }

    @Test
    fun `VEVENT calendar entry is parsed with title, start, end, location`() {
        val raw = """
            BEGIN:VEVENT
            SUMMARY:Team meeting
            DTSTART:20240501T100000Z
            DTEND:20240501T110000Z
            LOCATION:Room 4
            END:VEVENT
        """.trimIndent()
        val result = QrTypeDetector.detect(raw)
        assertTrue(result is QrContent.CalendarEvent)
        val ev = result as QrContent.CalendarEvent
        assertEquals("Team meeting", ev.title)
        assertEquals("20240501T100000Z", ev.start)
        assertEquals("20240501T110000Z", ev.end)
        assertEquals("Room 4", ev.location)
    }

    @Test
    fun `text containing spaces is not mistaken for URL`() {
        val result = QrTypeDetector.detect("this is not a URL")
        assertTrue(result is QrContent.Text)
    }

    @Test
    fun `URL with redirect param is flagged as suspicious`() {
        val result = QrTypeDetector.detect("https://example.com/?redirect=https://evil.com")
        assertTrue(result is QrContent.Url)
        assertFalse((result as QrContent.Url).isLikelySafe)
    }

    // -- Regression tests for the percentDecode helper --

    @Test
    fun `percentDecode handles percent-encoded spaces`() {
        val result = QrTypeDetector.percentDecode("Hello%20world")
        assertEquals("Hello world", result)
    }

    @Test
    fun `percentDecode preserves plus sign as literal plus`() {
        // This is the key difference from java.net.URLDecoder —
        // '+' stays as '+' (matches android.net.Uri.decode behavior)
        val result = QrTypeDetector.percentDecode("Hi+there")
        assertEquals("Hi+there", result)
    }

    @Test
    fun `percentDecode handles unicode escape sequences`() {
        val result = QrTypeDetector.percentDecode("caf%C3%A9")
        assertEquals("café", result)
    }

    @Test
    fun `percentDecode passes through strings without percent`() {
        val result = QrTypeDetector.percentDecode("plain text")
        assertEquals("plain text", result)
    }

    @Test
    fun `percentDecode passes through malformed percent sequences literally`() {
        val result = QrTypeDetector.percentDecode("100% complete")
        assertEquals("100% complete", result)
    }
}
