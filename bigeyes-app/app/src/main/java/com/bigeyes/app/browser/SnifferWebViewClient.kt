package com.bigeyes.app.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bigeyes.app.model.VideoCandidate

class SnifferWebViewClient(
    private val onPageTitleChanged: ((String?) -> Unit)? = null,
    private val onPageLoadingChanged: ((Boolean) -> Unit)? = null,
    var onRendererGoneCallback: ((didCrash: Boolean) -> Unit)? = null
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
            val cookie = headers["Cookie"] ?: headers["cookie"] ?: try {
                CookieManager.getInstance().getCookie(directUrl)
            } catch (_: Throwable) {
                null
            }

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

        // Persist login cookies and session tokens immediately to disk
        try {
            CookieManager.getInstance().flush()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to flush cookies on page finish: ${e.message}")
        }

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
            val desc = try { error?.description } catch (_: Throwable) { null }
            val code = try { error?.errorCode } catch (_: Throwable) { null }
            Log.w(TAG, "Main frame load error: [$code] $desc for ${request.url}")
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            Log.w(TAG, "HTTP error for main frame: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} for ${request.url}")
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        Log.w(TAG, "SSL error encountered: ${error?.primaryError} on URL: ${error?.url}")
        try {
            // Safely proceed SSL errors to prevent white screen / block on Cloudflare/custom cert sites
            handler?.proceed()
        } catch (e: Throwable) {
            Log.e(TAG, "Error proceeding SSL handler: ${e.message}")
            super.onReceivedSslError(view, handler, error)
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val didCrash = try {
            detail?.didCrash() ?: false
        } catch (_: Throwable) {
            false
        }
        Log.e(TAG, "Chromium Render Process Gone! didCrash=$didCrash")

        // Safely detach the crashed renderer view to free memory
        try {
            (view?.parent as? ViewGroup)?.removeView(view)
            view?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "Error destroying gone WebView: ${e.message}")
        }

        // Notify UI to gracefully recover / reload without terminating the application process
        try {
            onRendererGoneCallback?.invoke(didCrash)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onRendererGoneCallback: ${e.message}")
        }

        // Returning TRUE indicates that the host application handled the situation and must NOT be killed!
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        return handleUrlOverride(view, uri)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }
        return handleUrlOverride(view, uri)
    }

    private fun handleUrlOverride(view: WebView?, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false

        // 1. Allow standard web schemes to navigate internally
        if (scheme == "http" || scheme == "https" || scheme == "blob" || scheme == "data" || scheme == "javascript" || scheme == "about") {
            return false
        }

        // 2. Safely parse Android Intent URIs (e.g. intent://...)
        if (scheme == "intent") {
            try {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank()) {
                        view?.loadUrl(fallbackUrl)
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing intent URI: ${e.message}")
            }
            return true
        }

        // 3. Block or ignore other external market/app schemes to prevent crash/popup
        Log.d(TAG, "Ignored external scheme navigation: $uri")
        return true
    }
}
