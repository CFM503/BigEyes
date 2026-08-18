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
    private const val MAX_CANDIDATES = 5
    private const val MAX_LOGS = 20

    private val candidates = LinkedList<VideoCandidate>()
    private val sniffLogs = LinkedList<SniffLogEntry>()
    private val listeners = Collections.synchronizedList(mutableListOf<(List<VideoCandidate>) -> Unit>())
    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun addCandidate(candidate: VideoCandidate): Boolean {
        // De-duplicate by URL
        val existingIndex = candidates.indexOfFirst { it.url == candidate.url }
        if (existingIndex >= 0) {
            // Update timestamp and move to front
            candidates.removeAt(existingIndex)
        }

        candidates.addFirst(candidate)
        while (candidates.size > MAX_CANDIDATES) {
            candidates.removeLast()
        }

        // Record debug log
        val logHeaders = mutableMapOf<String, String>()
        candidate.referer?.let { logHeaders["Referer"] = it }
        candidate.userAgent?.let { logHeaders["User-Agent"] = it }
        candidate.cookie?.let { logHeaders["Cookie"] = it }
        addLog(SniffLogEntry(candidate.url, logHeaders, candidate.timestamp))

        try {
            Log.i(TAG, "Added candidate: ${candidate.displayTitle} -> ${candidate.url} (Total: ${candidates.size})")
        } catch (_: Throwable) {}

        notifyListeners()
        return true
    }

    @Synchronized
    fun getCandidates(): List<VideoCandidate> {
        return ArrayList(candidates)
    }

    @Synchronized
    fun clear() {
        candidates.clear()
        notifyListeners()
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
        listener(getCandidates())
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
                        listener(currentList)
                    }
                }
            }
        } else {
            synchronized(listeners) {
                for (listener in listeners) {
                    listener(currentList)
                }
            }
        }
    }
}
