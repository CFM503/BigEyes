package com.bigeyes.app.playback

import com.bigeyes.app.model.playback.Episode
import com.bigeyes.app.playback.queue.EpisodeQueue
import org.junit.Assert.*
import org.junit.Test

/** Freshness bookkeeping used to avoid handing expired stream URLs to the TV. */
class EpisodeQueueFreshnessTest {

    private fun ep(n: Int, playUrl: String? = null, resolvedAt: Long = System.currentTimeMillis()) = Episode(
        id = "ep_$n",
        seriesId = "s",
        seriesTitle = "Series",
        episodeNumber = n,
        episodeTitle = "Episode $n",
        episodeIndex = n - 1,
        playUrl = playUrl,
        resolvedAt = resolvedAt
    )

    @Test
    fun mergeEpisodesCarriesResolveTimestamp() {
        val queue = EpisodeQueue()
        val longAgo = System.currentTimeMillis() - 60 * 60 * 1000L
        queue.setQueue(listOf(ep(1, resolvedAt = longAgo), ep(2, resolvedAt = longAgo)))

        val freshAt = System.currentTimeMillis()
        queue.mergeEpisodes(listOf(ep(2, playUrl = "https://cdn.example.com/2.m3u8", resolvedAt = freshAt)))

        val merged = queue.items[1]
        assertEquals("https://cdn.example.com/2.m3u8", merged.playUrl)
        assertEquals(freshAt, merged.resolvedAt)
        assertTrue("Freshly merged URL must count as valid", merged.hasValidPlayUrl())
    }

    @Test
    fun mergeEpisodesKeepsStaleTimestampWhenNoNewUrl() {
        val queue = EpisodeQueue()
        val longAgo = System.currentTimeMillis() - 60 * 60 * 1000L
        queue.setQueue(listOf(ep(1, playUrl = "https://cdn.example.com/old.m3u8", resolvedAt = longAgo)))

        // Existing URL is not replaced by merge, so it stays expired.
        queue.mergeEpisodes(listOf(ep(1, playUrl = "https://cdn.example.com/new.m3u8")))
        assertEquals("https://cdn.example.com/old.m3u8", queue.items[0].playUrl)
        assertFalse(queue.items[0].hasValidPlayUrl())
    }

    @Test
    fun updateEpisodePlayUrlRefreshesTimestamp() {
        val queue = EpisodeQueue()
        val longAgo = System.currentTimeMillis() - 60 * 60 * 1000L
        queue.setQueue(listOf(ep(1, playUrl = "https://cdn.example.com/old.m3u8", resolvedAt = longAgo)))
        assertFalse(queue.items[0].hasValidPlayUrl())

        queue.updateEpisodePlayUrl(0, "https://cdn.example.com/fresh.m3u8")
        assertTrue(queue.items[0].hasValidPlayUrl())
    }

    @Test
    fun expiredUrlIsInvalid() {
        val elevenMinutesAgo = System.currentTimeMillis() - 11 * 60 * 1000L
        assertFalse(ep(1, playUrl = "https://cdn.example.com/1.m3u8", resolvedAt = elevenMinutesAgo).hasValidPlayUrl())
        assertTrue(ep(1, playUrl = "https://cdn.example.com/1.m3u8").hasValidPlayUrl())
        assertFalse(ep(1, playUrl = null).hasValidPlayUrl())
    }
}
