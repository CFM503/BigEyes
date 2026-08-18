package com.bigeyes.app.browser

import org.junit.Assert.*
import org.junit.Test

class SnifferWebViewClientTest {

    @Test
    fun testIsM3U8Stream() {
        val client = SnifferWebViewClient()

        assertTrue(client.isM3U8Stream("https://example.com/live/stream.m3u8"))
        assertTrue(client.isM3U8Stream("https://example.com/video.m3u8?token=12345"))
        assertTrue(client.isM3U8Stream("https://cdn.provider.com/hls/master.m3u8"))
        assertTrue(client.isM3U8Stream("https://cdn.provider.com/hls/playlist_hd.m3u8"))

        assertFalse(client.isM3U8Stream("https://example.com/page.html"))
        assertFalse(client.isM3U8Stream("https://example.com/style.css"))
        assertFalse(client.isM3U8Stream("https://example.com/app.js"))
    }
}
