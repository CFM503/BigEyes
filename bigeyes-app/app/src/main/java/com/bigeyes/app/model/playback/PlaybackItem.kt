package com.bigeyes.app.model.playback

import java.io.Serializable

data class PlaybackItem(
    val episode: Episode,
    val playUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val source: String? = null,
    val index: Int = episode.episodeIndex,
    val totalCount: Int = 1
) : Serializable {
    val displayTitle: String
        get() = episode.displayTitle

    val seriesTitle: String
        get() = episode.seriesTitle
}
