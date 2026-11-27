package com.bigeyes.app.browser

import com.bigeyes.app.model.VideoCandidate
import org.junit.Assert.*
import org.junit.Test

class CandidateRankerTest {

    private var seq = 0L

    private fun entry(url: String, epoch: Int = 0, title: String = "Movie", ts: Long = 1000L + seq) =
        CandidateRanker.Entry(VideoCandidate(url = url, title = title, timestamp = ts), epoch, ++seq)

    private fun rank(vararg entries: CandidateRanker.Entry, epoch: Int = 0, limit: Int = 5) =
        CandidateRanker.rank(entries.toList(), epoch, limit)

    @Test
    fun sameVideoWithDifferentTokensIsMerged() {
        val result = rank(
            entry("https://cdn.example.com/movie/index.m3u8?token=aaa&expires=1"),
            entry("https://cdn.example.com/movie/index.m3u8?token=bbb&expires=2"),
            entry("https://cdn.example.com/movie/index.m3u8?t=1700000000&sign=zzz")
        )
        assertEquals(1, result.size)
        assertEquals("Newest sighting (freshest token) is kept",
            "https://cdn.example.com/movie/index.m3u8?t=1700000000&sign=zzz", result[0].url)
    }

    @Test
    fun differentMeaningfulQueryParamsStaySeparate() {
        val result = rank(
            entry("https://cdn.example.com/get?file=a.mp4"),
            entry("https://cdn.example.com/get?file=b.mp4")
        )
        assertEquals(2, result.size)
    }

    @Test
    fun masterAndVariantsInSubfoldersAreGrouped() {
        val result = rank(
            entry("https://cdn.example.com/show/ep1/index.m3u8"),
            entry("https://cdn.example.com/show/ep1/1080p/index.m3u8"),
            entry("https://cdn.example.com/show/ep1/720p/index.m3u8")
        )
        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("Master playlist is the one to cast", "https://cdn.example.com/show/ep1/index.m3u8", c.url)
        assertTrue(c.isMasterPlaylist)
        assertEquals(3, c.variantCount)
        assertEquals(listOf("1080p", "720p"), c.qualities)
    }

    @Test
    fun masterNamedPlaylistGroupsSiblingVariants() {
        val result = rank(
            entry("https://cdn.example.com/v/abc/master.m3u8"),
            entry("https://cdn.example.com/v/abc/index_1920x1080.m3u8"),
            entry("https://cdn.example.com/v/abc/index_1280x720.m3u8")
        )
        assertEquals(1, result.size)
        assertEquals("https://cdn.example.com/v/abc/master.m3u8", result[0].url)
        assertEquals(listOf("1080p", "720p"), result[0].qualities)
    }

    @Test
    fun siblingEpisodesAreNotGrouped() {
        val result = rank(
            entry("https://cdn.example.com/show/ep1/index.m3u8"),
            entry("https://cdn.example.com/show/ep2/index.m3u8")
        )
        assertEquals(2, result.size)
    }

    @Test
    fun differentHostsAreNotGrouped() {
        val result = rank(
            entry("https://a.example.com/show/index.m3u8"),
            entry("https://b.example.com/show/1080p/index.m3u8")
        )
        assertEquals(2, result.size)
    }

    @Test
    fun suspiciousClipsAreRankedLast() {
        val result = rank(
            entry("https://cdn.example.com/movie/main.mp4"),
            entry("https://cdn.example.com/trailer/teaser.mp4")
        )
        assertEquals(2, result.size)
        assertEquals("https://cdn.example.com/movie/main.mp4", result[0].url)
        assertTrue(result[1].isSuspicious)
    }

    @Test
    fun suspiciousPlaylistIsNotMergedIntoMain() {
        val result = rank(
            entry("https://cdn.example.com/movie/index.m3u8"),
            entry("https://cdn.example.com/movie/preview/index.m3u8")
        )
        assertEquals(2, result.size)
        assertEquals("https://cdn.example.com/movie/index.m3u8", result[0].url)
        assertTrue(result[1].isSuspicious)
    }

    @Test
    fun staleCandidatesFromPreviousPageAreRankedLast() {
        val result = rank(
            entry("https://cdn.example.com/old-movie/index.m3u8", epoch = 0),
            entry("https://cdn.example.com/new-movie/index.m3u8", epoch = 1),
            epoch = 1
        )
        assertEquals("https://cdn.example.com/new-movie/index.m3u8", result[0].url)
        assertFalse(result[0].isStale)
        assertTrue(result[1].isStale)
    }

