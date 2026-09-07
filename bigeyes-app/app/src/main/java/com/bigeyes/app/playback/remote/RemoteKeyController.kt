package com.bigeyes.app.playback.remote

import android.view.KeyEvent
import com.bigeyes.app.model.playback.PlaybackState
import com.bigeyes.app.playback.controller.PlaybackController

interface RemoteUiCallbacks {
    fun showControlBar()
    fun hideControlBar()
    fun showEpisodeSelector()
    fun isControlBarVisible(): Boolean
    fun isEpisodeSelectorVisible(): Boolean
}

class RemoteKeyController(
    private val playbackController: PlaybackController,
    private val uiCallbacks: RemoteUiCallbacks
) {
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        val state = playbackController.session.value.playbackState
        val isPlaybackActive = state is PlaybackState.Playing ||
                state is PlaybackState.Paused ||
                state is PlaybackState.Buffering ||
                state is PlaybackState.CountdownNext

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playbackController.playNext()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playbackController.playPrevious()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                playbackController.togglePlayPause()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playbackController.resume()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playbackController.pause()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_STOP -> {
                playbackController.stop()
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (state is PlaybackState.CountdownNext) {
                    playbackController.confirmNextCountdown()
                    return true
                }
                if (isPlaybackActive) {
                    playbackController.togglePlayPause()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (isPlaybackActive) {
                    playbackController.seekRelative(-15)
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isPlaybackActive) {
                    playbackController.seekRelative(15)
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isPlaybackActive) {
                    uiCallbacks.showControlBar()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isPlaybackActive) {
                    uiCallbacks.showEpisodeSelector()
                    return true
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                if (state is PlaybackState.CountdownNext) {
                    playbackController.cancelNextCountdown()
                    return true
                }
                if (uiCallbacks.isEpisodeSelectorVisible()) {
                    return false
                }
                if (uiCallbacks.isControlBarVisible()) {
                    uiCallbacks.hideControlBar()
                    return true
                }
            }
        }

        return false
    }
}
