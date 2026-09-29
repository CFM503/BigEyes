package com.bigeyes.app.playback

import com.bigeyes.app.model.playback.Episode
import com.bigeyes.app.playback.contract.PlaybackIntentContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackIntentContractTest {

    @Test
    fun testActionConstants() {
        assertEquals("com.bigeyes.tv", PlaybackIntentContract.PACKAGE_BIGEYES_TV)
        assertEquals("com.bigeyes.tv.action.PLAY", PlaybackIntentContract.ACTION_PLAY)
        assertEquals("com.bigeyes.tv.action.PLAY_QUEUE", PlaybackIntentContract.ACTION_PLAY_QUEUE)
        assertEquals("com.bigeyes.tv.action.PAUSE", PlaybackIntentContract.ACTION_PAUSE)
        assertEquals("com.bigeyes.tv.action.RESUME", PlaybackIntentContract.ACTION_RESUME)
        assertEquals("com.bigeyes.tv.action.STOP", PlaybackIntentContract.ACTION_STOP)
        assertEquals("com.bigeyes.tv.action.NEXT", PlaybackIntentContract.ACTION_NEXT)
        assertEquals("com.bigeyes.tv.action.PREVIOUS", PlaybackIntentContract.ACTION_PREVIOUS)
        assertEquals("com.bigeyes.tv.action.SEEK", PlaybackIntentContract.ACTION_SEEK)
        assertEquals("com.bigeyes.tv.action.STATUS_UPDATE", PlaybackIntentContract.ACTION_STATUS_UPDATE)
    }

    @Test
    fun testExtraKeys() {
        assertEquals("extra_play_url", PlaybackIntentContract.EXTRA_PLAY_URL)
        assertEquals("extra_episode_queue", PlaybackIntentContract.EXTRA_EPISODE_QUEUE)
        assertEquals("extra_series_id", PlaybackIntentContract.EXTRA_SERIES_ID)
        assertEquals("extra_series_title", PlaybackIntentContract.EXTRA_SERIES_TITLE)
        assertEquals("extra_season_number", PlaybackIntentContract.EXTRA_SEASON_NUMBER)
        assertEquals("extra_episode_number", PlaybackIntentContract.EXTRA_EPISODE_NUMBER)
        assertEquals("extra_episode_index", PlaybackIntentContract.EXTRA_EPISODE_INDEX)
        assertEquals("extra_episode_title", PlaybackIntentContract.EXTRA_EPISODE_TITLE)
        assertEquals("extra_total_count", PlaybackIntentContract.EXTRA_TOTAL_COUNT)
        assertEquals("extra_position_ms", PlaybackIntentContract.EXTRA_POSITION_MS)
        assertEquals("extra_seek_position", PlaybackIntentContract.EXTRA_SEEK_POSITION)
        assertEquals("extra_duration_ms", PlaybackIntentContract.EXTRA_DURATION_MS)
        assertEquals("extra_auto_play_next", PlaybackIntentContract.EXTRA_AUTO_PLAY_NEXT)
        assertEquals("extra_state", PlaybackIntentContract.EXTRA_STATE)
        assertEquals("extra_header_referer", PlaybackIntentContract.EXTRA_HEADER_REFERER)
        assertEquals("extra_header_user_agent", PlaybackIntentContract.EXTRA_HEADER_USER_AGENT)
        assertEquals("extra_header_cookie", PlaybackIntentContract.EXTRA_HEADER_COOKIE)
    }

    @Test
    fun testStateConstants() {
        assertEquals("PLAYING", PlaybackIntentContract.STATE_PLAYING)
        assertEquals("PAUSED", PlaybackIntentContract.STATE_PAUSED)
        assertEquals("COMPLETED", PlaybackIntentContract.STATE_COMPLETED)
        assertEquals("STOPPED", PlaybackIntentContract.STATE_STOPPED)
        assertEquals("ERROR", PlaybackIntentContract.STATE_ERROR)
    }

    @Test
    fun testMinTvVersionCode() {
        assertEquals(16L, PlaybackIntentContract.MIN_TV_VERSION_CODE)
        // Must match bigeyestv's AndroidManifest android:name=".ui.MainActivity"
        assertEquals("com.bigeyes.tv.ui.MainActivity", PlaybackIntentContract.TV_MAIN_ACTIVITY)
        assertEquals("com.bigeyes.tv.playback.PlaybackCommandReceiver", PlaybackIntentContract.TV_COMMAND_RECEIVER)
    }

    @Test
    fun testTvComponentNamesAreFullyQualified() {
        assertTrue(
            "TV_MAIN_ACTIVITY must be absolute so ComponentName never relies on package defaults",
            PlaybackIntentContract.TV_MAIN_ACTIVITY.startsWith("${PlaybackIntentContract.PACKAGE_BIGEYES_TV}.")
        )
        assertTrue(
            "TV_COMMAND_RECEIVER must be absolute",
            PlaybackIntentContract.TV_COMMAND_RECEIVER.startsWith("${PlaybackIntentContract.PACKAGE_BIGEYES_TV}.")
        )
    }

    @Test
    fun testEpisodeToJsonFields() {
        val episode = Episode(
            seriesId = "series_1",
            seriesTitle = "三体",
            seasonNumber = 2,
            episodeNumber = 5,
            episodeTitle = "第五集",
            episodeIndex = 4,
            playUrl = "https://example.com/5.m3u8",
            playHeaders = mapOf("Referer" to "https://example.com/", "User-Agent" to "BigEyes"),
            durationMs = 2_540_000L,
            thumbnail = "https://example.com/5.jpg"
        )

        val json = PlaybackIntentContract.episodeToJson(episode)
        assertEquals("series_1", json.getString("seriesId"))
        assertEquals("三体", json.getString("seriesTitle"))
        assertEquals(2, json.getInt("seasonNumber"))
        assertEquals(5, json.getInt("episodeNumber"))
        assertEquals("第五集", json.getString("episodeTitle"))
        assertEquals(4, json.getInt("episodeIndex"))
        assertEquals("https://example.com/5.m3u8", json.getString("playUrl"))
        assertEquals("https://example.com/5.jpg", json.getString("thumbnail"))
        assertEquals(2_540_000L, json.getLong("duration"))

        val headers = json.getJSONObject("headers")
        assertEquals("https://example.com/", headers.getString("Referer"))
        assertEquals("BigEyes", headers.getString("User-Agent"))
    }

    @Test
    fun testEpisodeToJsonOmitsBlankOptionalFields() {
        val episode = Episode(
            seriesId = "series_1",
            seriesTitle = "三体",
            episodeNumber = 1,
            episodeTitle = "第一集",
            episodeIndex = 0
        )

        val json = PlaybackIntentContract.episodeToJson(episode)
        assertFalse(json.has("thumbnail"))
        assertFalse(json.has("headers"))
        assertEquals("", json.getString("playUrl"))
        assertEquals(0L, json.getLong("duration"))
    }

    @Test
    fun testBuildQueueJsonPreservesOrderAndIndices() {
        val episodes = (0 until 3).map { index ->
            Episode(
                seriesId = "series_1",
                seriesTitle = "三体",
                episodeNumber = index + 1,
                episodeTitle = "第 ${index + 1} 集",
                episodeIndex = index,
                playUrl = "https://example.com/${index + 1}.m3u8"
            )
        }

        val array = JSONArray(PlaybackIntentContract.buildQueueJson(episodes))
        assertEquals(3, array.length())
        for (index in 0 until array.length()) {
            val item: JSONObject = array.getJSONObject(index)
            assertEquals(index, item.getInt("episodeIndex"))
            assertEquals(index + 1, item.getInt("episodeNumber"))
            assertEquals("https://example.com/${index + 1}.m3u8", item.getString("playUrl"))
        }
    }
}

