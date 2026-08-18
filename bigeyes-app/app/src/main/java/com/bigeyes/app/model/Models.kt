package com.bigeyes.app.model

data class KeyItem(
    val index: Int,
    val method: String,
    val uri: String,
    val iv: String? = null,
    val keyFormat: String? = null
)

data class SegmentItem(
    val index: Int,
    val uri: String,
    val duration: Float = 0.0f,
    val title: String? = null,
    val keyIndex: Int? = null,
    val byteRange: String? = null
)

data class VariantItem(
    val index: Int,
    val bandwidth: Int = 0,
    val resolution: String? = null,
    val codecs: String? = null,
    val uri: String
)

data class StreamSession(
    val streamId: String,
    val originalUrl: String,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isMaster: Boolean = false,
    var variants: List<VariantItem> = emptyList(),
    var selectedVariantIndex: Int = 0,
    var segments: List<SegmentItem> = emptyList(),
    var keys: List<KeyItem> = emptyList(),
    var lastAccessedSeg: Int = 0
)

data class DlnaDevice(
    val id: String,
    val name: String,
    val ip: String,
    val locationUrl: String,
    val avTransportControlUrl: String? = null,
    val renderingControlUrl: String? = null,
    var selected: Boolean = false,
    var lastSeen: Long = System.currentTimeMillis()
)

data class CastStatus(
    val hasActiveStream: Boolean = false,
    val streamId: String? = null,
    val title: String? = null,
    val device: String? = null,
    val state: String = "idle", // playing, paused, stopped, buffering, idle
    val position: String = "00:00:00",
    val duration: String = "00:00:00"
)

data class SniffLogEntry(
    val url: String,
    val headers: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)
