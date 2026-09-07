package com.bigeyes.app.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.model.playback.Episode
import com.bigeyes.app.playback.controller.PlaybackController

class NextEpisodeCountdownView(
    private val container: View,
    private val controller: PlaybackController
) {
    private val tvTitle: TextView = container.findViewById(R.id.tv_next_episode_title)
    private val tvCountdown: TextView = container.findViewById(R.id.tv_countdown_seconds)
    private val btnCancel: Button = container.findViewById(R.id.btn_cancel_countdown)
    private val btnPlayNow: Button = container.findViewById(R.id.btn_play_now)

    init {
        btnCancel.setOnClickListener {
            controller.cancelNextCountdown()
            hide()
        }
        btnPlayNow.setOnClickListener {
            controller.confirmNextCountdown()
            hide()
        }
    }

    fun show(nextEpisode: Episode, remainingSecs: Int) {
        container.visibility = View.VISIBLE
        tvTitle.text = nextEpisode.displayTitle
        tvCountdown.text = "${remainingSecs} 秒后自动播放"
    }

    fun updateSeconds(remainingSecs: Int) {
        tvCountdown.text = "${remainingSecs} 秒后自动播放"
    }

    fun hide() {
        container.visibility = View.GONE
    }

    val isVisible: Boolean
        get() = container.visibility == View.VISIBLE
}
