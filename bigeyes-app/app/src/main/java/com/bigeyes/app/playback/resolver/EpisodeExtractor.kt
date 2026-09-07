package com.bigeyes.app.playback.resolver

import android.webkit.WebView
import com.bigeyes.app.model.playback.Episode
import org.json.JSONObject
import java.net.URI

data class ExtractedPlaylist(
    val seriesId: String,
    val seriesTitle: String,
    val currentActiveIndex: Int,
    val episodes: List<Episode>,
    val nextButtonUrl: String?
)

object EpisodeExtractor {

    fun generateSeriesId(pageUrl: String): String {
        return try {
            val uri = URI(pageUrl)
            val host = uri.host ?: "unknown"
            val path = uri.path ?: ""
            val cleanPath = path.substringBeforeLast('/').ifBlank { path }
            "${host}_${cleanPath}".replace("[^a-zA-Z0-9_]".toRegex(), "_")
        } catch (_: Exception) {
            "series_${pageUrl.hashCode()}"
        }
    }

    private val EXTRACTION_SCRIPT = """
        (function() {
            var pageUrl = window.location.href;
            var docTitle = document.title || '';
            var seriesTitle = docTitle;

            var ogTitle = document.querySelector('meta[property="og:title"]');
            if (ogTitle && ogTitle.content) {
                seriesTitle = ogTitle.content.trim();
            } else {
                var h1 = document.querySelector('h1');
                if (h1 && h1.innerText) {
                    seriesTitle = h1.innerText.trim();
                } else {
                    seriesTitle = docTitle.replace(/[-_].*$/, '').trim();
                }
            }
            if (!seriesTitle) seriesTitle = '在线剧集';

            var playlistSelectors = [
                '.playlist', '.episode-list', '[class*="playlist"]', '[class*="anthology"]',
                '[class*="juji"]', '[class*="ep-list"]', '[class*="play-list"]',
                '.module-play-list', '.stui-content__playlist', '.hl-plays-list',
                '[id*="playlist"]', '[id*="anthology"]'
            ];

            var container = null;
            for (var i = 0; i < playlistSelectors.length; i++) {
                var list = document.querySelectorAll(playlistSelectors[i]);
                for (var j = 0; j < list.length; j++) {
                    var items = list[j].querySelectorAll('a, button, li');
                    if (items.length >= 2) {
                        container = list[j];
                        break;
                    }
                }
                if (container) break;
            }

            var episodes = [];
            var activeIndex = -1;

            function cleanEpNumber(text, fallbackIndex) {
                var match = text.match(/第?\s*(\d+)\s*集?/);
                if (match) {
                    var n = parseInt(match[1], 10);
                    if (!isNaN(n)) return n;
                }
                var digits = text.match(/\d+/);
                if (digits) {
                    var d = parseInt(digits[0], 10);
                    if (!isNaN(d)) return d;
                }
                return fallbackIndex + 1;
            }

            if (container) {
                var itemEls = container.querySelectorAll('a, button, li');
                var seenUrls = {};
                var epCounter = 0;

                for (var k = 0; k < itemEls.length; k++) {
                    var el = itemEls[k];
                    var tag = el.tagName.toLowerCase();
                    var href = el.getAttribute('href') || el.getAttribute('data-url') || el.getAttribute('data-href') || '';
                    if (href && !href.startsWith('http://') && !href.startsWith('https://') && !href.startsWith('javascript:')) {
                        try {
                            href = new URL(href, window.location.href).href;
                        } catch(e) {}
                    }

                    var text = (el.innerText || el.textContent || '').trim();
                    if (!text && !href) continue;

                    var isAdOrFilter = text.indexOf('线路') !== -1 || text.indexOf('播放源') !== -1 || text.length > 30;
                    if (isAdOrFilter) continue;

                    var isActive = el.classList.contains('active') || el.classList.contains('current') ||
                                   el.classList.contains('on') || el.classList.contains('selected') ||
                                   el.classList.contains('cur') || (href && href === window.location.href);

                    if (isActive && activeIndex === -1) {
                        activeIndex = epCounter;
                    }

                    var epNum = cleanEpNumber(text, epCounter);
                    var title = text || ('第 ' + epNum + ' 集');

                    episodes.push({
                        number: epNum,
                        title: title,
                        pageUrl: href || window.location.href,
                        isActive: isActive
                    });
                    epCounter++;
                }
            }

            // Next button detection
            var nextButtonUrl = null;
            var nextKeywords = ['下一集', '下集', '下一话', '后一集', 'next episode', 'next'];
            var clickables = document.querySelectorAll('a, button');
            for (var c = 0; c < clickables.length; c++) {
                var btn = clickables[c];
                var bText = (btn.innerText || btn.textContent || '').trim().toLowerCase();
                for (var nk = 0; nk < nextKeywords.length; nk++) {
                    if (bText === nextKeywords[nk] || (bText.length <= 8 && bText.indexOf(nextKeywords[nk]) !== -1)) {
                        var bHref = btn.getAttribute('href') || btn.getAttribute('data-url') || '';
                        if (bHref && !bHref.startsWith('javascript:')) {
                            try {
                                nextButtonUrl = new URL(bHref, window.location.href).href;
                            } catch(e) {
                                nextButtonUrl = bHref;
                            }
                        }
                        break;
                    }
                }
                if (nextButtonUrl) break;
            }

            return JSON.stringify({
                pageUrl: pageUrl,
                seriesTitle: seriesTitle,
                activeIndex: activeIndex,
                episodes: episodes,
                nextButtonUrl: nextButtonUrl
            });
        })();
    """.trimIndent()

