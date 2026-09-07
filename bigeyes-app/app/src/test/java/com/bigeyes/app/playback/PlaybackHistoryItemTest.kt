package com.bigeyes.app.playback

import com.bigeyes.app.playback.history.PlaybackHistoryItem
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryItemTest {

    @Test
    fun testSerializationAndDeserialization() {
        val item = PlaybackHistoryItem(
            seriesId = "series_101",
            seriesTitle = "Test Series",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeIndex = 1,
            episodeTitle = "Episode 2",
            pageUrl = "https://example.com/ep2",
            positionMs = 125000L,
            durationMs = 250000L
        )

        val json = item.toJson()
        val restored = PlaybackHistoryItem.fromJson(json)

        assertNotNull(restored)
        assertEquals("series_101", restored?.seriesId)
        assertEquals("Test Series", restored?.seriesTitle)
        assertEquals(2, restored?.episodeNumber)
        assertEquals(1, restored?.episodeIndex)
        assertEquals("Episode 2", restored?.episodeTitle)
        assertEquals(125000L, restored?.positionMs)
        assertEquals(250000L, restored?.durationMs)
    }

    @Test
    fun testFormattedPositionAndProgress() {
        val item = PlaybackHistoryItem(
            seriesId = "s1",
            seriesTitle = "Title",
            episodeNumber = 1,
            episodeIndex = 0,
            episodeTitle = "Episode 1",
            positionMs = 75000L,
            durationMs = 150000L
        )

        assertEquals("01:15", item.formattedPosition)
        assertEquals(50, item.progressPercent)
        assertTrue(item.displaySummary.contains("Episode 1"))
        assertTrue(item.displaySummary.contains("01:15"))
    }
}
