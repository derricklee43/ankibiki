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

    companion object {
        private const val PREFS_NAME = "settings"
    }
}
