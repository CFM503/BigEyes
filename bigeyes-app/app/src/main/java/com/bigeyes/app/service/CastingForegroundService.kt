package com.bigeyes.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bigeyes.app.MainActivity
import com.bigeyes.app.dlna.DlnaDeviceManager
import com.bigeyes.app.model.CastStatus
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.model.playback.PlaybackItem
import com.bigeyes.app.proxy.EmbeddedProxyServer
import com.bigeyes.app.proxy.StreamManager
import kotlinx.coroutines.*

class CastingForegroundService : Service() {

    companion object {
        private const val TAG = "CastingService"
        private const val CHANNEL_ID = "bigeyes_casting_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_CAST = "com.bigeyes.app.action.START_CAST"
        const val ACTION_STOP_CAST = "com.bigeyes.app.action.STOP_CAST"
        const val EXTRA_CANDIDATE = "extra_candidate"

        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes dynamic sliding window
        private const val IDLE_SHUTDOWN_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes idle safety fallback

        // Singleton reference accessible by MainActivity while service is running
        var instance: CastingForegroundService? = null
            private set
    }

    lateinit var streamManager: StreamManager
        private set
    var proxyServer: EmbeddedProxyServer? = null
        private set
    lateinit var dlnaManager: DlnaDeviceManager
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var idleShutdownJob: Job? = null

