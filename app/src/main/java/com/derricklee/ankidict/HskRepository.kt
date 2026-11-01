package com.derricklee.ankidict

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Character -> HSK 3.0 level, built by scripts/build_hsk_db.py from drkameleon/complete-hsk-
 * vocabulary's official HSK 3.0 word list into the bundled hsk.db -- see that script's header for
 * how a word-level list gets turned into a per-character one (lowest level any word containing
 * the character belongs to). Every lookup degrades to "no level" rather than crashing when the
 * asset is missing, same pattern as IdsRepository/AudioRepository.
 */
class HskRepository(private val context: Context) {

    private val db: SQLiteDatabase? by lazy { openBundledDatabase(context, DB_NAME) }

    /** [character]'s HSK 3.0 level ("1".."6", "7-9"), or "" if there's no data for it. */
    fun hskLevel(character: String): String {
        val db = db ?: return ""
        db.rawQuery("SELECT level FROM hsk WHERE character = ?", arrayOf(character)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
    }

    companion object {
        private const val DB_NAME = "hsk.db"
    }
}
