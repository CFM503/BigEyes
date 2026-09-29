package com.bigeyes.app.playback.contract

import com.bigeyes.app.model.playback.Episode
import org.json.JSONArray
import org.json.JSONObject

object PlaybackIntentContract {
    const val PACKAGE_BIGEYES_TV = "com.bigeyes.tv"
    // Fully qualified class of the TV entry activity. Must match bigeyestv's
    // AndroidManifest android:name=".ui.MainActivity" (namespace com.bigeyes.tv).
    const val TV_MAIN_ACTIVITY = "com.bigeyes.tv.ui.MainActivity"
    const val TV_COMMAND_RECEIVER = "com.bigeyes.tv.playback.PlaybackCommandReceiver"

    /**
     * Minimal BigEyesTV versionCode required for direct casting.
     * v16+ ships the command broadcast receiver, status feedback and anti-hotlink header support;
     * older builds would silently swallow the intent, so they are excluded from direct casting.
     */
    const val MIN_TV_VERSION_CODE = 16L

    // Commands sent from BigEyes to BigEyesTV
    const val ACTION_PLAY = "com.bigeyes.tv.action.PLAY"
    const val ACTION_PLAY_QUEUE = "com.bigeyes.tv.action.PLAY_QUEUE"
    const val ACTION_PAUSE = "com.bigeyes.tv.action.PAUSE"
    const val ACTION_RESUME = "com.bigeyes.tv.action.RESUME"
    const val ACTION_STOP = "com.bigeyes.tv.action.STOP"
    const val ACTION_NEXT = "com.bigeyes.tv.action.NEXT"
    const val ACTION_PREVIOUS = "com.bigeyes.tv.action.PREVIOUS"
    const val ACTION_SEEK = "com.bigeyes.tv.action.SEEK"

    // Status broadcast sent from BigEyesTV back to BigEyes
    const val ACTION_STATUS_UPDATE = "com.bigeyes.tv.action.STATUS_UPDATE"

    // Extras
    const val EXTRA_PLAY_URL = "extra_play_url"
    const val EXTRA_EPISODE_QUEUE = "extra_episode_queue"
    const val EXTRA_SERIES_ID = "extra_series_id"
    const val EXTRA_SERIES_TITLE = "extra_series_title"
    const val EXTRA_SEASON_NUMBER = "extra_season_number"
    const val EXTRA_EPISODE_NUMBER = "extra_episode_number"
    const val EXTRA_EPISODE_INDEX = "extra_episode_index"
    const val EXTRA_EPISODE_TITLE = "extra_episode_title"
    const val EXTRA_TOTAL_COUNT = "extra_total_count"
    const val EXTRA_POSITION_MS = "extra_position_ms"
    const val EXTRA_SEEK_POSITION = "extra_seek_position"
    const val EXTRA_DURATION_MS = "extra_duration_ms"
    const val EXTRA_AUTO_PLAY_NEXT = "extra_auto_play_next"
    const val EXTRA_STATE = "extra_state" // PLAYING, PAUSED, COMPLETED, STOPPED, ERROR
    const val EXTRA_HEADER_REFERER = "extra_header_referer"
    const val EXTRA_HEADER_USER_AGENT = "extra_header_user_agent"
    const val EXTRA_HEADER_COOKIE = "extra_header_cookie"

    // States
    const val STATE_PLAYING = "PLAYING"
    const val STATE_PAUSED = "PAUSED"
    const val STATE_COMPLETED = "COMPLETED"
    const val STATE_STOPPED = "STOPPED"
    const val STATE_ERROR = "ERROR"

    /**
     * Serialize a queue into the JSON array consumed by BigEyesTV's PlaybackIntentContract.
     * Field names must stay in sync with the TV side ([com.bigeyes.tv.player.model.Episode]).
     */
    fun buildQueueJson(episodes: List<Episode>): String {
        val array = JSONArray()
        episodes.forEach { array.put(episodeToJson(it)) }
        return array.toString()
    }

    fun episodeToJson(episode: Episode): JSONObject {
        return JSONObject().apply {
            put("seriesId", episode.seriesId)
            put("seriesTitle", episode.seriesTitle)
            put("seasonNumber", episode.seasonNumber)
            put("episodeNumber", episode.episodeNumber)
            put("episodeTitle", episode.episodeTitle)
            put("episodeIndex", episode.episodeIndex)
            put("playUrl", episode.playUrl ?: "")
            episode.thumbnail?.takeIf { it.isNotBlank() }?.let { put("thumbnail", it) }
            put("duration", episode.durationMs)
            if (episode.playHeaders.isNotEmpty()) {
                val headers = JSONObject()
                episode.playHeaders.forEach { (key, value) -> headers.put(key, value) }
                put("headers", headers)
            }
        }
    }
}
