package com.bigeyes.app.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bigeyes.app.model.SniffLogEntry
import com.bigeyes.app.model.VideoCandidate
import java.util.Collections
import java.util.LinkedList

object CandidateManager {

    private const val TAG = "CandidateManager"

    /** Entries shown in the cast picker after grouping. */
    private const val MAX_CANDIDATES = 5

    /** Raw sightings kept for grouping (a master + its variants + retries can be many URLs). */
    private const val MAX_RAW_CANDIDATES = 30
    private const val MAX_LOGS = 20

    /** Newest first. */
    private val rawEntries = LinkedList<CandidateRanker.Entry>()
    private val sniffLogs = LinkedList<SniffLogEntry>()
    private val listeners = Collections.synchronizedList(mutableListOf<(List<VideoCandidate>) -> Unit>())

    private var seqCounter = 0L

    /** Bumped on in-page (SPA) navigation so earlier candidates can be marked "上一页面". */
    private var pageEpoch = 0
    private var currentPageKey: String? = null

    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun addCandidate(candidate: VideoCandidate): Boolean {
        val entry = CandidateRanker.Entry(candidate, pageEpoch, ++seqCounter)

        // Same video seen again (possibly with a fresh token): keep only the newest sighting.
        rawEntries.removeAll { it.dedupeKey == entry.dedupeKey }
        rawEntries.addFirst(entry)
        while (rawEntries.size > MAX_RAW_CANDIDATES) {
            rawEntries.removeLast()
        }

        // Record debug log
        val logHeaders = mutableMapOf<String, String>()
        candidate.referer?.let { logHeaders["Referer"] = it }
        candidate.userAgent?.let { logHeaders["User-Agent"] = it }
        candidate.cookie?.let { logHeaders["Cookie"] = it }
        addLog(SniffLogEntry(candidate.url, logHeaders, candidate.timestamp))

        try {
            Log.i(TAG, "Added candidate: ${candidate.displayTitle} -> ${candidate.url} (raw: ${rawEntries.size})")
        } catch (_: Throwable) {}

        notifyListeners()
        return true
    }

    /** Grouped, labelled and ranked candidates, best first. */
    @Synchronized
    fun getCandidates(): List<VideoCandidate> {
        return CandidateRanker.rank(ArrayList(rawEntries), pageEpoch, MAX_CANDIDATES)
    }

    @Synchronized
    fun clear() {
        rawEntries.clear()
        notifyListeners()
    }

    /** A new document starts loading in the main frame: forget everything from the old page. */
    @Synchronized
    fun onPageStarted(url: String?) {
        rawEntries.clear()
        currentPageKey = CandidateRanker.pageKey(url).ifBlank { null }
        notifyListeners()
    }

    /** The main frame committed [url] while a real page load is in progress (redirects etc.). */
    @Synchronized
    fun onPageCommitted(url: String?) {
        CandidateRanker.pageKey(url).takeIf { it.isNotBlank() }?.let { currentPageKey = it }
    }

    /**
     * URL changed without a page load (history.pushState in single-page sites). When it now
     * points at a different video page, older candidates are kept but marked as stale.
     *
     * @return true when this was treated as a switch to another page.
     */
    @Synchronized
    fun onInPageNavigation(url: String?): Boolean {
        val key = CandidateRanker.pageKey(url)
        if (key.isBlank()) return false
        val previous = currentPageKey
        currentPageKey = key
        if (previous == null || previous == key) return false
        pageEpoch++
        try {
            Log.i(TAG, "In-page navigation to $url, marking ${rawEntries.size} earlier candidates as stale")
        } catch (_: Throwable) {}
        notifyListeners()
        return true
    }

    @Synchronized
    private fun addLog(entry: SniffLogEntry) {
        sniffLogs.addFirst(entry)
        while (sniffLogs.size > MAX_LOGS) {
            sniffLogs.removeLast()
        }
    }

    @Synchronized
    fun getSniffLogs(): List<SniffLogEntry> {
        return ArrayList(sniffLogs)
    }

    fun addListener(listener: (List<VideoCandidate>) -> Unit) {
        listeners.add(listener)
        try {
            listener(getCandidates())
        } catch (e: Throwable) {
            Log.e(TAG, "Error in addListener callback: ${e.message}", e)
        }
    }

    fun removeListener(listener: (List<VideoCandidate>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val currentList = getCandidates()
        val handler = mainHandler
        if (handler != null) {
            handler.post {
                synchronized(listeners) {
                    for (listener in listeners) {
                        try {
                            listener(currentList)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error in candidate listener: ${e.message}", e)
                        }
                    }
                }
            }
        } else {
            synchronized(listeners) {
                for (listener in listeners) {
                    try {
                        listener(currentList)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in candidate listener: ${e.message}", e)
                    }
                }
            }
        }
    }
}
