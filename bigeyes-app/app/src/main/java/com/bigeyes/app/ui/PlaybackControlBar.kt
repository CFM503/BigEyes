package com.bigeyes.app.ui

import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.service.CastingForegroundService
import kotlinx.coroutines.*

class PlaybackControlBar(
    private val container: View,
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
    private val btnNextEpisode: Button? = container.findViewById(R.id.btn_next_episode)
    private val btnStop: Button = container.findViewById(R.id.btn_stop)

    var onNextEpisodeListener: (() -> Unit)? = null

    private var pollJob: Job? = null
    private var isUserSeeking = false
    private var currentTotalSecs = 0
    private var currentPosSecs = 0
    private var isPlaying = true

    init {
        setupListeners()
    }

    private fun setupListeners() {
        btnNextEpisode?.setOnClickListener {
            onNextEpisodeListener?.invoke()
        }

        btnPlayPause.setOnClickListener {
            val service = CastingForegroundService.instance ?: return@setOnClickListener
            val target = service.dlnaManager.getSelectedDevice() ?: return@setOnClickListener
            val ctrlUrl = target.avTransportControlUrl ?: return@setOnClickListener

            scope.launch {
                if (isPlaying) {
                    service.dlnaManager.controller.pause(ctrlUrl)
                } else {
                    service.dlnaManager.controller.play(ctrlUrl)
                }
                fetchStatus()
            }
        }

        btnStop.setOnClickListener {
            val service = CastingForegroundService.instance
            service?.stopCasting()
            hide()
        }

        btnClose.setOnClickListener {
            hide()
        }

        btnRewind.setOnClickListener {
            val service = CastingForegroundService.instance ?: return@setOnClickListener
            val target = service.dlnaManager.getSelectedDevice() ?: return@setOnClickListener
            val ctrlUrl = target.avTransportControlUrl ?: return@setOnClickListener

            scope.launch {
                val targetSecs = (currentPosSecs - 15).coerceAtLeast(0)
                service.dlnaManager.controller.seek(ctrlUrl, formatSeconds(targetSecs))
                fetchStatus()
            }
        }

        btnForward.setOnClickListener {
            val service = CastingForegroundService.instance ?: return@setOnClickListener
            val target = service.dlnaManager.getSelectedDevice() ?: return@setOnClickListener
            val ctrlUrl = target.avTransportControlUrl ?: return@setOnClickListener

            scope.launch {
                val targetSecs = if (currentTotalSecs > 0) {
                    (currentPosSecs + 15).coerceAtMost(currentTotalSecs)
                } else {
                    currentPosSecs + 15
                }
                service.dlnaManager.controller.seek(ctrlUrl, formatSeconds(targetSecs))
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
                    val service = CastingForegroundService.instance
                    val ctrlUrl = service?.dlnaManager?.getSelectedDevice()?.avTransportControlUrl
                    if (ctrlUrl != null) {
                        scope.launch {
                            service.dlnaManager.controller.seek(ctrlUrl, formatSeconds(targetSecs))
                        }
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
        val service = CastingForegroundService.instance ?: return
        val target = service.dlnaManager.getSelectedDevice() ?: return
        val ctrlUrl = target.avTransportControlUrl ?: return

        try {
            val posInfo = service.dlnaManager.controller.getPositionInfo(ctrlUrl)
            val transInfo = service.dlnaManager.controller.getTransportInfo(ctrlUrl)

            if (!isUserSeeking) {
                val relTime = posInfo["rel_time"] ?: "00:00:00"
                val duration = posInfo["track_duration"] ?: "00:00:00"
                val state = transInfo["current_transport_state"] ?: "STOPPED"

                isPlaying = state.contains("play", ignoreCase = true)
                btnPlayPause.text = if (isPlaying) "暂停" else "播放"

                tvCurrentTime.text = relTime
                tvTotalTime.text = duration

                currentPosSecs = parseTimeToSeconds(relTime)
                currentTotalSecs = parseTimeToSeconds(duration)

                if (currentTotalSecs > 0) {
                    val prog = ((currentPosSecs.toFloat() / currentTotalSecs.toFloat()) * 1000).toInt()
                    seekBar.progress = prog.coerceIn(0, 1000)
                }
            }
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
