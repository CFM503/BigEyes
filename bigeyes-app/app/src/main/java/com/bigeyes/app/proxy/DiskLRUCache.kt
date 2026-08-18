package com.bigeyes.app.proxy

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap

class DiskLRUCache(
    private val cacheDir: File,
    private val maxSizeBytes: Long = 300 * 1024 * 1024L // 300 MB default
) {
    companion object {
        private const val TAG = "DiskLRUCache"
    }

    private val lock = Any()
    // LinkedHashMap with accessOrder = true for LRU tracking
    private val entries = LinkedHashMap<String, Long>(32, 0.75f, true)
    private var currentSize: Long = 0

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        scanExisting()
    }

    private fun scanExisting() {
        synchronized(lock) {
            try {
                val files = cacheDir.listFiles() ?: return
                // Sort by lastModified ascending (oldest first)
                files.sortBy { it.lastModified() }
                for (f in files) {
                    if (f.isFile) {
                        val len = f.length()
                        entries[f.name] = len
                        currentSize += len
                    }
                }
                Log.i(TAG, "Initialized cache with ${entries.size} files, total size: ${currentSize / (1024 * 1024)} MB")
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning cache dir: ${e.message}")
            }
        }
    }

    private fun evictIfNeeded(incomingSize: Long) {
        while (currentSize + incomingSize > maxSizeBytes && entries.isNotEmpty()) {
            val oldestKey = entries.keys.firstOrNull() ?: break
            val oldestSize = entries.remove(oldestKey) ?: 0L
            val file = File(cacheDir, oldestKey)
            if (file.exists()) {
                file.delete()
            }
            currentSize -= oldestSize
            Log.d(TAG, "Evicted LRU segment: $oldestKey ($oldestSize bytes)")
        }
    }

    fun has(key: String): Boolean {
        synchronized(lock) {
            val exists = entries.containsKey(key)
            if (exists) {
                entries[key] // access to update LRU position
            }
            return exists && File(cacheDir, key).exists()
        }
    }

    fun get(key: String): ByteArray? {
        synchronized(lock) {
            if (!entries.containsKey(key)) return null
            val file = File(cacheDir, key)
            if (!file.exists()) {
                entries.remove(key)
                return null
            }
            // Trigger LRU order update
            entries[key]
            return try {
                file.readBytes()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cache file $key: ${e.message}")
                null
            }
        }
    }

    fun put(key: String, data: ByteArray) {
        val size = data.size.toLong()
        synchronized(lock) {
            evictIfNeeded(size)
            val file = File(cacheDir, key)
            try {
                file.writeBytes(data)
                entries[key] = size
                currentSize += size
            } catch (e: Exception) {
                Log.e(TAG, "Error writing cache file $key: ${e.message}")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                cacheDir.listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {
            }
            entries.clear()
            currentSize = 0
        }
    }
}
