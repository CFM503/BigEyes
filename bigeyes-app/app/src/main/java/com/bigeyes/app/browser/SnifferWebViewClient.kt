package com.bigeyes.app.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bigeyes.app.model.VideoCandidate

class SnifferWebViewClient(
    private val onPageTitleChanged: ((String?) -> Unit)? = null,
    private val onPageLoadingChanged: ((Boolean) -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "SnifferWebViewClient"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        val uri = request.url
        val urlString = uri.toString()

        if (isM3U8Stream(urlString)) {
            Log.d(TAG, "Sniffed candidate stream: $urlString")
            val headers = request.requestHeaders ?: emptyMap()
            val referer = headers["Referer"] ?: headers["referer"] ?: view?.url
            val userAgent = headers["User-Agent"] ?: headers["user-agent"] ?: view?.settings?.userAgentString
            val cookie = headers["Cookie"] ?: headers["cookie"] ?: CookieManager.getInstance().getCookie(urlString)

            mainHandler.post {
                val pageTitle = view?.title ?: uri.lastPathSegment
                val candidate = VideoCandidate(
                    url = urlString,
                    referer = referer,
                    userAgent = userAgent,
                    cookie = cookie,
                    title = pageTitle,
                    timestamp = System.currentTimeMillis()
                )
                CandidateManager.addCandidate(candidate)
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    fun isM3U8Stream(url: String): Boolean {
        val lower = url.lowercase()
        // Standard .m3u8 in path or query
        if (lower.contains(".m3u8")) {
            return true
        }
        // Common HLS streaming patterns
        if (lower.contains("/hls/") && (lower.endsWith(".m3u8") || lower.contains("playlist"))) {
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageLoadingChanged?.invoke(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoadingChanged?.invoke(false)
        onPageTitleChanged?.invoke(view?.title)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onPageLoadingChanged?.invoke(false)
            Log.w(TAG, "Main frame load error: ${error?.description}")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false

        // Allow web schemes to navigate internally
        if (scheme == "http" || scheme == "https" || scheme == "blob" || scheme == "data" || scheme == "javascript" || scheme == "about") {
            return false
        }

        // Block or ignore malicious third-party market/app intents from popup ads
        Log.d(TAG, "Blocked external scheme navigation: $uri")
        return true
    }
}
