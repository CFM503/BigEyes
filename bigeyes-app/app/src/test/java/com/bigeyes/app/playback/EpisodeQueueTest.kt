package com.bigeyes.app.playback

import com.bigeyes.app.model.playback.Episode
import com.bigeyes.app.playback.queue.EpisodeQueue
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EpisodeQueueTest {

    private lateinit var queue: EpisodeQueue

    @Before
    fun setUp() {
        queue = EpisodeQueue()
    }

    @Test
    fun testEmptyQueue() {
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.size)
        assertNull(queue.currentEpisode)
        assertFalse(queue.hasNext)
        assertFalse(queue.hasPrevious)
        assertNull(queue.advance())
        assertNull(queue.retreat())
    }

    @Test
    fun testSingleEpisodeQueue() {
        val ep = Episode(
            id = "ep_1",
            seriesId = "series_1",
            seriesTitle = "My Series",
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeIndex = 0
        )
        queue.setQueue(listOf(ep), 0)

        assertEquals(1, queue.size)
        assertFalse(queue.isEmpty)
        assertEquals(ep, queue.currentEpisode)
        assertTrue(queue.isFirst)
        assertTrue(queue.isLast)
        assertFalse(queue.hasNext)
        assertFalse(queue.hasPrevious)
        assertNull(queue.nextEpisode)
        assertNull(queue.previousEpisode)
        assertNull(queue.advance())
    }

    @Test
    fun testMultiEpisodeNavigation() {
        val episodes = (1..5).map { i ->
            Episode(
                id = "ep_$i",
                seriesId = "series_1",
                seriesTitle = "My Series",
                episodeNumber = i,
                episodeTitle = "Episode $i",
                episodeIndex = i - 1
            )
        }
        queue.setQueue(episodes, initialIndex = 0)

        assertEquals(5, queue.size)
        assertEquals(0, queue.currentIndex)
        assertTrue(queue.hasNext)
        assertFalse(queue.hasPrevious)
        assertEquals("ep_2", queue.nextEpisode?.id)

        // Advance to 2
        val ep2 = queue.advance()
        assertNotNull(ep2)
        assertEquals(1, queue.currentIndex)
        assertEquals("ep_2", ep2?.id)
        assertTrue(queue.hasPrevious)
        assertTrue(queue.hasNext)

        // Advance to 3, 4, 5
        queue.advance()
        queue.advance()
        val ep5 = queue.advance()
        assertEquals(4, queue.currentIndex)
        assertEquals("ep_5", ep5?.id)
        assertTrue(queue.isLast)
        assertFalse(queue.hasNext)
        assertNull(queue.advance())

        // Retreat back to 4
        val retreated = queue.retreat()
        assertEquals(3, queue.currentIndex)
        assertEquals("ep_4", retreated?.id)

        // Jump to index 0
        val jumped = queue.jumpTo(0)
        assertEquals(0, queue.currentIndex)
        assertEquals("ep_1", jumped?.id)
        assertTrue(queue.isFirst)

        // Invalid jump out of bounds returns null and keeps current index
        assertNull(queue.jumpTo(99))
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun testUpdateEpisodePlayUrl() {
        val ep = Episode(
            id = "ep_1",
            seriesId = "series_1",
            seriesTitle = "My Series",
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeIndex = 0
        )
        queue.setQueue(listOf(ep), 0)

        assertNull(queue.currentEpisode?.playUrl)
        queue.updateCurrentPlayUrl("https://example.com/stream.m3u8", mapOf("Referer" to "https://example.com"))

        assertEquals("https://example.com/stream.m3u8", queue.currentEpisode?.playUrl)
        assertEquals("https://example.com", queue.currentEpisode?.playHeaders?.get("Referer"))
        assertTrue(queue.currentEpisode?.hasValidPlayUrl() == true)
    }

    @Test
    fun testQueueListenerNotification() {
        var callCount = 0
        val listener: (EpisodeQueue) -> Unit = { callCount++ }
        queue.addListener(listener)

        assertEquals(1, callCount)

        val ep = Episode(
            id = "ep_1",
            seriesId = "s",
            seriesTitle = "S",
            episodeNumber = 1,
            episodeTitle = "E1",
            episodeIndex = 0
        )
        queue.setQueue(listOf(ep), 0)
        assertEquals(2, callCount)

        queue.removeListener(listener)
        queue.clear()
        assertEquals(2, callCount)
    }
}
