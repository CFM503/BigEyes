package com.bigeyes.app.browser

import org.junit.Assert.*
import org.junit.Test

class SnifferWebViewClientTest {

    @Test
    fun testIsVideoStreamUrl() {
        val client = SnifferWebViewClient()

        // HLS m3u8 streams
        assertTrue(client.isM3U8Stream("https://example.com/live/stream.m3u8"))
        assertTrue(client.isM3U8Stream("https://example.com/video.m3u8?token=12345"))
        assertTrue(client.isM3U8Stream("https://cdn.provider.com/hls/master.m3u8"))
        assertTrue(client.isM3U8Stream("https://cdn.provider.com/hls/playlist_hd.m3u8"))

        // MP4, FLV, WebM, TS streams
        assertTrue(VideoSnifferHelper.isVideoStreamUrl("https://example.com/video.mp4"))
        assertTrue(VideoSnifferHelper.isVideoStreamUrl("https://example.com/stream.flv?auth=abc"))
        assertTrue(VideoSnifferHelper.isVideoStreamUrl("https://example.com/clip.webm"))

        // Nested URL encoded parameter
        assertTrue(VideoSnifferHelper.isVideoStreamUrl("https://player.example.com/play?url=https%3A%2F%2Fcdn.com%2Fvideo%2Findex.m3u8"))
        assertEquals(
            "https://cdn.com/video/index.m3u8",
            VideoSnifferHelper.extractDirectVideoUrl("https://player.example.com/play?url=https%3A%2F%2Fcdn.com%2Fvideo%2Findex.m3u8")
        )

        // Non-media static web assets and HTML/API pages
        assertFalse(client.isM3U8Stream("https://example.com/page.html"))
        assertFalse(client.isM3U8Stream("https://example.com/style.css"))
        assertFalse(client.isM3U8Stream("https://example.com/app.js"))
        assertFalse(VideoSnifferHelper.isVideoStreamUrl("https://example.com/logo.png"))
        assertFalse(VideoSnifferHelper.isVideoStreamUrl("https://example.com/font.woff2"))
        assertFalse(VideoSnifferHelper.isVideoStreamUrl("https://site.com/static/video/player.js"))

        // Segment slices should not be treated as standalone playable streams
        assertFalse(VideoSnifferHelper.isVideoStreamUrl("https://hnts.ymuuy.com:65/hls/918/20260710/4270253/1.ts"))
        assertFalse(VideoSnifferHelper.isVideoStreamUrl("https://example.com/segment_0.ts"))
        assertFalse(VideoSnifferHelper.isVideoStreamUrl("https://example.com/chunk.m4s"))
        assertFalse(VideoSnifferHelper.isVideoStreamUrl("https://example.com/enc.key"))
    }
}
