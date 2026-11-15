package com.derricklee.ankidict

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v11 photo flagger (user 2026-11-15 laundry list + answers): `scraped_photos.flag_color`
 * stores an OPAQUE color string; the palette lives only in PhotoFlagPalette (so a future
 * color selector is a UI change, not a migration). Setters are targeted UPDATEs; photo
 * REPLACE-upserts must PRESERVE the flag — it is user annotation, not machine output.
 */
@RunWith(AndroidJUnit4::class)
class PhotoFlagInstrumentedTest {

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

    private fun photo(id: Long, flag: String? = null) = ScrapedPhoto(
        mediaStoreId = id, contentUri = "content://test/$id", dateAdded = 1L,
        languageTag = LanguageTag.JAPANESE, extractedText = "文$id。",
        ocrBlocksJson = null, status = PhotoStatus.KEEP, flagColor = flag,
    )

    private fun columns(table: String): List<String> =
        repo.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
        }

    @Test
    fun freshDatabase_v11_hasFlagColumnAndFoldersTable() {
        val uv = repo.readableDatabase.rawQuery("PRAGMA user_version", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(MiningRepository.expectedUserVersion(), uv)
        assertTrue("flag_color missing", "flag_color" in columns("scraped_photos"))
        val tables = repo.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table'", null,
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        assertTrue("word_folders missing", "word_folders" in tables)
    }

    @Test
    fun setAndClearFlag_roundTrip() {
        repo.upsertPhoto(photo(101L))
        assertNull(repo.getPhoto(101L)?.flagColor)
        repo.setPhotoFlag(101L, PhotoFlagPalette.KEY_RED)
        assertEquals("red", repo.getPhoto(101L)?.flagColor)
        repo.setPhotoFlag(101L, null)
        assertNull(repo.getPhoto(101L)?.flagColor)
    }

    @Test
    fun upsertPhotoReplacement_preservesExistingFlag() {
        repo.upsertPhoto(photo(102L))
        repo.setPhotoFlag(102L, "blue")
        // Re-mining path: a ScrapedPhoto WITHOUT a flag must not erase the stored one.
        repo.upsertPhoto(photo(102L, flag = null))
        assertEquals("blue", repo.getPhoto(102L)?.flagColor)
        // An explicit value does write through (hex, like a future color picker would store).
        repo.upsertPhoto(photo(102L, flag = "#ff8800"))
        assertEquals("#ff8800", repo.getPhoto(102L)?.flagColor)
    }

    @Test
    fun flaggedOnlyFacet_returnsOnlyFlaggedPhotos() {
        repo.upsertPhoto(photo(201L, flag = "green"))
        repo.upsertPhoto(photo(202L))
        repo.upsertPhoto(photo(203L, flag = "purple"))
        val flagged = repo.getScrapedPhotosFiltered(
            unseenOnly = false, goldOnly = false, tags = emptySet(), flaggedOnly = true,
        ).map { it.mediaStoreId }.toSet()
        assertEquals(setOf(201L, 203L), flagged)
        val all = repo.getScrapedPhotosFiltered(false, false, emptySet())
        assertEquals(setOf(201L, 202L, 203L), all.map { it.mediaStoreId }.toSet())
    }

    @Test
    fun migration_v10toV11_addsColumnsUnseeded_keepsRows() {
        repo.close()
        val dbFile = context.getDatabasePath("test_mig_v10.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("""CREATE TABLE scraped_photos (
                media_store_id INTEGER PRIMARY KEY, content_uri TEXT NOT NULL, date_added INTEGER NOT NULL,
                language_tag TEXT NOT NULL, extracted_text TEXT NOT NULL, ocr_blocks_json TEXT,
                status TEXT NOT NULL, duplicate_of_photo_id INTEGER, modified_by_user_flag INTEGER NOT NULL DEFAULT 0,
                is_gold INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("""CREATE TABLE extracted_items (
                language_tag TEXT NOT NULL, item_type TEXT NOT NULL, expression TEXT NOT NULL,
                kanji TEXT NOT NULL, kana TEXT NOT NULL, meaning TEXT NOT NULL, level_badge TEXT,
                corpus_rank INTEGER, anki_account_id TEXT, deck_id INTEGER, deck_name TEXT, anki_note_id INTEGER,
                is_hidden INTEGER NOT NULL DEFAULT 0, visibility_state TEXT NOT NULL DEFAULT 'ACTIVE',
                sentence_ranks TEXT, PRIMARY KEY (language_tag, item_type, expression))""")
            db.execSQL("""CREATE TABLE item_occurrences (
                id INTEGER PRIMARY KEY AUTOINCREMENT, language_tag TEXT NOT NULL, item_type TEXT NOT NULL,
                expression TEXT NOT NULL, photo_id INTEGER NOT NULL, sentence TEXT NOT NULL,
                created_at INTEGER NOT NULL, state TEXT NOT NULL, modified_by_user_flag INTEGER NOT NULL DEFAULT 0,
                is_manual INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("PRAGMA user_version = 10")
            val v = ContentValues().apply {
                put("media_store_id", 301L); put("content_uri", "content://old/301"); put("date_added", 1L)
                put("language_tag", "JAPANESE"); put("extracted_text", "古い文。"); put("status", "KEEP")
            }
            db.insert("scraped_photos", null, v)
            val i = ContentValues().apply {
                put("language_tag", "JAPANESE"); put("item_type", "WORD"); put("expression", "古")
                put("kanji", "古"); put("kana", ""); put("meaning", "old")
            }
            db.insert("extracted_items", null, i)
        }
        val migrated = MiningRepository(context, "test_mig_v10.db")
        try {
            val db = migrated.readableDatabase
            assertEquals(MiningRepository.expectedUserVersion(),
                db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getInt(0) })
            assertTrue("flag_color missing after v11 migration", "flag_color" in columns("scraped_photos"))
            assertTrue("folder_id missing after v11 migration", "folder_id" in columns("extracted_items"))
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
                .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            assertTrue("word_folders missing after v11 migration", "word_folders" in tables)
            // Rows survive; new columns are unseeded (flags/marks start at zero — gold precedent).
            assertNotNull(migrated.getPhoto(301L))
            assertNull(migrated.getPhoto(301L)?.flagColor)
            migrated.setPhotoFlag(301L, "yellow")
            assertEquals("yellow", migrated.getPhoto(301L)?.flagColor)
        } finally {
            migrated.close()
            context.deleteDatabase("test_mig_v10.db")
        }
    }

    private companion object {
        const val DB_NAME = "test_photo_flag.db"
    }
}
