package com.derricklee.ankidict

import android.content.Context

/**
 * Stremio console secrets + small UI state (design cross-cutting rules): SharedPreferences
 * MODE_PRIVATE only — prefs are outside the sqlite file, therefore outside every bundle
 * contract automatically (D7: secrets OUT). Values never appear in logs; the console shows
 * masked presence only. Keystore hardening is a documented follow-up, not a launch gate
 * for a personal app.
 */
class StremioPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("stremio_console", Context.MODE_PRIVATE)

    var authKey: String?
        get() = prefs.getString(KEY_AUTH, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_AUTH, value.orEmpty()).apply()
        }

    var rdToken: String?
        get() = prefs.getString(KEY_RD_TOKEN, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_RD_TOKEN, value.orEmpty()).apply()
        }

    /** Jimaku free-key (spikes/007 builtin source). Same prefs = outside-bundle rule. */
    var jimakuKey: String?
        get() = prefs.getString(KEY_JIMAKU, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_JIMAKU, value.orEmpty()).apply()
        }

    /** Per-show Jimaku entry cache (ruling D3a): validated entry ids by baseId. */
    fun jimakuEntryId(baseId: String): Long? =
        prefs.getLong(KEY_JIMAKU_ENTRY + baseId, 0L).takeIf { it > 0 }

    fun setJimakuEntryId(baseId: String, entryId: Long) {
        prefs.edit().putLong(KEY_JIMAKU_ENTRY + baseId, entryId).apply()
    }

    fun clearJimakuEntryId(baseId: String) {
        prefs.edit().remove(KEY_JIMAKU_ENTRY + baseId).apply()
    }

    /** Last successful full sync, for the console header line. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC, value).apply()
        }

    companion object {
        private const val KEY_AUTH = "stremio_auth_key"
        private const val KEY_RD_TOKEN = "rd_api_token"
        private const val KEY_JIMAKU = "jimaku_api_key"
        private const val KEY_JIMAKU_ENTRY = "jimaku_entry_"
        private const val KEY_LAST_SYNC = "last_sync_at"

        fun mask(secret: String?): String =
            if (secret.isNullOrEmpty()) "not configured"
            else "${secret.take(3)}…${secret.takeLast(2)} (${secret.length})"
    }
}
