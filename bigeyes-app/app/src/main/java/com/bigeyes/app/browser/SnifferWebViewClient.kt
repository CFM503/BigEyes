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

    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        val uri = request.url
        val rawUrl = uri.toString()
        val directUrl = VideoSnifferHelper.extractDirectVideoUrl(rawUrl)

        if (VideoSnifferHelper.isVideoStreamUrl(directUrl)) {
            Log.d(TAG, "Sniffed candidate stream: $directUrl (raw: $rawUrl)")
            val headers = request.requestHeaders ?: emptyMap()
            val referer = headers["Referer"] ?: headers["referer"] ?: view?.url
            val userAgent = headers["User-Agent"] ?: headers["user-agent"] ?: view?.settings?.userAgentString
            val cookie = headers["Cookie"] ?: headers["cookie"] ?: CookieManager.getInstance().getCookie(directUrl)

            val action = Runnable {
                val pageTitle = view?.title ?: uri.lastPathSegment ?: "在线视频"
                val candidate = VideoCandidate(
                    url = directUrl,
                    referer = referer,
                    userAgent = userAgent,
                    cookie = cookie,
                    title = pageTitle,
                    timestamp = System.currentTimeMillis()
                )
                CandidateManager.addCandidate(candidate)
            }

            val handler = mainHandler
            if (handler != null) {
                handler.post(action)
            } else {
                action.run()
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    fun isM3U8Stream(url: String): Boolean {
        return VideoSnifferHelper.isVideoStreamUrl(url)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url != null && !url.startsWith("about:") && !url.startsWith("javascript:")) {
            CandidateManager.clear()
        }
        onPageLoadingChanged?.invoke(true)
        injectSnifferScript(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoadingChanged?.invoke(false)
        onPageTitleChanged?.invoke(view?.title)
        injectSnifferScript(view)
    }

    private fun injectSnifferScript(view: WebView?) {
        view?.post {
            try {
                view.evaluateJavascript(VideoSnifferHelper.getInjectionScript(), null)
            } catch (e: Exception) {
                Log.d(TAG, "evaluateJavascript injection error: ${e.message}")
            }
        }
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
