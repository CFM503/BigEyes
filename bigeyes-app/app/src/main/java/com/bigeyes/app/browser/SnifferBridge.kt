package com.bigeyes.app.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.bigeyes.app.model.VideoCandidate

class SnifferBridge(
    private val getCurrentUserAgent: () -> String?,
    private val getCurrentCookie: (String) -> String?,
    var onPlaybackStateListener: ((Boolean) -> Unit)? = null
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
        try {
            if (rawUrl.isNullOrBlank()) return

            val directUrl = VideoSnifferHelper.extractDirectVideoUrl(rawUrl)
            if (!VideoSnifferHelper.isVideoStreamUrl(directUrl)) return

            Log.d(TAG, "JS bridge sniffed candidate stream: $directUrl (raw: $rawUrl)")

            val action = Runnable {
                try {
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
                } catch (e: Throwable) {
                    Log.e(TAG, "Error adding candidate in bridge: ${e.message}", e)
                }
            }

            val handler = mainHandler
            if (handler != null) {
                handler.post(action)
            } else {
                action.run()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onVideoDetected: ${e.message}", e)
        }
    }

    @JavascriptInterface
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        try {
            val action = Runnable {
                try {
                    onPlaybackStateListener?.invoke(isPlaying)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error in playbackStateListener: ${e.message}", e)
                }
            }
            val handler = mainHandler
            if (handler != null) {
                handler.post(action)
            } else {
                action.run()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onPlaybackStateChanged: ${e.message}", e)
        }
    }
}
