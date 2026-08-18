package com.bigeyes.app.proxy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

class StreamFetcher {

    companion object {
        private const val TAG = "StreamFetcher"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 30, TimeUnit.SECONDS))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun buildRequest(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent ?: DEFAULT_UA)
            .header("Accept", "*/*")

        referer?.let {
            builder.header("Referer", it)
            try {
                val uri = URI(it)
                builder.header("Origin", "${uri.scheme}://${uri.host}")
            } catch (_: Exception) {
            }
        }

        cookie?.let {
            builder.header("Cookie", it)
        }

        return builder.build()
    }

    suspend fun fetchText(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(url, referer, userAgent, cookie)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} fetching m3u8: $url")
            }
            response.body?.string() ?: throw Exception("Empty body from $url")
        }
    }

    suspend fun fetchBytes(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null,
        maxRetries: Int = 3
    ): ByteArray = withContext(Dispatchers.IO) {
        val backoffs = listOf(500L, 1000L, 2000L)
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                val request = buildRequest(url, referer, userAgent, cookie)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code} fetching segment: $url")
                    }
                    return@withContext response.body?.bytes() ?: throw Exception("Empty segment data from $url")
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoff = backoffs[minOf(attempt, backoffs.size - 1)]
                    Log.w(TAG, "Fetch failed for $url (attempt ${attempt + 1}/$maxRetries). Retrying in ${backoff}ms...")
                    delay(backoff)
                } else {
                    Log.e(TAG, "Fetch permanently failed for $url: ${e.message}")
                }
            }
        }

        throw lastException ?: Exception("Failed fetching $url")
    }
}
