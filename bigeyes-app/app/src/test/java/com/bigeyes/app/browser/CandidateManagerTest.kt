package com.bigeyes.app.browser

import com.bigeyes.app.model.VideoCandidate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CandidateManagerTest {

    @Before
    fun setUp() {
        CandidateManager.clear()
    }

    @Test
    fun testAddAndDeduplicateCandidate() {
        val candidate1 = VideoCandidate(
            url = "https://example.com/live/1.m3u8",
            referer = "https://example.com",
            userAgent = "BigEyes",
            cookie = null,
            title = "Episode 1",
            timestamp = 1000L
        )

        CandidateManager.addCandidate(candidate1)
        assertEquals(1, CandidateManager.getCandidates().size)

        // Adding candidate with same URL updates timestamp and keeps list size 1
        val candidate1Updated = VideoCandidate(
            url = "https://example.com/live/1.m3u8",
            referer = "https://example.com",
            userAgent = "BigEyes",
            cookie = null,
            title = "Episode 1 (Updated)",
            timestamp = 2000L
        )
        CandidateManager.addCandidate(candidate1Updated)
        assertEquals(1, CandidateManager.getCandidates().size)
        assertEquals("Episode 1 (Updated)", CandidateManager.getCandidates().first().title)
    }

    @Test
    fun testCandidateCapacityLimit() {
        for (i in 1..10) {
            CandidateManager.addCandidate(
                VideoCandidate(
                    url = "https://example.com/stream_$i.m3u8",
                    referer = null,
                    userAgent = null,
                    cookie = null,
                    title = "Stream $i",
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // Default capacity is MAX_CANDIDATES = 5
        val candidates = CandidateManager.getCandidates()
        assertEquals(5, candidates.size)
        assertEquals("Stream 10", candidates.first().title)
    }
}
