package com.bigeyes.app.playback.tv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bigeyes.app.model.DlnaDevice
import com.bigeyes.app.model.playback.Episode
import com.bigeyes.app.model.playback.PlaybackItem
import com.bigeyes.app.playback.contract.PlaybackIntentContract
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bridge between the BigEyes phone app and the BigEyesTV receiver app.
 *
 * Supports two complementary transport layers:
 * 1. BigEyes Companion Protocol v1.0 (LAN HTTP REST on port 7000):
 *    Direct phone-to-TV communication over local Wi-Fi with anti-hotlink header passthrough,
 *    full episode queue handover, and 1000ms status mirroring.
 * 2. Local Android IPC (Intent/Broadcast):
 *    Single-device fallback used when phone and TV are installed on the same device.
 */
class BigEyesTvConnector(private val context: Context) {

    companion object {
        private const val TAG = "BigEyesTvConnector"
        private const val PREFS_NAME = "bigeyes_tv_companion"
        private const val KEY_SAVED_TV_IP = "saved_tv_ip"
        private const val KEY_SAVED_TV_PORT = "saved_tv_port"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    data class TvStatusUpdate(
        val state: String,
        val seriesId: String? = null,
        val seriesTitle: String? = null,
        val episodeIndex: Int = 0,
        val episodeTitle: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val speed: Float = 1.0f,
        val autoPlayNext: Boolean? = null,
        val errorMessage: String? = null,
        /** True when the phone lost the TV connection (not a playback error reported by the TV). */
        val isConnectionLost: Boolean = false
    )

    var onTvStatusUpdateListener: ((TvStatusUpdate) -> Unit)? = null

    private var statusReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    var boundDevice: DlnaDevice? = null
        private set
    var targetTvIp: String? = null
        private set
    var targetTvPort: Int = 7000
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val probeHttpClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        targetTvIp = prefs.getString(KEY_SAVED_TV_IP, null)
        targetTvPort = prefs.getInt(KEY_SAVED_TV_PORT, 7000)
    }

