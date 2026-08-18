package com.bigeyes.app.network

import android.util.Log
import com.bigeyes.app.model.CastStatus
import com.bigeyes.app.model.DlnaDevice
import com.bigeyes.app.model.VideoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ServerApiClient {

    companion object {
        private const val TAG = "ServerApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    var serverHost: String? = null
    var serverPort: Int = 8765

    val baseUrl: String?
        get() = if (!serverHost.isNullOrBlank()) "http://$serverHost:$serverPort" else null

    fun updateServerAddress(host: String, port: Int) {
        this.serverHost = host
        this.serverPort = port
        Log.i(TAG, "Updated server address to http://$host:$port")
    }

    suspend fun checkConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("No server configured"))
        try {
            val request = Request.Builder().url("$base/").get().build()
            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cast(candidate: VideoCandidate): Result<JSONObject> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("No server configured"))
        try {
            val json = JSONObject().apply {
                put("url", candidate.url)
                candidate.referer?.let { put("referer", it) }
                candidate.userAgent?.let { put("user_agent", it) }
                candidate.cookie?.let { put("cookie", it) }
                candidate.title?.let { put("title", it) }
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url("$base/api/cast").post(body).build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(JSONObject(respStr))
                } else {
                    Result.failure(Exception("Cast error: HTTP ${response.code} - $respStr"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cast request failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getDevices(): Result<List<DlnaDevice>> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("No server configured"))
        try {
            val request = Request.Builder().url("$base/api/devices").get().build()
            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val array = JSONArray(respStr)
                    val devices = mutableListOf<DlnaDevice>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        devices.add(
                            DlnaDevice(
                                id = obj.optString("id"),
                                name = obj.optString("name", "Unknown TV"),
                                ip = obj.optString("ip"),
                                selected = obj.optBoolean("selected", false)
                            )
                        )
                    }
                    Result.success(devices)
                } else {
                    Result.failure(Exception("Get devices failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun selectDevice(deviceId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("No server configured"))
        try {
            val json = JSONObject().apply { put("device_id", deviceId) }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url("$base/api/select_device").post(body).build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun control(action: String, position: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("No server configured"))
        try {
            val json = JSONObject().apply {
                put("action", action)
                position?.let { put("position", it) }
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url("$base/api/control").post(body).build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStatus(): Result<CastStatus> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("No server configured"))
        try {
            val request = Request.Builder().url("$base/api/status").get().build()
            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val obj = JSONObject(respStr)
                    val status = CastStatus(
                        hasActiveStream = obj.optBoolean("has_active_stream", false),
                        streamId = obj.optString("stream_id", null),
                        title = obj.optString("title", null),
                        device = obj.optString("device", null),
                        state = obj.optString("state", "idle"),
                        position = obj.optString("position", "00:00:00"),
                        duration = obj.optString("duration", "00:00:00")
                    )
                    Result.success(status)
                } else {
                    Result.failure(Exception("Get status failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
