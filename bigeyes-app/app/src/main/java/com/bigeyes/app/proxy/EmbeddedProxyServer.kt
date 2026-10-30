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
        // Use bounded thread pool (4~16 threads) for NanoHTTPD request handling
        setAsyncRunner(PooledAsyncRunner())
    }

    val serverPort: Int = port

    fun getProxyBaseUrl(): String {
        val lanIp = NetworkUtils.getLocalIpAddress(context)
        return "http://$lanIp:$serverPort"
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

        val rewrittenM3U8 = streamManager.getRewrittenM3U8(streamId, baseUrl)
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
            try {
                val rangeSpec = rangeHeader.substring(6).trim()
                val rangeParts = rangeSpec.split('-')
                val start = rangeParts[0].toLongOrNull() ?: 0L
                val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                    rangeParts[1].toLongOrNull() ?: (data.size - 1L)
                } else {
                    data.size - 1L
                }
                val safeStart = start.coerceIn(0L, (data.size - 1L).coerceAtLeast(0L))
                val safeEnd = end.coerceIn(safeStart, (data.size - 1L).coerceAtLeast(0L))
                val length = (safeEnd - safeStart + 1).toInt()
                val partialData = data.copyOfRange(safeStart.toInt(), safeStart.toInt() + length)

                val resp = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    "video/mp2t",
                    ByteArrayInputStream(partialData),
                    length.toLong()
                )
                resp.addHeader("Content-Range", "bytes $safeStart-$safeEnd/${data.size}")
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

    private fun serveDirectMedia(session: IHTTPSession, uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size < 3) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid media path")
        }
        val streamId = parts[1]
        val streamSession = streamManager.getSession(streamId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream session not found")

        val targetUrl = streamSession.originalUrl
        val rangeHeader = session.headers["range"]

        return runBlocking {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val reqBuilder = okhttp3.Request.Builder().url(targetUrl)
                streamSession.referer?.let { reqBuilder.addHeader("Referer", it) }
                streamSession.userAgent?.let { reqBuilder.addHeader("User-Agent", it) }
                streamSession.cookie?.let { reqBuilder.addHeader("Cookie", it) }
                if (rangeHeader != null) {
                    reqBuilder.addHeader("Range", rangeHeader)
                }

                val upstreamResp = client.newCall(reqBuilder.build()).execute()
                val contentType = upstreamResp.header("Content-Type") ?: "video/mp4"
                val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                val contentLength = upstreamResp.body?.contentLength() ?: -1L

                val status = if (upstreamResp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                val resp = if (contentLength >= 0) {
                    newFixedLengthResponse(status, contentType, bodyStream, contentLength)
                } else {
                    newChunkedResponse(status, contentType, bodyStream)
                }

                upstreamResp.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
                resp.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(resp)
                resp
            } catch (e: Exception) {
                Log.e(TAG, "Error proxying direct media: ${e.message}", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
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
