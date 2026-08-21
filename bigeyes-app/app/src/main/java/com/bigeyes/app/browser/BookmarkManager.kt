package com.bigeyes.app.browser

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bigeyes.app.model.Bookmark
import org.json.JSONArray
import java.util.Collections

object BookmarkManager {

    private const val TAG = "BookmarkManager"
    private const val PREFS_NAME = "bigeyes_bookmarks"
    private const val KEY_BOOKMARKS_JSON = "bookmarks_list"
    private const val KEY_INITIALIZED = "bookmarks_initialized"

    private val cachedBookmarks = Collections.synchronizedList(mutableListOf<Bookmark>())
    private var isLoaded = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getBookmarks(context: Context): List<Bookmark> {
        if (!isLoaded) {
            loadFromStorage(context)
        }
        return ArrayList(cachedBookmarks)
    }

    @Synchronized
    fun addBookmark(context: Context, title: String, url: String): Bookmark {
        if (!isLoaded) {
            loadFromStorage(context)
        }

        val cleanUrl = url.trim()
        val cleanTitle = if (title.isNotBlank()) title.trim() else cleanUrl

        // If URL already exists, update title and timestamp, move to top
        val existingIndex = cachedBookmarks.indexOfFirst { normalizeUrl(it.url) == normalizeUrl(cleanUrl) }
        val bookmark = if (existingIndex >= 0) {
            val existing = cachedBookmarks.removeAt(existingIndex)
            existing.copy(title = cleanTitle, timestamp = System.currentTimeMillis())
        } else {
            Bookmark(title = cleanTitle, url = cleanUrl)
        }

        cachedBookmarks.add(0, bookmark)
        saveToStorage(context)
        return bookmark
    }

    @Synchronized
    fun removeBookmark(context: Context, id: String): Boolean {
        if (!isLoaded) {
            loadFromStorage(context)
        }
        val removed = cachedBookmarks.removeAll { it.id == id }
        if (removed) {
            saveToStorage(context)
        }
        return removed
    }

    @Synchronized
    fun removeBookmarkByUrl(context: Context, url: String): Boolean {
        if (!isLoaded) {
            loadFromStorage(context)
        }
        val targetNorm = normalizeUrl(url)
        val removed = cachedBookmarks.removeAll { normalizeUrl(it.url) == targetNorm }
        if (removed) {
            saveToStorage(context)
        }
        return removed
    }

    @Synchronized
    fun updateBookmark(context: Context, id: String, newTitle: String, newUrl: String): Boolean {
        if (!isLoaded) {
            loadFromStorage(context)
        }
        val index = cachedBookmarks.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = cachedBookmarks[index].copy(
                title = newTitle.trim().ifEmpty { newUrl.trim() },
                url = newUrl.trim()
            )
            cachedBookmarks[index] = updated
            saveToStorage(context)
            return true
        }
        return false
    }

    @Synchronized
    fun isBookmarked(context: Context, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!isLoaded) {
            loadFromStorage(context)
        }
        val targetNorm = normalizeUrl(url)
        return cachedBookmarks.any { normalizeUrl(it.url) == targetNorm }
    }

    @Synchronized
    fun toggleBookmark(context: Context, title: String?, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val bookmarked = isBookmarked(context, url)
        return if (bookmarked) {
            removeBookmarkByUrl(context, url)
            false
        } else {
            addBookmark(context, title ?: "书签", url)
            true
        }
    }

    @Synchronized
    fun resetToDefaults(context: Context) {
        cachedBookmarks.clear()
        cachedBookmarks.addAll(getDefaultBookmarks())
        saveToStorage(context)
    }

    private fun getDefaultBookmarks(): List<Bookmark> {
        return listOf(
            Bookmark(title = "腾讯视频", url = "https://v.qq.com"),
            Bookmark(title = "爱奇艺", url = "https://www.iqiyi.com"),
            Bookmark(title = "优酷视频", url = "https://www.youku.com"),
            Bookmark(title = "芒果TV", url = "https://www.mgtv.com"),
            Bookmark(title = "哔哩哔哩", url = "https://www.bilibili.com"),
            Bookmark(title = "VodPlus 影视聚合", url = "https://vodplus.pages.dev")
        )
    }

    private fun loadFromStorage(context: Context) {
        cachedBookmarks.clear()
        val prefs = getPrefs(context)
        val hasInitialized = prefs.getBoolean(KEY_INITIALIZED, false)

        if (!hasInitialized) {
            // First time seeding default bookmarks
            cachedBookmarks.addAll(getDefaultBookmarks())
            saveToStorage(context)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            isLoaded = true
            return
        }

        val jsonStr = prefs.getString(KEY_BOOKMARKS_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val bookmark = Bookmark.fromJson(obj)
                    if (bookmark != null) {
                        cachedBookmarks.add(bookmark)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing bookmarks JSON: ${e.message}", e)
            }
        }
        isLoaded = true
    }

    private fun saveToStorage(context: Context) {
        try {
            val jsonArray = JSONArray()
            for (bm in cachedBookmarks) {
                jsonArray.put(bm.toJson())
            }
            getPrefs(context).edit()
                .putString(KEY_BOOKMARKS_JSON, jsonArray.toString())
                .putBoolean(KEY_INITIALIZED, true)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving bookmarks to storage: ${e.message}", e)
        }
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().trimEnd('/')
    }
}
