package com.bigeyes.app.model.playback

data class PlaybackSession(
    val seriesId: String = "",
    val seriesTitle: String = "",
    val currentIndex: Int = 0,
    val episodeList: List<Episode> = emptyList(),
    val currentEpisode: Episode? = null,
    val autoPlayNext: Boolean = true,
    val playbackState: PlaybackState = PlaybackState.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val targetDevice: String? = null
)
