package com.bigeyes.app.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Covers the DLNA proxy session persistence: a TV that still holds an old proxy URL must keep
 * working after the casting service (and its in-memory StreamManager) was recreated.
 */
class StreamManagerPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var filesDir: File
    private val managers = mutableListOf<StreamManager>()

    /** In-memory network layer: URL -> playlist text; segments return the URL bytes. */
    private class FakeFetcher : StreamFetcher() {
        val texts = Collections.synchronizedMap(mutableMapOf<String, String>())
        val textRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val byteRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var failText = false

        override suspend fun fetchText(url: String, referer: String?, userAgent: String?, cookie: String?): String {
            textRequests += url
            if (failText) throw Exception("HTTP 404 fetching m3u8: $url")
            return texts[url] ?: throw Exception("HTTP 404 fetching m3u8: $url")
        }

        override suspend fun fetchBytes(
            url: String,
            referer: String?,
            userAgent: String?,
            cookie: String?,
            maxRetries: Int,
            byteRangeHeader: String?
        ): ByteArray {
            byteRequests += url
            return url.toByteArray(Charsets.UTF_8)
        }

        fun count(url: String) = synchronized(textRequests) { textRequests.count { it == url } }
    }

    private val masterUrl = "https://cdn.example.com/show/master.m3u8"
    private val hiUrl = "https://cdn.example.com/show/1080p/index.m3u8"
    private val loUrl = "https://cdn.example.com/show/720p/index.m3u8"

    private val masterPlaylist = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
        1080p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
        720p/index.m3u8
    """.trimIndent()

    private fun mediaPlaylist(tag: String) = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        ${tag}_seg0.ts
        #EXTINF:10.0,
        ${tag}_seg1.ts
        #EXTINF:10.0,
        ${tag}_seg2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private fun newFetcher() = FakeFetcher().apply {
        texts[masterUrl] = masterPlaylist
        texts[hiUrl] = mediaPlaylist("hi")
        texts[loUrl] = mediaPlaylist("lo")
    }

    private fun newManager(fetcher: StreamFetcher): StreamManager =
        StreamManager(cacheDir, filesDir, fetcher).also { managers += it }

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("cache")
        filesDir = tempFolder.newFolder("files")
    }

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.release() } }
    }

    @Test
    fun directMediaSessionIsRestoredAfterServiceRestart() = runBlocking {
        val first = newManager(newFetcher())
        val created = first.createSession(
            url = "https://cdn.example.com/movie.mp4?token=abc",
            referer = "https://site.example.com/play/1",
            userAgent = "UA-Test",
            cookie = "sid=1",
            title = "Movie"
        )
        assertTrue(created.isDirectMedia)
        first.release()

        val fetcher = newFetcher()
        val second = newManager(fetcher)
        assertNull("Nothing in memory before restore", second.getSession(created.streamId))

        val restored = second.ensureSession(created.streamId)
        assertNotNull(restored)
        restored!!
        assertTrue(restored.isDirectMedia)
        assertEquals(created.originalUrl, restored.originalUrl)
        assertEquals("https://site.example.com/play/1", restored.referer)
        assertEquals("UA-Test", restored.userAgent)
        assertEquals("sid=1", restored.cookie)
        assertEquals("Movie", restored.title)
        assertTrue("Direct media restore must not hit the network", fetcher.textRequests.isEmpty())

        val playlist = second.getRewrittenM3U8(created.streamId, "http://192.168.1.2:8765")
        assertTrue(playlist.contains("/stream/${created.streamId}/video.mp4"))
    }

    @Test
    fun hlsSessionRestoresSameVariantWithoutRefetchingMaster() = runBlocking {
        val firstFetcher = newFetcher()
        val first = newManager(firstFetcher)
        val created = first.createSession(url = masterUrl, referer = "https://site.example.com/")
        assertTrue(created.isMaster)
        val variantUsed = firstFetcher.textRequests.first { it != masterUrl }
        first.release()

        val fetcher = newFetcher()
        val second = newManager(fetcher)
        val restored = second.ensureSession(created.streamId)
        assertNotNull(restored)
        assertEquals(0, fetcher.count(masterUrl))
        assertEquals("Restore re-fetches exactly the variant used before", 1, fetcher.count(variantUsed))
        assertEquals(3, restored!!.segments.size)
    }

    @Test
    fun segmentCanBeServedAfterRestartWithoutPlaylistRequest() = runBlocking {
        val first = newManager(newFetcher())
        val created = first.createSession(url = hiUrl)
        first.release()

        val fetcher = newFetcher()
        val second = newManager(fetcher)
        // The TV cached the rewritten playlist earlier and asks for a segment directly.
        val data = second.getSegment(created.streamId, 1)
        assertEquals("https://cdn.example.com/show/1080p/hi_seg1.ts", String(data, Charsets.UTF_8))
    }

    @Test
    fun playlistIsRewrittenAfterRestart() = runBlocking {
        val first = newManager(newFetcher())
        val created = first.createSession(url = hiUrl)
        first.release()

        val second = newManager(newFetcher())
        val playlist = second.getRewrittenM3U8(created.streamId, "http://192.168.1.2:8765")
        assertTrue(playlist.contains("http://192.168.1.2:8765/stream/${created.streamId}/seg/0.ts"))
        assertTrue(playlist.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun unknownSessionIsNotFound() = runBlocking {
        val manager = newManager(newFetcher())
        assertNull(manager.ensureSession("deadbeef"))
        try {
            manager.getRewrittenM3U8("deadbeef", "http://192.168.1.2:8765")
            fail("Expected NoSuchElementException so the proxy answers 404")
        } catch (e: NoSuchElementException) {
            // expected
        }
        try {
            manager.getSegment("deadbeef", 0)
            fail("Expected NoSuchElementException so the proxy answers 404")
        } catch (e: NoSuchElementException) {
            // expected
        }
    }

    @Test
    fun expiredPersistedSessionIsNotRestored() = runBlocking {
        val oldRecord = JSONObject().apply {
            put("streamId", "old00001")
            put("originalUrl", "https://cdn.example.com/old.mp4")
            put("createdAt", System.currentTimeMillis() - 13 * 60 * 60 * 1000L)
            put("isMaster", false)
            put("isDirectMedia", true)
        }
        File(filesDir, "bigeyes_stream_sessions.json").writeText(JSONArray().put(oldRecord).toString())

        val manager = newManager(newFetcher())
        assertNull(manager.ensureSession("old00001"))
    }

    @Test
    fun corruptedStoreIsIgnored() = runBlocking {
        File(filesDir, "bigeyes_stream_sessions.json").writeText("{not json")

        val manager = newManager(newFetcher())
        assertNull(manager.ensureSession("whatever"))
        val created = manager.createSession(url = "https://cdn.example.com/a.mp4")
        assertNotNull(manager.ensureSession(created.streamId))

        // And the store was rewritten as valid JSON.
        val restoredByNext = newManager(newFetcher()).ensureSession(created.streamId)
        assertNotNull(restoredByNext)
    }

    @Test
    fun restoreFailureReturnsNull() = runBlocking {
        val first = newManager(newFetcher())
        val created = first.createSession(url = hiUrl)
        first.release()

        val fetcher = newFetcher().apply { failText = true }
        val second = newManager(fetcher)
        assertNull("Expired upstream playlist cannot be restored", second.ensureSession(created.streamId))
    }

    @Test
    fun concurrentRequestsRestoreOnlyOnce() = runBlocking {
        val first = newManager(newFetcher())
        val created = first.createSession(url = hiUrl)
        first.release()

        val fetcher = newFetcher()
        val second = newManager(fetcher)
        val results = (1..8).map {
            async(Dispatchers.Default) { second.ensureSession(created.streamId) }
        }.awaitAll()

        assertTrue(results.all { it != null })
        assertEquals(1, results.map { it!! }.distinct().size)
        assertEquals("Playlist fetched once for 8 parallel requests", 1, fetcher.count(hiUrl))
    }

    @Test
    fun recentlyUsedSessionIsNotEvictedFromMemory() = runBlocking {
        val manager = newManager(newFetcher())
        val firstSession = manager.createSession(url = "https://cdn.example.com/v0.mp4")
        val others = (1..15).map { manager.createSession(url = "https://cdn.example.com/v$it.mp4") }

        // The TV keeps requesting the first stream.
        assertNotNull(manager.getSession(firstSession.streamId))

        // A 17th session forces one eviction: it must be the least recently used one.
        manager.createSession(url = "https://cdn.example.com/v16.mp4")
        assertNotNull(manager.getSession(firstSession.streamId))
        assertNull(manager.getSession(others.first().streamId))

        // The evicted one is still recoverable from disk.
        assertNotNull(manager.ensureSession(others.first().streamId))
    }
}
