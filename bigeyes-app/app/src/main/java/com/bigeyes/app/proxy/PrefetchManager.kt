package com.bigeyes.app.proxy

import android.util.Log
import com.bigeyes.app.model.SegmentItem
import com.bigeyes.app.model.StreamSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class PrefetchManager(
    private val fetcher: StreamFetcher,
    private val cache: DiskLRUCache,
    private val concurrency: Int = 2,
    private val window: Int = 4
) {
    companion object {
        private const val TAG = "PrefetchManager"
    }

    private val semaphore = Semaphore(concurrency)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTasks = ConcurrentHashMap<String, MutableSet<Int>>()
    private val streamJobs = ConcurrentHashMap<String, MutableList<Job>>()

    private fun getCacheKey(streamId: String, segIndex: Int): String {
        return "${streamId}_seg_${segIndex}.ts"
    }

    fun triggerPrefetch(session: StreamSession, currentSegIndex: Int) {
        val streamId = session.streamId
        val totalSegs = session.segments.size
        if (totalSegs == 0) return

        val startIdx = currentSegIndex + 1
        val endIdx = minOf(startIdx + window, totalSegs)

        for (idx in startIdx until endIdx) {
            val cacheKey = getCacheKey(streamId, idx)
            if (cache.has(cacheKey)) continue

            val activeSet = activeTasks.computeIfAbsent(streamId) { Collections.synchronizedSet(mutableSetOf()) }
            if (!activeSet.add(idx)) continue

            val seg = session.segments[idx]
            val job = scope.launch {
                try {
                    semaphore.withPermit {
                        if (cache.has(cacheKey)) return@withPermit
                        Log.d(TAG, "[Prefetch] Fetching segment $idx for $streamId: ${seg.uri}")
                        val data = fetcher.fetchBytes(
                            url = seg.uri,
                            referer = session.referer,
                            userAgent = session.userAgent,
                            cookie = session.cookie
                        )
                        cache.put(cacheKey, data)
                        Log.d(TAG, "[Prefetch] Cached segment $idx (${data.size} bytes)")
                    }
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    Log.w(TAG, "[Prefetch] Error prefetching segment $idx: ${e.message}")
                } finally {
                    activeSet.remove(idx)
                }
            }

            val jobsList = streamJobs.computeIfAbsent(streamId) { Collections.synchronizedList(mutableListOf()) }
            jobsList.add(job)
            job.invokeOnCompletion { jobsList.remove(job) }
        }
    }

    fun cancelStream(streamId: String) {
        activeTasks.remove(streamId)
        streamJobs.remove(streamId)?.forEach { it.cancel() }
    }

    fun release() {
        scope.cancel()
        activeTasks.clear()
        streamJobs.clear()
    }
}
