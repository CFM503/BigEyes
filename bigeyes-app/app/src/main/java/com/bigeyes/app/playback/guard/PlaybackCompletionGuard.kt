package com.bigeyes.app.playback.guard

import android.util.Log

class PlaybackCompletionGuard(
    private val minIntervalMs: Long = 4000L
) {
    companion object {
        private const val TAG = "PlaybackCompletionGuard"
    }

    private var lastCompletedEpisodeId: String? = null
    private var lastCompletionTimestamp: Long = 0L

    @Synchronized
    fun checkAndAcquire(episodeId: String): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastCompletionTimestamp

        if (episodeId == lastCompletedEpisodeId && elapsed < minIntervalMs) {
            Log.w(TAG, "Duplicate completion event ignored for episodeId=$episodeId (elapsed=${elapsed}ms < minInterval=${minIntervalMs}ms)")
            return false
        }

        lastCompletedEpisodeId = episodeId
        lastCompletionTimestamp = now
        Log.i(TAG, "Acquired completion lock for episodeId=$episodeId at timestamp=$now")
        return true
    }

    @Synchronized
    fun reset() {
        lastCompletedEpisodeId = null
        lastCompletionTimestamp = 0L
    }
}
