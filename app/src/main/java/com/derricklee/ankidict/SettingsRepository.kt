package com.derricklee.ankidict

import android.content.Context

/** Persists which [SearchSource]s are enabled. Defaults to all enabled. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(source: SearchSource): Boolean = prefs.getBoolean(source.name, true)

    fun setEnabled(source: SearchSource, enabled: Boolean) {
        prefs.edit().putBoolean(source.name, enabled).apply()
    }

    fun enabledSources(): Set<SearchSource> = SearchSource.entries.filterTo(mutableSetOf(), ::isEnabled)

    fun isFloatingBubbleEnabled(): Boolean = prefs.getBoolean(KEY_FLOATING_BUBBLE, false)

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_BUBBLE, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_FLOATING_BUBBLE = "floating_bubble_enabled"
    }
}
