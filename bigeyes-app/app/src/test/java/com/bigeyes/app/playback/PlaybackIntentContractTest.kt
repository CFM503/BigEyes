package com.bigeyes.app.playback

import com.bigeyes.app.playback.contract.PlaybackIntentContract
import org.junit.Assert.*
import org.junit.Test

class PlaybackIntentContractTest {

    @Test
    fun testActionConstants() {
        assertEquals("com.bigeyes.tv", PlaybackIntentContract.PACKAGE_BIGEYES_TV)
        assertEquals("com.bigeyes.tv.action.PLAY", PlaybackIntentContract.ACTION_PLAY)
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
        assertEquals("extra_series_id", PlaybackIntentContract.EXTRA_SERIES_ID)
        assertEquals("extra_series_title", PlaybackIntentContract.EXTRA_SERIES_TITLE)
        assertEquals("extra_episode_index", PlaybackIntentContract.EXTRA_EPISODE_INDEX)
        assertEquals("extra_position_ms", PlaybackIntentContract.EXTRA_POSITION_MS)
        assertEquals("extra_auto_play_next", PlaybackIntentContract.EXTRA_AUTO_PLAY_NEXT)
        assertEquals("extra_state", PlaybackIntentContract.EXTRA_STATE)
    }

    @Test
    fun testStateConstants() {
        assertEquals("PLAYING", PlaybackIntentContract.STATE_PLAYING)
        assertEquals("PAUSED", PlaybackIntentContract.STATE_PAUSED)
        assertEquals("COMPLETED", PlaybackIntentContract.STATE_COMPLETED)
        assertEquals("STOPPED", PlaybackIntentContract.STATE_STOPPED)
        assertEquals("ERROR", PlaybackIntentContract.STATE_ERROR)
    }
}
