package com.bigeyes.app.proxy

import com.bigeyes.app.model.KeyItem
import com.bigeyes.app.model.SegmentItem
import com.bigeyes.app.model.VariantItem
import java.net.URI
import java.util.regex.Pattern

object M3U8Parser {

    private val KEY_URI_PATTERN = Pattern.compile("URI=([\"'])(.*?)\\1")
    private val BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)")
    private val RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+x\\d+)")
    private val CODECS_PATTERN = Pattern.compile("CODECS=([\"'])(.*?)\\1")
    private val EXTINF_PATTERN = Pattern.compile("#EXTINF:([0-9.]+)(?:,(.*))?")
    private val KEY_METHOD_PATTERN = Pattern.compile("METHOD=([A-Za-z0-9-]+)")
    private val KEY_IV_PATTERN = Pattern.compile("IV=(0x[0-9A-Fa-f]+)")

    fun isMasterPlaylist(content: String): Boolean {
        return content.contains("#EXT-X-STREAM-INF")
    }

    fun resolveUri(baseUrl: String, relativeUri: String): String {
        return try {
            val base = URI(baseUrl)
            base.resolve(relativeUri.trim()).toString()
        } catch (_: Exception) {
            if (relativeUri.startsWith("http://") || relativeUri.startsWith("https://")) {
                relativeUri
            } else if (relativeUri.startsWith("//")) {
                "https:$relativeUri"
            } else {
                val lastSlash = baseUrl.lastIndexOf('/')
                if (lastSlash >= 0) {
                    baseUrl.substring(0, lastSlash + 1) + relativeUri.trimStart('/')
                } else {
                    relativeUri
                }
            }
        }
    }

    fun parseMasterPlaylist(content: String, baseUrl: String): List<VariantItem> {
        val variants = mutableListOf<VariantItem>()
        val lines = content.lines()
        var currentBandwidth = 0
        var currentResolution: String? = null
        var currentCodecs: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                val bwMatcher = BANDWIDTH_PATTERN.matcher(trimmed)
                currentBandwidth = if (bwMatcher.find()) bwMatcher.group(1)?.toIntOrNull() ?: 0 else 0

                val resMatcher = RESOLUTION_PATTERN.matcher(trimmed)
                currentResolution = if (resMatcher.find()) resMatcher.group(1) else null

                val codecsMatcher = CODECS_PATTERN.matcher(trimmed)
                currentCodecs = if (codecsMatcher.find()) codecsMatcher.group(2) else null
            } else if (!trimmed.startsWith("#")) {
                val absUri = resolveUri(baseUrl, trimmed)
                variants.add(
                    VariantItem(
                        index = variants.size,
                        bandwidth = currentBandwidth,
                        resolution = currentResolution,
                        codecs = currentCodecs,
                        uri = absUri
                    )
                )
                currentBandwidth = 0
                currentResolution = null
                currentCodecs = null
            }
        }

        // Sort descending by bandwidth
        variants.sortByDescending { it.bandwidth }
        return variants.mapIndexed { idx, item -> item.copy(index = idx) }
    }

    fun selectDefaultVariant(variants: List<VariantItem>): VariantItem {
        if (variants.isEmpty()) throw IllegalArgumentException("Variants list is empty")
        if (variants.size == 1) return variants[0]
        // Prefer medium-high variant (~1080p or index 1)
        return variants[minOf(1, variants.size - 1)]
    }

    fun rewriteMediaPlaylist(
        content: String,
        baseUrl: String,
        streamId: String,
        serverBaseUrl: String
    ): Triple<String, List<SegmentItem>, List<KeyItem>> {
        val segments = mutableListOf<SegmentItem>()
        val keys = mutableListOf<KeyItem>()
        val keyUrlMap = mutableMapOf<String, Int>()

        val lines = content.lines()
        val rewrittenLines = mutableListOf<String>()

        var currentKeyIndex: Int? = null
        var currentDuration = 0.0f
        var currentTitle: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXT-X-KEY")) {
                val uriMatcher = KEY_URI_PATTERN.matcher(trimmed)
                val methodMatcher = KEY_METHOD_PATTERN.matcher(trimmed)
                val ivMatcher = KEY_IV_PATTERN.matcher(trimmed)

                val method = if (methodMatcher.find()) methodMatcher.group(1) ?: "AES-128" else "AES-128"
                val iv = if (ivMatcher.find()) ivMatcher.group(1) else null

                if (uriMatcher.find()) {
                    val rawUri = uriMatcher.group(2) ?: ""
                    val absKeyUri = resolveUri(baseUrl, rawUri)

                    val keyIdx = keyUrlMap.getOrPut(absKeyUri) {
                        val newIdx = keys.size
                        keys.add(
                            KeyItem(
                                index = newIdx,
                                method = method,
                                uri = absKeyUri,
                                iv = iv
                            )
                        )
                        newIdx
                    }
                    currentKeyIndex = keyIdx

                    val proxyKeyUrl = "$serverBaseUrl/stream/$streamId/key/$keyIdx.key"
                    val rewrittenKeyLine = uriMatcher.replaceAll("URI=\"$proxyKeyUrl\"")
                    rewrittenLines.add(rewrittenKeyLine)
                } else {
                    rewrittenLines.add(line)
                }
            } else if (trimmed.startsWith("#EXTINF")) {
                val infMatcher = EXTINF_PATTERN.matcher(trimmed)
                if (infMatcher.find()) {
                    currentDuration = infMatcher.group(1)?.toFloatOrNull() ?: 0.0f
                    currentTitle = infMatcher.group(2)
                }
                rewrittenLines.add(line)
            } else if (trimmed.startsWith("#")) {
                rewrittenLines.add(line)
            } else {
                // Segment URL line
                val segIdx = segments.size
                val absSegUri = resolveUri(baseUrl, trimmed)
                segments.add(
                    SegmentItem(
                        index = segIdx,
                        uri = absSegUri,
                        duration = currentDuration,
                        title = currentTitle,
                        keyIndex = currentKeyIndex
                    )
                )

                val proxySegUrl = "$serverBaseUrl/stream/$streamId/seg/$segIdx.ts"
                rewrittenLines.add(proxySegUrl)
            }
        }

        if (!rewrittenLines.any { it.trim().startsWith("#EXTM3U", ignoreCase = true) }) {
            rewrittenLines.add(0, "#EXTM3U")
        }

        return Triple(rewrittenLines.joinToString("\n") + "\n", segments, keys)
    }
}
