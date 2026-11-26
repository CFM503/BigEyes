package com.bigeyes.app.model

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoCandidate(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val title: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val mimeType: String? = null,
    // ---- Labels filled in by CandidateRanker for the cast picker ----
    /** How many distinct sniffed URLs were merged into this entry (master + variants). */
    val variantCount: Int = 1,
    /** Resolutions seen for this video, best first (e.g. ["1080p", "720p"]). */
    val qualities: List<String> = emptyList(),
    /** HLS master playlist (contains several qualities); preferred for casting. */
    val isMasterPlaylist: Boolean = false,
    /** URL looks like an ad, trailer or preview clip. */
    val isSuspicious: Boolean = false,
    /** Sniffed on a previous in-page (SPA) navigation, probably another video. */
    val isStale: Boolean = false
) : Serializable {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val displayTitle: String
        get() = if (!title.isNullOrBlank()) title else "视频流 (${formattedTime})"
}
