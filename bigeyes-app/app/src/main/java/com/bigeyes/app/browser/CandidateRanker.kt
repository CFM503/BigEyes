package com.bigeyes.app.browser

import com.bigeyes.app.model.VideoCandidate

/**
 * Pure (Android-free) logic that turns the raw list of sniffed stream URLs into the short list
 * shown in the cast picker:
 *
 * 1. Same video, different volatile query params (timestamp, token, signature...) -> one entry.
 * 2. An HLS master playlist and the per-quality variant playlists the player fetched from it
 *    -> one entry (the master), annotated with the qualities seen.
 * 3. Likely ads / trailers / previews and candidates left over from a previous in-page (SPA)
 *    navigation are labelled and ranked last.
 */
object CandidateRanker {

    /** A sniffed candidate as recorded, before grouping. */
    data class Entry(
        val candidate: VideoCandidate,
        val pageEpoch: Int,
        val seq: Long,
        val dedupeKey: String = CandidateRanker.dedupeKey(candidate.url)
    )

    /** Query params that change between requests for the same video (signatures, cache busters). */
    private val VOLATILE_PARAMS = setOf(
        "t", "ts", "time", "timestamp", "_", "_t", "rnd", "random", "r", "cb", "nocache",
        "token", "sign", "signature", "sig", "auth", "auth_key", "authkey", "expires", "expire",
        "exp", "e", "st", "nonce", "hdnts", "hdnea", "wssecret", "wstime", "txsecret", "txtime",
        "policy", "key-pair-id", "vkey", "guid", "sid", "session", "uuid", "pt", "deadline"
    )

    /** Params that change in a page URL without the page showing a different video. */
    private val PAGE_VOLATILE_PARAMS = VOLATILE_PARAMS + setOf(
        "start", "position", "progress", "seek", "from", "spm", "share", "share_source",
        "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term"
    )

    /** Soft signals for ads/trailers/previews (hard ad URLs are already dropped by the sniffer). */
    private val SUSPICIOUS_KEYWORDS = listOf(
        "preview", "trailer", "teaser", "promo", "preroll", "pre-roll", "pre_roll",
        "midroll", "mid-roll", "postroll", "post-roll", "vast", "sample", "hover",
        "/ad_", "_ad.", "-ad.", "/ads_", "adpod", "/pianotou/", "piantou", "/qianpian/"
    )

    private val QUALITY_P = Regex("(?<![0-9])(4320|2160|1440|1080|720|576|540|480|360|240)[pP](?![a-zA-Z0-9])")
    private val QUALITY_WH = Regex("(?<![0-9])([0-9]{3,4})[xX]([0-9]{3,4})(?![0-9])")
    private val QUALITY_4K = Regex("(?i)(?<![a-z0-9])(4k|uhd)(?![a-z0-9])")

    private const val SCORE_MASTER = 30
    private const val SCORE_SUSPICIOUS = -50
    private const val SCORE_STALE = -100

    private data class ParsedUrl(
        val host: String,
        val path: String,
        val query: String
    ) {
        val dir: String get() = path.substringBeforeLast('/', "")
        val fileName: String get() = path.substringAfterLast('/').lowercase()
        val depth: Int get() = dir.count { it == '/' }
    }

    private fun parse(url: String): ParsedUrl {
        val noFragment = url.substringBefore('#')
        val afterScheme = if (noFragment.contains("://")) noFragment.substringAfter("://") else noFragment
        val cut = afterScheme.indexOfFirst { it == '/' || it == '?' }
        val authority = if (cut < 0) afterScheme else afterScheme.substring(0, cut)
        val remainder = if (cut < 0) "" else afterScheme.substring(cut)
        return ParsedUrl(
            host = authority.substringAfterLast('@').lowercase(),
            path = remainder.substringBefore('?'),
            query = if (remainder.contains('?')) remainder.substringAfter('?') else ""
        )
    }

    private fun stableQuery(query: String, volatile: Set<String>): String {
        if (query.isBlank()) return ""
        return query.split('&')
            .filter { it.isNotBlank() }
            .filter { pair ->
                val name = pair.substringBefore('=').lowercase()
                name !in volatile && !name.startsWith("x-amz-") && !name.startsWith("x-oss-")
            }
            .sorted()
            .joinToString("&")
    }

    /** Key under which two sniffed URLs are considered the same video. */
    fun dedupeKey(url: String): String {
        val p = parse(url)
        val q = stableQuery(p.query, VOLATILE_PARAMS)
        return if (q.isEmpty()) "${p.host}${p.path}" else "${p.host}${p.path}?$q"
    }

    /** Key identifying the page the user is on; changes only when a different video page is shown. */
    fun pageKey(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val p = parse(url)
        val q = stableQuery(p.query, PAGE_VOLATILE_PARAMS)
        return if (q.isEmpty()) "${p.host}${p.path}" else "${p.host}${p.path}?$q"
    }

    fun isHls(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8") || path.contains(".m3u8/") ||
            lower.contains("format=m3u8") || lower.contains("type=m3u8") ||
            lower.contains("format=hls") || lower.contains("type=hls") ||
            lower.contains("ext=m3u8") || lower.contains("output=m3u8")
    }

    fun isSuspicious(url: String): Boolean {
        val lower = url.lowercase()
        return SUSPICIOUS_KEYWORDS.any { lower.contains(it) }
    }

    fun isMasterNamed(url: String): Boolean = parse(url).fileName.contains("master")

