package com.bigeyes.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object AppPreferences {

    private const val PREFS_NAME = "bigeyes_browser_prefs"
    private const val KEY_HOMEPAGE_URL = "default_homepage_url"
    private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
    private const val KEY_PREFER_BIGEYES_TV = "prefer_bigeyes_tv"
    private const val KEY_CONTROL_BAR_EXPANDED = "control_bar_expanded_by_default"
    private const val KEY_SHOW_CAST_TOASTS = "show_cast_progress_toasts"
    private const val KEY_SHOW_TECH_INFO = "show_technical_info"
    private const val KEY_APPEARANCE = "appearance_skin"

    /** App skin: follow the system, or force one of the two skins. */
    enum class Appearance(val key: String, val label: String) {
        SYSTEM("system", "跟随系统"),
        DARK("dark", "暗夜影院"),
        LIGHT("light", "清爽明亮");

        companion object {
            fun fromKey(key: String?): Appearance = values().firstOrNull { it.key == key } ?: SYSTEM
        }
    }

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

    /** Show the full playback controls right away instead of the one-line compact bar. */
    fun isControlBarExpandedByDefault(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CONTROL_BAR_EXPANDED, false)
    }

    fun setControlBarExpandedByDefault(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CONTROL_BAR_EXPANDED, enabled).apply()
    }

    /**
     * Pop up a Toast for every casting step (connecting, pushing, success). Off by default:
     * the control bar shows the status instead. Failures are always reported.
     */
    fun isShowCastToasts(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_CAST_TOASTS, false)
    }

    fun setShowCastToasts(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_CAST_TOASTS, enabled).apply()
    }

    /**
     * 显示技术信息: full stream URLs and sniff time in the candidate list, device IPs in the
     * device list, and direct / DLNA-proxy mode on the control bar. Off = simple view for family.
     */
    fun isShowTechInfo(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_TECH_INFO, false)
    }

    fun setShowTechInfo(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_TECH_INFO, enabled).apply()
    }

    fun getAppearance(context: Context): Appearance {
        return Appearance.fromKey(getPrefs(context).getString(KEY_APPEARANCE, Appearance.SYSTEM.key))
    }

    fun setAppearance(context: Context, appearance: Appearance) {
        getPrefs(context).edit().putString(KEY_APPEARANCE, appearance.key).apply()
    }

    /**
     * 暗夜影院 = night resources, 清爽明亮 = day resources. Activities without uiMode in
     * configChanges are recreated by AppCompat; MainActivity rebuilds itself when safe.
     */
    fun applyAppearance(context: Context) {
        val mode = when (getAppearance(context)) {
            Appearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            Appearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Appearance.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
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
