package com.bigeyes.app.proxy

import android.content.Context
import android.util.Log
import com.bigeyes.app.service.CastingForegroundService
import com.bigeyes.app.utils.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class EmbeddedProxyServer(
    private val context: Context,
    private val streamManager: StreamManager,
    port: Int = 8765
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "EmbeddedProxyServer"
        const val DEFAULT_PORT = 8765
    }

    class PooledAsyncRunner(
        private val executor: ExecutorService = ThreadPoolExecutor(
            4,
            32,
            60L,
            TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(128),
            Executors.defaultThreadFactory(),
            ThreadPoolExecutor.CallerRunsPolicy()
        )
    ) : AsyncRunner {
        private val running = Collections.synchronizedList(mutableListOf<ClientHandler>())

        override fun closeAll() {
            running.toList().forEach { it.close() }
            executor.shutdown()
        }

        override fun closed(clientHandler: ClientHandler) {
            running.remove(clientHandler)
        }

        override fun exec(clientHandler: ClientHandler) {
            running.add(clientHandler)
            executor.execute(clientHandler)
        }
    }

    init {
        // Use bounded thread pool (4~32 threads) for NanoHTTPD request handling
        setAsyncRunner(PooledAsyncRunner())
    }

    private val directMediaClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(8, 30, TimeUnit.SECONDS))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val serverPort: Int = port

    fun getProxyBaseUrl(): String {
        val lanIp = NetworkUtils.getLocalIpAddress(context)
        val effectivePort = if (listeningPort > 0) listeningPort else serverPort
        return "http://$lanIp:$effectivePort"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Serving HTTP request: ${session.method} $uri")

        // Renew WakeLock on every incoming request
        CastingForegroundService.instance?.renewLocks()

        if (session.method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(resp)
            return resp
        }

        try {
            return when {
                uri.startsWith("/stream/") && uri.endsWith("/index.m3u8") -> {
                    serveM3U8(uri)
                }
                uri.startsWith("/stream/") && (uri.contains("/seg/")) -> {
                    serveSegment(session, uri)
                }
                uri.startsWith("/stream/") && (uri.contains("/map/")) -> {
                    serveInitMap(uri)
                }
                uri.startsWith("/stream/") && (uri.endsWith("/video.mp4") || uri.contains("/video.")) -> {
                    serveDirectMedia(session, uri)
                }
                uri.startsWith("/stream/") && (uri.contains("/key/")) -> {
                    serveKey(uri)
                }
                uri == "/" -> {
                    val resp = newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        "{\"service\":\"BigEyes Standalone App\",\"status\":\"running\",\"ip\":\"${NetworkUtils.getLocalIpAddress(context)}\",\"port\":$serverPort}"
                    )
                    addCorsHeaders(resp)
                    resp
                }
                else -> {
                    val resp = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
                    addCorsHeaders(resp)
                    resp
                }
            }
        } catch (e: NoSuchElementException) {
            // Session unknown even after trying to restore it from disk (expired or never created).
            Log.w(TAG, "Stream not found for $uri: ${e.message}")
            val resp = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream session not found")
            addCorsHeaders(resp)
            return resp
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request $uri: ${e.message}", e)
            val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error: ${e.message}")
            addCorsHeaders(resp)
            return resp
        }
    }

    private fun serveM3U8(uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size < 3) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid m3u8 path")
        }
        val streamId = parts[1]
        val baseUrl = getProxyBaseUrl()

        val rewrittenM3U8 = runBlocking {
            streamManager.getRewrittenM3U8(streamId, baseUrl)
        }
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "application/vnd.apple.mpegurl",
            rewrittenM3U8
        )
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        addCorsHeaders(resp)
        return resp
    }

    private fun serveSegment(session: IHTTPSession, uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size < 4) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid segment path")
        }
        val streamId = parts[1]
        val segRaw = parts[3].replace(".ts", "")
        val segIndex = segRaw.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid segment index")

        val data = runBlocking {
            streamManager.getSegment(streamId, segIndex)
        }

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            if (data.isEmpty()) {
                val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                resp.addHeader("Content-Range", "bytes */0")
                addCorsHeaders(resp)
                return resp
            }
            try {
                val rangeSpec = rangeHeader.substring(6).trim().substringBefore(',')
                val rangeParts = rangeSpec.split('-', limit = 2)
                val totalSize = data.size.toLong()
                val start: Long
                val end: Long
                if (rangeParts[0].isEmpty()) {
                    // Suffix range: bytes=-N
                    val suffixLen = rangeParts.getOrNull(1)?.toLongOrNull() ?: 0L
                    if (suffixLen <= 0L) {
                        val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                        resp.addHeader("Content-Range", "bytes */$totalSize")
                        addCorsHeaders(resp)
                        return resp
                    }
                    start = (totalSize - suffixLen).coerceAtLeast(0L)
                    end = totalSize - 1L
                } else {
                    start = rangeParts[0].toLongOrNull() ?: 0L
                    val reqEnd = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                        rangeParts[1].toLongOrNull() ?: (totalSize - 1L)
                    } else {
                        totalSize - 1L
                    }
                    end = reqEnd.coerceAtMost(totalSize - 1L)
                }

                if (start < 0L || start >= totalSize || end < start) {
                    val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    resp.addHeader("Content-Range", "bytes */$totalSize")
                    addCorsHeaders(resp)
                    return resp
                }

                val length = (end - start + 1).toInt()
                val partialData = data.copyOfRange(start.toInt(), start.toInt() + length)

                val resp = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    "video/mp2t",
                    ByteArrayInputStream(partialData),
                    length.toLong()
                )
                resp.addHeader("Content-Range", "bytes $start-$end/$totalSize")
                resp.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(resp)
                return resp
            } catch (e: Exception) {
                Log.w(TAG, "Error processing range $rangeHeader: ${e.message}")
            }
        }

        val stream = ByteArrayInputStream(data)
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "video/mp2t",
            stream,
            data.size.toLong()
        )
        resp.addHeader("Accept-Ranges", "bytes")
        addCorsHeaders(resp)
        return resp
    }

    private fun serveInitMap(uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size < 4) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid map path")
        }
        val streamId = parts[1]
        val mapRaw = parts[3].substringBefore('.')
        val mapIndex = mapRaw.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid map index")

        val data = runBlocking {
            streamManager.getInitMap(streamId, mapIndex)
        }
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "video/mp4",
            ByteArrayInputStream(data),
            data.size.toLong()
        )
        resp.addHeader("Accept-Ranges", "bytes")
        addCorsHeaders(resp)
        return resp
    }

    private fun serveDirectMedia(session: IHTTPSession, uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size < 3) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid media path")
        }
        val streamId = parts[1]
        // Restores the session from disk if the casting service was restarted meanwhile.
        val streamSession = runBlocking { streamManager.ensureSession(streamId) }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream session not found")

        val targetUrl = streamSession.originalUrl
        val rangeHeader = session.headers["range"]

        return runBlocking {
            try {
                val reqBuilder = okhttp3.Request.Builder().url(targetUrl)
                if (session.method == Method.HEAD) {
                    reqBuilder.head()
                }
                streamSession.referer?.let { reqBuilder.addHeader("Referer", it) }
                streamSession.userAgent?.let { reqBuilder.addHeader("User-Agent", it) }
                streamSession.cookie?.let { reqBuilder.addHeader("Cookie", it) }
                if (rangeHeader != null) {
                    reqBuilder.addHeader("Range", rangeHeader)
                }

                val upstreamResp = directMediaClient.newCall(reqBuilder.build()).execute()
                if (!upstreamResp.isSuccessful) {
                    val code = upstreamResp.code
                    val errMsg = "Upstream HTTP $code"
                    upstreamResp.close()
                    val status = when (code) {
                        403 -> Response.Status.FORBIDDEN
                        404 -> Response.Status.NOT_FOUND
                        416 -> Response.Status.RANGE_NOT_SATISFIABLE
                        in 400..499 -> Response.Status.BAD_REQUEST
                        else -> Response.Status.INTERNAL_ERROR
                    }
                    val errResp = newFixedLengthResponse(status, MIME_PLAINTEXT, errMsg)
                    addCorsHeaders(errResp)
                    return@runBlocking errResp
                }

                val contentType = upstreamResp.header("Content-Type") ?: "video/mp4"
                val body = upstreamResp.body
                val contentLength = body?.contentLength() ?: -1L
                val rawStream = body?.byteStream()
                if (session.method == Method.HEAD || rawStream == null) {
                    upstreamResp.close()
                    val headResp = newFixedLengthResponse(
                        if (upstreamResp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                        contentType,
                        ""
                    )
                    if (contentLength >= 0) {
                        headResp.addHeader("Content-Length", contentLength.toString())
                    }
                    upstreamResp.header("Content-Range")?.let { headResp.addHeader("Content-Range", it) }
                    headResp.addHeader("Accept-Ranges", "bytes")
                    addCorsHeaders(headResp)
                    return@runBlocking headResp
                }

                val closingStream = object : java.io.FilterInputStream(rawStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            upstreamResp.close()
                        }
                    }
                }

                val status = if (upstreamResp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                val resp = if (contentLength >= 0) {
                    newFixedLengthResponse(status, contentType, closingStream, contentLength)
                } else {
                    newChunkedResponse(status, contentType, closingStream)
                }

                upstreamResp.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
                resp.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(resp)
                resp
            } catch (e: Exception) {
                Log.e(TAG, "Error proxying direct media: ${e.message}", e)
                val errResp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
                addCorsHeaders(errResp)
                errResp
            }
        }
    }

    private fun serveKey(uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size < 4) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid key path")
        }
        val streamId = parts[1]
        val keyRaw = parts[3].replace(".key", "")
        val keyIndex = keyRaw.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid key index")

        val data = runBlocking {
            streamManager.getKey(streamId, keyIndex)
        }

        val stream = ByteArrayInputStream(data)
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            stream,
            data.size.toLong()
        )
        addCorsHeaders(resp)
        return resp
    }

    private fun addCorsHeaders(resp: Response) {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        resp.addHeader("Access-Control-Allow-Headers", "*")
    }
}
