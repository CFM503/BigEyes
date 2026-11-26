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

    private var hasSeenActiveTvState: Boolean = false

    /** True while BigEyesTV owns a fully resolved queue and advances episodes on its own. */
    private var tvOwnsQueue: Boolean = false

    /** One-shot UI override of the cast target; null means "use the user preference". */
    private var bigEyesTvOverride: Boolean? = null

    /** Episode index chosen from playback history when the queue was loaded. */
    private var resumeTargetIndex: Int = -1

    /** Playhead carried over from history and handed to the next dispatched playback. */
    private var pendingResumePositionMs: Long = 0L

    private var lastHistoryPersistAt = 0L
    private var resumeFromHistoryNext: Boolean = true
    private var resolvingMirrorIndex: Int = -1

    /** Episode id already auto-recovered after a BigEyesTV playback error (one attempt per episode). */
    private var tvErrorRecoveryEpisodeId: String? = null

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

        tvConnector.onTvStatusUpdateListener = { update ->
            handleTvStatusUpdate(update)
        }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        AppPreferences.setAutoPlayNext(context, enabled)
        _session.value = _session.value.copy(autoPlayNext = enabled)
        if (isPlayingOnBigEyesTv) {
            tvConnector.setAutoPlayNext(enabled)
        }
    }

    fun mergeQueueEpisodes(newEpisodes: List<Episode>) {
        queue.mergeEpisodes(newEpisodes)
        _session.value = _session.value.copy(
            episodeList = queue.items,
            currentIndex = queue.currentIndex,
            currentEpisode = queue.currentEpisode
        )
    }

    fun loadQueue(episodes: List<Episode>, initialIndex: Int = 0) {
        countdownJob?.cancel()
        completionGuard.reset()
        val (resumeIndex, resumePositionMs) = resolveResumeTarget(episodes, initialIndex)
        resumeTargetIndex = resumeIndex
        pendingResumePositionMs = resumePositionMs
        resumeFromHistoryNext = true
        resolvingMirrorIndex = -1
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
    fun buildQueueForTvHandover(): List<Episode>? = buildTvQueue()

    fun playEpisode(index: Int, useBigEyesTv: Boolean? = null) {
        countdownJob?.cancel()
        // An explicit selection overrides the saved playhead unless it is the same episode.
        if (index != resumeTargetIndex) {
            resumeTargetIndex = -1
            pendingResumePositionMs = 0L
            resumeFromHistoryNext = false
        }
        val targetEpisode = queue.jumpTo(index) ?: return
        bigEyesTvOverride = useBigEyesTv
        if (useBigEyesTv != false && isPlayingOnBigEyesTv && tvOwnsQueue) {
            switchEpisodeOnBoundTv(targetEpisode, index)
        } else {
            resolveAndStartPlayback(targetEpisode)
        }
    }

    /**
     * Non-destructive episode switch when BigEyesTV already holds the active series queue.
     *
     * The fresh URL is always pushed to the TV (updateEpisodeUrl) BEFORE asking it to play the
     * episode, so the TV never starts an episode with a stale (expired, 404) address it was
     * handed earlier.
     */
    private fun switchEpisodeOnBoundTv(episode: Episode, index: Int) {
        if (episode.hasValidPlayUrl()) {
            val item = PlaybackItem(
                episode = episode.copy(episodeIndex = index),
                playUrl = episode.playUrl!!,
                headers = episode.playHeaders,
                source = episode.sourceId,
                index = index,
                totalCount = queue.size.coerceAtLeast(1)
            )
            _session.value = _session.value.copy(
                currentEpisode = episode,
                currentIndex = index,
                playbackState = PlaybackState.Buffering(item, 0L, episode.durationMs),
                positionMs = 0L,
                durationMs = episode.durationMs
            )
            pushUrlThenPlayOnTv(item, index)
            return
        }
        // Unresolved or expired episode: resolve first, then push the fresh URL and play.
        // Do NOT call playEpisode before resolving, otherwise the TV plays its stale URL (404).
        _session.value = _session.value.copy(
            playbackState = PlaybackState.Resolving(episode),
            currentEpisode = episode,
            currentIndex = index
        )
        scope.launch {
            val result = videoResolver.resolveEpisodeStream(episode, queue.size)
            result.fold(
                onSuccess = { playbackItem ->
                    queue.updateEpisodePlayUrl(index, playbackItem.playUrl, playbackItem.headers)
                    val updatedEp = queue.items.getOrNull(index) ?: episode.copy(
                        playUrl = playbackItem.playUrl,
                        playHeaders = playbackItem.headers
                    )
                    val updatedItem = playbackItem.copy(episode = updatedEp, index = index)
                    _session.value = _session.value.copy(
                        currentEpisode = updatedEp,
                        currentIndex = index,
                        playbackState = PlaybackState.Buffering(updatedItem, 0L, updatedEp.durationMs),
                        positionMs = 0L
                    )
                    pushUrlThenPlayOnTv(updatedItem, index)
                },
                onFailure = { error ->
                    _session.value = _session.value.copy(
                        playbackState = PlaybackState.Error(
                            message = error.message ?: "剧集地址解析失败",
                            canRetry = true,
                            failedEpisode = episode
                        )
                    )
                }
            )
        }
    }

    /**
     * Sends the fresh play URL for [index] to BigEyesTV, then switches the TV to that episode.
     * Falls back to a full re-dispatch when the TV did not accept the URL update.
     */
    private fun pushUrlThenPlayOnTv(item: PlaybackItem, index: Int) {
        tvConnector.updateEpisodeUrl(index, item.playUrl, item.headers) { ok ->
            if (ok && tvConnector.playEpisode(index)) {
                Log.i(TAG, "Pushed fresh URL and switched BigEyesTV to episode $index")
            } else {
                Log.w(TAG, "BigEyesTV did not accept fresh URL for episode $index, re-dispatching")
                dispatchPlayback(item)
            }
        }
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
        val prefer = override ?: (isPlayingOnBigEyesTv || AppPreferences.isPreferBigEyesTv(context))
        return prefer && tvConnector.isDirectCastSupported()
    }

    fun playNext() {
        countdownJob?.cancel()
        clearPendingResume()
        resumeFromHistoryNext = false
        if (!queue.hasNext) {
            Log.i(TAG, "Already at the last episode in queue")
            _session.value = _session.value.copy(
                playbackState = PlaybackState.Completed(queue.currentEpisode, isSeriesFinished = true)
            )
            return
        }
        val nextEp = queue.advance() ?: return
        if (isPlayingOnBigEyesTv && tvOwnsQueue) {
            switchEpisodeOnBoundTv(nextEp, queue.currentIndex)
        } else {
            resolveAndStartPlayback(nextEp)
        }
    }

    fun playPrevious() {
        countdownJob?.cancel()
        clearPendingResume()
        resumeFromHistoryNext = false
        if (!queue.hasPrevious) {
            Log.i(TAG, "Already at the first episode in queue")
            return
        }
        val prevEp = queue.retreat() ?: return
        if (isPlayingOnBigEyesTv && tvOwnsQueue) {
            switchEpisodeOnBoundTv(prevEp, queue.currentIndex)
        } else {
            resolveAndStartPlayback(prevEp)
        }
    }

    fun retryCurrentEpisode() {
        val current = queue.currentEpisode ?: return
        resumeFromHistoryNext = false
        if (isPlayingOnBigEyesTv && tvOwnsQueue && current.hasValidPlayUrl()) {
            tvConnector.retry()
            return
        }
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

        val shouldResumeFromHistory = resumeFromHistoryNext
        resumeFromHistoryNext = true
        isPlayingOnBigEyesTv = resolveCastTarget()
        tvOwnsQueue = false

        if (isPlayingOnBigEyesTv) {
            val tvQueue = buildTvQueue()
            val tvItem = if (tvQueue != null && current.episodeIndex != queue.currentIndex) {
                item.copy(episode = current.copy(episodeIndex = queue.currentIndex))
            } else {
                item
            }
            tvConnector.startTvPlayback(
                item = tvItem,
                autoPlayNext = _session.value.autoPlayNext,
                startPositionMs = startPositionMs,
                queue = tvQueue,
                resumeFromHistory = shouldResumeFromHistory
            ) { ok ->
                if (ok) {
                    tvOwnsQueue = tvQueue != null
                    _session.value = _session.value.copy(targetDevice = "BigEyesTV")
                    Log.i(TAG, "Dispatched to BigEyesTV (tvOwnsQueue=$tvOwnsQueue)")
                } else {
                    Log.w(TAG, "BigEyesTV launch failed, falling back to DLNA/Local")
                    isPlayingOnBigEyesTv = false
                    fallbackToCastOrLocal(item, startPositionMs)
                }
            }
            return
        }

        fallbackToCastOrLocal(item, startPositionMs)
    }

    private fun clearPendingResume() {
        resumeTargetIndex = -1
        pendingResumePositionMs = 0L
    }

    /**
     * Builds the entire episode queue for BigEyesTV handover.
     * Includes all episodes metadata so TV can render full episode selector and previous/next buttons.
     * The active episode carries the resolved playUrl, while yet-unresolved episodes retain blank/cached URLs.
     *
     * Non-current episodes whose cached URL has expired (see [Episode.hasValidPlayUrl]) are handed
     * over with a blank URL instead: signed stream URLs expire and the TV would get 404. A blank URL
     * makes the phone resolve a fresh one when the TV switches to it (see [mirrorTvEpisode]).
     */
    fun buildTvQueue(): List<Episode>? {
        val items = queue.items
        if (items.size <= 1) return null
        val currentIdx = queue.currentIndex
        return items.mapIndexed { index, episode ->
            val handoverUrl = when {
                index == currentIdx && episode.playUrl.isNullOrBlank() ->
                    (_session.value.playbackState as? PlaybackState.Playing)?.item?.playUrl ?: episode.playUrl
                index == currentIdx -> episode.playUrl
                episode.hasValidPlayUrl() -> episode.playUrl
                else -> null
            }
            episode.copy(
                episodeIndex = index,
                playUrl = handoverUrl,
                playHeaders = if (handoverUrl.isNullOrBlank()) emptyMap() else episode.playHeaders
            )
        }
    }

    /**
     * Entry point used when the user casts a raw sniffed candidate directly to BigEyesTV
     * (投屏按钮直连分支). Mirrors what [dispatchPlayback] would do for an episode playback.
     */
    fun onTvDirectCastStarted(item: PlaybackItem, startPositionMs: Long? = null, queueHandedOver: Boolean = false) {
        countdownJob?.cancel()
        completionGuard.reset()
        hasSeenActiveTvState = false
        isPlayingOnBigEyesTv = true
        tvOwnsQueue = queueHandedOver

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

    fun setPlaybackSpeed(speed: Float) {
        _session.value = _session.value.copy(playbackSpeed = speed)
        if (isPlayingOnBigEyesTv) {
            tvConnector.setPlaybackSpeed(speed)
        }
    }

    /**
     * Syncs external renderer play/pause changes (e.g. TV remote pausing a DLNA stream)
     * into the phone session so togglePlayPause sends the matching command next.
     */
    fun syncExternalPlayPause(playing: Boolean) {
        if (isPlayingOnBigEyesTv) return
        val snapshot = _session.value
        val item = (snapshot.playbackState as? PlaybackState.Playing)?.item
            ?: (snapshot.playbackState as? PlaybackState.Paused)?.item
            ?: (snapshot.playbackState as? PlaybackState.Buffering)?.item
            ?: return
        val currentlyPlaying = snapshot.playbackState is PlaybackState.Playing
        if (currentlyPlaying == playing) return
        val newState = if (playing) {
            PlaybackState.Playing(item, snapshot.positionMs, snapshot.durationMs)
        } else {
            PlaybackState.Paused(item, snapshot.positionMs, snapshot.durationMs)
        }
        _session.value = snapshot.copy(playbackState = newState)
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

    private fun handleTvStatusUpdate(update: BigEyesTvConnector.TvStatusUpdate) {
        if (!isPlayingOnBigEyesTv) return

        // Sync speed & autoPlayNext reported by BigEyesTV
        val syncedSpeed = if (update.speed > 0f) update.speed else _session.value.playbackSpeed
        val syncedAutoPlay = update.autoPlayNext ?: _session.value.autoPlayNext
        if (syncedSpeed != _session.value.playbackSpeed || syncedAutoPlay != _session.value.autoPlayNext) {
            _session.value = _session.value.copy(
                playbackSpeed = syncedSpeed,
                autoPlayNext = syncedAutoPlay
            )
        }

        // Mirror the episode BigEyesTV switched to (only meaningful when the TV owns the queue)
        if (tvOwnsQueue && update.episodeIndex != queue.currentIndex && update.episodeIndex in queue.items.indices) {
            mirrorTvEpisode(update.episodeIndex, update.positionMs, update.durationMs)
        } else {
            updateProgress(update.positionMs, update.durationMs)
        }

        when (update.state) {
            PlaybackIntentContract.STATE_PLAYING -> {
                hasSeenActiveTvState = true
                tvErrorRecoveryEpisodeId = null
                applyTvPlayingState(true)
            }
            PlaybackIntentContract.STATE_PAUSED -> {
                hasSeenActiveTvState = true
                applyTvPlayingState(false)
            }
            PlaybackIntentContract.STATE_BUFFERING -> {
                hasSeenActiveTvState = true
                val snapshot = _session.value
                val item = currentItemFromState(snapshot.playbackState) ?: buildFallbackItem(queue.currentEpisode)
                if (item != null) {
                    _session.value = snapshot.copy(
                        playbackState = PlaybackState.Buffering(item, snapshot.positionMs, snapshot.durationMs)
                    )
                }
            }
            PlaybackIntentContract.STATE_COMPLETED -> {
                // When TV owns the queue, BigEyesTV autonomously advances to the next episode.
                // The phone mirrors the new episode via mirrorTvEpisode() without double-advancing.
                if (!tvOwnsQueue) {
                    onPlaybackCompleted()
                }
            }
            PlaybackIntentContract.STATE_STOPPED -> {
                if (!hasSeenActiveTvState) {
                    Log.d(TAG, "Ignoring initial transient TV STOPPED state during launch grace period")
                    return
                }
                tvOwnsQueue = false
                isPlayingOnBigEyesTv = false
                hasSeenActiveTvState = false
                resolvingMirrorIndex = -1
                tvErrorRecoveryEpisodeId = null
                countdownJob?.cancel()
                _session.value = _session.value.copy(
                    playbackState = PlaybackState.Idle,
                    positionMs = 0L
                )
            }
            PlaybackIntentContract.STATE_ERROR -> {
                if (!update.isConnectionLost && tryRecoverTvPlaybackError(update)) {
                    return
                }
                _session.value = _session.value.copy(
                    playbackState = PlaybackState.Error(
                        message = update.errorMessage ?: "大屏播放出错，请重试",
                        canRetry = true,
                        failedEpisode = queue.currentEpisode
                    )
                )
            }
            else -> {}
        }
    }

    /**
     * BigEyesTV failed to play an episode from the queue it owns. The most common cause is an
     * expired signed stream URL (upstream 404) when the TV auto-advanced on its own. Re-resolve the
     * episode on the phone (bypassing the cached URL) and push the fresh URL with the error-time
     * playhead; the TV restarts the current episode when it is in ERROR and receives a new URL.
     *
     * Only one automatic attempt per episode, so a genuinely broken source still surfaces the error.
     *
     * @return true when a recovery attempt was started (the caller should not show the error yet).
     */
    private fun tryRecoverTvPlaybackError(update: BigEyesTvConnector.TvStatusUpdate): Boolean {
        if (!tvOwnsQueue) return false
        val index = update.episodeIndex
        val episode = queue.items.getOrNull(index) ?: return false
        if (episode.pageUrl.isNullOrBlank()) return false
        // A recovery is already running: keep waiting instead of surfacing the repeated ERROR polls.
        if (resolvingMirrorIndex == index) return true
        if (tvErrorRecoveryEpisodeId == episode.id) return false

        tvErrorRecoveryEpisodeId = episode.id
        resolvingMirrorIndex = index
        val resumeAtMs = update.positionMs.coerceAtLeast(0L)
        Log.w(TAG, "BigEyesTV error on episode $index (${update.errorMessage}), re-resolving fresh URL")

        _session.value = _session.value.copy(
            playbackState = PlaybackState.Resolving(episode),
            currentEpisode = episode,
            currentIndex = index
        )

        scope.launch {
            fun finishRecovery() {
                if (resolvingMirrorIndex == index) {
                    resolvingMirrorIndex = -1
                }
            }
            try {
                // Drop the cached URL so the resolver sniffs a fresh one instead of reusing it.
                val result = videoResolver.resolveEpisodeStream(episode.copy(playUrl = null), queue.size)
                result.fold(
                    onSuccess = { resolvedItem ->
                        queue.updateEpisodePlayUrl(index, resolvedItem.playUrl, resolvedItem.headers)
                        tvConnector.updateEpisodeUrl(
                            episodeIndex = index,
                            playUrl = resolvedItem.playUrl,
                            headers = resolvedItem.headers,
                            positionMs = resumeAtMs
                        ) { ok ->
                            // Cleared only after the TV answered, so stale ERROR polls in between
                            // do not flash the error state on the phone.
                            finishRecovery()
                            if (!ok) {
                                showTvRecoveryFailed(episode, "大屏播放出错，请重试")
                            } else {
                                Log.i(TAG, "Pushed re-resolved URL to BigEyesTV for episode $index at ${resumeAtMs}ms")
                            }
                        }
                    },
                    onFailure = { error ->
                        finishRecovery()
                        Log.w(TAG, "Re-resolve after TV error failed for episode $index: ${error.message}")
                        showTvRecoveryFailed(episode, error.message ?: "大屏播放出错，请重试")
                    }
                )
            } catch (e: CancellationException) {
                finishRecovery()
                throw e
            }
        }
        return true
    }

    private fun showTvRecoveryFailed(episode: Episode, message: String) {
        _session.value = _session.value.copy(
            playbackState = PlaybackState.Error(
                message = message,
                canRetry = true,
                failedEpisode = episode
            )
        )
    }

    private fun currentItemFromState(state: PlaybackState): PlaybackItem? = when (state) {
        is PlaybackState.Playing -> state.item
        is PlaybackState.Paused -> state.item
        is PlaybackState.Buffering -> state.item
        else -> null
    }

    private fun buildFallbackItem(episode: Episode?): PlaybackItem? {
        if (episode == null) return null
        return PlaybackItem(
            episode = episode,
            playUrl = episode.playUrl ?: "",
            headers = episode.playHeaders,
            source = episode.sourceId,
            index = queue.currentIndex,
            totalCount = queue.size.coerceAtLeast(1)
        )
    }

    /** Keep the control bar play/pause state in sync with what the TV is actually doing. */
    private fun applyTvPlayingState(playing: Boolean) {
        val snapshot = _session.value
        val item = currentItemFromState(snapshot.playbackState) ?: buildFallbackItem(queue.currentEpisode) ?: return

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
        val item = episode.playUrl?.takeIf { it.isNotBlank() }?.let { url ->
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
            item == null -> PlaybackState.Resolving(episode)
            snapshot.playbackState is PlaybackState.Playing ->
                PlaybackState.Playing(item, posMs, durMs)
            snapshot.playbackState is PlaybackState.Paused ->
                PlaybackState.Paused(item, posMs, durMs)
            else -> PlaybackState.Buffering(item, posMs, durMs)
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

        // If target episode does not have a resolved stream URL yet, resolve and push non-destructively
        if (item == null || !episode.hasValidPlayUrl()) {
            if (resolvingMirrorIndex == index) return
            resolvingMirrorIndex = index
            Log.i(TAG, "Target episode $index has no resolved playUrl, resolving for updateEpisodeUrl...")
            scope.launch {
                try {
                    val result = videoResolver.resolveEpisodeStream(episode, queue.size)
                    result.fold(
                        onSuccess = { resolvedItem ->
                            queue.updateEpisodePlayUrl(index, resolvedItem.playUrl, resolvedItem.headers)
                            tvConnector.updateEpisodeUrl(
                                episodeIndex = index,
                                playUrl = resolvedItem.playUrl,
                                headers = resolvedItem.headers
                            )
                        },
                        onFailure = { error ->
                            Log.w(TAG, "Mirror resolution failed for episode $index: ${error.message}")
                            _session.value = _session.value.copy(
                                playbackState = PlaybackState.Error(
                                    message = error.message ?: "下一集地址解析失败",
                                    canRetry = true,
                                    failedEpisode = episode
                                )
                            )
                        }
                    )
                } finally {
                    if (resolvingMirrorIndex == index) {
                        resolvingMirrorIndex = -1
                    }
                }
            }
        }
    }

    fun release() {
        countdownJob?.cancel()
        tvConnector.unregisterStatusReceiver()
    }
}
