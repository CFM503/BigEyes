package com.bigeyes.app

import com.bigeyes.app.proxy.DiskLRUCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiskLRUCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testLruEviction() {
        val dir = tempFolder.newFolder("cache_evict")
        val cache = DiskLRUCache(dir, maxSizeBytes = 100L)

        // Item 1: 40 bytes
        cache.put("item1.ts", ByteArray(40) { 1 })
        assertTrue(cache.has("item1.ts"))
        assertEquals(40, cache.get("item1.ts")?.size)

        // Item 2: 40 bytes -> total 80 bytes
        cache.put("item2.ts", ByteArray(40) { 2 })
        assertTrue(cache.has("item1.ts"))
        assertTrue(cache.has("item2.ts"))

        // Item 3: 40 bytes -> exceeds 100 bytes -> item1.ts evicted
        cache.put("item3.ts", ByteArray(40) { 3 })
        assertFalse(cache.has("item1.ts"))
        assertTrue(cache.has("item2.ts"))
        assertTrue(cache.has("item3.ts"))

        // Access item2.ts to make item3.ts oldest
        cache.get("item2.ts")

        // Item 4: 40 bytes -> item3.ts evicted
        cache.put("item4.ts", ByteArray(40) { 4 })
        assertTrue(cache.has("item2.ts"))
        assertFalse(cache.has("item3.ts"))
        assertTrue(cache.has("item4.ts"))
    }

    @Test
    fun testOversizedSingleFileHandling() {
        val dir = tempFolder.newFolder("cache_oversize")
        // Cache max capacity: 100 bytes
        val cache = DiskLRUCache(dir, maxSizeBytes = 100L)

        cache.put("small1.ts", ByteArray(30) { 1 })
        cache.put("small2.ts", ByteArray(30) { 2 })
        assertEquals(2, dir.listFiles()?.size)

        // Put an oversized file (150 bytes > 100 bytes capacity)
        val oversizedData = ByteArray(150) { 9 }
        cache.put("oversized.ts", oversizedData)

        // All smaller files must be evicted
        assertFalse(cache.has("small1.ts"))
        assertFalse(cache.has("small2.ts"))
        // Oversized file should be stored cleanly without loop/crash
        assertTrue(cache.has("oversized.ts"))
        assertEquals(150, cache.get("oversized.ts")?.size)

        // Next item arriving should evict the oversized file
        cache.put("small3.ts", ByteArray(50) { 3 })
        assertFalse(cache.has("oversized.ts"))
        assertTrue(cache.has("small3.ts"))
    }

    @Test
    fun testConcurrentReadWriteThreadSafety() {
        val dir = tempFolder.newFolder("cache_concurrent")
        val cache = DiskLRUCache(dir, maxSizeBytes = 500_000L)

        val threadCount = 10
        val operationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until operationsPerThread) {
                        val key = "shared_key_${i % 5}.ts"
                        val data = ByteArray(100) { (t + i).toByte() }
                        cache.put(key, data)
                        val readData = cache.get(key)
                        if (readData == null || readData.size != 100) {
                            errorCount.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent operations should complete within 10s", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("There should be zero concurrency errors", 0, errorCount.get())
    }
}
