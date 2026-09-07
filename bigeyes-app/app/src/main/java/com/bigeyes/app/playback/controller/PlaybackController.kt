package com.bigeyes.app.playback.controller

import android.content.Context
import android.util.Log
import com.bigeyes.app.model.playback.*
import com.bigeyes.app.playback.contract.PlaybackIntentContract
import com.bigeyes.app.playback.guard.PlaybackCompletionGuard
import com.bigeyes.app.playback.history.PlaybackHistoryItem
import com.bigeyes.app.playback.history.PlaybackHistoryManager
import com.bigeyes.app.playback.queue.EpisodeQueue
import com.bigeyes.app.playback.resolver.VideoResolver
import com.bigeyes.app.playback.tv.BigEyesTvConnector
import com.bigeyes.app.utils.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaybackController(
    private val context: Context,
    private val videoResolver: VideoResolver,
    private val tvConnector: BigEyesTvConnector,
    private val scope: CoroutineScope,
    var onCastPlaybackHandler: ((PlaybackItem, onResult: (Boolean, String?) -> Unit) -> Unit)? = null,
    var onLocalPlaybackHandler: ((PlaybackItem) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PlaybackController"
        const val COUNTDOWN_SECONDS = 10
    }

    val queue = EpisodeQueue()
    val completionGuard = PlaybackCompletionGuard()

    private val _session = MutableStateFlow(PlaybackSession())
    val session: StateFlow<PlaybackSession> = _session.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(0)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private var countdownJob: Job? = null
    private var isPlayingOnBigEyesTv: Boolean = false

    init {
        val defaultAutoPlay = AppPreferences.isAutoPlayNext(context)
        _session.value = _session.value.copy(autoPlayNext = defaultAutoPlay)

        queue.addListener { q ->
            _session.value = _session.value.copy(
                currentIndex = q.currentIndex,
                episodeList = q.items,
                currentEpisode = q.currentEpisode
            )
        }

        tvConnector.onTvStatusUpdateListener = { state, _, epIndex, posMs, durMs ->
            handleTvStatusUpdate(state, epIndex, posMs, durMs)
        }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        AppPreferences.setAutoPlayNext(context, enabled)
        _session.value = _session.value.copy(autoPlayNext = enabled)
    }

    fun loadQueue(episodes: List<Episode>, initialIndex: Int = 0) {
        countdownJob?.cancel()
        completionGuard.reset()
        queue.setQueue(episodes, initialIndex)
        val current = queue.currentEpisode
        _session.value = _session.value.copy(
            seriesId = current?.seriesId ?: "",
            seriesTitle = current?.seriesTitle ?: "",
            currentIndex = queue.currentIndex,
            episodeList = queue.items,
            currentEpisode = current,
            playbackState = PlaybackState.Idle
        )
    }

    fun playEpisode(index: Int, useBigEyesTv: Boolean = false) {
        countdownJob?.cancel()
        val targetEpisode = queue.jumpTo(index) ?: return
        isPlayingOnBigEyesTv = useBigEyesTv && tvConnector.isTvAppInstalled()
        resolveAndStartPlayback(targetEpisode)
    }

    fun playNext() {
        countdownJob?.cancel()
        if (!queue.hasNext) {
            Log.i(TAG, "Already at the last episode in queue")
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Completed(queue.currentEpisode, isSeriesFinished = true)
            )
            return
        }
        val nextEp = queue.advance() ?: return
        resolveAndStartPlayback(nextEp)
    }

    fun playPrevious() {
        countdownJob?.cancel()
        if (!queue.hasPrevious) {
            Log.i(TAG, "Already at the first episode in queue")
            return
        }
        val prevEp = queue.retreat() ?: return
        resolveAndStartPlayback(prevEp)
    }

    fun retryCurrentEpisode() {
        val current = queue.currentEpisode ?: return
        resolveAndStartPlayback(current)
    }

    private fun resolveAndStartPlayback(episode: Episode) {
        countdownJob?.cancel()
        _session.value = _session.value.copy(
            playbackState = PlaybackState.Resolving(episode),
            currentEpisode = episode,
            currentIndex = queue.currentIndex
        )

        scope.launch {
            Log.i(TAG, "Resolving stream for episode: ${episode.displayTitle} (Index ${episode.episodeIndex})")
            val result = videoResolver.resolveEpisodeStream(episode, queue.size)

            result.fold(
                onSuccess = { playbackItem ->
                    queue.updateCurrentPlayUrl(playbackItem.playUrl, playbackItem.headers)
                    dispatchPlayback(playbackItem)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed resolving episode stream: ${error.message}")
                    _session.value = _session.value.copy(
                        playbackState = PlaybackState.Error(
                            message = error.message ?: "下一集加载失败",
                            canRetry = true,
                            failedEpisode = episode
                        )
                    )
                }
            )
        }
    }

    private fun dispatchPlayback(item: PlaybackItem) {
        val current = item.episode
        _session.value = _session.value.copy(
            currentEpisode = current,
            playbackState = PlaybackState.Playing(item, 0L, current.durationMs)
        )

        // Save entry into history
        PlaybackHistoryManager.saveHistory(
            context,
            PlaybackHistoryItem(
                seriesId = current.seriesId,
                seriesTitle = current.seriesTitle,
                seasonNumber = current.seasonNumber,
                episodeNumber = current.episodeNumber,
                episodeIndex = current.episodeIndex,
                episodeTitle = current.displayTitle,
                pageUrl = current.pageUrl,
                positionMs = 0L,
                durationMs = current.durationMs
            )
        )

        if (isPlayingOnBigEyesTv && tvConnector.isTvAppInstalled()) {
            val ok = tvConnector.startTvPlayback(item, _session.value.autoPlayNext)
            if (!ok) {
                Log.w(TAG, "BigEyesTV launch failed, falling back to DLNA/Local")
                fallbackToCastOrLocal(item)
            }
        } else {
            fallbackToCastOrLocal(item)
        }
    }

    private fun fallbackToCastOrLocal(item: PlaybackItem) {
        val castHandler = onCastPlaybackHandler
        if (castHandler != null) {
            castHandler(item) { success, devName ->
                if (!success) {
                    onLocalPlaybackHandler?.invoke(item)
                } else {
                    _session.value = _session.value.copy(targetDevice = devName)
                }
            }
        } else {
            onLocalPlaybackHandler?.invoke(item)
        }
    }

    /**
     * Centralized video completion event handler.
     * Guaranteed to execute at most once per completed episode via PlaybackCompletionGuard.
     */
    fun onPlaybackCompleted() {
        val current = queue.currentEpisode ?: return
        if (!completionGuard.checkAndAcquire(current.id)) {
            return
        }

        Log.i(TAG, "onPlaybackCompleted for episode: ${current.displayTitle}")

        // Mark 100% completed in history
        PlaybackHistoryManager.saveHistory(
            context,
            PlaybackHistoryItem(
                seriesId = current.seriesId,
                seriesTitle = current.seriesTitle,
                seasonNumber = current.seasonNumber,
                episodeNumber = current.episodeNumber,
                episodeIndex = current.episodeIndex,
                episodeTitle = current.displayTitle,
                pageUrl = current.pageUrl,
                positionMs = current.durationMs,
                durationMs = current.durationMs
            )
        )

        if (!queue.hasNext) {
            Log.i(TAG, "Last episode of series reached, stopping playback.")
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Completed(current, isSeriesFinished = true)
            )
            return
        }

        val nextEp = queue.nextEpisode
        if (nextEp == null) {
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Completed(current, isSeriesFinished = true)
            )
            return
        }

        if (_session.value.autoPlayNext) {
            startCountdown(nextEp)
        } else {
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Completed(current, isSeriesFinished = false)
            )
        }
    }

    private fun startCountdown(nextEpisode: Episode) {
        countdownJob?.cancel()
        _session.value = _session.value.copy(
            playbackState = PlaybackState.CountdownNext(nextEpisode, COUNTDOWN_SECONDS)
        )
        _countdownSeconds.value = COUNTDOWN_SECONDS

        countdownJob = scope.launch {
            for (sec in COUNTDOWN_SECONDS downTo 1) {
                _countdownSeconds.value = sec
                _session.value = _session.value.copy(
                    playbackState = PlaybackState.CountdownNext(nextEpisode, sec)
                )
                delay(1000L)
            }
            _countdownSeconds.value = 0
            Log.i(TAG, "Countdown completed, automatically starting next episode")
            playNext()
        }
    }

    fun cancelNextCountdown() {
        if (countdownJob?.isActive == true) {
            countdownJob?.cancel()
            countdownJob = null
            _countdownSeconds.value = 0
            Log.i(TAG, "Auto play next countdown canceled by user")
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Completed(queue.currentEpisode, isSeriesFinished = false)
            )
        }
    }

    fun confirmNextCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = 0
        playNext()
    }

    fun togglePlayPause() {
        when (val state = _session.value.playbackState) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> resume()
            is PlaybackState.CountdownNext -> cancelNextCountdown()
            else -> {}
        }
    }

    fun pause() {
        if (isPlayingOnBigEyesTv) {
            tvConnector.pause()
        }
        val currentItem = (_session.value.playbackState as? PlaybackState.Playing)?.item
        if (currentItem != null) {
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Paused(currentItem, _session.value.positionMs, _session.value.durationMs)
            )
        }
    }

    fun resume() {
        if (isPlayingOnBigEyesTv) {
            tvConnector.resume()
        }
        val pausedItem = (_session.value.playbackState as? PlaybackState.Paused)?.item
        if (pausedItem != null) {
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Playing(pausedItem, _session.value.positionMs, _session.value.durationMs)
            )
        }
    }

    fun seekRelative(secondsDelta: Int) {
        val newPos = (_session.value.positionMs + secondsDelta * 1000L).coerceAtLeast(0L)
        seekTo(newPos)
    }

    fun seekTo(positionMs: Long) {
        if (isPlayingOnBigEyesTv) {
            tvConnector.seekTo(positionMs)
        }
        _session.value = _session.value.copy(positionMs = positionMs)
    }

    fun stop() {
        countdownJob?.cancel()
        if (isPlayingOnBigEyesTv) {
            tvConnector.stop()
        }
        _session.value = _session.value.copy(
            playbackState = PlaybackState.Idle,
            positionMs = 0L
        )
    }

    fun updateProgress(posMs: Long, durMs: Long) {
        _session.value = _session.value.copy(positionMs = posMs, durationMs = durMs)
    }

    private fun handleTvStatusUpdate(state: String, epIndex: Int, posMs: Long, durMs: Long) {
        updateProgress(posMs, durMs)
        when (state) {
            PlaybackIntentContract.STATE_COMPLETED -> onPlaybackCompleted()
            PlaybackIntentContract.STATE_STOPPED -> {
                _session.value = _session.value.copy(playbackState = PlaybackState.Idle)
            }
        }
    }

    fun release() {
        countdownJob?.cancel()
        tvConnector.unregisterStatusReceiver()
    }
}
