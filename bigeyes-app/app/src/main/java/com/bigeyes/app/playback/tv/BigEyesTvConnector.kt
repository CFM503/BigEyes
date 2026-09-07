package com.bigeyes.app.playback.tv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.bigeyes.app.model.playback.PlaybackItem
import com.bigeyes.app.playback.contract.PlaybackIntentContract

class BigEyesTvConnector(private val context: Context) {

    companion object {
        private const val TAG = "BigEyesTvConnector"
    }

    var onTvStatusUpdateListener: ((state: String, seriesId: String?, episodeIndex: Int, posMs: Long, durMs: Long) -> Unit)? = null

    private var statusReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    fun isTvAppInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    PlaybackIntentContract.PACKAGE_BIGEYES_TV,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(PlaybackIntentContract.PACKAGE_BIGEYES_TV, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Error checking BigEyesTV installation: ${e.message}")
            false
        }
    }

    fun startTvPlayback(
        item: PlaybackItem,
        autoPlayNext: Boolean,
        startPositionMs: Long = 0L
    ): Boolean {
        if (!isTvAppInstalled()) {
            Log.w(TAG, "BigEyesTV is not installed on this device")
            return false
        }

        registerStatusReceiver()

        val intent = Intent(PlaybackIntentContract.ACTION_PLAY).apply {
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
            putExtra(PlaybackIntentContract.EXTRA_AUTO_PLAY_NEXT, autoPlayNext)

            item.headers["Referer"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_REFERER, it) }
            item.headers["User-Agent"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_USER_AGENT, it) }
            item.headers["Cookie"]?.let { putExtra(PlaybackIntentContract.EXTRA_HEADER_COOKIE, it) }
        }

        return try {
            context.startActivity(intent)
            Log.i(TAG, "Successfully dispatched ACTION_PLAY to BigEyesTV for episode: ${item.episode.displayTitle}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch BigEyesTV: ${e.message}", e)
            false
        }
    }

    fun sendCommand(action: String, extraConfig: (Intent.() -> Unit)? = null): Boolean {
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

    fun pause() = sendCommand(PlaybackIntentContract.ACTION_PAUSE)
    fun resume() = sendCommand(PlaybackIntentContract.ACTION_RESUME)
    fun stop() = sendCommand(PlaybackIntentContract.ACTION_STOP)
    fun next() = sendCommand(PlaybackIntentContract.ACTION_NEXT)
    fun previous() = sendCommand(PlaybackIntentContract.ACTION_PREVIOUS)

    fun seekTo(positionMs: Long) = sendCommand(PlaybackIntentContract.ACTION_SEEK) {
        putExtra(PlaybackIntentContract.EXTRA_POSITION_MS, positionMs)
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
        if (!isReceiverRegistered || statusReceiver == null) return
        try {
            context.unregisterReceiver(statusReceiver)
        } catch (_: Throwable) {}
        statusReceiver = null
        isReceiverRegistered = false
    }
}
