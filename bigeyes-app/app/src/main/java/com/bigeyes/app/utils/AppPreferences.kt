package com.bigeyes.app.utils

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREFS_NAME = "bigeyes_browser_prefs"
    private const val KEY_HOMEPAGE_URL = "default_homepage_url"
    private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
    private const val KEY_PREFER_BIGEYES_TV = "prefer_bigeyes_tv"

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

    /**
     * Prefer direct BigEyesTV casting (Intent based, with header passthrough and status feedback)
     * over the DLNA proxy cast whenever a compatible BigEyesTV build is installed.
     */
    fun isPreferBigEyesTv(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PREFER_BIGEYES_TV, true)
    }

    fun setPreferBigEyesTv(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PREFER_BIGEYES_TV, enabled).apply()
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
