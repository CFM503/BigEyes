package com.bigeyes.app.proxy

import android.content.Context
import android.util.Log
import com.bigeyes.app.utils.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

class EmbeddedProxyServer(
    private val context: Context,
    private val streamManager: StreamManager,
    port: Int = 8765
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "EmbeddedProxyServer"
        const val DEFAULT_PORT = 8765
    }

    val serverPort: Int = port

    fun getProxyBaseUrl(): String {
        val lanIp = NetworkUtils.getLocalIpAddress(context)
        return "http://$lanIp:$serverPort"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Serving HTTP request: ${session.method} $uri")

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
                    serveSegment(uri)
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

    private fun serveSegment(uri: String): Response {
        // e.g. /stream/{streamId}/seg/{index}.ts or /stream/{streamId}/seg/{index}
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

    private fun serveKey(uri: String): Response {
        // e.g. /stream/{streamId}/key/{index}.key
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
