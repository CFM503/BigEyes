package com.bigeyes.app.playback.history

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

data class PlaybackHistoryItem(
    val seriesId: String,
    val seriesTitle: String,
    val seasonNumber: Int = 1,
    val episodeNumber: Int,
    val episodeIndex: Int,
    val episodeTitle: String,
    val pageUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) : Serializable {

    val formattedPosition: String
        get() {
            val totalSecs = (positionMs / 1000).toInt()
            val m = totalSecs / 60
            val s = totalSecs % 60
            return String.format("%02d:%02d", m, s)
        }

    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs.toFloat() / durationMs) * 100).toInt().coerceIn(0, 100) else 0

    val displaySummary: String
        get() = "上次看到: $episodeTitle $formattedPosition"

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("seriesId", seriesId)
            put("seriesTitle", seriesTitle)
            put("seasonNumber", seasonNumber)
            put("episodeNumber", episodeNumber)
            put("episodeIndex", episodeIndex)
            put("episodeTitle", episodeTitle)
            put("pageUrl", pageUrl ?: "")
            put("positionMs", positionMs)
            put("durationMs", durationMs)
            put("updatedAt", updatedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PlaybackHistoryItem? {
            return try {
                PlaybackHistoryItem(
                    seriesId = json.getString("seriesId"),
                    seriesTitle = json.optString("seriesTitle", "未命名剧集"),
                    seasonNumber = json.optInt("seasonNumber", 1),
                    episodeNumber = json.optInt("episodeNumber", 1),
                    episodeIndex = json.optInt("episodeIndex", 0),
                    episodeTitle = json.optString("episodeTitle", "第1集"),
                    pageUrl = json.optString("pageUrl").takeIf { it.isNotBlank() },
                    positionMs = json.optLong("positionMs", 0L),
                    durationMs = json.optLong("durationMs", 0L),
                    updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object PlaybackHistoryManager {

    private const val TAG = "PlaybackHistoryManager"
    private const val PREFS_NAME = "bigeyes_playback_history"
    private const val KEY_HISTORY_DATA = "history_entries"
    private const val MAX_HISTORY_COUNT = 100

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun saveHistory(context: Context, item: PlaybackHistoryItem) {
        try {
            val list = getAllHistory(context).toMutableList()
            list.removeAll { it.seriesId == item.seriesId }
            list.add(0, item.copy(updatedAt = System.currentTimeMillis()))

            while (list.size > MAX_HISTORY_COUNT) {
                list.removeAt(list.size - 1)
            }

            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }

            getPrefs(context).edit().putString(KEY_HISTORY_DATA, array.toString()).apply()
            Log.d(TAG, "Saved playback history for: ${item.seriesTitle} -> ${item.episodeTitle}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving playback history: ${e.message}")
        }
    }

    @Synchronized
    fun getHistory(context: Context, seriesId: String): PlaybackHistoryItem? {
        if (seriesId.isBlank()) return null
        return getAllHistory(context).firstOrNull { it.seriesId == seriesId }
    }

    @Synchronized
    fun getAllHistory(context: Context): List<PlaybackHistoryItem> {
        val raw = getPrefs(context).getString(KEY_HISTORY_DATA, null) ?: return emptyList()
        val result = mutableListOf<PlaybackHistoryItem>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                PlaybackHistoryItem.fromJson(obj)?.let { result.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing playback history: ${e.message}")
        }
        return result
    }

    @Synchronized
    fun deleteHistory(context: Context, seriesId: String) {
        val list = getAllHistory(context).filterNot { it.seriesId == seriesId }
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        getPrefs(context).edit().putString(KEY_HISTORY_DATA, array.toString()).apply()
    }

    @Synchronized
    fun clearHistory(context: Context) {
        getPrefs(context).edit().remove(KEY_HISTORY_DATA).apply()
    }
}