    @Test
    fun withoutOtherSignalsNewestComesFirst() {
        val result = rank(
            entry("https://cdn.example.com/a.mp4"),
            entry("https://cdn.example.com/b.mp4"),
            entry("https://cdn.example.com/c.mp4")
        )
        assertEquals(listOf("c", "b", "a"), result.map { it.url.substringAfterLast('/').substringBefore('.') })
    }

    @Test
    fun groupTimestampIsLatestOfMembers() {
        val result = rank(
            entry("https://cdn.example.com/show/index.m3u8", ts = 1000L),
            entry("https://cdn.example.com/show/1080p/index.m3u8", ts = 5000L)
        )
        assertEquals(5000L, result[0].timestamp)
    }

    @Test
    fun limitIsApplied() {
        val entries = (1..10).map { entry("https://cdn.example.com/v$it.mp4") }.toTypedArray()
        assertEquals(5, rank(*entries).size)
    }

    @Test
    fun pageKeyIgnoresPlaybackPositionParams() {
        assertEquals(
            CandidateRanker.pageKey("https://site.example.com/play?id=42"),
            CandidateRanker.pageKey("https://site.example.com/play?id=42&t=120#comments")
        )
        assertNotEquals(
            CandidateRanker.pageKey("https://site.example.com/play?id=42"),
            CandidateRanker.pageKey("https://site.example.com/play?id=43")
        )
    }

    @Test
    fun qualityAndFormatLabels() {
        assertEquals("1080p", CandidateRanker.qualityOf("https://cdn.example.com/1080p/index.m3u8"))
        assertEquals("720p", CandidateRanker.qualityOf("https://cdn.example.com/v_1280x720.mp4"))
        assertEquals("4K", CandidateRanker.qualityOf("https://cdn.example.com/4k/index.m3u8"))
        assertNull(CandidateRanker.qualityOf("https://cdn.example.com/movie/index.m3u8"))
        assertEquals("HLS", CandidateRanker.formatOf("https://cdn.example.com/a.m3u8?x=1"))
        assertEquals("MP4", CandidateRanker.formatOf("https://cdn.example.com/a.mp4"))
    }

    @Test
    fun describeShowsMergedInfo() {
        val c = VideoCandidate(
            url = "https://cdn.example.com/show/index.m3u8",
            variantCount = 3,
            qualities = listOf("1080p", "720p"),
            isMasterPlaylist = true
        )
        assertEquals("HLS · 主列表 · 1080p/720p（合并 3 个地址）", CandidateRanker.describe(c))
        val ad = VideoCandidate(url = "https://cdn.example.com/promo.mp4", isSuspicious = true, isStale = true)
        assertEquals("MP4 · ⚠ 疑似广告/预告 · 上一页面", CandidateRanker.describe(ad))
    }

    @Test
    fun describeSimpleUsesPlainWords() {
        val master = VideoCandidate(
            url = "https://cdn.example.com/show/index.m3u8",
            variantCount = 3,
            qualities = listOf("1080p", "720p"),
            isMasterPlaylist = true
        )
        assertEquals("1080p / 720p 可选", CandidateRanker.describeSimple(master))

        val masterNoQuality = VideoCandidate(url = "https://cdn.example.com/m/master.m3u8", isMasterPlaylist = true)
        assertEquals("多种清晰度", CandidateRanker.describeSimple(masterNoQuality))

        assertEquals("在线视频", CandidateRanker.describeSimple(VideoCandidate(url = "https://cdn.example.com/a.m3u8")))
        assertEquals("MP4 视频", CandidateRanker.describeSimple(VideoCandidate(url = "https://cdn.example.com/a.mp4")))
        assertEquals("720p", CandidateRanker.describeSimple(VideoCandidate(url = "https://cdn.example.com/a_720p.mp4")))

        val ad = VideoCandidate(url = "https://cdn.example.com/promo.mp4", isSuspicious = true, isStale = true)
        assertEquals("疑似广告/预告 · MP4 视频 · 上一页面的视频", CandidateRanker.describeSimple(ad))
    }
}
