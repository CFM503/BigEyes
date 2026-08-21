package com.bigeyes.app.model

import org.junit.Assert.*
import org.junit.Test

class BookmarkTest {

    @Test
    fun testBookmarkJsonSerialization() {
        val original = Bookmark(
            id = "test-id-123",
            title = "腾讯视频",
            url = "https://v.qq.com",
            timestamp = 1700000000L
        )

        val json = original.toJson()
        val deserialized = Bookmark.fromJson(json)

        assertNotNull(deserialized)
        assertEquals("test-id-123", deserialized?.id)
        assertEquals("腾讯视频", deserialized?.title)
        assertEquals("https://v.qq.com", deserialized?.url)
        assertEquals(1700000000L, deserialized?.timestamp)
    }

    @Test
    fun testBookmarkInvalidJson() {
        val emptyJson = org.json.JSONObject()
        val result = Bookmark.fromJson(emptyJson)
        assertNull(result)
    }
}
