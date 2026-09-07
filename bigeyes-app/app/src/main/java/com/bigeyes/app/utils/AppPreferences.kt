package com.bigeyes.app.utils

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREFS_NAME = "bigeyes_browser_prefs"
    private const val KEY_HOMEPAGE_URL = "default_homepage_url"
    private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"

    // Default factory homepage
    const val DEFAULT_HOMEPAGE_URL = "https://zip0.com/"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoPlayNext(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_PLAY_NEXT, true)
    }

    fun setAutoPlayNext(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_PLAY_NEXT, enabled).apply()
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
