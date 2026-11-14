package com.bigeyes.app

import com.bigeyes.app.dlna.DlnaController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaControllerTest {

    private val controller = DlnaController()

    @Test
    fun testEscapeXmlEscapesSpecialCharacters() {
        val raw = "A & B <tag> \"quoted\" 'single'"
        val escaped = controller.escapeXml(raw)
        assertEquals("A &amp; B &lt;tag&gt; &quot;quoted&quot; &apos;single&apos;", escaped)
    }

    @Test
    fun testInferMimeTypeByExtension() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            controller.inferMimeType("https://example.com/live/index.m3u8?token=a&b=c")
        )
        assertEquals("video/mp4", controller.inferMimeType("https://cdn.example.com/movie.m4v?auth=1"))
        assertEquals("video/mp4", controller.inferMimeType("https://cdn.example.com/movie.mp4?auth=1"))
        assertEquals("video/x-matroska", controller.inferMimeType("https://cdn.example.com/movie.mkv"))
        assertEquals("video/webm", controller.inferMimeType("https://cdn.example.com/clip.webm"))
        assertEquals("video/*", controller.inferMimeType("https://cdn.example.com/stream/unknown"))
    }

    @Test
    fun testBuildDidlMetadataEscapesUriAndTitle() {
        val uri = "https://cdn.example.com/v.mp4?token=1&mode=2"
        val title = "Test & <Movie> \"Ep 1\""
        val didl = controller.buildDidlMetadata(uri, title)

        assertTrue(didl.contains("<dc:title>Test &amp; &lt;Movie&gt; &quot;Ep 1&quot;</dc:title>"))
        assertTrue(didl.contains("https://cdn.example.com/v.mp4?token=1&amp;mode=2"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
    }
}
