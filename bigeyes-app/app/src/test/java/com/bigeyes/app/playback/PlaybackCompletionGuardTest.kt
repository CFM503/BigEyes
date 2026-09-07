package com.bigeyes.app.playback

import com.bigeyes.app.playback.guard.PlaybackCompletionGuard
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackCompletionGuardTest {

    private lateinit var guard: PlaybackCompletionGuard

    @Before
    fun setUp() {
        guard = PlaybackCompletionGuard(minIntervalMs = 2000L)
    }

    @Test
    fun testFirstAcquisitionSucceeds() {
        assertTrue(guard.checkAndAcquire("ep_1"))
    }

    @Test
    fun testRapidDuplicateAcquisitionBlocked() {
        assertTrue(guard.checkAndAcquire("ep_1"))
        assertFalse(guard.checkAndAcquire("ep_1"))
        assertFalse(guard.checkAndAcquire("ep_1"))
    }

    @Test
    fun testDifferentEpisodeAllowedImmediately() {
        assertTrue(guard.checkAndAcquire("ep_1"))
        assertTrue(guard.checkAndAcquire("ep_2"))
    }

    @Test
    fun testResetAllowsAcquisitionAgain() {
        assertTrue(guard.checkAndAcquire("ep_1"))
        assertFalse(guard.checkAndAcquire("ep_1"))

        guard.reset()
        assertTrue(guard.checkAndAcquire("ep_1"))
    }
}
