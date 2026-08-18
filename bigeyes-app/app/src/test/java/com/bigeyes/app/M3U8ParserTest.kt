package com.bigeyes.app

import com.bigeyes.app.proxy.M3U8Parser
import org.junit.Assert.*
import org.junit.Test

class M3U8ParserTest {

    private val sampleMasterPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160,CODECS="avc1.640033,mp4a.40.2"
        4k.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.4d4028,mp4a.40.2"
        1080p.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
        720p.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480,CODECS="avc1.4d401e,mp4a.40.2"
        480p.m3u8
    """.trimIndent()

    private val sampleMediaWithKey = """
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
    fun testMasterPlaylistParsingAndMediumBitrateSelection() {
        assertTrue(M3U8Parser.isMasterPlaylist(sampleMasterPlaylist))
        val variants = M3U8Parser.parseMasterPlaylist(sampleMasterPlaylist, "https://example.com/master.m3u8")
        assertEquals(4, variants.size)
        // Ordered descending by bandwidth
        assertEquals("3840x2160", variants[0].resolution)
        assertEquals(15000000, variants[0].bandwidth)
        assertEquals("1920x1080", variants[1].resolution)
        assertEquals(6000000, variants[1].bandwidth)

        // Select default variant: Must choose medium bitrate (1080p / index 1), NOT the highest 4K (index 0)
        val selected = M3U8Parser.selectDefaultVariant(variants)
        assertNotEquals("Should not select highest 4K bitrate", variants[0].bandwidth, selected.bandwidth)
        assertEquals("1080p.m3u8", selected.uri.substringAfterLast('/'))
        assertEquals("1920x1080", selected.resolution)
    }

    @Test
    fun testMediaPlaylistWithKey() {
        assertFalse(M3U8Parser.isMasterPlaylist(sampleMediaWithKey))
        val baseUrl = "https://example.com/media/index.m3u8"
        val serverBase = "http://192.168.1.50:8765"
        val streamId = "stream99"

        val (rewrittenText, segments, keys) = M3U8Parser.rewriteMediaPlaylist(
            content = sampleMediaWithKey,
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

    @Test
    fun testMediaPlaylistWithoutKey() {
        val noKeyPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val (rewrittenText, segments, keys) = M3U8Parser.rewriteMediaPlaylist(
            content = noKeyPlaylist,
            baseUrl = "https://example.com/vod/index.m3u8",
            streamId = "nokey_stream",
            serverBaseUrl = "http://192.168.1.50:8765"
        )

        assertEquals(0, keys.size)
        assertEquals(2, segments.size)
        assertFalse(rewrittenText.contains("#EXT-X-KEY"))
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/nokey_stream/seg/0.ts"))
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/nokey_stream/seg/1.ts"))
    }

    @Test
    fun testMalformedAndNoTrailingNewlinePlaylist() {
        // Missing #EXTINF on second segment, comments mixed in, no newline at EOF
        val malformedPlaylist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nseg0.ts\n# Random Comment\nseg1.ts"

        val (rewrittenText, segments, _) = M3U8Parser.rewriteMediaPlaylist(
            content = malformedPlaylist,
            baseUrl = "https://example.com/video/playlist.m3u8",
            streamId = "malformed_stream",
            serverBaseUrl = "http://192.168.1.50:8765"
        )

        assertEquals(2, segments.size)
        assertEquals("https://example.com/video/seg0.ts", segments[0].uri)
        assertEquals("https://example.com/video/seg1.ts", segments[1].uri)
        assertTrue(rewrittenText.endsWith("\n"))
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/malformed_stream/seg/0.ts"))
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/malformed_stream/seg/1.ts"))
    }

    @Test
    fun testUrlsWithQueryParamsAndRelativeSchemes() {
        val queryPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:5.0,
            seg_relative.ts?token=xyz123&exp=9999
            #EXTINF:5.0,
            /root_relative/seg_1.ts?auth=abc
            #EXTINF:5.0,
            //cdn.global.com/scheme_relative/seg_2.ts?key=v1
            #EXTINF:5.0,
            https://secure.cdn.com/absolute/seg_3.ts?sign=8888&uid=10
            #EXT-X-ENDLIST
        """.trimIndent()

        val (rewrittenText, segments, _) = M3U8Parser.rewriteMediaPlaylist(
            content = queryPlaylist,
            baseUrl = "https://example.com/media/stream.m3u8",
            streamId = "query_stream",
            serverBaseUrl = "http://192.168.1.50:8765"
        )

        assertEquals(4, segments.size)
        assertEquals("https://example.com/media/seg_relative.ts?token=xyz123&exp=9999", segments[0].uri)
        assertEquals("https://example.com/root_relative/seg_1.ts?auth=abc", segments[1].uri)
        assertEquals("https://cdn.global.com/scheme_relative/seg_2.ts?key=v1", segments[2].uri)
        assertEquals("https://secure.cdn.com/absolute/seg_3.ts?sign=8888&uid=10", segments[3].uri)

        // All rewritten URLs point to clean proxy paths
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/query_stream/seg/0.ts"))
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/query_stream/seg/1.ts"))
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/query_stream/seg/2.ts"))
        assertTrue(rewrittenText.contains("http://192.168.1.50:8765/stream/query_stream/seg/3.ts"))
    }
}
