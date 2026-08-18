package com.bigeyes.app

import com.bigeyes.app.proxy.DiskLRUCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskLRUCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testLruEviction() {
        val dir = tempFolder.newFolder("cache")
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
}
