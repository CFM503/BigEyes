package com.bigeyes.app.browser

import org.junit.Assert.*
import org.junit.Test

class WebViewDownloadHelperTest {

    @Test
    fun testParseDataUriBase64() {
        val base64Json = "data:application/json;base64,eyJmb28iOiJiYXIifQ=="
        val parsed = WebViewDownloadHelper.parseDataUri(base64Json)

        assertNotNull(parsed)
        assertEquals("application/json", parsed?.mimeType)
        assertEquals("json", parsed?.suggestedExtension)
        assertEquals("{\"foo\":\"bar\"}", String(parsed!!.data, Charsets.UTF_8))
    }

    @Test
    fun testParseDataUriPlainText() {
        val textData = "data:text/plain;charset=utf-8,Hello%20World%21"
        val parsed = WebViewDownloadHelper.parseDataUri(textData)

        assertNotNull(parsed)
        assertEquals("text/plain", parsed?.mimeType)
        assertEquals("txt", parsed?.suggestedExtension)
        assertEquals("Hello World!", String(parsed!!.data, Charsets.UTF_8))
    }

    @Test
    fun testParseInvalidDataUri() {
        val invalid1 = "http://example.com/file.json"
        assertNull(WebViewDownloadHelper.parseDataUri(invalid1))

        val invalid2 = "data:invalid_without_comma"
        assertNull(WebViewDownloadHelper.parseDataUri(invalid2))
    }

    @Test
    fun testGuessExtensionFromMimeType() {
        assertEquals("json", WebViewDownloadHelper.guessExtensionFromMimeType("application/json"))
        assertEquals("txt", WebViewDownloadHelper.guessExtensionFromMimeType("text/plain"))
        assertEquals("html", WebViewDownloadHelper.guessExtensionFromMimeType("text/html"))
        assertEquals("png", WebViewDownloadHelper.guessExtensionFromMimeType("image/png"))
        assertEquals("jpg", WebViewDownloadHelper.guessExtensionFromMimeType("image/jpeg"))
        assertEquals("bin", WebViewDownloadHelper.guessExtensionFromMimeType("application/octet-stream"))
    }

    @Test
    fun testSanitizeFilename() {
        val clean1 = WebViewDownloadHelper.sanitizeFilename("test/bad*name?.json")
        assertEquals("test_bad_name_.json", clean1)

        val clean2 = WebViewDownloadHelper.sanitizeFilename("no_extension", "json")
        assertEquals("no_extension.json", clean2)

        val clean3 = WebViewDownloadHelper.sanitizeFilename("", "txt")
        assertTrue(clean3.startsWith("bigeyes_export_"))
        assertTrue(clean3.endsWith(".txt"))
    }
}
