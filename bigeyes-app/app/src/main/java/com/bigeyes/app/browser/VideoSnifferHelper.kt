package com.bigeyes.app.browser

import android.util.Log
import android.webkit.WebView
import java.net.URLDecoder

object VideoSnifferHelper {

    private const val TAG = "VideoSnifferHelper"

    // Playable standalone video stream/container extensions
    private val PLAYABLE_VIDEO_EXTENSIONS = listOf(
        ".m3u8", ".mp4", ".flv", ".f4v", ".webm",
        ".m3u", ".mov", ".mkv", ".avi"
    )

    // Sub-segment chunks to strictly ignore from candidate list
    private val SEGMENT_EXTENSIONS = listOf(
        ".ts", ".m4s", ".key", ".vtt", ".srt", ".ass"
    )

    // Non-media static asset and page extensions to ignore immediately
    private val IGNORED_EXTENSIONS = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".webp",
        ".svg", ".ico", ".woff", ".woff2", ".ttf", ".eot", ".map",
        ".json", ".xml", ".html", ".htm", ".php", ".jsp", ".asp", ".aspx"
    )

    // Ad keyword patterns to eliminate advertisement video streams
    private val AD_KEYWORDS = listOf(
        "ad.m3u8", "adv.m3u8", "guanggao", "/ad/", "/ads/", "/adv/", "/advert/",
        "adservice", "adsystem", "union", "dsp.", "creative", "popunder",
        "commercial", "googleads", "doubleclick", "tanx.com", "umeng.com"
    )

    fun isLikelyAdUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank()) return false
        val lower = rawUrl.lowercase()
        for (kw in AD_KEYWORDS) {
            if (lower.contains(kw)) {
                return true
            }
        }
        return false
    }

    fun isVideoStreamUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank()) return false
        val lower = rawUrl.lowercase()

        // 1. Filter out obvious non-video schemas
        if (lower.startsWith("blob:") || lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("about:")) {
            return false
        }

        // 2. Filter out known advertisement stream URLs
        if (isLikelyAdUrl(lower)) {
            return false
        }

        val pathOnly = lower.substringBefore('?').substringBefore('#')

        // 3. Ignore non-media static assets
        for (ign in IGNORED_EXTENSIONS) {
            if (pathOnly.endsWith(ign)) {
                return false
            }
        }

        // 4. Strictly ignore individual segment chunks (TS/M4S/Key)
        for (seg in SEGMENT_EXTENSIONS) {
            if (pathOnly.endsWith(seg) || lower.contains("$seg?") || lower.contains("$seg#") || lower.contains("$seg&")) {
                return false
            }
        }

        // 5. Match playable video extensions
        for (ext in PLAYABLE_VIDEO_EXTENSIONS) {
            if (pathOnly.endsWith(ext) || lower.contains("$ext?") || lower.contains("$ext#") || lower.contains("$ext&") || lower.contains("$ext/")) {
                return true
            }
        }

        // 5. Explicit streaming query parameters or manifest signatures
        if (lower.contains("format=m3u8") || lower.contains("type=m3u8") || lower.contains("format=hls") ||
            lower.contains("ext=m3u8") || lower.contains("output=m3u8") || lower.contains("type=hls") ||
            lower.contains(".m3u8") || lower.contains("manifest.m3u8") || lower.contains("master.m3u8") ||
            (lower.contains("/hls/") && lower.contains(".m3u8"))) {
            return true
        }

        // 6. URL-encoded video URLs inside query parameters (e.g. ?url=https%3A%2F%2F...m3u8)
        if (lower.contains("url=http") || lower.contains("v=http") || lower.contains("src=http") || lower.contains("link=http")) {
            val decoded = try {
                URLDecoder.decode(rawUrl, "UTF-8").lowercase()
            } catch (_: Exception) {
                ""
            }
            for (ext in PLAYABLE_VIDEO_EXTENSIONS) {
                if (decoded.contains(ext)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Extract inner video URL if nested inside player parameters, e.g. player.html?url=https://.../index.m3u8
     */
    fun extractDirectVideoUrl(rawUrl: String): String {
        try {
            val queryIndex = rawUrl.indexOf('?')
            if (queryIndex != -1 && queryIndex < rawUrl.length - 1) {
                val queryString = rawUrl.substring(queryIndex + 1).substringBefore('#')
                val pairs = queryString.split('&')
                val targetKeys = setOf("url", "v", "src", "link", "video", "play", "file")
                for (pair in pairs) {
                    val eqIndex = pair.indexOf('=')
                    if (eqIndex != -1) {
                        val key = pair.substring(0, eqIndex).lowercase()
                        if (key in targetKeys) {
                            val rawValue = pair.substring(eqIndex + 1)
                            val decoded = try {
                                URLDecoder.decode(rawValue, "UTF-8")
                            } catch (_: Exception) {
                                rawValue
                            }
                            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                                if (isVideoStreamUrl(decoded)) {
                                    return decoded
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return rawUrl
    }

    /**
     * JavaScript code to hook DOM Video Elements, Hls.js, Artplayer, DPlayer, and network requests.
     */
    fun getInjectionScript(): String {
        return """
            (function() {
                if (window.__bigeyes_sniffer_installed__) return;
                window.__bigeyes_sniffer_installed__ = true;

                function reportVideo(url, title) {
                    if (!url || typeof url !== 'string') return;
                    if (url.startsWith('blob:') || url.startsWith('data:') || url.startsWith('javascript:')) return;
                    if (url.startsWith('//')) url = window.location.protocol + url;
                    if (!url.startsWith('http://') && !url.startsWith('https://')) return;

                    var cleanTitle = title || document.title || 'Video Stream';
                    var ref = window.location.href;

                    if (window.BigEyesSnifferBridge && window.BigEyesSnifferBridge.onVideoDetected) {
                        window.BigEyesSnifferBridge.onVideoDetected(url, cleanTitle, ref);
                    }
                }

                function isVideoUrl(u) {
                    if (!u || typeof u !== 'string') return false;
                    var l = u.toLowerCase();
                    return l.indexOf('.m3u8') !== -1 || l.indexOf('.mp4') !== -1 ||
                           l.indexOf('.flv') !== -1 || l.indexOf('/hls/') !== -1 ||
                           l.indexOf('playlist') !== -1 || l.indexOf('url=http') !== -1;
                }

                // 1. Hook HTMLMediaElement & HTMLVideoElement
                try {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        if (this.src && isVideoUrl(this.src)) {
                            reportVideo(this.src, document.title);
                        } else if (this.currentSrc && isVideoUrl(this.currentSrc)) {
                            reportVideo(this.currentSrc, document.title);
                        }
                        return origPlay.apply(this, arguments);
                    };

                    var origSrcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                    if (origSrcDesc && origSrcDesc.set) {
                        var origSrcSet = origSrcDesc.set;
                        Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                            set: function(val) {
                                if (isVideoUrl(val)) {
                                    reportVideo(val, document.title);
                                }
                                return origSrcSet.apply(this, arguments);
                            },
                            get: origSrcDesc.get,
                            configurable: true
                        });
                    }
                } catch(e) {}

                // 2. Hook Hls.js loadSource
                try {
                    if (window.Hls && window.Hls.prototype && window.Hls.prototype.loadSource) {
                        var origLoad = window.Hls.prototype.loadSource;
                        window.Hls.prototype.loadSource = function(url) {
                            reportVideo(url, document.title);
                            return origLoad.apply(this, arguments);
                        };
                    }
                } catch(e) {}

                // 3. Hook window.fetch
                try {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        if (isVideoUrl(url)) {
                            reportVideo(url, document.title);
                        }
                        return origFetch.apply(this, arguments);
                    };
                } catch(e) {}

                // 4. Hook XMLHttpRequest
                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        if (isVideoUrl(url)) {
                            reportVideo(url, document.title);
                        }
                        return origOpen.apply(this, arguments);
                    };
                } catch(e) {}

                // 5. Periodic & MutationObserver Scan
                function scanNow() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.src && isVideoUrl(v.src)) reportVideo(v.src, document.title);
                        if (v.currentSrc && isVideoUrl(v.currentSrc)) reportVideo(v.currentSrc, document.title);
                        var sources = v.querySelectorAll('source');
                        for (var j = 0; j < sources.length; j++) {
                            if (sources[j].src && isVideoUrl(sources[j].src)) reportVideo(sources[j].src, document.title);
                        }
                    }

                    if (window.art && window.art.url && isVideoUrl(window.art.url)) reportVideo(window.art.url, document.title);
                    if (window.artplayer && window.artplayer.url && isVideoUrl(window.artplayer.url)) reportVideo(window.artplayer.url, document.title);
                    if (window.dp && window.dp.video && window.dp.video.src && isVideoUrl(window.dp.video.src)) reportVideo(window.dp.video.src, document.title);
                    if (window.hls && window.hls.url && isVideoUrl(window.hls.url)) reportVideo(window.hls.url, document.title);
                    if (window.player && window.player.src && isVideoUrl(window.player.src)) reportVideo(window.player.src, document.title);
                }

                scanNow();
                setInterval(scanNow, 1500);

                var observer = new MutationObserver(function() {
                    scanNow();
                });
                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
    }

    /**
     * Active scanner invoked on demand (e.g. when user clicks "投屏" button).
     */
    fun scanVideoInPage(webView: WebView, callback: (List<String>) -> Unit) {
        val script = """
            (function() {
                var list = [];
                function add(u) {
                    if (!u || typeof u !== 'string') return;
                    if (u.startsWith('blob:') || u.startsWith('data:') || u.startsWith('javascript:')) return;
                    if (u.startsWith('//')) u = window.location.protocol + u;
                    if (!u.startsWith('http://') && !u.startsWith('https://')) return;
                    if (list.indexOf(u) === -1) list.push(u);
                }

                // Scan all videos
                var videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    if (v.src) add(v.src);
                    if (v.currentSrc) add(v.currentSrc);
                    v.querySelectorAll('source').forEach(function(s) { if (s.src) add(s.src); });
                });

                // Scan JS player instances
                if (window.art && window.art.url) add(window.art.url);
                if (window.artplayer && window.artplayer.url) add(window.artplayer.url);
                if (window.dp && window.dp.video && window.dp.video.src) add(window.dp.video.src);
                if (window.hls && window.hls.url) add(window.hls.url);
                if (window.player && window.player.src) add(window.player.src);

                // Scan iframes
                var iframes = document.querySelectorAll('iframe');
                iframes.forEach(function(f) {
                    if (f.src) add(f.src);
                });

                // Scan HTML text for m3u8 and mp4 patterns
                var html = document.documentElement.innerHTML;
                var regex = /https?:\/\/[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*/gi;
                var matches = html.match(regex);
                if (matches) {
                    matches.forEach(function(m) { add(m); });
                }

                return JSON.stringify(list);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { jsonResult ->
            val resultList = mutableListOf<String>()
            try {
                if (!jsonResult.isNullOrBlank() && jsonResult != "null" && jsonResult != "\"[]\"") {
                    var raw = jsonResult.trim()
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                        raw = raw.substring(1, raw.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    }
                    if (raw.startsWith("[") && raw.endsWith("]")) {
                        val regex = Regex("\"([^\"]+)\"")
                        regex.findAll(raw).forEach { match ->
                            val item = match.groupValues[1]
                            if (isVideoStreamUrl(item)) {
                                resultList.add(extractDirectVideoUrl(item))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            callback(resultList.distinct())
        }
    }

    /**
     * Attempts to find and trigger the next episode button/link on the current web page,
     * or computes and navigates to the next episode URL.
     */
    fun triggerNextEpisode(webView: WebView, callback: ((Boolean) -> Unit)? = null) {
        val script = """
            (function() {
                // 1. Try finding explicit "Next Episode" buttons or links
                var nextKeywords = ['下一集', '下集', '下一话', '下一期', '后一集', 'next'];
                var clickableElements = document.querySelectorAll('a, button, div, span, li');
                for (var i = 0; i < clickableElements.length; i++) {
                    var el = clickableElements[i];
                    var text = (el.innerText || el.textContent || '').trim().toLowerCase();
                    for (var k = 0; k < nextKeywords.length; k++) {
                        if (text === nextKeywords[k] || (text.length <= 8 && text.indexOf(nextKeywords[k]) !== -1)) {
                            el.click();
                            return JSON.stringify({ success: true, method: 'keyword_click', text: text });
                        }
                    }
                }

                // 2. Try finding the active/current episode element and click its next sibling
                var activeSelectors = ['.active', '.current', '.on', '.selected', '.cur'];
                for (var s = 0; s < activeSelectors.length; s++) {
                    var activeEl = document.querySelector(activeSelectors[s]);
                    if (activeEl) {
                        var nextSibling = activeEl.nextElementSibling;
                        if (nextSibling) {
                            var clickTarget = nextSibling.querySelector('a, button') || nextSibling;
                            clickTarget.click();
                            return JSON.stringify({ success: true, method: 'sibling_click' });
                        } else if (activeEl.parentElement && activeEl.parentElement.nextElementSibling) {
                            var parentNext = activeEl.parentElement.nextElementSibling;
                            var clickTarget = parentNext.querySelector('a, button') || parentNext;
                            clickTarget.click();
                            return JSON.stringify({ success: true, method: 'parent_sibling_click' });
                        }
                    }
                }

                // 3. Try URL pattern replacement (e.g. /play/123-1-1.html -> /play/123-1-2.html or ?ep=1 -> ?ep=2)
                var href = window.location.href;
                var patterns = [
                    /([-_/])(\d+)(\.html?)/i,
                    /([-_/])(\d+)(\/|$)/i,
                    /([?&](?:ep|episode|p|index|num)=)(\d+)/i
                ];

                for (var p = 0; p < patterns.length; p++) {
                    var match = href.match(patterns[p]);
                    if (match) {
                        var prefix = match[1];
                        var num = parseInt(match[2], 10);
                        var suffix = match[3] || '';
                        if (!isNaN(num)) {
                            var nextNum = num + 1;
                            var nextUrl = href.replace(patterns[p], prefix + nextNum + suffix);
                            if (nextUrl !== href) {
                                window.location.href = nextUrl;
                                return JSON.stringify({ success: true, method: 'url_increment', url: nextUrl });
                            }
                        }
                    }
                }

                return JSON.stringify({ success: false });
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { jsonResult ->
            val success = jsonResult != null && jsonResult.contains("\"success\":true")
            Log.i("VideoSnifferHelper", "Trigger next episode result: $jsonResult (success=$success)")
            callback?.invoke(success)
        }
    }
}
