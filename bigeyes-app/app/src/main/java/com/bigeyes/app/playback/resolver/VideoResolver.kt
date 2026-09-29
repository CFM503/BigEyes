package com.bigeyes.app.playback.resolver

import android.webkit.CookieManager
import android.webkit.WebView
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.browser.VideoSnifferHelper
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.model.playback.Episode
import com.bigeyes.app.model.playback.PlaybackItem
import kotlinx.coroutines.*

class VideoResolver(
    private val webViewProvider: () -> WebView?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val RESOLVE_TIMEOUT_MS = 15000L
    }

    private var activeResolveJob: Job? = null

    suspend fun resolveEpisodeStream(
        episode: Episode,
        totalEpisodes: Int = 1
    ): Result<PlaybackItem> = withContext(Dispatchers.Main) {
        // 1. If episode already has a fresh playable stream URL, reuse it directly
        if (episode.hasValidPlayUrl()) {
            val headers = episode.playHeaders.ifEmpty {
                mapOf(
                    "Referer" to (episode.pageUrl ?: ""),
                    "User-Agent" to (webViewProvider()?.settings?.userAgentString ?: "")
                )
            }
            return@withContext Result.success(
                PlaybackItem(
                    episode = episode,
                    playUrl = episode.playUrl!!,
                    headers = headers,
                    source = episode.sourceId,
                    index = episode.episodeIndex,
                    totalCount = totalEpisodes
                )
            )
        }

        // 2. Otherwise, navigate WebView to the episode's pageUrl to trigger fresh sniffing
        val wv = webViewProvider() ?: return@withContext Result.failure(
            IllegalStateException("WebView is not available for video resolution")
        )

        activeResolveJob?.cancel()

        val targetPageUrl = episode.pageUrl ?: wv.url ?: return@withContext Result.failure(
            IllegalArgumentException("No target webpage URL specified for episode")
        )

        // Track the in-flight resolve so [cancelActiveResolve] can actually abort it.
        val thisJob = coroutineContext[Job]
        activeResolveJob = thisJob

        try {
            val resolvedItem = withTimeout(RESOLVE_TIMEOUT_MS) {
                val candidateDeferred = CompletableDeferred<VideoCandidate>()

                val candidateListener: (List<VideoCandidate>) -> Unit = { candidates ->
                    val latest = candidates.firstOrNull()
                    if (latest != null && !candidateDeferred.isCompleted) {
                        candidateDeferred.complete(latest)
                    }
                }

                CandidateManager.addListener(candidateListener)

                try {
                    CandidateManager.clear()
                    if (wv.url != targetPageUrl) {
                        wv.loadUrl(targetPageUrl)
                    } else {
                        VideoSnifferHelper.scanVideoInPage(wv) { scannedUrls ->
                            val first = scannedUrls.firstOrNull()
                            if (first != null && !candidateDeferred.isCompleted) {
                                val userAgent = wv.settings.userAgentString
                                val cookie = CookieManager.getInstance().getCookie(first)
                                candidateDeferred.complete(
                                    VideoCandidate(
                                        url = first,
                                        referer = targetPageUrl,
                                        userAgent = userAgent,
                                        cookie = cookie,
                                        title = episode.displayTitle
                                    )
                                )
                            }
                        }
                    }

                    val candidate = candidateDeferred.await()
                    val headers = mutableMapOf<String, String>()
                    candidate.referer?.let { headers["Referer"] = it }
                    candidate.userAgent?.let { headers["User-Agent"] = it }
                    candidate.cookie?.let { headers["Cookie"] = it }

                    val updatedEpisode = episode.copy(
                        playUrl = candidate.url,
                        playHeaders = headers,
                        resolvedAt = System.currentTimeMillis()
                    )

                    PlaybackItem(
                        episode = updatedEpisode,
                        playUrl = candidate.url,
                        headers = headers,
                        source = episode.sourceId,
                        index = episode.episodeIndex,
                        totalCount = totalEpisodes
                    )
                } finally {
                    CandidateManager.removeListener(candidateListener)
                }
            }
            Result.success(resolvedItem)
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("下一集加载超时，请检查网络或网站数据源"))
        } catch (e: Throwable) {
            Result.failure(Exception("下一集解析失败: ${e.message}"))
        } finally {
            if (activeResolveJob === thisJob) {
                activeResolveJob = null
            }
        }
    }

    fun cancelActiveResolve() {
        activeResolveJob?.cancel()
        activeResolveJob = null
    }
}
