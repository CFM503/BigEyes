package com.bigeyes.app.playback.queue

import com.bigeyes.app.model.playback.Episode
import java.util.Collections

class EpisodeQueue {

    private val _items = mutableListOf<Episode>()
    private val listeners = Collections.synchronizedList(mutableListOf<(EpisodeQueue) -> Unit>())

    val items: List<Episode>
        get() = synchronized(this) { _items.toList() }

    var currentIndex: Int = 0
        private set

    val size: Int
        get() = synchronized(this) { _items.size }

    val isEmpty: Boolean
        get() = synchronized(this) { _items.isEmpty() }

    val currentEpisode: Episode?
        get() = synchronized(this) { _items.getOrNull(currentIndex) }

    val nextEpisode: Episode?
        get() = synchronized(this) {
            if (hasNext) _items.getOrNull(currentIndex + 1) else null
        }

    val previousEpisode: Episode?
        get() = synchronized(this) {
            if (hasPrevious) _items.getOrNull(currentIndex - 1) else null
        }

    val hasNext: Boolean
        get() = synchronized(this) {
            _items.isNotEmpty() && currentIndex + 1 < _items.size
        }

    val hasPrevious: Boolean
        get() = synchronized(this) {
            _items.isNotEmpty() && currentIndex > 0
        }

    val isLast: Boolean
        get() = synchronized(this) {
            _items.isNotEmpty() && currentIndex == _items.size - 1
        }

    val isFirst: Boolean
        get() = synchronized(this) {
            _items.isNotEmpty() && currentIndex == 0
        }

    @Synchronized
    fun setQueue(episodes: List<Episode>, initialIndex: Int = 0) {
        _items.clear()
        _items.addAll(episodes)
        currentIndex = if (_items.isNotEmpty()) {
            initialIndex.coerceIn(0, _items.size - 1)
        } else {
            0
        }
        notifyChanged()
    }

    @Synchronized
    fun advance(): Episode? {
        if (!hasNext) return null
        currentIndex++
        val ep = currentEpisode
        notifyChanged()
        return ep
    }

    @Synchronized
    fun retreat(): Episode? {
        if (!hasPrevious) return null
        currentIndex--
        val ep = currentEpisode
        notifyChanged()
        return ep
    }

    @Synchronized
    fun jumpTo(index: Int): Episode? {
        if (index !in 0 until _items.size) return null
        currentIndex = index
        val ep = currentEpisode
        notifyChanged()
        return ep
    }

    @Synchronized
    fun updateCurrentPlayUrl(playUrl: String, headers: Map<String, String> = emptyMap()) {
        updateEpisodePlayUrl(currentIndex, playUrl, headers)
    }

    @Synchronized
    fun updateEpisodePlayUrl(index: Int, playUrl: String, headers: Map<String, String> = emptyMap()) {
        val target = _items.getOrNull(index) ?: return
        val updated = target.copy(
            playUrl = playUrl,
            playHeaders = headers,
            resolvedAt = System.currentTimeMillis()
        )
        _items[index] = updated
        notifyChanged()
    }

    @Synchronized
    fun addOrUpdateEpisode(episode: Episode) {
        val existingIndex = _items.indexOfFirst {
            it.episodeNumber == episode.episodeNumber || it.id == episode.id
        }
        if (existingIndex >= 0) {
            _items[existingIndex] = episode
        } else {
            _items.add(episode)
            _items.sortBy { it.episodeNumber }
        }
        notifyChanged()
    }

    @Synchronized
    fun clear() {
        _items.clear()
        currentIndex = 0
        notifyChanged()
    }

    fun addListener(listener: (EpisodeQueue) -> Unit) {
        listeners.add(listener)
        listener(this)
    }

    fun removeListener(listener: (EpisodeQueue) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        synchronized(listeners) {
            for (listener in listeners) {
                try {
                    listener(this)
                } catch (_: Throwable) {}
            }
        }
    }
}
