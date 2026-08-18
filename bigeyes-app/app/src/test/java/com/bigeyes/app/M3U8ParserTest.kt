package com.bigeyes.app

import com.bigeyes.app.proxy.M3U8Parser
import org.junit.Assert.*
import org.junit.Test

class M3U8ParserTest {

    private val sampleMasterPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=720x480
        low.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
        mid.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=7680000,RESOLUTION=1920x1080
        high.m3u8
    """.trimIndent()

    private val sampleMediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-KEY:METHOD=AES-128,URI="https://example.com/keys/enc.key",IV=0x1234567890abcdef1234567890abcdef
        #EXTINF:9.009,
        segment_0.ts
        #EXTINF:9.009,
        /media/hls/segment_1.ts
        #EXTINF:9.009,
        https://cdn.example.com/live/segment_2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun testMasterPlaylistParsing() {
        assertTrue(M3U8Parser.isMasterPlaylist(sampleMasterPlaylist))
        val variants = M3U8Parser.parseMasterPlaylist(sampleMasterPlaylist, "https://example.com/master.m3u8")
        assertEquals(3, variants.size)
        assertEquals("1920x1080", variants[0].resolution)
        assertEquals(7680000, variants[0].bandwidth)

        val selected = M3U8Parser.selectDefaultVariant(variants)
        assertTrue(selected.uri.endsWith("high.m3u8") || selected.uri.endsWith("mid.m3u8"))
    }

    @Test
    fun testMediaPlaylistRewriting() {
        assertFalse(M3U8Parser.isMasterPlaylist(sampleMediaPlaylist))
        val baseUrl = "https://example.com/media/index.m3u8"
        val serverBase = "http://192.168.1.50:8765"
        val streamId = "stream99"

        val (rewrittenText, segments, keys) = M3U8Parser.rewriteMediaPlaylist(
            content = sampleMediaPlaylist,
            baseUrl = baseUrl,
            streamId = streamId,
            serverBaseUrl = serverBase
        )

        assertEquals(3, segments.size)
        assertEquals("https://example.com/media/segment_0.ts", segments[0].uri)
        assertEquals("https://example.com/media/hls/segment_1.ts", segments[1].uri)
        assertEquals("https://cdn.example.com/live/segment_2.ts", segments[2].uri)

        assertEquals(1, keys.size)
        assertEquals("https://example.com/keys/enc.key", keys[0].uri)

        assertTrue(rewrittenText.contains("$serverBase/stream/$streamId/seg/0.ts"))
        assertTrue(rewrittenText.contains("$serverBase/stream/$streamId/seg/1.ts"))
        assertTrue(rewrittenText.contains("$serverBase/stream/$streamId/seg/2.ts"))
        assertTrue(rewrittenText.contains("$serverBase/stream/$streamId/key/0.key"))
    }
}
