package com.bigeyes.app.browser

import com.bigeyes.app.model.VideoCandidate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CandidateManagerTest {

    @Before
    fun setUp() {
        CandidateManager.onPageStarted("https://site.example.com/movie/1")
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

    @Test
    fun testMasterAndVariantsShowAsOneCandidate() {
        CandidateManager.addCandidate(VideoCandidate(url = "https://cdn.example.com/m/index.m3u8", title = "Movie"))
        CandidateManager.addCandidate(VideoCandidate(url = "https://cdn.example.com/m/1080p/index.m3u8", title = "Movie"))
        CandidateManager.addCandidate(VideoCandidate(url = "https://cdn.example.com/m/720p/index.m3u8", title = "Movie"))

        val candidates = CandidateManager.getCandidates()
        assertEquals(1, candidates.size)
        assertEquals("https://cdn.example.com/m/index.m3u8", candidates.first().url)
        assertEquals(3, candidates.first().variantCount)
    }

    @Test
    fun testInPageNavigationMarksOldCandidatesStale() {
        CandidateManager.addCandidate(VideoCandidate(url = "https://cdn.example.com/movie1/index.m3u8", title = "Movie 1"))

        // Playback position update in the URL is not a new page.
        assertFalse(CandidateManager.onInPageNavigation("https://site.example.com/movie/1?t=30"))
        assertFalse(CandidateManager.getCandidates().first().isStale)

        // SPA switches to another movie.
        assertTrue(CandidateManager.onInPageNavigation("https://site.example.com/movie/2"))
        assertTrue(CandidateManager.getCandidates().first().isStale)

        CandidateManager.addCandidate(VideoCandidate(url = "https://cdn.example.com/movie2/index.m3u8", title = "Movie 2"))
        val candidates = CandidateManager.getCandidates()
        assertEquals("Movie 2", candidates.first().title)
        assertFalse(candidates.first().isStale)
        assertTrue(candidates[1].isStale)
    }
}
