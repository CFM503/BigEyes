package com.bigeyes.app.model.playback

sealed class PlaybackState {
    object Idle : PlaybackState()
    data class Resolving(val episode: Episode) : PlaybackState()
    data class Playing(
        val item: PlaybackItem,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    ) : PlaybackState()
    data class Paused(
        val item: PlaybackItem,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    ) : PlaybackState()
    data class Buffering(
        val item: PlaybackItem,
        val positionMs: Long = 0L
    ) : PlaybackState()
    data class CountdownNext(
        val nextEpisode: Episode,
        val remainingSeconds: Int
    ) : PlaybackState()
    data class Completed(
        val lastEpisode: Episode?,
        val isSeriesFinished: Boolean
    ) : PlaybackState()
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val failedEpisode: Episode? = null
    ) : PlaybackState()
}