    /** Resolution hint found in the URL, e.g. "1080p" or "4K"; null when the URL does not say. */
    fun qualityOf(url: String): String? {
        val target = url.substringBefore('?')
        QUALITY_4K.find(target)?.let { return "4K" }
        QUALITY_P.find(target)?.let { return it.groupValues[1] + "p" }
        QUALITY_WH.find(target)?.let { m ->
            val w = m.groupValues[1].toInt()
            val h = m.groupValues[2].toInt()
            if (w in 160..7680 && h in 120..4320) return "${minOf(w, h)}p"
        }
        return null
    }

    fun formatOf(url: String): String {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") -> "HLS"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "MP4"
            path.endsWith(".flv") || path.endsWith(".f4v") -> "FLV"
            path.endsWith(".webm") -> "WebM"
            path.endsWith(".mkv") -> "MKV"
            path.endsWith(".mov") -> "MOV"
            path.endsWith(".avi") -> "AVI"
            path.endsWith(".m3u") -> "M3U"
            isHls(url) -> "HLS"
            else -> "视频"
        }
    }

    private fun qualityRank(q: String): Int = when (q) {
        "4K" -> 2160
        else -> q.removeSuffix("p").toIntOrNull() ?: 0
    }

    private class Node(val entry: Entry) {
        val url: String get() = entry.candidate.url
        val parsed = parse(entry.candidate.url)
        val suspicious = isSuspicious(entry.candidate.url)
        val hls = isHls(entry.candidate.url)
        val masterNamed = parsed.fileName.contains("master")
        val members = mutableListOf(this)
    }

    /** True when [child] looks like a variant playlist fetched from the master [root]. */
    private fun isVariantOf(child: Node, root: Node): Boolean {
        if (child.parsed.host != root.parsed.host) return false
        if (child.entry.pageEpoch != root.entry.pageEpoch) return false
        val rootDir = root.parsed.dir
        val childDir = child.parsed.dir
        if (childDir.startsWith("$rootDir/")) {
            return child.parsed.depth - root.parsed.depth in 1..2
        }
        return childDir == rootDir && root.masterNamed && !child.masterNamed
    }

    /**
     * Groups, labels and orders the raw entries; returns at most [limit] candidates, best first.
     * Each returned candidate carries the URL/headers of its group's representative, the latest
     * timestamp of the group and its labels (variantCount, qualities, isStale, isSuspicious).
     */
    fun rank(entries: List<Entry>, currentEpoch: Int, limit: Int): List<VideoCandidate> {
        if (entries.isEmpty() || limit <= 0) return emptyList()

        // 1. Same video sniffed again (fresh token, retry...): keep only the latest sighting.
        val latestByKey = LinkedHashMap<String, Entry>()
        for (e in entries.sortedByDescending { it.seq }) {
            latestByKey.putIfAbsent(e.dedupeKey, e)
        }
        val nodes = latestByKey.values.map { Node(it) }

        // 2. Fold HLS variant playlists into their master playlist.
        val roots = mutableListOf<Node>()
        val hlsOrdered = nodes
            .filter { it.hls && !it.suspicious }
            .sortedWith(
                compareByDescending<Node> { it.masterNamed }
                    .thenBy { it.parsed.depth }
                    .thenBy { it.entry.seq }
            )
        for (node in hlsOrdered) {
            val root = roots.firstOrNull { isVariantOf(node, it) }
            if (root != null) root.members += node else roots += node
        }
        roots += nodes.filter { !it.hls || it.suspicious }

        // 3. Build one candidate per group and order by score, then by most recent activity.
        data class Ranked(val candidate: VideoCandidate, val score: Int, val lastSeq: Long)

        val ranked = roots.map { root ->
            val isStale = root.entry.pageEpoch != currentEpoch
            val isMaster = root.masterNamed || root.members.size > 1
            val qualities = root.members
                .mapNotNull { qualityOf(it.url) }
                .distinct()
                .sortedByDescending { qualityRank(it) }
            val candidate = root.entry.candidate.copy(
                timestamp = root.members.maxOf { it.entry.candidate.timestamp },
                variantCount = root.members.size,
                qualities = qualities,
                isMasterPlaylist = isMaster,
                isSuspicious = root.suspicious,
                isStale = isStale
            )
            var score = 0
            if (isMaster) score += SCORE_MASTER
            if (root.suspicious) score += SCORE_SUSPICIOUS
            if (isStale) score += SCORE_STALE
            Ranked(candidate, score, root.members.maxOf { it.entry.seq })
        }

        return ranked
            .sortedWith(compareByDescending<Ranked> { it.score }.thenByDescending { it.lastSeq })
            .take(limit)
            .map { it.candidate }
    }

    /** One-line labels for the picker, e.g. "HLS · 主列表 · 1080p/720p（合并 3 个地址）". */
    fun describe(candidate: VideoCandidate): String {
        val parts = mutableListOf<String>()
        parts += formatOf(candidate.url)
        if (candidate.isMasterPlaylist) parts += "主列表"
        val qualities = candidate.qualities.ifEmpty { listOfNotNull(qualityOf(candidate.url)) }
        if (qualities.isNotEmpty()) parts += qualities.joinToString("/")
        var text = parts.joinToString(" · ")
        if (candidate.variantCount > 1) text += "（合并 ${candidate.variantCount} 个地址）"
        if (candidate.isSuspicious) text += " · ⚠ 疑似广告/预告"
        if (candidate.isStale) text += " · 上一页面"
        return text
    }
}
