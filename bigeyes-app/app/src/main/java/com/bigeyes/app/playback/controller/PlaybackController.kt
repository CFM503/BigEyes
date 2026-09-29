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
    var onCastPlaybackHandler: ((PlaybackItem, startPositionMs: Long, onResult: (Boolean, String?) -> Unit) -> Unit)? = null,
    var onLocalPlaybackHandler: ((PlaybackItem) -> Unit)? = null,
    /** Wired by the UI; used whenever the active renderer is not BigEyesTV (DLNA cast). */
    var castTransportControl: CastTransportControl? = null
) {
    companion object {
        private const val TAG = "PlaybackController"
        const val COUNTDOWN_SECONDS = 10

        /** Saved positions closer than this to either end of an episode are ignored. */
        private const val RESUME_MIN_POSITION_MS = 5_000L
        private const val RESUME_TAIL_MS = 10_000L

        /** How often real playhead progress is written back to playback history. */
        private const val HISTORY_SAVE_INTERVAL_MS = 10_000L
    }

    val queue = EpisodeQueue()
    val completionGuard = PlaybackCompletionGuard()

    private val _session = MutableStateFlow(PlaybackSession())
    val session: StateFlow<PlaybackSession> = _session.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(0)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private var countdownJob: Job? = null
    private var isPlayingOnBigEyesTv: Boolean = false

    /** True while BigEyesTV owns a fully resolved queue and advances episodes on its own. */
    private var tvOwnsQueue: Boolean = false

    /** One-shot UI override of the cast target; null means "use the user preference". */
    private var bigEyesTvOverride: Boolean? = null

    /** Episode index chosen from playback history when the queue was loaded. */
    private var resumeTargetIndex: Int = -1

    /** Playhead carried over from history and handed to the next dispatched playback. */
    private var pendingResumePositionMs: Long = 0L

    private var lastHistoryPersistAt = 0L

    /** Read-only view of the active cast target, used by the UI to mirror BigEyesTV status. */
    val isCastingToBigEyesTv: Boolean
        get() = isPlayingOnBigEyesTv

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
        val (resumeIndex, resumePositionMs) = resolveResumeTarget(episodes, initialIndex)
        resumeTargetIndex = resumeIndex
        pendingResumePositionMs = resumePositionMs
        queue.setQueue(episodes, resumeIndex)
        val current = queue.currentEpisode
        _session.value = _session.value.copy(
            seriesId = current?.seriesId ?: "",
            seriesTitle = current?.seriesTitle ?: "",
            currentIndex = queue.currentIndex,
            episodeList = queue.items,
            currentEpisode = current,
            playbackState = PlaybackState.Idle,
            positionMs = resumePositionMs
        )
        if (resumePositionMs > 0L) {
            Log.i(TAG, "Resuming ${current?.displayTitle} from ${resumePositionMs}ms (history)")
        }
    }

    /**
     * Picks up where the viewer stopped: returns the episode index and playhead to restore.
     * A page that already points at a specific episode always wins over the saved index.
     */
    private fun resolveResumeTarget(episodes: List<Episode>, requestedIndex: Int): Pair<Int, Long> {
        if (episodes.isEmpty()) return requestedIndex to 0L
        val history = PlaybackHistoryManager.getHistory(context, episodes.first().seriesId)
            ?: return requestedIndex to 0L

        val savedIndex = history.episodeIndex
        if (savedIndex !in episodes.indices) return requestedIndex to 0L
        if (requestedIndex != 0 && requestedIndex != savedIndex) return requestedIndex to 0L

        val positionMs = history.positionMs
        if (positionMs < RESUME_MIN_POSITION_MS) return savedIndex to 0L
        if (history.durationMs > 0 && positionMs >= history.durationMs - RESUME_TAIL_MS) {
            return savedIndex to 0L
        }
        return savedIndex to positionMs
    }

    private fun consumeResumePosition(): Long {
        val positionMs = pendingResumePositionMs
        pendingResumePositionMs = 0L
        resumeTargetIndex = -1
        return positionMs
    }

    /**
     * Read-only view of the pending resume playhead. Callers that may still fall back to a
     * different renderer peek first and let [onTvDirectCastStarted] / [onDlnaCastStarted]
     * consume it once a target actually accepted the stream.
     */
    fun peekResumePositionMs(): Long = pendingResumePositionMs

    /** Queue handed over to BigEyesTV only when every episode still carries a fresh URL. */
    fun buildQueueForTvHandover(): List<Episode>? = buildFullyResolvedQueue()

    fun playEpisode(index: Int, useBigEyesTv: Boolean? = null) {
        countdownJob?.cancel()
        // An explicit selection overrides the saved playhead unless it is the same episode.
        if (index != resumeTargetIndex) {
            resumeTargetIndex = -1
            pendingResumePositionMs = 0L
        }
        val targetEpisode = queue.jumpTo(index) ?: return
        bigEyesTvOverride = useBigEyesTv
        resolveAndStartPlayback(targetEpisode)
    }

    /**
     * Whether casts should target the BigEyesTV receiver app directly (Intent based, with
     * anti-hotlink header passthrough and status feedback) instead of the DLNA proxy pipeline.
     */
    fun shouldCastToBigEyesTv(): Boolean {
        return AppPreferences.isPreferBigEyesTv(context) && tvConnector.isDirectCastSupported()
    }

    private fun resolveCastTarget(): Boolean {
        val override = bigEyesTvOverride
        bigEyesTvOverride = null
        val prefer = override ?: AppPreferences.isPreferBigEyesTv(context)
        return prefer && tvConnector.isDirectCastSupported()
    }

    fun playNext() {
        countdownJob?.cancel()
        clearPendingResume()
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
        clearPendingResume()
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
        val startPositionMs = consumeResumePosition()

        _session.value = _session.value.copy(
            currentEpisode = current,
            playbackState = PlaybackState.Playing(item, startPositionMs, current.durationMs),
            positionMs = startPositionMs,
            durationMs = current.durationMs,
            targetDevice = null
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
                positionMs = startPositionMs,
                durationMs = current.durationMs
            )
        )

        isPlayingOnBigEyesTv = resolveCastTarget()
        tvOwnsQueue = false

        if (isPlayingOnBigEyesTv) {
            val tvQueue = buildFullyResolvedQueue()
            val tvItem = if (tvQueue != null && current.episodeIndex != queue.currentIndex) {
                item.copy(episode = current.copy(episodeIndex = queue.currentIndex))
            } else {
                item
            }
            val ok = tvConnector.startTvPlayback(
                item = tvItem,
                autoPlayNext = _session.value.autoPlayNext,
                startPositionMs = startPositionMs,
                queue = tvQueue
            )
            if (ok) {
                tvOwnsQueue = tvQueue != null
                _session.value = _session.value.copy(targetDevice = "BigEyesTV")
                Log.i(TAG, "Dispatched to BigEyesTV (tvOwnsQueue=$tvOwnsQueue)")
                return
            }
            Log.w(TAG, "BigEyesTV launch failed, falling back to DLNA/Local")
            isPlayingOnBigEyesTv = false
        }

        fallbackToCastOrLocal(item, startPositionMs)
    }

    private fun clearPendingResume() {
        resumeTargetIndex = -1
        pendingResumePositionMs = 0L
    }

    /**
     * Returns the whole queue only when every episode carries a still fresh resolved URL,
     * so BigEyesTV can auto-advance without asking the phone to re-resolve expired streams.
     * Episode indices are normalized to queue positions (that is what the TV reports back).
     */
    private fun buildFullyResolvedQueue(): List<Episode>? {
        val items = queue.items
        if (items.size <= 1) return null
        if (!items.all { it.hasValidPlayUrl() }) return null
        return items.mapIndexed { index, episode -> episode.copy(episodeIndex = index) }
    }

    /**
     * Entry point used when the user casts a raw sniffed candidate directly to BigEyesTV
     * (投屏按钮直连分支). Mirrors what [dispatchPlayback] would do for an episode playback.
     */
    fun onTvDirectCastStarted(item: PlaybackItem, startPositionMs: Long? = null) {
        countdownJob?.cancel()
        completionGuard.reset()
        isPlayingOnBigEyesTv = true
        tvOwnsQueue = false

        val positionMs = startPositionMs ?: peekResumePositionMs()
        consumeResumePosition()

        val total = queue.size.coerceAtLeast(1)
        _session.value = _session.value.copy(
            currentEpisode = item.episode,
            currentIndex = queue.currentIndex,
            playbackState = PlaybackState.Playing(item, positionMs, item.episode.durationMs),
            positionMs = positionMs,
            durationMs = item.episode.durationMs,
            targetDevice = "BigEyesTV"
        )
        Log.i(TAG, "Direct BigEyesTV cast started for ${item.episode.displayTitle} (queue=$total)")
    }

    /**
     * Entry point used when a local-proxy DLNA cast actually reached a renderer
     * (投屏按钮 DLNA 分支). Without this the session never entered [PlaybackState.Playing],
     * so every transport button on the control bar was a no-op on a DLNA television.
     */
    fun onDlnaCastStarted(item: PlaybackItem, deviceName: String?, startPositionMs: Long = 0L) {
        countdownJob?.cancel()
        completionGuard.reset()
        isPlayingOnBigEyesTv = false
        tvOwnsQueue = false
        consumeResumePosition()

        _session.value = _session.value.copy(
            currentEpisode = item.episode,
            currentIndex = queue.currentIndex,
            playbackState = PlaybackState.Playing(item, startPositionMs, item.episode.durationMs),
            positionMs = startPositionMs,
            durationMs = item.episode.durationMs,
            targetDevice = deviceName ?: "电视"
        )
        Log.i(TAG, "DLNA cast started for ${item.episode.displayTitle} on $deviceName")
    }

    /** Convenience overload for the cast button, where only a queue episode is available. */
    fun onDlnaCastStarted(episode: Episode, deviceName: String?, startPositionMs: Long = 0L) {
        onDlnaCastStarted(
            PlaybackItem(
                episode = episode,
                playUrl = episode.playUrl ?: "",
                headers = episode.playHeaders,
                index = queue.currentIndex,
                totalCount = queue.size.coerceAtLeast(1)
            ),
            deviceName,
            startPositionMs
        )
    }

    private fun fallbackToCastOrLocal(item: PlaybackItem, startPositionMs: Long) {
        val castHandler = onCastPlaybackHandler
        if (castHandler != null) {
            castHandler(item, startPositionMs) { success, devName ->
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
        when (_session.value.playbackState) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> resume()
            is PlaybackState.CountdownNext -> cancelNextCountdown()
            else -> {}
        }
    }

    fun pause() {
        if (isPlayingOnBigEyesTv) {
            tvConnector.pause()
        } else {
            castTransportControl?.pause()
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
        } else {
            castTransportControl?.resume()
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
        } else {
            castTransportControl?.seekTo(positionMs)
        }
        _session.value = _session.value.copy(positionMs = positionMs)
    }

    fun stop() {
        countdownJob?.cancel()
        if (isPlayingOnBigEyesTv) {
            tvConnector.stop()
        } else {
            castTransportControl?.stop()
        }
        isPlayingOnBigEyesTv = false
        tvOwnsQueue = false
        _session.value = _session.value.copy(
            playbackState = PlaybackState.Idle,
            positionMs = 0L
        )
    }

    fun updateProgress(posMs: Long, durMs: Long) {
        _session.value = _session.value.copy(positionMs = posMs, durationMs = durMs)
        persistProgress(posMs, durMs)
    }

    /**
     * Throttled write-back of the live playhead so playback history is a usable
     * resume point instead of only ever holding 0 or the full duration.
     */
    private fun persistProgress(posMs: Long, durMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastHistoryPersistAt < HISTORY_SAVE_INTERVAL_MS) return
        val episode = queue.currentEpisode ?: return
        if (episode.seriesId.isBlank()) return

        val state = _session.value.playbackState
        if (state !is PlaybackState.Playing && state !is PlaybackState.Paused) return

        lastHistoryPersistAt = now
        PlaybackHistoryManager.saveHistory(
            context,
            PlaybackHistoryItem(
                seriesId = episode.seriesId,
                seriesTitle = episode.seriesTitle,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                episodeIndex = episode.episodeIndex,
                episodeTitle = episode.displayTitle,
                pageUrl = episode.pageUrl,
                positionMs = posMs.coerceAtLeast(0L),
                durationMs = durMs.coerceAtLeast(0L)
            )
        )
    }

    private fun handleTvStatusUpdate(state: String, epIndex: Int, posMs: Long, durMs: Long) {
        // Mirror the episode BigEyesTV switched to (only meaningful when the TV owns the queue)
        if (tvOwnsQueue && epIndex != queue.currentIndex && epIndex in queue.items.indices) {
            mirrorTvEpisode(epIndex, posMs, durMs)
        } else {
            updateProgress(posMs, durMs)
        }

        when (state) {
            PlaybackIntentContract.STATE_PLAYING -> applyTvPlayingState(true)
            PlaybackIntentContract.STATE_PAUSED -> applyTvPlayingState(false)
            PlaybackIntentContract.STATE_COMPLETED -> onPlaybackCompleted()
            PlaybackIntentContract.STATE_STOPPED -> {
                tvOwnsQueue = false
                isPlayingOnBigEyesTv = false
                countdownJob?.cancel()
                _session.value = _session.value.copy(
                    playbackState = PlaybackState.Idle,
                    positionMs = 0L
                )
            }
            PlaybackIntentContract.STATE_ERROR -> {
                _session.value = _session.value.copy(
                    playbackState = PlaybackState.Error(
                        message = "大屏播放出错，请重试",
                        canRetry = true,
                        failedEpisode = queue.currentEpisode
                    )
                )
            }
            else -> {}
        }
    }

    /** Keep the control bar play/pause state in sync with what the TV is actually doing. */
    private fun applyTvPlayingState(playing: Boolean) {
        val snapshot = _session.value
        val item = (snapshot.playbackState as? PlaybackState.Playing)?.item
            ?: (snapshot.playbackState as? PlaybackState.Paused)?.item
            ?: return

        val newState = if (playing) {
            PlaybackState.Playing(item, snapshot.positionMs, snapshot.durationMs)
        } else {
            PlaybackState.Paused(item, snapshot.positionMs, snapshot.durationMs)
        }
        _session.value = snapshot.copy(playbackState = newState)
    }

    /** Follow BigEyesTV when it auto-advances inside a fully handed-over queue. */
    private fun mirrorTvEpisode(index: Int, posMs: Long, durMs: Long) {
        val episode = queue.jumpTo(index) ?: return
        val snapshot = _session.value
        val item = episode.playUrl?.let { url ->
            PlaybackItem(
                episode = episode,
                playUrl = url,
                headers = episode.playHeaders,
                source = episode.sourceId,
                index = index,
                totalCount = queue.size
            )
        }

        val state = when {
            item == null -> snapshot.playbackState
            snapshot.playbackState is PlaybackState.Playing ->
                PlaybackState.Playing(item, posMs, durMs)
            snapshot.playbackState is PlaybackState.Paused ->
                PlaybackState.Paused(item, posMs, durMs)
            else -> snapshot.playbackState
        }

        _session.value = snapshot.copy(
            seriesId = episode.seriesId,
            seriesTitle = episode.seriesTitle,
            currentIndex = index,
            currentEpisode = episode,
            playbackState = state,
            positionMs = posMs,
            durationMs = durMs
        )
        Log.i(TAG, "Mirrored BigEyesTV episode switch to index $index (${episode.displayTitle})")
    }

    fun release() {
        countdownJob?.cancel()
        tvConnector.unregisterStatusReceiver()
    }
}
