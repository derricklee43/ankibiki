package com.derricklee.ankidict

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit gold membership (user decision 2026-11-13): `scraped_photos.is_gold` (schema v7) +
 * the Mark-as-gold button's storage rules. Gold = marked, NOT touched — so partial edits across
 * passes neither add nor drop a photo, never-edited perfect photos can join, and a full image
 * rescan (which replaces the text) retires the mark. The v6→v7 migration seeds the flag from the
 * old modified_by_user_flag so the reviewed set carries over.
 */
@RunWith(AndroidJUnit4::class)
class PhotoGoldFlagInstrumentedTest {

    private lateinit var context: android.content.Context
    private lateinit var repo: MiningRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, DB_NAME)
        repo.writableDatabase.delete("scraped_photos", null, null)
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun photo(id: Long, text: String = "文。") = ScrapedPhoto(
        mediaStoreId = id, contentUri = "content://test/$id", dateAdded = 1L,
        languageTag = LanguageTag.JAPANESE, extractedText = text,
        ocrBlocksJson = null, status = PhotoStatus.KEEP,
    )

    @Test
    fun freshDatabase_v7_hasIsGoldColumn() {
        val db = repo.readableDatabase
        val uv = db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(MiningRepository.expectedUserVersion(), uv)
        val cols = db.rawQuery("PRAGMA table_info(scraped_photos)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
        }
        assertTrue("is_gold column missing on fresh v7 db", "is_gold" in cols)
    }

    @Test
    fun migration_v6toV7_addsColumn_startingAllUnmarked() {
        repo.close()
        val dbFile = context.getDatabasePath("test_mig_v6.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("""CREATE TABLE scraped_photos (
                media_store_id INTEGER PRIMARY KEY, content_uri TEXT NOT NULL, date_added INTEGER NOT NULL,
                language_tag TEXT NOT NULL, extracted_text TEXT NOT NULL, ocr_blocks_json TEXT,
                status TEXT NOT NULL, duplicate_of_photo_id INTEGER, modified_by_user_flag INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("""CREATE TABLE extracted_items (
                language_tag TEXT NOT NULL, item_type TEXT NOT NULL, expression TEXT NOT NULL,
                kanji TEXT NOT NULL, kana TEXT NOT NULL, meaning TEXT NOT NULL, level_badge TEXT,
                corpus_rank INTEGER, anki_account_id TEXT, deck_id INTEGER, deck_name TEXT, anki_note_id INTEGER,
                is_hidden INTEGER NOT NULL DEFAULT 0, visibility_state TEXT NOT NULL DEFAULT 'ACTIVE',
                sentence_ranks TEXT, PRIMARY KEY (language_tag, item_type, expression))""")
            db.execSQL("""CREATE TABLE item_occurrences (
                id INTEGER PRIMARY KEY AUTOINCREMENT, language_tag TEXT NOT NULL, item_type TEXT NOT NULL,
                expression TEXT NOT NULL, photo_id INTEGER NOT NULL, sentence TEXT NOT NULL,
                created_at INTEGER NOT NULL, state TEXT NOT NULL, modified_by_user_flag INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("""CREATE TABLE card_templates (
                deck_id INTEGER NOT NULL, model_id INTEGER NOT NULL, ordinal INTEGER NOT NULL,
                bound_field TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY (deck_id, model_id, ordinal))""")
            db.execSQL("""CREATE TABLE ocr_corrections (
                id INTEGER PRIMARY KEY AUTOINCREMENT, photo_id INTEGER NOT NULL, line_index INTEGER NOT NULL,
                a_start INTEGER NOT NULL, a_end INTEGER NOT NULL, a_text TEXT NOT NULL, b_text TEXT NOT NULL,
                kind TEXT NOT NULL, margin REAL NOT NULL, anchored_a INTEGER NOT NULL, anchored_b INTEGER NOT NULL,
                confusable INTEGER NOT NULL, nameplate_excluded INTEGER NOT NULL, decision TEXT NOT NULL,
                reason TEXT, line_before TEXT NOT NULL, line_after TEXT NOT NULL, created_at INTEGER NOT NULL)""")
            db.execSQL("PRAGMA user_version = 6")
            for ((id, touched) in listOf(11L to 1, 22L to 0, 33L to 1)) {
                val v = ContentValues().apply {
                    put("media_store_id", id); put("content_uri", "content://old/$id"); put("date_added", 1L)
                    put("language_tag", "JAPANESE"); put("extracted_text", "古い文$id。"); put("status", "KEEP")
                    put("modified_by_user_flag", touched)
                }
                db.insert("scraped_photos", null, v)
            }
        }
        val migrated = MiningRepository(context, "test_mig_v6.db")
        try {
            val db = migrated.readableDatabase
            assertEquals(MiningRepository.expectedUserVersion(), db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getInt(0) })
            // NOT seeded (user 2026-11-13): touched photos do NOT inherit gold — membership
            // starts at zero and every 金 mark is declared.
            assertEquals(false, migrated.getPhoto(11L)?.isGold)
            assertEquals(false, migrated.getPhoto(22L)?.isGold)
            assertEquals(false, migrated.getPhoto(33L)?.isGold)
            assertEquals(listOf<Long>(), migrated.getGoldPhotoIds())
            assertEquals("古い文11。", migrated.getPhoto(11L)?.extractedText)
        } finally {
            migrated.close()
            context.deleteDatabase("test_mig_v6.db")
        }
        repo = MiningRepository(context, DB_NAME)
    }

    @Test
    fun toggle_roundTrips_andNeverTouchesEditProvenance() {
        repo.upsertPhoto(photo(101L))
        repo.setPhotoIsGold(101L, true)
        var p = repo.getPhoto(101L)!!
        assertTrue(p.isGold)
        assertFalse("marking is verification, not an edit", p.modifiedByUserFlag)
        repo.setPhotoIsGold(101L, false)
        p = repo.getPhoto(101L)!!
        assertFalse(p.isGold)
        assertEquals(listOf<Long>(), repo.getGoldPhotoIds().filter { it == 101L })
    }

    @Test
    fun textEdit_keepsGoldMark() {
        repo.upsertPhoto(photo(102L))
        repo.setPhotoIsGold(102L, true)
        repo.updatePhotoExtractedText(102L, "編集後の文。")
        val p = repo.getPhoto(102L)!!
        assertTrue("partial edits must not drop gold membership", p.isGold)
        assertTrue("edit provenance tracked independently", p.modifiedByUserFlag)
        assertEquals(listOf(102L), repo.getGoldPhotoIds())
    }

    @Test
    fun rescanReplace_upsertWithoutGold_clearsMark() {
        // Mirrors the rescan write: processOcrDocument upserts a fresh model (isGold defaults
        // false), which is exactly how a replaced text retires an outdated gold mark.
        repo.upsertPhoto(photo(103L).copy(isGold = true))
        assertTrue(repo.getPhoto(103L)!!.isGold)
        repo.upsertPhoto(photo(103L, "再スキャン後の文。"))
        assertFalse("rescan replaces the text gold attested to -> mark retired", repo.getPhoto(103L)!!.isGold)
    }

    companion object {
        private const val DB_NAME = "test_photo_gold_flag.db"
    }
}
