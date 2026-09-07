package com.bigeyes.app.model.playback

import java.io.Serializable

data class Episode(
    val id: String = java.util.UUID.randomUUID().toString(),
    val seriesId: String,
    val seriesTitle: String,
    val seasonNumber: Int = 1,
    val episodeNumber: Int,
    val episodeTitle: String,
    val episodeIndex: Int,
    val sourceId: String? = null,
    val pageUrl: String? = null,
    val playUrl: String? = null,
    val playHeaders: Map<String, String> = emptyMap(),
    val durationMs: Long = 0L,
    val thumbnail: String? = null,
    val extraMetadata: Map<String, String> = emptyMap(),
    val resolvedAt: Long = System.currentTimeMillis()
) : Serializable {

    val displayTitle: String
        get() = if (episodeTitle.isNotBlank()) episodeTitle else "第 ${episodeNumber} 集"

    fun hasValidPlayUrl(maxAgeMs: Long = 10 * 60 * 1000L): Boolean {
        if (playUrl.isNullOrBlank()) return false
        val age = System.currentTimeMillis() - resolvedAt
        return age in 0..maxAgeMs
    }
}
