package com.derricklee.ankidict

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Character decomposition trees (JSON), built by scripts/build_ids_db.py from cjkvi-ids into
 * the bundled (gitignored) ids.db -- see that script's header for why it's not committed like
 * dictionary.db. Every lookup degrades to "no tree" rather than crashing when the asset is
 * missing, same pattern as AudioRepository.
 */
class IdsRepository(private val context: Context) {

    private val db: SQLiteDatabase? by lazy { openBundledDatabase(context, DB_NAME) }

    /** The recursive decomposition tree (JSON) for [character], or "" if there's no data for it. */
    fun constituentsTreeJson(character: String): String {
        val db = db ?: return ""
        db.rawQuery("SELECT tree_json FROM ids WHERE character = ?", arrayOf(character)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
    }

    companion object {
        private const val DB_NAME = "ids.db"
    }
}
