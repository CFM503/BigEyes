package com.bigeyes.app.proxy

import android.content.Context
import android.util.Log
import com.bigeyes.app.model.StreamSession
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StreamManager(context: Context) {

    companion object {
        private const val TAG = "StreamManager"
    }

    private val fetcher = StreamFetcher()
    val cache = DiskLRUCache(File(context.cacheDir, "bigeyes_stream_cache"), maxSizeBytes = 300 * 1024 * 1024L)
    private val prefetcher = PrefetchManager(fetcher, cache, concurrency = 2, window = 4)

    private val sessions = ConcurrentHashMap<String, StreamSession>()
    private val rawMediaPlaylists = ConcurrentHashMap<String, String>()
    private val rawBaseUrls = ConcurrentHashMap<String, String>()
    private var activeStreamId: String? = null

    fun getActiveSession(): StreamSession? {
        val id = activeStreamId ?: return null
        return sessions[id]
    }

    fun getSession(streamId: String): StreamSession? {
        return sessions[streamId]
    }

    suspend fun createSession(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null,
        title: String? = null
    ): StreamSession {
        val streamId = UUID.randomUUID().toString().substring(0, 8)
        Log.i(TAG, "Creating new stream session $streamId for URL: $url")

        // 1. Fetch root playlist
        val rootContent = fetcher.fetchText(url, referer, userAgent, cookie)
        val isM3U8 = rootContent.contains("#EXTM3U", ignoreCase = true) ||
                     rootContent.contains("#EXTINF", ignoreCase = true) ||
                     rootContent.contains("#EXT-X-STREAM-INF", ignoreCase = true)
        if (!isM3U8) {
            throw IllegalArgumentException("嗅探到的地址返回的不是标准 M3U8 播放列表")
        }
        val isMaster = M3U8Parser.isMasterPlaylist(rootContent)
        var mediaContent = rootContent
        var mediaBaseUrl = url
        var variants = emptyList<com.bigeyes.app.model.VariantItem>()

        if (isMaster) {
            Log.i(TAG, "Stream $streamId is Master Playlist. Parsing variants...")
            variants = M3U8Parser.parseMasterPlaylist(rootContent, url)
            val defaultVariant = M3U8Parser.selectDefaultVariant(variants)
            Log.i(TAG, "Selected default variant: ${defaultVariant.resolution ?: "default"} (${defaultVariant.uri})")
            mediaBaseUrl = defaultVariant.uri
            mediaContent = fetcher.fetchText(defaultVariant.uri, referer, userAgent, cookie)
        }

        val session = StreamSession(
            streamId = streamId,
            originalUrl = url,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            title = title,
            isMaster = isMaster,
            variants = variants
        )

        rawMediaPlaylists[streamId] = mediaContent
        rawBaseUrls[streamId] = mediaBaseUrl
        sessions[streamId] = session

        // Cancel previous prefetch tasks
        activeStreamId?.let { oldId ->
            if (oldId != streamId) {
                prefetcher.cancelStream(oldId)
            }
        }
        activeStreamId = streamId

        return session
    }

    fun getRewrittenM3U8(streamId: String, serverBaseUrl: String): String {
        val session = sessions[streamId] ?: throw NoSuchElementException("Stream session $streamId not found")
        val rawContent = rawMediaPlaylists[streamId] ?: throw NoSuchElementException("Raw content for $streamId not found")
        val baseUrl = rawBaseUrls[streamId] ?: session.originalUrl

        val (rewrittenText, segments, keys) = M3U8Parser.rewriteMediaPlaylist(
            content = rawContent,
            baseUrl = baseUrl,
            streamId = streamId,
            serverBaseUrl = serverBaseUrl
        )

        session.segments = segments
        session.keys = keys

        // Warm up prefetch for first few segments
        if (segments.isNotEmpty()) {
            prefetcher.triggerPrefetch(session, currentSegIndex = -1)
        }

        return rewrittenText
    }

    suspend fun getSegment(streamId: String, segIndex: Int): ByteArray {
        val session = sessions[streamId] ?: throw NoSuchElementException("Stream session $streamId not found")
        if (segIndex < 0 || segIndex >= session.segments.size) {
            throw IndexOutOfBoundsException("Segment index $segIndex out of bounds (${session.segments.size})")
        }

        val seg = session.segments[segIndex]
        val cacheKey = "${streamId}_seg_${segIndex}.ts"

        var data = cache.get(cacheKey)
        if (data == null) {
            Log.i(TAG, "Cache miss for seg $segIndex in stream $streamId. Fetching from: ${seg.uri}")
            data = fetcher.fetchBytes(
                url = seg.uri,
                referer = session.referer,
                userAgent = session.userAgent,
                cookie = session.cookie
            )
            cache.put(cacheKey, data)
        } else {
            Log.d(TAG, "Cache hit for seg $segIndex in stream $streamId")
        }

        session.lastAccessedSeg = segIndex
        prefetcher.triggerPrefetch(session, segIndex)

        return data
    }

    suspend fun getKey(streamId: String, keyIndex: Int): ByteArray {
        val session = sessions[streamId] ?: throw NoSuchElementException("Stream session $streamId not found")
        if (keyIndex < 0 || keyIndex >= session.keys.size) {
            throw IndexOutOfBoundsException("Key index $keyIndex out of bounds (${session.keys.size})")
        }

        val keyItem = session.keys[keyIndex]
        val cacheKey = "${streamId}_key_${keyIndex}.key"

        var data = cache.get(cacheKey)
        if (data == null) {
            Log.i(TAG, "Fetching key $keyIndex for stream $streamId: ${keyItem.uri}")
            data = fetcher.fetchBytes(
                url = keyItem.uri,
                referer = session.referer,
                userAgent = session.userAgent,
                cookie = session.cookie
            )
            cache.put(cacheKey, data)
        }
        return data
    }

    fun release() {
        prefetcher.release()
        sessions.clear()
        rawMediaPlaylists.clear()
        rawBaseUrls.clear()
    }
}
