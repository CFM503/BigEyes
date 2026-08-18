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
    val mimeType: String? = null
) : Serializable {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val displayTitle: String
        get() = if (!title.isNullOrBlank()) title else "视频流 (${formattedTime})"
}