    var currentStatus: CastStatus = CastStatus()
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        streamManager = StreamManager(this)
        dlnaManager = DlnaDeviceManager(this)
        createNotificationChannel()
        acquireLocks()
        startProxyServer()
        dlnaManager.startPeriodicScan()
        resetIdleTimeout()
        Log.i(TAG, "CastingForegroundService created")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BigEyes 投屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台 HLS 代理与电视 DLNA 投屏连接"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BigEyes:CastingWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            Log.d(TAG, "WakeLock acquired with 15min dynamic window")

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BigEyes:CastingWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = wm?.createMulticastLock("BigEyes:CastingMulticastLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock & MulticastLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    /**
     * Dynamically renew WakeLock and reset idle timeout timer.
     * Invoked automatically whenever a segment is requested, m3u8 is parsed, or playback command executes.
     */
    fun renewLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
                it.acquire(WAKELOCK_TIMEOUT_MS)
            }
            wifiLock?.let {
                if (!it.isHeld) it.acquire()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Lock renewal: ${e.message}")
        }
        resetIdleTimeout()
    }

    private fun resetIdleTimeout() {
        idleShutdownJob?.cancel()
        idleShutdownJob = scope.launch {
            delay(IDLE_SHUTDOWN_TIMEOUT_MS)
            Log.i(TAG, "Idle timeout reached (30 min inactive). Automatically stopping casting service to conserve battery.")
            stopCasting()
            stopSelf()
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            multicastLock?.let { if (it.isHeld) it.release() }
            Log.d(TAG, "All locks released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing locks: ${e.message}")
        }
    }

    private fun startProxyServer() {
        if (proxyServer == null) {
            try {
                proxyServer = EmbeddedProxyServer(this, streamManager, EmbeddedProxyServer.DEFAULT_PORT)
                proxyServer?.start()
                Log.i(TAG, "Embedded HTTP proxy server started on port ${EmbeddedProxyServer.DEFAULT_PORT}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting proxy server: ${e.message}")
            }
        }
    }

    private fun stopProxyServer() {
        try {
            proxyServer?.stop()
            proxyServer = null
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_CAST
        when (action) {
            ACTION_STOP_CAST -> {
                stopCasting()
                stopSelf()
            }
            ACTION_START_CAST -> {
                @Suppress("DEPRECATION")
                val candidate = intent?.getSerializableExtra(EXTRA_CANDIDATE) as? VideoCandidate
                startForegroundNotification("正在准备投屏...", "BigEyes 正在为您提供本地流代理")
                renewLocks()
                if (candidate != null) {
                    castCandidate(candidate)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(title: String, content: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Notification action feeding ACTION_STOP_CAST, so the user can end a cast without
        // reopening the app (and so the stop branch is reachable).
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CastingForegroundService::class.java).apply {
                action = ACTION_STOP_CAST
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止投屏", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun castPlaybackItem(
        item: com.bigeyes.app.model.playback.PlaybackItem,
        targetDeviceId: String? = null,
        startPositionMs: Long = 0L,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        val candidate = VideoCandidate(
            url = item.playUrl,
            referer = item.headers["Referer"],
            userAgent = item.headers["User-Agent"],
            cookie = item.headers["Cookie"],
            title = item.displayTitle,
            timestamp = System.currentTimeMillis()
        )
        castCandidate(candidate, targetDeviceId, startPositionMs, onResult)
    }

    fun castCandidate(
        candidate: VideoCandidate,
        targetDeviceId: String? = null,
        startPositionMs: Long = 0L,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        scope.launch {
            try {
                renewLocks()

                // 1. Create Stream Session in local proxy
                val session = streamManager.createSession(
                    url = candidate.url,
                    referer = candidate.referer,
                    userAgent = candidate.userAgent,
                    cookie = candidate.cookie,
                    title = candidate.title
                )

                val proxyUrl = "${proxyServer?.getProxyBaseUrl()}/stream/${session.streamId}/index.m3u8"
                Log.i(TAG, "Stream created in mobile proxy: $proxyUrl")

                // 2. Resolve DLNA Device
                if (targetDeviceId != null) {
                    dlnaManager.selectDevice(targetDeviceId)
                }
                var targetDevice = dlnaManager.getSelectedDevice()
                if (targetDevice == null) {
                    // Try immediate scan
                    val found = dlnaManager.scanOnce()
                    targetDevice = found.firstOrNull()
                }

                if (targetDevice != null && !targetDevice.avTransportControlUrl.isNullOrBlank()) {
                    val devName = targetDevice.name
                    val ctrlUrl = targetDevice.avTransportControlUrl!!

                    startForegroundNotification("正在投屏: ${candidate.displayTitle}", "目标电视: $devName")

                    val okSet = dlnaManager.controller.setAvTransportUri(
                        controlUrl = ctrlUrl,
                        uri = proxyUrl,
                        title = candidate.title ?: "BigEyes Video"
                    )
                    if (okSet) {
                        dlnaManager.controller.play(ctrlUrl)
                        if (startPositionMs > 0L) {
                            // AVTransport always starts at 0 after SetAVTransportURI.
                            dlnaManager.controller.seek(ctrlUrl, formatAvTransportTime(startPositionMs))
                            Log.i(TAG, "Resuming DLNA playback at ${startPositionMs}ms")
                        }
                        currentStatus = CastStatus(
                            hasActiveStream = true,
                            streamId = session.streamId,
                            title = candidate.displayTitle,
                            device = devName,
                            state = "playing"
                        )
                        startPlaybackMonitor(ctrlUrl)
                        onResult?.invoke(true, devName)
                    } else {
                        onResult?.invoke(false, "无法向电视推送播放地址")
                    }
                } else {
                    // No renderer answered: this is a failure, not a successful cast.
                    Log.w(TAG, "No DLNA renderer with an AVTransport control URL was discovered")
                    currentStatus = CastStatus()
                    onResult?.invoke(false, "未找到可用的 DLNA 电视设备")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cast failed: ${e.message}", e)
                onResult?.invoke(false, e.message)
            }
        }
    }

    private var playbackMonitorJob: Job? = null
    var onAutoNextEpisodeListener: (() -> Unit)? = null

    private fun startPlaybackMonitor(ctrlUrl: String) {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = scope.launch {
            var hasStartedPlaying = false
            var consecutiveStoppedCount = 0

            while (isActive && currentStatus.hasActiveStream) {
                delay(2500)
                try {
                    val transInfo = dlnaManager.controller.getTransportInfo(ctrlUrl)
                    val state = transInfo["current_transport_state"] ?: "STOPPED"

                    if (state.contains("play", ignoreCase = true)) {
                        hasStartedPlaying = true
                        consecutiveStoppedCount = 0
                        renewLocks()
                    } else if (state.equals("STOPPED", ignoreCase = true) && hasStartedPlaying) {
                        consecutiveStoppedCount++
                        if (consecutiveStoppedCount >= 2) {
                            Log.i(TAG, "Playback naturally ended on TV. Requesting auto-advance to next episode.")
                            hasStartedPlaying = false
                            consecutiveStoppedCount = 0
                            withContext(Dispatchers.Main) {
                                onAutoNextEpisodeListener?.invoke()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Playback monitor error: ${e.message}")
                }
            }
        }
    }

    fun stopCasting() {
        playbackMonitorJob?.cancel()
        scope.launch {
            idleShutdownJob?.cancel()
            dlnaManager.getSelectedDevice()?.avTransportControlUrl?.let { ctrlUrl ->
                try {
                    dlnaManager.controller.stop(ctrlUrl)
                } catch (_: Exception) {
                }
            }
            currentStatus = CastStatus()
        }
    }

    // ==================== DLNA transport control ====================
    // Invoked by PlaybackController whenever BigEyesTV direct cast is not the active target.

    fun pausePlayback() = withControlUrl("Pause") { dlnaManager.controller.pause(it) }

    fun resumePlayback() = withControlUrl("Play") { dlnaManager.controller.play(it) }

    fun seekPlayback(positionMs: Long) = withControlUrl("Seek") { ctrlUrl ->
        dlnaManager.controller.seek(ctrlUrl, formatAvTransportTime(positionMs))
    }

    fun stopPlayback() {
        stopCasting()
    }

    private fun withControlUrl(command: String, block: suspend (String) -> Boolean) {
        val ctrlUrl = dlnaManager.getSelectedDevice()?.avTransportControlUrl
        if (ctrlUrl.isNullOrBlank()) {
            Log.w(TAG, "Ignoring $command: no DLNA renderer is selected")
            return
        }
        scope.launch {
            renewLocks()
            try {
                if (!block(ctrlUrl)) {
                    Log.w(TAG, "$command was rejected by the renderer")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$command failed: ${e.message}")
            }
        }
    }

    /** AVTransport RelTime/AbsTime use absolute `HH:MM:SS`. */
    private fun formatAvTransportTime(positionMs: Long): String {
        val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(
            "%02d:%02d:%02d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        playbackMonitorJob?.cancel()
        idleShutdownJob?.cancel()
        stopProxyServer()
        dlnaManager.release()
        streamManager.release()
        releaseLocks()
        scope.cancel()
        Log.i(TAG, "CastingForegroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
