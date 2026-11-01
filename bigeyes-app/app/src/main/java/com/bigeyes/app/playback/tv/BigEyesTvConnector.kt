package com.bigeyes.app.playback.tv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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

    var onTvStatusUpdateListener: ((state: String, seriesId: String?, episodeIndex: Int, posMs: Long, durMs: Long) -> Unit)? = null

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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        targetTvIp = prefs.getString(KEY_SAVED_TV_IP, null)
        targetTvPort = prefs.getInt(KEY_SAVED_TV_PORT, 7000)
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
     * Start playback on BigEyesTV.
     *
     * @param queue optional fully resolved episode queue; when provided the TV owns local
     * auto-next playback, otherwise the phone drives episode transitions.
     */
    fun startTvPlayback(
        item: PlaybackItem,
        autoPlayNext: Boolean,
        startPositionMs: Long = 0L,
        queue: List<Episode>? = null
    ): Boolean {
        if (!isDirectCastSupported()) {
            Log.w(TAG, "BigEyesTV direct cast is unavailable on this device")
            return false
        }

        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            return startNetworkCompanionPlayback(ip, targetTvPort, item, autoPlayNext, startPositionMs, queue)
        }

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

            item.headers["Referer"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_REFERER, it) }
            item.headers["User-Agent"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_USER_AGENT, it) }
            item.headers["Cookie"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_COOKIE, it) }

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

    private fun startNetworkCompanionPlayback(
        ip: String,
        port: Int,
        item: PlaybackItem,
        autoPlayNext: Boolean,
        startPositionMs: Long,
        queue: List<Episode>?
    ): Boolean {
        return runBlocking(Dispatchers.IO) {
            try {
                val json = JSONObject()
                json.put("seriesId", item.episode.seriesId)
                json.put("seriesTitle", item.episode.seriesTitle)
                json.put("startIndex", item.episode.episodeIndex)
                json.put("startPositionMs", startPositionMs)
                json.put("autoPlayNext", autoPlayNext)

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
                    epObj.put("title", ep.displayTitle)
                    epObj.put("url", ep.playUrl)
                    epObj.put("durationMs", ep.durationMs)
                    queueArray.put(epObj)
                }
                json.put("queue", queueArray)

                val playUrl = "http://$ip:$port/bigeyes/api/play"
                val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder().url(playUrl).post(body).build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
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
                Log.e(TAG, "Network error starting BigEyesTV Companion playback: ${e.message}", e)
                false
            }
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
                                val seriesId = if (json.has("seriesId") && !json.isNull("seriesId")) json.optString("seriesId") else null
                                val epIndex = json.optInt("episodeIndex", 0)
                                val posMs = json.optLong("currentPositionMs", 0L)
                                val durMs = json.optLong("durationMs", 0L)

                                withContext(Dispatchers.Main) {
                                    onTvStatusUpdateListener?.invoke(state, seriesId, epIndex, posMs, durMs)
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
                            onTvStatusUpdateListener?.invoke(PlaybackIntentContract.STATE_ERROR, null, 0, 0L, 0L)
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
        return false
    }

    fun playEpisode(index: Int): Boolean {
        val ip = targetTvIp
        if (!ip.isNullOrBlank()) {
            val payload = JSONObject().apply {
                put("action", "playEpisode")
                put("index", index)
                put("episodeIndex", index)
            }
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
                val epIndex = intent.getIntExtra(PlaybackIntentContract.EXTRA_EPISODE_INDEX, 0)
                val pos = intent.getLongExtra(PlaybackIntentContract.EXTRA_POSITION_MS, 0L)
                val dur = intent.getLongExtra(PlaybackIntentContract.EXTRA_DURATION_MS, 0L)

                Log.d(TAG, "Received TV state update: state=$state, seriesId=$seriesId, epIndex=$epIndex, pos=$pos")
                onTvStatusUpdateListener?.invoke(state, seriesId, epIndex, pos, dur)
            }
        }

        val filter = IntentFilter(PlaybackIntentContract.ACTION_STATUS_UPDATE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(statusReceiver, filter)
            }
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