    fun extractPlaylistFromPage(
        webView: WebView,
        onExtracted: (ExtractedPlaylist?) -> Unit
    ) {
        webView.evaluateJavascript(EXTRACTION_SCRIPT) { rawJson ->
            if (rawJson.isNullOrBlank() || rawJson == "null" || rawJson == "\"\"") {
                onExtracted(null)
                return@evaluateJavascript
            }

            try {
                var jsonStr = rawJson.trim()
                if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"") && jsonStr.length >= 2) {
                    jsonStr = jsonStr.substring(1, jsonStr.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                }

                val obj = JSONObject(jsonStr)
                val pageUrl = obj.optString("pageUrl", webView.url ?: "")
                val seriesTitle = obj.optString("seriesTitle", "在线剧集")
                val activeIdx = obj.optInt("activeIndex", 0)
                val nextBtnUrl = obj.optString("nextButtonUrl").takeIf { it.isNotBlank() }

                val seriesId = generateSeriesId(pageUrl)
                val epArray = obj.optJSONArray("episodes")

                val episodes = mutableListOf<Episode>()
                if (epArray != null && epArray.length() > 0) {
                    for (i in 0 until epArray.length()) {
                        val epObj = epArray.getJSONObject(i)
                        val num = epObj.optInt("number", i + 1)
                        val title = epObj.optString("title", "第 ${num} 集")
                        val epUrl = epObj.optString("pageUrl").takeIf { it.isNotBlank() } ?: pageUrl

                        episodes.add(
                            Episode(
                                id = "${seriesId}_ep_${num}",
                                seriesId = seriesId,
                                seriesTitle = seriesTitle,
                                seasonNumber = 1,
                                episodeNumber = num,
                                episodeTitle = title,
                                episodeIndex = i,
                                pageUrl = epUrl,
                                playUrl = null
                            )
                        )
                    }
                }

                val result = ExtractedPlaylist(
                    seriesId = seriesId,
                    seriesTitle = seriesTitle,
                    currentActiveIndex = if (activeIdx >= 0) activeIdx else 0,
                    episodes = episodes,
                    nextButtonUrl = nextBtnUrl
                )
                onExtracted(result)
            } catch (e: Exception) {
                onExtracted(null)
            }
        }
    }
}
