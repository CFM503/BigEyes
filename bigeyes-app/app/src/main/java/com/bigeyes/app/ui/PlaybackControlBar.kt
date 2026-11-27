package com.bigeyes.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.util.TypedValue
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.service.CastingForegroundService
import com.bigeyes.app.utils.AppPreferences
import kotlinx.coroutines.*

/**
 * Bottom playback control card.
 *
 * Collapsed it shows only title, cast status, play/pause and progress; tapping the header or
 * the "更多控制" hint expands the episode/seek buttons and secondary actions. The cast status
 * (connecting, resolving, buffering, playing, paused, error + retry) is always visible here,
 * replacing the stream of Toasts the cast flow used to produce.
 */
class PlaybackControlBar(
    private val container: View,
    private val scope: CoroutineScope
) {
    /** What the bar tells the user about the cast, independent of the play/pause button. */
    enum class CastStatus { CONNECTING, RESOLVING, BUFFERING, PLAYING, PAUSED, COUNTDOWN, ERROR }

    private val context = container.context

    private val viewGrabHandle: View = container.findViewById(R.id.view_grab_handle)
    private val layoutHeader: View = container.findViewById(R.id.layout_header)
    private val tvTitle: TextView = container.findViewById(R.id.tv_playing_title)
    private val tvQueueIndicator: TextView = container.findViewById(R.id.tv_queue_indicator)
    private val tvStatus: TextView = container.findViewById(R.id.tv_cast_status)
    private val tvDevice: TextView = container.findViewById(R.id.tv_target_device)
    private val btnPlayPause: ImageButton = container.findViewById(R.id.btn_play_pause)
    private val btnPlayPauseBig: ImageButton = container.findViewById(R.id.btn_play_pause_big)
    private val pbMiniProgress: ProgressBar = container.findViewById(R.id.pb_mini_progress)
    private val btnClose: ImageButton = container.findViewById(R.id.btn_close_control)

    private val layoutError: View = container.findViewById(R.id.layout_error)
    private val tvErrorMessage: TextView = container.findViewById(R.id.tv_error_message)
    private val btnRetry: Button = container.findViewById(R.id.btn_retry)

    private val tvCurrentTime: TextView = container.findViewById(R.id.tv_current_time)
    private val tvTotalTime: TextView = container.findViewById(R.id.tv_total_time)
    private val seekBar: SeekBar = container.findViewById(R.id.seekbar_progress)

    private val layoutExpanded: View = container.findViewById(R.id.layout_expanded)
    private val colPreviousEpisode: View = container.findViewById(R.id.col_previous_episode)
    private val colNextEpisode: View = container.findViewById(R.id.col_next_episode)
    private val btnPreviousEpisode: ImageButton = container.findViewById(R.id.btn_previous_episode)
    private val btnRewind: ImageButton = container.findViewById(R.id.btn_rewind)
    private val btnForward: ImageButton = container.findViewById(R.id.btn_forward)
    private val btnNextEpisode: ImageButton = container.findViewById(R.id.btn_next_episode)
    private val btnSelectEpisode: View = container.findViewById(R.id.btn_select_episode)
    private val btnSpeed: View = container.findViewById(R.id.btn_speed)
    private val tvSpeedLabel: TextView = container.findViewById(R.id.tv_speed_label)
    private val ivSpeedIcon: ImageView = container.findViewById(R.id.iv_speed_icon)
    private val btnSwitchDevice: View = container.findViewById(R.id.btn_switch_device)
    private val btnStop: View = container.findViewById(R.id.btn_stop)

    private val layoutExpandToggle: View = container.findViewById(R.id.layout_expand_toggle)
    private val tvExpandHint: TextView = container.findViewById(R.id.tv_expand_hint)
    private val ivExpandArrow: ImageView = container.findViewById(R.id.iv_expand_arrow)

    var onPreviousEpisodeListener: (() -> Unit)? = null
    var onNextEpisodeListener: (() -> Unit)? = null
    var onSelectEpisodeListener: (() -> Unit)? = null
    var onPlayPauseClickListener: (() -> Unit)? = null
    var onRewindClickListener: (() -> Unit)? = null
    var onForwardClickListener: (() -> Unit)? = null
    var onStopClickListener: (() -> Unit)? = null
    var onRetryListener: (() -> Unit)? = null

    var onCloseClickListener: (() -> Unit)? = null
    var onSeekToListener: ((Long) -> Unit)? = null
    var onSpeedSelectedListener: ((Float) -> Unit)? = null
    var onSwitchDeviceListener: (() -> Unit)? = null
    var onExternalPlayPauseChanged: ((Boolean) -> Unit)? = null

    /** Panel expanded/collapsed; MainActivity dims the page behind the expanded panel. */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    private val speedPresets = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
    private var currentSpeed: Float = 1.0f

    /**
     * DLNA polling callback. Feeds the polled renderer position back into PlaybackController
     * so relative transport commands (±15s) always start from the real playhead.
     */
    var onRemoteProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null

    private var pollJob: Job? = null
    private var isUserSeeking = false
    private var currentTotalSecs = 0
    private var currentPosSecs = 0
    private var isPlaying = true

    /** 直连 BigEyesTV 投屏时进度由电视状态广播推送，跳过 DLNA 轮询。 */
    private var remoteProgressSource = false

    var isExpanded: Boolean = false
        private set

    /** Device name as given by the caller; the label may add 直连 / DLNA 代理 (显示技术信息). */
    private var deviceBaseName: String? = null

    var status: CastStatus = CastStatus.CONNECTING
        private set

    private var pulseAnimator: ObjectAnimator? = null

    init {
        setupListeners()
        setPlaybackSpeed(1.0f)
        applyExpanded(false)
        applyStatus()
    }

    private fun setupListeners() {
        layoutHeader.setOnClickListener { toggleExpanded() }
        layoutExpandToggle.setOnClickListener { toggleExpanded() }
        viewGrabHandle.setOnClickListener { collapse() }

        btnPreviousEpisode.setOnClickListener { onPreviousEpisodeListener?.invoke() }
        btnNextEpisode.setOnClickListener { onNextEpisodeListener?.invoke() }
        btnSelectEpisode.setOnClickListener { onSelectEpisodeListener?.invoke() }
        btnPlayPause.setOnClickListener { onPlayPauseClickListener?.invoke() }
        btnPlayPauseBig.setOnClickListener { onPlayPauseClickListener?.invoke() }
        btnRewind.setOnClickListener { onRewindClickListener?.invoke() }
        btnForward.setOnClickListener { onForwardClickListener?.invoke() }
        btnRetry.setOnClickListener { onRetryListener?.invoke() }

        btnStop.setOnClickListener {
            onStopClickListener?.invoke()
            hide()
        }

        btnClose.setOnClickListener {
            hide()
            onCloseClickListener?.invoke()
        }

        btnSpeed.setOnClickListener {
            val currentIdx = speedPresets.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.05f }
            val nextSpeed = speedPresets[(currentIdx + 1) % speedPresets.size]
            setPlaybackSpeed(nextSpeed)
            onSpeedSelectedListener?.invoke(nextSpeed)
        }

        btnSwitchDevice.setOnClickListener { onSwitchDeviceListener?.invoke() }
        tvDevice.setOnClickListener { onSwitchDeviceListener?.invoke() }

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
                    onSeekToListener?.invoke(targetSecs * 1000L)
                }
            }
        })
    }

    // ==================== Expand / collapse ====================

    fun toggleExpanded() = applyExpanded(!isExpanded)

    /** @return true when the panel was expanded and is now collapsed (used by the back key). */
    fun collapse(): Boolean {
        if (!isExpanded) return false
        applyExpanded(false)
        return true
    }

    private fun applyExpanded(expanded: Boolean) {
        val changed = isExpanded != expanded
        isExpanded = expanded
        // Expanded: full panel with the big play button. Collapsed: mini card.
        layoutExpanded.visibility = if (expanded) View.VISIBLE else View.GONE
        viewGrabHandle.visibility = if (expanded) View.VISIBLE else View.GONE
        layoutExpandToggle.visibility = if (expanded) View.GONE else View.VISIBLE
        btnPlayPause.visibility = if (expanded) View.GONE else View.VISIBLE
        pbMiniProgress.visibility = if (expanded) View.GONE else View.VISIBLE
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (expanded) 17f else 14f)
        tvExpandHint.text = "更多控制"
        ivExpandArrow.setImageResource(R.drawable.ic_expand_less)
        if (changed) onExpandedChanged?.invoke(expanded)
    }

    // ==================== Cast status ====================

    fun setStatus(newStatus: CastStatus) {
        if (newStatus != CastStatus.ERROR) {
            layoutError.visibility = View.GONE
        }
        status = newStatus
        applyStatus()
    }

    /** Called when a cast is being started (before the TV accepted it). */
    fun showConnecting(title: String?, deviceName: String?) {
        show(title, deviceName)
        setStatus(CastStatus.CONNECTING)
    }

    /** Failure shown in the bar with a retry button; the bar stays open. */
    fun showError(message: String?, canRetry: Boolean = true) {
        status = CastStatus.ERROR
        tvErrorMessage.text = message?.takeIf { it.isNotBlank() } ?: "播放失败，请重试"
        btnRetry.visibility = if (canRetry) View.VISIBLE else View.GONE
        layoutError.visibility = View.VISIBLE
        applyStatus()
    }

    private fun applyStatus() {
        val (label, bgColor, textColor) = when (status) {
            CastStatus.CONNECTING -> Triple("正在连接…", R.color.status_busy_bg, R.color.status_busy_text)
            CastStatus.RESOLVING -> Triple("正在解析地址…", R.color.status_busy_bg, R.color.status_busy_text)
            CastStatus.BUFFERING -> Triple("缓冲中…", R.color.status_busy_bg, R.color.status_busy_text)
            CastStatus.PLAYING -> Triple("播放中", R.color.status_ok_bg, R.color.status_ok_text)
            CastStatus.PAUSED -> Triple("已暂停", R.color.status_idle_bg, R.color.status_idle_text)
            CastStatus.COUNTDOWN -> Triple("即将播放下一集", R.color.status_busy_bg, R.color.status_busy_text)
            CastStatus.ERROR -> Triple("播放失败", R.color.status_err_bg, R.color.status_err_text)
        }
        tvStatus.text = "● $label"
        tvStatus.backgroundTintList = ColorStateList.valueOf(context.getColor(bgColor))
        tvStatus.setTextColor(context.getColor(textColor))

        val busy = status == CastStatus.CONNECTING || status == CastStatus.RESOLVING || status == CastStatus.BUFFERING
        // Only animate while the bar is on screen.
        if (busy && container.visibility == View.VISIBLE) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ObjectAnimator.ofFloat(tvStatus, View.ALPHA, 1f, 0.45f).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        tvStatus.alpha = 1f
    }

    // ==================== Playback state ====================

    fun updateQueueState(currentIndex: Int, totalEpisodes: Int, hasPrev: Boolean, hasNxt: Boolean) {
        if (totalEpisodes > 1) {
            tvQueueIndicator.visibility = View.VISIBLE
            tvQueueIndicator.text = "第 ${currentIndex + 1} 集 / 共 ${totalEpisodes} 集"
            setEpisodeButtonEnabled(btnPreviousEpisode, colPreviousEpisode, hasPrev)
            setEpisodeButtonEnabled(btnNextEpisode, colNextEpisode, hasNxt)
            btnSelectEpisode.visibility = View.VISIBLE
        } else {
            tvQueueIndicator.visibility = View.GONE
            setEpisodeButtonEnabled(btnPreviousEpisode, colPreviousEpisode, false)
            setEpisodeButtonEnabled(btnNextEpisode, colNextEpisode, false)
            btnSelectEpisode.visibility = View.GONE
        }
    }

    private fun setEpisodeButtonEnabled(button: View, column: View, enabled: Boolean) {
        button.isEnabled = enabled
        column.alpha = if (enabled) 1.0f else 0.35f
    }

    /** true 时进度/状态由电视广播驱动，控制条不再轮询 DLNA AVTransport。 */
    fun setRemoteProgressSource(enabled: Boolean) {
        val changed = remoteProgressSource != enabled
        remoteProgressSource = enabled
        btnSpeed.visibility = if (enabled) View.VISIBLE else View.GONE
        if (changed) renderDevice()
    }

    private fun renderDevice() {
        val base = deviceBaseName?.takeIf { it.isNotBlank() } ?: if (remoteProgressSource) "BigEyesTV" else "电视"
        tvDevice.text = if (AppPreferences.isShowTechInfo(context)) {
            "$base · ${if (remoteProgressSource) "直连" else "DLNA 代理"}"
        } else {
            base
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        val label = if (speed == 1.0f || speed == 2.0f) "${speed.toInt()}.0x" else "${speed}x"
        tvSpeedLabel.text = label
        val normal = kotlin.math.abs(speed - 1.0f) < 0.05f
        tvSpeedLabel.setTextColor(context.getColor(if (normal) R.color.text_primary else R.color.brand_primary))
        ivSpeedIcon.imageTintList = ColorStateList.valueOf(
            context.getColor(if (normal) R.color.icon_secondary else R.color.brand_primary)
        )
    }

    fun setPlayPauseState(playing: Boolean) {
        isPlaying = playing
        updatePlayPauseIcon()
        setStatus(if (playing) CastStatus.PLAYING else CastStatus.PAUSED)
    }

    fun setBufferingState(buffering: Boolean) {
        if (buffering) {
            setStatus(CastStatus.BUFFERING)
        } else if (status == CastStatus.BUFFERING) {
            setStatus(if (isPlaying) CastStatus.PLAYING else CastStatus.PAUSED)
        }
    }

    private fun updatePlayPauseIcon() {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val desc = if (isPlaying) "暂停" else "播放"
        for (button in arrayOf(btnPlayPause, btnPlayPauseBig)) {
            button.setImageResource(icon)
            button.contentDescription = desc
        }
    }

    fun setProgress(posMs: Long, durMs: Long) {
        if (isUserSeeking) return
        currentPosSecs = (posMs.coerceAtLeast(0L) / 1000).toInt()
        currentTotalSecs = (durMs.coerceAtLeast(0L) / 1000).toInt()
        renderProgress()
    }

    private fun renderProgress() {
        tvCurrentTime.text = formatSeconds(currentPosSecs)
        tvTotalTime.text = formatSeconds(currentTotalSecs)
        val progress = if (currentTotalSecs > 0) {
            ((currentPosSecs.toFloat() / currentTotalSecs.toFloat()) * 1000).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        seekBar.progress = progress
        pbMiniProgress.progress = progress
    }

    // ==================== Visibility ====================

    fun show(title: String? = null, deviceName: String? = null) {
        val wasHidden = container.visibility != View.VISIBLE
        container.visibility = View.VISIBLE
        if (wasHidden) {
            // Each time the bar appears it starts in the user's preferred size.
            applyExpanded(AppPreferences.isControlBarExpandedByDefault(context))
            applyStatus()
        }
        if (title != null) {
            tvTitle.text = title
        } else if (tvTitle.text.isNullOrEmpty()) {
            tvTitle.text = "正在播放..."
        }
        if (deviceName != null) {
            deviceBaseName = deviceName
        }
        if (deviceName != null || wasHidden) {
            renderDevice()
        }
        btnSpeed.visibility = if (remoteProgressSource) View.VISIBLE else View.GONE
        // Session changes re-enter show() every poll cycle; never restart an active poller.
        if (pollJob?.isActive != true) {
            startPolling()
        }
    }

    fun hide() {
        stopPolling()
        stopPulse()
        applyExpanded(false)
        container.visibility = View.GONE
    }

    val isVisible: Boolean
        get() = container.visibility == View.VISIBLE

    // ==================== DLNA polling ====================

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
        if (remoteProgressSource) return
        val service = CastingForegroundService.instance ?: return
        val target = service.dlnaManager.getSelectedDevice() ?: return
        val ctrlUrl = target.avTransportControlUrl ?: return

        try {
            val posInfo = service.dlnaManager.controller.getPositionInfo(ctrlUrl)
            val transInfo = service.dlnaManager.controller.getTransportInfo(ctrlUrl)

            if (!isUserSeeking) {
                val relTime = posInfo["rel_time"] ?: "00:00:00"
                val duration = posInfo["track_duration"] ?: "00:00:00"
                val state = transInfo["current_transport_state"]?.trim() ?: "STOPPED"

                isPlaying = state.equals("PLAYING", ignoreCase = true)
                updatePlayPauseIcon()
                onExternalPlayPauseChanged?.invoke(isPlaying)

                // Never overwrite an error the user still has to act on.
                if (status != CastStatus.ERROR) {
                    when {
                        isPlaying -> setStatus(CastStatus.PLAYING)
                        state.equals("PAUSED_PLAYBACK", ignoreCase = true) -> setStatus(CastStatus.PAUSED)
                        state.equals("TRANSITIONING", ignoreCase = true) -> setStatus(CastStatus.BUFFERING)
                    }
                }

                currentPosSecs = parseTimeToSeconds(relTime)
                currentTotalSecs = parseTimeToSeconds(duration)
                renderProgress()

                onRemoteProgress?.invoke(currentPosSecs * 1000L, currentTotalSecs * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun parseTimeToSeconds(timeStr: String): Int {
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].substringBefore('.').toInt()
                2 -> parts[0].toInt() * 60 + parts[1].substringBefore('.').toInt()
                1 -> parts[0].substringBefore('.').toInt()
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
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }
}
