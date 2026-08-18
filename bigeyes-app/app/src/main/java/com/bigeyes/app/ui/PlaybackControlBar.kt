package com.bigeyes.app.ui

import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.network.ServerApiClient
import kotlinx.coroutines.*

class PlaybackControlBar(
    private val container: View,
    private val apiClient: ServerApiClient,
    private val scope: CoroutineScope
) {
    private val tvTitle: TextView = container.findViewById(R.id.tv_playing_title)
    private val tvDevice: TextView = container.findViewById(R.id.tv_target_device)
    private val btnClose: ImageButton = container.findViewById(R.id.btn_close_control)
    private val tvCurrentTime: TextView = container.findViewById(R.id.tv_current_time)
    private val tvTotalTime: TextView = container.findViewById(R.id.tv_total_time)
    private val seekBar: SeekBar = container.findViewById(R.id.seekbar_progress)
    private val btnPlayPause: Button = container.findViewById(R.id.btn_play_pause)
    private val btnRewind: Button = container.findViewById(R.id.btn_rewind)
    private val btnForward: Button = container.findViewById(R.id.btn_forward)
    private val btnStop: Button = container.findViewById(R.id.btn_stop)

    private var pollJob: Job? = null
    private var isUserSeeking = false
    private var currentTotalSecs = 0
    private var currentPosSecs = 0
    private var isPlaying = true

    init {
        setupListeners()
    }

    private fun setupListeners() {
        btnPlayPause.setOnClickListener {
            scope.launch {
                val action = if (isPlaying) "pause" else "play"
                apiClient.control(action)
                fetchStatus()
            }
        }

        btnStop.setOnClickListener {
            scope.launch {
                apiClient.control("stop")
                hide()
            }
        }

        btnClose.setOnClickListener {
            hide()
        }

        btnRewind.setOnClickListener {
            scope.launch {
                val targetSecs = (currentPosSecs - 15).coerceAtLeast(0)
                apiClient.control("seek", formatSeconds(targetSecs))
                fetchStatus()
            }
        }

        btnForward.setOnClickListener {
            scope.launch {
                val targetSecs = if (currentTotalSecs > 0) {
                    (currentPosSecs + 15).coerceAtMost(currentTotalSecs)
                } else {
                    currentPosSecs + 15
                }
                apiClient.control("seek", formatSeconds(targetSecs))
                fetchStatus()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentTotalSecs > 0) {
                    val seekSecs = (progress.toFloat() / 1000f * currentTotalSecs).toInt()
                    tvCurrentTime.text = formatSeconds(seekSecs)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                val progress = sb?.progress ?: 0
                if (currentTotalSecs > 0) {
                    val targetSecs = (progress.toFloat() / 1000f * currentTotalSecs).toInt()
                    scope.launch {
                        apiClient.control("seek", formatSeconds(targetSecs))
                    }
                }
            }
        })
    }

    fun show(title: String?, deviceName: String?) {
        container.visibility = View.VISIBLE
        tvTitle.text = title ?: "正在电视投屏播放..."
        tvDevice.text = deviceName ?: "DLNA 电视"
        startPolling()
    }

    fun hide() {
        stopPolling()
        container.visibility = View.GONE
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                fetchStatus()
                delay(1500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun fetchStatus() {
        val result = apiClient.getStatus()
        result.onSuccess { status ->
            if (!isUserSeeking) {
                tvTitle.text = status.title ?: tvTitle.text
                status.device?.let { tvDevice.text = it }

                isPlaying = status.state.contains("play", ignoreCase = true)
                btnPlayPause.text = if (isPlaying) "暂停" else "播放"

                tvCurrentTime.text = status.position
                tvTotalTime.text = status.duration

                currentPosSecs = parseTimeToSeconds(status.position)
                currentTotalSecs = parseTimeToSeconds(status.duration)

                if (currentTotalSecs > 0) {
                    val prog = ((currentPosSecs.toFloat() / currentTotalSecs.toFloat()) * 1000).toInt()
                    seekBar.progress = prog.coerceIn(0, 1000)
                }
            }
        }
    }

    private fun parseTimeToSeconds(timeStr: String): Int {
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                1 -> parts[0].toInt()
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun formatSeconds(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
