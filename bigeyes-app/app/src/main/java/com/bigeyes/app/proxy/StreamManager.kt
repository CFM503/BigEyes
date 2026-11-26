package com.bigeyes.app.proxy

import android.content.Context
import android.util.Log
import com.bigeyes.app.model.StreamSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * @param cacheDir directory for the segment disk cache
 * @param filesDir directory holding the persisted session descriptors
 * @param fetcher network layer (replaceable in unit tests)
 */
class StreamManager(
    cacheDir: File,
    filesDir: File,
    private val fetcher: StreamFetcher = StreamFetcher()
) {

    constructor(context: Context) : this(context.cacheDir, context.filesDir)

    companion object {
        private const val TAG = "StreamManager"
        private const val MAX_SESSIONS = 16
        private val DIRECT_MEDIA_EXTENSIONS = setOf("mp4", "m4v", "mkv", "flv", "webm", "mov", "avi")

        /** Session descriptors kept on disk so a TV still playing an old proxy URL keeps working. */
        private const val MAX_PERSISTED_SESSIONS = 32
        private const val PERSISTED_SESSION_MAX_AGE_MS = 12 * 60 * 60 * 1000L
        private const val SESSION_STORE_FILE = "bigeyes_stream_sessions.json"
    }

    val cache = DiskLRUCache(File(cacheDir, "bigeyes_stream_cache"), maxSizeBytes = 300 * 1024 * 1024L)
    private val prefetcher = PrefetchManager(fetcher, cache, concurrency = 2, window = 4)

    private val sessions = ConcurrentHashMap<String, StreamSession>()
    private val sessionOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val rawMediaPlaylists = ConcurrentHashMap<String, String>()
    private val rawBaseUrls = ConcurrentHashMap<String, String>()
    @Volatile
    private var activeStreamId: String? = null

    private val sessionStore = PersistedSessionStore(File(filesDir, SESSION_STORE_FILE))
    private val restoreMutex = Mutex()

    private fun isLikelyDirectMediaUrl(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = cleanPath.substringAfterLast('.', "")
        return ext in DIRECT_MEDIA_EXTENSIONS
    }

    private fun registerSession(
        session: StreamSession,
        rawPlaylist: String? = null,
        rawBaseUrl: String? = null,
        persist: Boolean = true
    ) {
        if (rawPlaylist != null) rawMediaPlaylists[session.streamId] = rawPlaylist
        if (rawBaseUrl != null) rawBaseUrls[session.streamId] = rawBaseUrl
        sessions[session.streamId] = session
        synchronized(sessionOrder) {
            sessionOrder.remove(session.streamId)
            sessionOrder.add(session.streamId)
            while (sessionOrder.size > MAX_SESSIONS) {
                val oldestId = sessionOrder.removeAt(0)
                if (oldestId != activeStreamId) {
                    // Only dropped from memory; the descriptor stays on disk and is restored on demand.
                    sessions.remove(oldestId)
                    rawMediaPlaylists.remove(oldestId)
                    rawBaseUrls.remove(oldestId)
                    prefetcher.cancelStream(oldestId)
                }
            }
        }
        if (persist) {
            sessionStore.put(session, rawBaseUrl)
        }
    }

    /** Marks a session as recently used so in-flight TV playback is never evicted first. */
    private fun touch(streamId: String) {
        synchronized(sessionOrder) {
            if (sessionOrder.lastOrNull() == streamId) return
            if (sessionOrder.remove(streamId)) {
                sessionOrder.add(streamId)
            }
        }
    }

    fun getActiveSession(): StreamSession? {
        val id = activeStreamId ?: return null
        return sessions[id]
    }

    fun getSession(streamId: String): StreamSession? {
        return sessions[streamId]?.also { touch(streamId) }
    }

    /**
     * Returns the session for [streamId], restoring it from disk when it is no longer in memory
     * (casting service restarted, or evicted). Without this a DLNA TV that still holds the old
     * proxy URL gets 404/500 for every request.
     */
    suspend fun ensureSession(streamId: String): StreamSession? {
        getSession(streamId)?.let { return it }
        return restoreMutex.withLock {
            getSession(streamId) ?: restoreSession(streamId)
        }
    }

    private suspend fun restoreSession(streamId: String): StreamSession? {
        val record = sessionStore.get(streamId) ?: return null
        Log.i(TAG, "Restoring stream session $streamId from disk: ${record.originalUrl}")
        return try {
            if (record.isDirectMedia) {
                val session = StreamSession(
                    streamId = streamId,
                    originalUrl = record.originalUrl,
                    referer = record.referer,
                    userAgent = record.userAgent,
                    cookie = record.cookie,
                    title = record.title,
                    createdAt = record.createdAt,
                    isMaster = false,
                    isDirectMedia = true
                )
                registerSession(session, persist = false)
                session
            } else {
                // Re-fetch exactly the media playlist used before (same variant), so segment
                // indices the TV already knows keep pointing at the same segments.
                val mediaUrl = record.mediaBaseUrl ?: record.originalUrl
                val mediaContent = fetcher.fetchText(mediaUrl, record.referer, record.userAgent, record.cookie)
                val session = StreamSession(
                    streamId = streamId,
                    originalUrl = record.originalUrl,
                    referer = record.referer,
                    userAgent = record.userAgent,
                    cookie = record.cookie,
                    title = record.title,
                    createdAt = record.createdAt,
                    isMaster = record.isMaster
                )
                registerSession(session, mediaContent, mediaUrl, persist = false)
                // Rebuild segment/key/map tables; the playlist text itself is not needed here.
                rewriteForSession(session, serverBaseUrl = "", warmUpPrefetch = false)
                Log.i(TAG, "Restored HLS session $streamId with ${session.segments.size} segments")
                session
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore stream session $streamId: ${e.message}")
            null
        }
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

    suspend fun getRewrittenM3U8(streamId: String, serverBaseUrl: String): String {
        val session = ensureSession(streamId) ?: throw NoSuchElementException("Stream session $streamId not found")
        if (session.isDirectMedia) {
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:7200\n#EXTINF:7200.0,\n$serverBaseUrl/stream/$streamId/video.mp4\n#EXT-X-ENDLIST\n"
        }
        return rewriteForSession(session, serverBaseUrl, warmUpPrefetch = true)
    }

    private fun rewriteForSession(session: StreamSession, serverBaseUrl: String, warmUpPrefetch: Boolean): String {
        val streamId = session.streamId
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
        if (warmUpPrefetch && rewritten.segments.isNotEmpty()) {
            prefetcher.triggerPrefetch(session, currentSegIndex = -1)
        }

        return rewritten.playlist
    }

    /**
     * HLS sessions need their segment table before segments/keys/maps can be served. A TV that
     * already cached the playlist may request a segment first, so build the table on demand.
     */
    private suspend fun requireHlsSession(streamId: String): StreamSession {
        val session = ensureSession(streamId) ?: throw NoSuchElementException("Stream session $streamId not found")
        if (!session.isDirectMedia && session.segments.isEmpty() && rawMediaPlaylists.containsKey(streamId)) {
            rewriteForSession(session, serverBaseUrl = "", warmUpPrefetch = false)
        }
        return session
    }

    suspend fun getSegment(streamId: String, segIndex: Int): ByteArray {
        val session = requireHlsSession(streamId)
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
        val session = requireHlsSession(streamId)
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
        val session = requireHlsSession(streamId)
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

    /**
     * Releases in-memory state. Persisted session descriptors are intentionally kept so the
     * next service instance can keep serving a TV that is still playing an earlier proxy URL.
     */
    fun release() {
        prefetcher.release()
        sessions.clear()
        synchronized(sessionOrder) { sessionOrder.clear() }
        rawMediaPlaylists.clear()
        rawBaseUrls.clear()
    }

    /**
     * Small JSON-backed store of session descriptors (no segment data), in app-private storage.
     */
    private class PersistedSessionStore(private val file: File) {

        data class Record(
            val streamId: String,
            val originalUrl: String,
            val mediaBaseUrl: String?,
            val referer: String?,
            val userAgent: String?,
            val cookie: String?,
            val title: String?,
            val createdAt: Long,
            val isMaster: Boolean,
            val isDirectMedia: Boolean
        )

        private val lock = Any()
        private var records: LinkedHashMap<String, Record>? = null

        fun put(session: StreamSession, mediaBaseUrl: String?) {
            synchronized(lock) {
                val map = load()
                map.remove(session.streamId)
                map[session.streamId] = Record(
                    streamId = session.streamId,
                    originalUrl = session.originalUrl,
                    mediaBaseUrl = mediaBaseUrl,
                    referer = session.referer,
                    userAgent = session.userAgent,
                    cookie = session.cookie,
                    title = session.title,
                    createdAt = session.createdAt,
                    isMaster = session.isMaster,
                    isDirectMedia = session.isDirectMedia
                )
                prune(map)
                save(map)
            }
        }

        fun get(streamId: String): Record? {
            synchronized(lock) {
                val record = load()[streamId] ?: return null
                if (System.currentTimeMillis() - record.createdAt > PERSISTED_SESSION_MAX_AGE_MS) {
                    return null
                }
                return record
            }
        }

        private fun prune(map: LinkedHashMap<String, Record>) {
            val now = System.currentTimeMillis()
            map.entries.removeAll { now - it.value.createdAt > PERSISTED_SESSION_MAX_AGE_MS }
            while (map.size > MAX_PERSISTED_SESSIONS) {
                val oldest = map.keys.firstOrNull() ?: break
                map.remove(oldest)
            }
        }

        private fun load(): LinkedHashMap<String, Record> {
            records?.let { return it }
            val map = LinkedHashMap<String, Record>()
            try {
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("streamId")
                        val url = o.optString("originalUrl")
                        if (id.isBlank() || url.isBlank()) continue
                        map[id] = Record(
                            streamId = id,
                            originalUrl = url,
                            mediaBaseUrl = o.optStringOrNull("mediaBaseUrl"),
                            referer = o.optStringOrNull("referer"),
                            userAgent = o.optStringOrNull("userAgent"),
                            cookie = o.optStringOrNull("cookie"),
                            title = o.optStringOrNull("title"),
                            createdAt = o.optLong("createdAt", 0L),
                            isMaster = o.optBoolean("isMaster", false),
                            isDirectMedia = o.optBoolean("isDirectMedia", false)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring unreadable stream session store: ${e.message}")
                map.clear()
            }
            records = map
            return map
        }

        private fun save(map: LinkedHashMap<String, Record>) {
            try {
                val arr = JSONArray()
                for (r in map.values) {
                    arr.put(JSONObject().apply {
                        put("streamId", r.streamId)
                        put("originalUrl", r.originalUrl)
                        r.mediaBaseUrl?.let { put("mediaBaseUrl", it) }
                        r.referer?.let { put("referer", it) }
                        r.userAgent?.let { put("userAgent", it) }
                        r.cookie?.let { put("cookie", it) }
                        r.title?.let { put("title", it) }
                        put("createdAt", r.createdAt)
                        put("isMaster", r.isMaster)
                        put("isDirectMedia", r.isDirectMedia)
                    })
                }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(file)) {
                    file.writeText(arr.toString())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist stream sessions: ${e.message}")
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).takeIf { it.isNotBlank() }
        }
    }
}