    fun clearSavedDevice() {
        boundDevice = null
        targetTvIp = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SAVED_TV_IP)
            .remove(KEY_SAVED_TV_PORT)
            .apply()
        stopStatusPolling()
    }

    /**
     * Fast async validation (~800ms) of the saved BigEyesTV IP via /bigeyes/api/info.
     * Clears stale IP automatically if unreachable so callers fall back to discovery.
     */
    fun validateSavedDevice(onResult: ((Boolean) -> Unit)? = null) {
        val ip = targetTvIp
        val port = targetTvPort
        if (ip.isNullOrBlank()) {
            onResult?.invoke(false)
            return
        }
        scope.launch {
            val reachable = probeCompanionReachable(ip, port)
            if (!reachable && targetTvIp == ip) {
                Log.w(TAG, "Saved BigEyesTV IP $ip:$port unreachable, clearing stale binding")
                clearSavedDevice()
            }
            withContext(Dispatchers.Main) {
                onResult?.invoke(reachable)
            }
        }
    }

    private fun probeCompanionReachable(ip: String, port: Int): Boolean {
        return try {
            val req = Request.Builder().url("http://$ip:$port/bigeyes/api/info").build()
            probeHttpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Binds a target BigEyesTV device discovered over the LAN.
     */
    fun bindDevice(device: DlnaDevice?) {
        boundDevice = device
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (device != null && device.isBigEyesTv) {
            targetTvIp = device.ip
            targetTvPort = if (device.apiPort > 0) device.apiPort else 7000
            prefs.edit()
                .putString(KEY_SAVED_TV_IP, targetTvIp)
                .putInt(KEY_SAVED_TV_PORT, targetTvPort)
                .apply()
            Log.i(TAG, "Bound BigEyesTV companion device: ${device.name} ($targetTvIp:$targetTvPort)")
        } else if (device == null) {
            targetTvIp = null
            prefs.edit().remove(KEY_SAVED_TV_IP).remove(KEY_SAVED_TV_PORT).apply()
            stopStatusPolling()
            Log.i(TAG, "Unbound BigEyesTV companion device")
        }
    }

    /** Version code of the installed BigEyesTV package, or 0 when not installed locally. */
    fun getTvVersionCode(): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    PlaybackIntentContract.PACKAGE_BIGEYES_TV,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(PlaybackIntentContract.PACKAGE_BIGEYES_TV, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        } catch (e: Throwable) {
            Log.w(TAG, "Error checking BigEyesTV installation: ${e.message}")
            0L
        }
    }

    fun isTvAppInstalled(): Boolean {
        return getTvVersionCode() > 0L
    }

    /**
     * Direct casting is supported if a BigEyesTV companion is bound over Wi-Fi
     * or if the TV app package is installed locally with command receiver support.
     */
    fun isDirectCastSupported(): Boolean {
        if (!targetTvIp.isNullOrBlank() || boundDevice?.isBigEyesTv == true) {
            return true
        }
        val versionCode = getTvVersionCode()
        return if (versionCode <= 0L) {
            false
        } else {
            val supported = versionCode >= PlaybackIntentContract.MIN_TV_VERSION_CODE
            if (!supported) {
                Log.w(
                    TAG,
                    "Local BigEyesTV v$versionCode is older than required " +
                        "v${PlaybackIntentContract.MIN_TV_VERSION_CODE}, direct cast disabled"
                )
            }
            supported
        }
    }

    /**
     * Start playback on BigEyesTV asynchronously without blocking the main thread.
     *
     * @param queue optional episode queue; when provided the TV owns local auto-next playback.
     */
    fun startTvPlayback(
        item: PlaybackItem,
        autoPlayNext: Boolean,
        startPositionMs: Long = 0L,
        queue: List<Episode>? = null,
        resumeFromHistory: Boolean = true,
        onResult: (Boolean) -> Unit
    ) {
        if (!isDirectCastSupported()) {
            Log.w(TAG, "BigEyesTV direct cast is unavailable on this device")
            onResult(false)
            return
        }

        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val port = targetTvPort
            scope.launch {
                val ok = startNetworkCompanionPlayback(
                    ip = ip,
                    port = port,
                    item = item,
                    autoPlayNext = autoPlayNext,
                    startPositionMs = startPositionMs,
                    queue = queue,
                    resumeFromHistory = resumeFromHistory
                )
                withContext(Dispatchers.Main) {
                    onResult(ok)
                }
            }
            return
        }

        val localOk = startLocalTvPlayback(item, autoPlayNext, startPositionMs, queue, resumeFromHistory)
        onResult(localOk)
    }

    suspend fun startTvPlaybackSuspend(
        item: PlaybackItem,
        autoPlayNext: Boolean,
        startPositionMs: Long = 0L,
        queue: List<Episode>? = null,
        resumeFromHistory: Boolean = true
    ): Boolean {
        if (!isDirectCastSupported()) {
            Log.w(TAG, "BigEyesTV direct cast is unavailable on this device")
            return false
        }
        val ip = targetTvIp
        return if (!ip.isNullOrBlank()) {
            startNetworkCompanionPlayback(
                ip = ip,
                port = targetTvPort,
                item = item,
                autoPlayNext = autoPlayNext,
                startPositionMs = startPositionMs,
                queue = queue,
                resumeFromHistory = resumeFromHistory
            )
        } else {
            withContext(Dispatchers.Main) {
                startLocalTvPlayback(item, autoPlayNext, startPositionMs, queue, resumeFromHistory)
            }
        }
    }

    private fun startLocalTvPlayback(
        item: PlaybackItem,
        autoPlayNext: Boolean,
        startPositionMs: Long,
        queue: List<Episode>?,
        resumeFromHistory: Boolean
    ): Boolean {
        // Local intent dispatch
        registerStatusReceiver()

        val action = if (queue != null && queue.size > 1) {
            PlaybackIntentContract.ACTION_PLAY_QUEUE
        } else {
            PlaybackIntentContract.ACTION_PLAY
        }

        val intent = Intent(action).apply {
            component = ComponentName(
                PlaybackIntentContract.PACKAGE_BIGEYES_TV,
                PlaybackIntentContract.TV_MAIN_ACTIVITY
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PlaybackIntentContract.EXTRA_PLAY_URL, item.playUrl)
            putExtra(PlaybackIntentContract.EXTRA_SERIES_ID, item.episode.seriesId)
            putExtra(PlaybackIntentContract.EXTRA_SERIES_TITLE, item.episode.seriesTitle)
            putExtra(PlaybackIntentContract.EXTRA_SEASON_NUMBER, item.episode.seasonNumber)
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_NUMBER, item.episode.episodeNumber)
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_INDEX, item.episode.episodeIndex)
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_TITLE, item.episode.episodeTitle)
            putExtra(PlaybackIntentContract.EXTRA_TOTAL_COUNT, item.totalCount)
            putExtra(PlaybackIntentContract.EXTRA_POSITION_MS, startPositionMs)
            putExtra(PlaybackIntentContract.EXTRA_SEEK_POSITION, startPositionMs)
            putExtra(PlaybackIntentContract.EXTRA_AUTO_PLAY_NEXT, autoPlayNext)
            putExtra(PlaybackIntentContract.EXTRA_RESUME_FROM_HISTORY, resumeFromHistory)

            item.headers["Referer"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_REFERER, it) }
            item.headers["User-Agent"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_USER_AGENT, it) }
            item.headers["Cookie"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_COOKIE, it) }
            if (item.headers.isNotEmpty()) {
                putExtra(PlaybackIntentContract.EXTRA_HEADERS, JSONObject(item.headers).toString())
            }

            if (action == PlaybackIntentContract.ACTION_PLAY_QUEUE && queue != null) {
                putExtra(
                    PlaybackIntentContract.EXTRA_EPISODE_QUEUE,
                    PlaybackIntentContract.buildQueueJson(queue)
                )
            }
        }

        return try {
            context.startActivity(intent)
            Log.i(
                TAG,
                "Dispatched ${item.episode.displayTitle} to BigEyesTV " +
                    "(action=$action, queue=${queue?.size ?: 1}, autoPlayNext=$autoPlayNext)"
            )
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch BigEyesTV: ${e.message}", e)
            false
        }
    }

    private suspend fun startNetworkCompanionPlayback(
        ip: String,
        port: Int,
        item: PlaybackItem,
        autoPlayNext: Boolean,
        startPositionMs: Long,
        queue: List<Episode>?,
        resumeFromHistory: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject()
            json.put("seriesId", item.episode.seriesId)
            json.put("seriesTitle", item.episode.seriesTitle)
            json.put("startIndex", item.episode.episodeIndex)
            json.put("startPositionMs", startPositionMs)
            json.put("autoPlayNext", autoPlayNext)
            json.put("resumeFromHistory", resumeFromHistory)

            val headersObj = JSONObject()
            item.headers.forEach { (k, v) -> headersObj.put(k, v) }
            json.put("headers", headersObj)

            val queueArray = JSONArray()
            val episodesToQueue = if (!queue.isNullOrEmpty()) queue else listOf(item.episode.copy(playUrl = item.playUrl))
            for (ep in episodesToQueue) {
                val epObj = JSONObject()
                epObj.put("seriesId", ep.seriesId)
                epObj.put("seriesTitle", ep.seriesTitle)
                epObj.put("seasonNumber", ep.seasonNumber)
                epObj.put("episodeNumber", ep.episodeNumber)
                epObj.put("episodeIndex", ep.episodeIndex)
                epObj.put("episodeTitle", ep.displayTitle)
                epObj.put("title", ep.displayTitle)
                val resolvedUrl = if (ep.episodeIndex == item.episode.episodeIndex && ep.playUrl.isNullOrBlank()) {
                    item.playUrl
                } else {
                    ep.playUrl ?: ""
                }
                epObj.put("playUrl", resolvedUrl)
                epObj.put("url", resolvedUrl)
                epObj.put("duration", ep.durationMs)
                epObj.put("durationMs", ep.durationMs)

                val epHeaders = if (ep.playHeaders.isNotEmpty()) {
                    ep.playHeaders
                } else if (ep.episodeIndex == item.episode.episodeIndex) {
                    item.headers
                } else {
                    emptyMap()
                }
                if (epHeaders.isNotEmpty()) {
                    val epHeadersObj = JSONObject()
                    epHeaders.forEach { (k, v) -> epHeadersObj.put(k, v) }
                    epObj.put("headers", epHeadersObj)
                }
                queueArray.put(epObj)
            }
            json.put("queue", queueArray)

            val playUrl = "http://$ip:$port/bigeyes/api/play"
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(playUrl).post(body).build()

            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "Successfully handed over playback to BigEyesTV Companion at $playUrl")
                    startStatusPolling(ip, port)
                    true
                } else {
                    Log.w(TAG, "BigEyesTV Companion rejected play request: ${resp.code} ${resp.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error starting BigEyesTV Companion playback ($ip:$port): ${e.message}", e)
            if (targetTvIp == ip) {
                clearSavedDevice()
            }
            false
        }
    }

    private fun startStatusPolling(ip: String, port: Int) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            val statusUrl = "http://$ip:$port/bigeyes/api/status"
            val request = Request.Builder().url(statusUrl).build()
            Log.d(TAG, "Starting BigEyesTV companion status polling at $statusUrl")

            var consecutiveFailures = 0
            while (isActive) {
                try {
                    httpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            consecutiveFailures = 0
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                val json = JSONObject(body)
                                val state = json.optString("state", PlaybackIntentContract.STATE_STOPPED)
                                val seriesId = json.optString("seriesId", "").takeIf { it.isNotBlank() && !json.isNull("seriesId") }
                                val seriesTitle = json.optString("seriesTitle", "").takeIf { it.isNotBlank() && !json.isNull("seriesTitle") }
                                val epIndex = json.optInt("episodeIndex", 0)
                                val epTitle = json.optString("episodeTitle", "").takeIf { it.isNotBlank() && !json.isNull("episodeTitle") }
                                val posMs = json.optLong("currentPositionMs", 0L)
                                val durMs = json.optLong("durationMs", 0L)
                                val speed = json.optDouble("speed", 1.0).toFloat()
                                val autoPlayNext = if (json.has("autoPlayNext") && !json.isNull("autoPlayNext")) {
                                    json.optBoolean("autoPlayNext")
                                } else null
                                val errorMsg = json.optString("errorMessage", "").takeIf { it.isNotBlank() && !json.isNull("errorMessage") }

                                val update = TvStatusUpdate(
                                    state = state,
                                    seriesId = seriesId,
                                    seriesTitle = seriesTitle,
                                    episodeIndex = epIndex,
                                    episodeTitle = epTitle,
                                    positionMs = posMs,
                                    durationMs = durMs,
                                    speed = speed,
                                    autoPlayNext = autoPlayNext,
                                    errorMessage = errorMsg
                                )
                                withContext(Dispatchers.Main) {
                                    onTvStatusUpdateListener?.invoke(update)
                                }
                            }
                        } else {
                            consecutiveFailures++
                        }
                        Unit
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.d(TAG, "Companion status poll tick: ${e.message} (consecutive: $consecutiveFailures)")
                    if (consecutiveFailures >= 5) {
                        Log.w(TAG, "Lost connection to BigEyesTV after $consecutiveFailures consecutive poll failures")
                        withContext(Dispatchers.Main) {
                            onTvStatusUpdateListener?.invoke(
                                TvStatusUpdate(
                                    state = PlaybackIntentContract.STATE_ERROR,
                                    errorMessage = "与大屏端连接已断开，请检查网络或重新投屏",
                                    isConnectionLost = true
                                )
                            )
                        }
                        break
                    }
                }
                delay(1000L)
            }
        }
    }

    fun stopStatusPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun sendCommand(action: String, extraConfig: (Intent.() -> Unit)? = null): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val controlAction = when (action) {
                PlaybackIntentContract.ACTION_PAUSE -> "pause"
                PlaybackIntentContract.ACTION_RESUME -> "resume"
                PlaybackIntentContract.ACTION_STOP -> "stop"
                PlaybackIntentContract.ACTION_NEXT -> "next"
                PlaybackIntentContract.ACTION_PREVIOUS -> "prev"
                PlaybackIntentContract.ACTION_SEEK -> "seek"
                else -> action
            }

            val payload = JSONObject()
            payload.put("action", controlAction)

            if (action == PlaybackIntentContract.ACTION_SEEK) {
                val dummyIntent = Intent()
                extraConfig?.invoke(dummyIntent)
                val pos = dummyIntent.getLongExtra(
                    PlaybackIntentContract.EXTRA_SEEK_POSITION,
                    dummyIntent.getLongExtra(PlaybackIntentContract.EXTRA_POSITION_MS, 0L)
                )
                payload.put("positionMs", pos)
            }

            scope.launch {
                sendNetworkControl(ip, targetTvPort, payload)
            }
            return true
        }

        if (!isTvAppInstalled()) return false

        val intent = Intent(action).apply {
            setPackage(PlaybackIntentContract.PACKAGE_BIGEYES_TV)
            extraConfig?.invoke(this)
        }

        return try {
            context.sendBroadcast(intent)
            Log.d(TAG, "Dispatched broadcast command to BigEyesTV: $action")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed dispatching broadcast to BigEyesTV: ${e.message}")
            false
        }
    }

    private suspend fun sendNetworkControl(ip: String, port: Int, payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val url = "http://$ip:$port/bigeyes/api/control"
        try {
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder().url(url).post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                Log.d(TAG, "Dispatched companion network command to $url: $payload -> ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed dispatching companion network command to $url: ${e.message}")
            false
        }
    }

    fun pause() = sendCommand(PlaybackIntentContract.ACTION_PAUSE)
    fun resume() = sendCommand(PlaybackIntentContract.ACTION_RESUME)
    fun stop(): Boolean {
        val ok = sendCommand(PlaybackIntentContract.ACTION_STOP)
        stopStatusPolling()
        return ok
    }
    fun next() = sendCommand(PlaybackIntentContract.ACTION_NEXT)
    fun previous() = sendCommand(PlaybackIntentContract.ACTION_PREVIOUS)

    fun seekTo(positionMs: Long): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply {
                put("action", "seek")
                put("positionMs", positionMs)
            }
            scope.launch { sendNetworkControl(ip, targetTvPort, payload) }
            return true
        }
        return sendCommand(PlaybackIntentContract.ACTION_SEEK) {
            putExtra(PlaybackIntentContract.EXTRA_SEEK_POSITION, positionMs)
            putExtra(PlaybackIntentContract.EXTRA_POSITION_MS, positionMs)
        }
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply {
                put("action", "speed")
                put("speed", speed.toDouble())
            }
            scope.launch { sendNetworkControl(ip, targetTvPort, payload) }
            return true
        }
        return sendCommand(PlaybackIntentContract.ACTION_SET_SPEED) {
            putExtra(PlaybackIntentContract.EXTRA_SPEED, speed)
        }
    }

    fun playEpisode(index: Int, positionMs: Long = 0L): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply {
                put("action", "playEpisode")
                put("index", index)
                put("episodeIndex", index)
                if (positionMs > 0L) {
                    put("positionMs", positionMs)
                }
            }
            scope.launch { sendNetworkControl(ip, targetTvPort, payload) }
            return true
        }
        return sendCommand(PlaybackIntentContract.ACTION_PLAY_EPISODE) {
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_INDEX, index)
            if (positionMs > 0L) {
                putExtra(PlaybackIntentContract.EXTRA_POSITION_MS, positionMs)
                putExtra(PlaybackIntentContract.EXTRA_SEEK_POSITION, positionMs)
            }
        }
    }

    fun updateEpisodeUrl(
        episodeIndex: Int,
        playUrl: String,
        headers: Map<String, String> = emptyMap(),
        positionMs: Long? = null,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply {
                put("action", "updateEpisodeUrl")
                put("episodeIndex", episodeIndex)
                put("index", episodeIndex)
                put("playUrl", playUrl)
                if (headers.isNotEmpty()) {
                    put("headers", JSONObject(headers))
                }
                if (positionMs != null && positionMs >= 0L) {
                    put("positionMs", positionMs)
                }
            }
            scope.launch {
                val ok = sendNetworkControl(ip, targetTvPort, payload)
                withContext(Dispatchers.Main) {
                    onResult?.invoke(ok)
                }
            }
            return true
        }
        val ok = sendCommand(PlaybackIntentContract.ACTION_UPDATE_EPISODE_URL) {
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_INDEX, episodeIndex)
            putExtra(PlaybackIntentContract.EXTRA_PLAY_URL, playUrl)
            headers["Referer"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_REFERER, it) }
            headers["User-Agent"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_USER_AGENT, it) }
            headers["Cookie"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_COOKIE, it) }
            if (headers.isNotEmpty()) {
                putExtra(PlaybackIntentContract.EXTRA_HEADERS, JSONObject(headers).toString())
            }
            if (positionMs != null && positionMs >= 0L) {
                putExtra(PlaybackIntentContract.EXTRA_POSITION_MS, positionMs)
                putExtra(PlaybackIntentContract.EXTRA_SEEK_POSITION, positionMs)
            }
        }
        onResult?.invoke(ok)
        return ok
    }

    fun setAutoPlayNext(enabled: Boolean): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply {
                put("action", "autoPlayNext")
                put("enabled", enabled)
            }
            scope.launch { sendNetworkControl(ip, targetTvPort, payload) }
            return true
        }
        return false
    }

    fun retry(): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply { put("action", "retry") }
            scope.launch { sendNetworkControl(ip, targetTvPort, payload) }
            return true
        }
        return false
    }

    fun cancelCountdown(): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply { put("action", "cancelCountdown") }
            scope.launch { sendNetworkControl(ip, targetTvPort, payload) }
            return true
        }
        return false
    }

    @Synchronized
    fun registerStatusReceiver() {
        if (isReceiverRegistered) return

        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null || intent.action != PlaybackIntentContract.ACTION_STATUS_UPDATE) return

                val state = intent.getStringExtra(PlaybackIntentContract.EXTRA_STATE) ?: return
                val seriesId = intent.getStringExtra(PlaybackIntentContract.EXTRA_SERIES_ID)
                val seriesTitle = intent.getStringExtra(PlaybackIntentContract.EXTRA_SERIES_TITLE)
                val epIndex = intent.getIntExtra(PlaybackIntentContract.EXTRA_EPISODE_INDEX, 0)
                val epTitle = intent.getStringExtra(PlaybackIntentContract.EXTRA_EPISODE_TITLE)
                val pos = intent.getLongExtra(PlaybackIntentContract.EXTRA_POSITION_MS, 0L)
                val dur = intent.getLongExtra(PlaybackIntentContract.EXTRA_DURATION_MS, 0L)
                val speed = intent.getFloatExtra(PlaybackIntentContract.EXTRA_SPEED, 1.0f)
                val autoPlayNext = if (intent.hasExtra(PlaybackIntentContract.EXTRA_AUTO_PLAY_NEXT)) {
                    intent.getBooleanExtra(PlaybackIntentContract.EXTRA_AUTO_PLAY_NEXT, true)
                } else null
                val errorMsg = intent.getStringExtra(PlaybackIntentContract.EXTRA_ERROR_MESSAGE)

                Log.d(TAG, "Received TV state update: state=$state, seriesId=$seriesId, epIndex=$epIndex, pos=$pos")
                onTvStatusUpdateListener?.invoke(
                    TvStatusUpdate(
                        state = state,
                        seriesId = seriesId,
                        seriesTitle = seriesTitle,
                        episodeIndex = epIndex,
                        episodeTitle = epTitle,
                        positionMs = pos,
                        durationMs = dur,
                        speed = speed,
                        autoPlayNext = autoPlayNext,
                        errorMessage = errorMsg
                    )
                )
            }
        }

        val filter = IntentFilter(PlaybackIntentContract.ACTION_STATUS_UPDATE)
        try {
            ContextCompat.registerReceiver(
                context,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
            Log.d(TAG, "BigEyesTV status BroadcastReceiver registered")
        } catch (e: Throwable) {
            Log.w(TAG, "Error registering BigEyesTV status receiver: ${e.message}")
        }
    }

    @Synchronized
    fun unregisterStatusReceiver() {
        stopStatusPolling()
        if (!isReceiverRegistered || statusReceiver == null) return
        try {
            context.unregisterReceiver(statusReceiver)
        } catch (_: Throwable) {}
        statusReceiver = null
        isReceiverRegistered = false
    }
}
