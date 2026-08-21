package com.bigeyes.app.utils

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREFS_NAME = "bigeyes_browser_prefs"
    private const val KEY_HOMEPAGE_URL = "default_homepage_url"

    // Default factory homepage: Tencent Video
    const val DEFAULT_HOMEPAGE_URL = "https://v.qq.com"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getHomepageUrl(context: Context): String {
        val url = getPrefs(context).getString(KEY_HOMEPAGE_URL, DEFAULT_HOMEPAGE_URL)
        return if (!url.isNullOrBlank()) url else DEFAULT_HOMEPAGE_URL
    }

    fun setHomepageUrl(context: Context, url: String) {
        var cleanUrl = url.trim()
        if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }
        getPrefs(context).edit().putString(KEY_HOMEPAGE_URL, cleanUrl).apply()
    }

    fun resetHomepageUrl(context: Context) {
        getPrefs(context).edit().putString(KEY_HOMEPAGE_URL, DEFAULT_HOMEPAGE_URL).apply()
    }
}
