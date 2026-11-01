package com.derricklee.ankidict

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Kyuujitai/Traditional-Chinese character -> Japanese Shinjitai character, built by
 * scripts/build_shinjitai_db.py from OpenCC's JPShinjitaiCharacters.txt into the bundled
 * shinjitai.db -- the third leg of HanziWordSyncService.backfillJpTile()'s cross-reference,
 * for characters where Japan's own simplification diverged from both Simplified and
 * Traditional Chinese (e.g. 灵 Simplified / 靈 Traditional / 霊 Shinjitai are three different
 * characters -- see that script's header for why Unihan doesn't cover this). Every lookup
 * degrades to "no match" rather than crashing when the asset is missing, same pattern as
 * IdsRepository/HskRepository/AudioRepository.
 */
class ShinjitaiRepository(private val context: Context) {

    private val db: SQLiteDatabase? by lazy { openBundledDatabase(context, DB_NAME) }

    /** The Shinjitai form of [kyuujitaiOrTraditional], or null if it's unchanged in Japanese
     *  (not in this table at all -- most characters) or there's no data bundled. */
    fun shinjitaiFor(kyuujitaiOrTraditional: String): String? {
        val db = db ?: return null
        db.rawQuery(
            "SELECT shinjitai FROM shinjitai WHERE kyuujitai = ? LIMIT 1",
            arrayOf(kyuujitaiOrTraditional),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    companion object {
        private const val DB_NAME = "shinjitai.db"
    }
}
