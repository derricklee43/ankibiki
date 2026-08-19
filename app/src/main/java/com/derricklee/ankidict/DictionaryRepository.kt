package com.derricklee.ankidict

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Exact-match lookups (character/kanji, Japanese word, Chinese word/hanzi) against a bundled
 * SQLite DB built from KANJIDIC2 + JMdict + RADKFILE (EDRDG, CC BY-SA 4.0) and CC-CEDICT
 * (CC BY-SA 4.0). See ATTRIBUTION.md for the required credit.
 */
class DictionaryRepository(context: Context) {

    private val db: SQLiteDatabase by lazy { openDatabase(context) }

    private fun openDatabase(context: Context): SQLiteDatabase {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                dbFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    /** All exact matches for [query] across whichever of [enabledSources] apply here. */
    fun lookupExact(query: String, enabledSources: Set<SearchSource>): List<DictionaryEntry> {
        val results = mutableListOf<DictionaryEntry>()
        if (query.length == 1 && SearchSource.DICT_KANJI in enabledSources) {
            lookupKanji(query)?.let { results.add(it) }
        }
        if (SearchSource.DICT_JAPANESE_WORDS in enabledSources) {
            results.addAll(lookupJapaneseWords(query))
        }
        if (SearchSource.DICT_CHINESE_WORDS in enabledSources) {
            results.addAll(lookupChineseWords(query))
        }
        return results
    }

    private fun lookupKanji(character: String): DictionaryEntry.Kanji? {
        db.rawQuery(
            "SELECT on_yomi, kun_yomi, meanings, stroke_count, grade, jlpt FROM kanji WHERE character = ?",
            arrayOf(character),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val radicals = db.rawQuery(
                "SELECT radical FROM kanji_radicals WHERE character = ?",
                arrayOf(character),
            ).use { radCursor ->
                generateSequence { if (radCursor.moveToNext()) radCursor.getString(0) else null }.toList()
            }
            return DictionaryEntry.Kanji(
                character = character,
                onYomi = cursor.getString(0).orEmpty().split("、").filter { it.isNotBlank() },
                kunYomi = cursor.getString(1).orEmpty().split("、").filter { it.isNotBlank() },
                meanings = cursor.getString(2).orEmpty(),
                strokeCount = cursor.getIntOrNull(3),
                grade = cursor.getIntOrNull(4),
                jlpt = cursor.getIntOrNull(5),
                radicals = radicals,
            )
        }
    }

    private fun lookupJapaneseWords(query: String): List<DictionaryEntry.JapaneseWord> {
        val results = mutableListOf<DictionaryEntry.JapaneseWord>()
        db.rawQuery(
            "SELECT headword, reading, glosses, is_common FROM words " +
                "WHERE headword = ? OR reading = ? ORDER BY is_common DESC",
            arrayOf(query, query),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    DictionaryEntry.JapaneseWord(
                        headword = cursor.getString(0),
                        reading = cursor.getString(1),
                        glosses = cursor.getString(2),
                        isCommon = cursor.getInt(3) == 1,
                    ),
                )
            }
        }
        return results
    }

    /** Exact matches for [query] against CC-CEDICT's simplified/traditional headwords, ordered
     *  so a surname/proper-noun sense (CC-CEDICT's convention: pinyin capitalized, e.g. "Shao4"
     *  for 绍 the surname vs. "shao4" for 绍 "to continue") never wins over a common-word sense
     *  when both exist for the same character -- otherwise callers taking [firstOrNull] (e.g.
     *  HanziWordSyncService, auto-filling a brand-new note's Meaning) can land on "surname Shao"
     *  for a character whose everyday meaning is completely different. Also used directly by
     *  HanziWordSyncService (not just [lookupExact]) to look up a single hanzi character's or
     *  word's meaning/pinyin when auto-filling sync-owned fields. */
    fun lookupChineseWords(query: String): List<DictionaryEntry.ChineseWord> {
        val results = mutableListOf<DictionaryEntry.ChineseWord>()
        db.rawQuery(
            "SELECT traditional, simplified, pinyin, definitions FROM cedict " +
                "WHERE simplified = ? OR traditional = ? " +
                "ORDER BY (pinyin GLOB '[A-Z]*'), rowid",
            arrayOf(query, query),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    DictionaryEntry.ChineseWord(
                        traditional = cursor.getString(0),
                        simplified = cursor.getString(1),
                        pinyin = cursor.getString(2),
                        definitions = cursor.getString(3),
                    ),
                )
            }
        }
        return results
    }

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    companion object {
        private const val DB_NAME = "dictionary.db"
    }
}
