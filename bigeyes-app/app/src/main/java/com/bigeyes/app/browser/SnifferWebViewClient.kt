package com.bigeyes.app.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private val onPageUrlChanged: ((String?) -> Unit)? = null,
    var onRendererGoneCallback: ((didCrash: Boolean, failedUrl: String?) -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "SnifferWebViewClient"

        // Tier 1 Fast static asset extensions filter
        private val NON_MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "avif", "bmp",
            "css", "js", "json", "woff", "woff2", "ttf", "eot", "otf",
            "map", "html", "htm", "txt", "xml", "pdf"
        )
    }

    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    private fun isStaticNonMediaUrl(rawUrl: String): Boolean {
        val clean = rawUrl.substringBefore('?').substringBefore('#')
        val dotIdx = clean.lastIndexOf('.')
        if (dotIdx != -1 && dotIdx < clean.length - 1) {
            val ext = clean.substring(dotIdx + 1).lowercase()
            if (ext in NON_MEDIA_EXTENSIONS) {
                return true
            }
        }
        return false
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        val uri = request.url ?: return null
        val rawUrl = uri.toString()

        // Tier 1: Cheap fast filter for non-media static web assets
        if (isStaticNonMediaUrl(rawUrl)) {
            return super.shouldInterceptRequest(view, request)
        }

        // Tier 2: Deep media analysis only for prospective stream candidates
        val directUrl = VideoSnifferHelper.extractDirectVideoUrl(rawUrl)
        if (VideoSnifferHelper.isVideoStreamUrl(directUrl)) {
            Log.d(TAG, "Sniffed candidate stream: $directUrl (raw: $rawUrl)")
            val headers = request.requestHeaders ?: emptyMap()
            val referer = headers["Referer"] ?: headers["referer"] ?: view?.url
            val userAgent = headers["User-Agent"] ?: headers["user-agent"] ?: view?.settings?.userAgentString

            // Only query CookieManager when a confirmed video resource is intercepted
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
            onPageUrlChanged?.invoke(url)
        }
        onPageLoadingChanged?.invoke(true)
        injectSnifferScript(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoadingChanged?.invoke(false)
        onPageTitleChanged?.invoke(view?.title)
        if (url != null && !url.startsWith("about:") && !url.startsWith("javascript:")) {
            onPageUrlChanged?.invoke(url)
        }

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
        val currentUrl = view?.url
        val webViewHash = view?.hashCode()
        Log.e(TAG, "[WebViewRenderer] Renderer Gone! didCrash=$didCrash, webViewHashCode=$webViewHash, url=$currentUrl")

        // Notify MainActivity to handle clean teardown and fresh WebView recreation
        try {
            onRendererGoneCallback?.invoke(didCrash, currentUrl)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onRendererGoneCallback: ${e.message}")
        }

        // Returning TRUE tells Android that host application handled the situation and must NOT be killed!
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
