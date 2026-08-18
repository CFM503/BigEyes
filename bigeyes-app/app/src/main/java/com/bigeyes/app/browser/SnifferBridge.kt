package com.bigeyes.app.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.bigeyes.app.model.VideoCandidate

class SnifferBridge(
    private val getCurrentUserAgent: () -> String?,
    private val getCurrentCookie: (String) -> String?
) {

    companion object {
        const val JAVASCRIPT_NAME = "BigEyesSnifferBridge"
        private const val TAG = "SnifferBridge"
    }

    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    @JavascriptInterface
    fun onVideoDetected(rawUrl: String?, title: String?, referer: String?) {
        if (rawUrl.isNullOrBlank()) return

        val directUrl = VideoSnifferHelper.extractDirectVideoUrl(rawUrl)
        if (!VideoSnifferHelper.isVideoStreamUrl(directUrl)) return

        Log.d(TAG, "JS bridge sniffed candidate stream: $directUrl (raw: $rawUrl)")

        val action = Runnable {
            val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "在线视频"
            val userAgent = getCurrentUserAgent()
            val cookie = getCurrentCookie(directUrl)

            val candidate = VideoCandidate(
                url = directUrl,
                referer = referer,
                userAgent = userAgent,
                cookie = cookie,
                title = cleanTitle,
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
}
