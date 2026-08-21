package com.bigeyes.app.model

import org.json.JSONObject
import java.util.UUID

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("url", url)
            put("timestamp", timestamp)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Bookmark? {
            val title = json.optString("title", "")
            val url = json.optString("url", "")
            if (url.isBlank()) return null
            val id = json.optString("id", UUID.randomUUID().toString())
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            return Bookmark(
                id = id,
                title = if (title.isNotBlank()) title else url,
                url = url,
                timestamp = timestamp
            )
        }
    }
}
