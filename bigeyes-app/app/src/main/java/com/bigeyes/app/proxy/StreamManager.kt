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
        private const val MAX_SESSIONS = 16
        private val DIRECT_MEDIA_EXTENSIONS = setOf("mp4", "m4v", "mkv", "flv", "webm", "mov", "avi")
    }

    private val fetcher = StreamFetcher()
    val cache = DiskLRUCache(File(context.cacheDir, "bigeyes_stream_cache"), maxSizeBytes = 300 * 1024 * 1024L)
    private val prefetcher = PrefetchManager(fetcher, cache, concurrency = 2, window = 4)

    private val sessions = ConcurrentHashMap<String, StreamSession>()
    private val sessionOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val rawMediaPlaylists = ConcurrentHashMap<String, String>()
    private val rawBaseUrls = ConcurrentHashMap<String, String>()
    private var activeStreamId: String? = null

    private fun isLikelyDirectMediaUrl(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = cleanPath.substringAfterLast('.', "")
        return ext in DIRECT_MEDIA_EXTENSIONS
    }

    private fun registerSession(session: StreamSession, rawPlaylist: String? = null, rawBaseUrl: String? = null) {
        if (rawPlaylist != null) rawMediaPlaylists[session.streamId] = rawPlaylist
        if (rawBaseUrl != null) rawBaseUrls[session.streamId] = rawBaseUrl
        sessions[session.streamId] = session
        synchronized(sessionOrder) {
            sessionOrder.remove(session.streamId)
            sessionOrder.add(session.streamId)
            while (sessionOrder.size > MAX_SESSIONS) {
                val oldestId = sessionOrder.removeAt(0)
                if (oldestId != activeStreamId) {
                    sessions.remove(oldestId)
                    rawMediaPlaylists.remove(oldestId)
                    rawBaseUrls.remove(oldestId)
                    prefetcher.cancelStream(oldestId)
                }
            }
        }
    }

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

        if (isLikelyDirectMediaUrl(url)) {
            Log.i(TAG, "Direct media extension detected. Treating as direct media stream: $url")
            val session = StreamSession(
                streamId = streamId,
                originalUrl = url,
                referer = referer,
                userAgent = userAgent,
                cookie = cookie,
                title = title,
                isMaster = false,
                isDirectMedia = true
            )
            registerSession(session)
            switchActiveStream(streamId)
            return session
        }

        // 1. Fetch root playlist (rethrow HTTP/network errors rather than silently misclassifying as direct media)
        val rootContent = fetcher.fetchText(url, referer, userAgent, cookie)
        val isM3U8 = rootContent.contains("#EXTM3U", ignoreCase = true) ||
                     rootContent.contains("#EXTINF", ignoreCase = true) ||
                     rootContent.contains("#EXT-X-STREAM-INF", ignoreCase = true)
        if (!isM3U8) {
            Log.i(TAG, "URL is not M3U8 playlist. Treating as direct media stream: $url")
            val session = StreamSession(
                streamId = streamId,
                originalUrl = url,
                referer = referer,
                userAgent = userAgent,
                cookie = cookie,
                title = title,
                isMaster = false,
                isDirectMedia = true
            )
            registerSession(session)
            switchActiveStream(streamId)
            return session
        }
        val isMaster = M3U8Parser.isMasterPlaylist(rootContent)
        var mediaContent = rootContent
        var mediaBaseUrl = url
        var variants = emptyList<com.bigeyes.app.model.VariantItem>()

        if (isMaster) {
            Log.i(TAG, "Stream $streamId is Master Playlist. Parsing variants...")
            variants = M3U8Parser.parseMasterPlaylist(rootContent, url)
            val defaultVariant = M3U8Parser.selectDefaultVariant(variants)
            val orderedCandidates = listOf(defaultVariant) + variants.filter { it.uri != defaultVariant.uri }
            var fetchedVariantText: String? = null
            var lastVariantErr: Exception? = null
            for (variant in orderedCandidates) {
                try {
                    Log.i(TAG, "Trying variant: ${variant.resolution ?: "default"} (${variant.uri})")
                    fetchedVariantText = fetcher.fetchText(variant.uri, referer, userAgent, cookie)
                    mediaBaseUrl = variant.uri
                    break
                } catch (e: Exception) {
                    lastVariantErr = e
                    Log.w(TAG, "Variant fetch failed (${variant.uri}): ${e.message}")
                }
            }
            mediaContent = fetchedVariantText ?: throw (lastVariantErr ?: Exception("All variants failed for $url"))
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

        registerSession(session, mediaContent, mediaBaseUrl)
        switchActiveStream(streamId)

        return session
    }

    private fun switchActiveStream(newStreamId: String) {
        activeStreamId?.let { oldId ->
            if (oldId != newStreamId) {
                prefetcher.cancelStream(oldId)
            }
        }
        activeStreamId = newStreamId
    }

    fun getRewrittenM3U8(streamId: String, serverBaseUrl: String): String {
        val session = sessions[streamId] ?: throw NoSuchElementException("Stream session $streamId not found")
        if (session.isDirectMedia) {
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:7200\n#EXTINF:7200.0,\n$serverBaseUrl/stream/$streamId/video.mp4\n#EXT-X-ENDLIST\n"
        }
        val rawContent = rawMediaPlaylists[streamId] ?: throw NoSuchElementException("Raw content for $streamId not found")
        val baseUrl = rawBaseUrls[streamId] ?: session.originalUrl

        val rewritten = M3U8Parser.rewriteMediaPlaylistFull(
            content = rawContent,
            baseUrl = baseUrl,
            streamId = streamId,
            serverBaseUrl = serverBaseUrl
        )

        session.segments = rewritten.segments
        session.keys = rewritten.keys
        session.initMaps = rewritten.initMaps

        // Warm up prefetch for first few segments
        if (rewritten.segments.isNotEmpty()) {
            prefetcher.triggerPrefetch(session, currentSegIndex = -1)
        }

        return rewritten.playlist
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
                cookie = session.cookie,
                byteRangeHeader = seg.byteRange
            )
            cache.put(cacheKey, data)
        } else {
            Log.d(TAG, "Cache hit for seg $segIndex in stream $streamId")
        }

        session.lastAccessedSeg = segIndex
        prefetcher.triggerPrefetch(session, segIndex)

        return data
    }

    suspend fun getInitMap(streamId: String, mapIndex: Int): ByteArray {
        val session = sessions[streamId] ?: throw NoSuchElementException("Stream session $streamId not found")
        if (mapIndex < 0 || mapIndex >= session.initMaps.size) {
            throw IndexOutOfBoundsException("Init map index $mapIndex out of bounds (${session.initMaps.size})")
        }
        val mapUri = session.initMaps[mapIndex]
        val cacheKey = "${streamId}_map_${mapIndex}.mp4"
        var data = cache.get(cacheKey)
        if (data == null) {
            data = fetcher.fetchBytes(
                url = mapUri,
                referer = session.referer,
                userAgent = session.userAgent,
                cookie = session.cookie
            )
            cache.put(cacheKey, data)
        }
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
        synchronized(sessionOrder) { sessionOrder.clear() }
        rawMediaPlaylists.clear()
        rawBaseUrls.clear()
    }
}
