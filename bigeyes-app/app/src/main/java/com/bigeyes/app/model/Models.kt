package com.bigeyes.app.model

data class DlnaDevice(
    val id: String,
    val name: String,
    val ip: String,
    val selected: Boolean = false
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
