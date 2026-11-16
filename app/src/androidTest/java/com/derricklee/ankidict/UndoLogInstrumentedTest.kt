package com.derricklee.ankidict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-view undo end-to-end (user go 2026-11-15, docs/cross-view-undo-proposal.md):
 * every undoable action round-trips against REAL SQLite — mutate via the production
 * repository/runner paths, undo via UndoManager, assert the captured before-state is back
 * field-for-field and the log row is consumed. Plus: v12 migration (undo_log materializes
 * over a v10 corpus with rows intact), the stale-target guard (later divergent change =>
 * UNDO REFUSED, nothing clobbered), delivery announce-once, and the newest-50 prune.
 */
@RunWith(AndroidJUnit4::class)
class UndoLogInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository
    private lateinit var runner: MiningPipelineRunner

    private class NoDup : DuplicatePhotoDetector {
        override fun checkDuplicate(extractedText: String, tag: LanguageTag, config: DuplicateDetectionConfig) =
            DuplicateCheckResult(isDuplicate = false)
    }

    /** Every sentence analyzes to exactly one candidate: the sentence itself. */
    private class EveryWord : WordAnalyzer {
        override fun extractWords(sentence: String) = listOf(NormalizedCandidate(sentence, sentence))
    }

    private fun candidate(id: Long) = PhotoCandidate(id, "content://test/$id", 1000L)

    private fun photo(id: Long, text: String = "", gold: Boolean = false) = ScrapedPhoto(
        mediaStoreId = id,
        contentUri = "content://test/$id",
        dateAdded = 1000L + id,
        languageTag = LanguageTag.JAPANESE,
        extractedText = text,
        ocrBlocksJson = null,
        status = PhotoStatus.KEEP,
        isGold = gold,
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, DB_NAME)
        val config = PipelineConfig(
            tag = LanguageTag.JAPANESE,
            textExtractor = object : TextExtractor {
                override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) =
                    OcrDocument("", emptyList())
            },
            duplicateDetector = NoDup(),
            sentenceParser = CjkSentenceParser(),
            wordAnalyzer = EveryWord(),
            grammarAnalyzer = NoOpGrammarAnalyzer(),
            dictionaryProviders = emptyList(),
        )
        runner = MiningPipelineRunner(
            miningRepository = repo,
            photoSource = object : PhotoSource {
                override fun findUnprocessedPhotos(config: ScanDiscoveryConfig) = emptyList<PhotoCandidate>()
            },
            imagePreprocessor = object : ImagePreprocessor {
                override fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage? = null
            },
            imageTagger = object : ImageTagger {
                override fun classify(image: PreparedImage) = TagResult(LanguageTag.JAPANESE, 1f, null)
            },
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to config),
        )
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIG_DB)
    }

    // ---- schema / migration ----

    @Test
    fun freshDb_hasUndoLogAtCurrentVersion() {
        assertEquals(MiningRepository.expectedUserVersion(), repo.readableDatabase.version)
        assertTrue(
            "undo_log table missing",
            repo.readableDatabase.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='undo_log'", null,
            ).use { it.moveToFirst() },
        )
    }

    @Test
    fun migration_v10Corpus_gainsUndoLogAndKeepsRows() {
        // v10-era DB file (pre-v11 columns absent, no undo_log), then reopen through the chain.
        val file = context.getDatabasePath(MIG_DB)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE scraped_photos (media_store_id INTEGER PRIMARY KEY,
                content_uri TEXT NOT NULL, date_added INTEGER NOT NULL, language_tag TEXT NOT NULL,
                extracted_text TEXT NOT NULL, ocr_blocks_json TEXT, status TEXT NOT NULL,
                duplicate_of_photo_id INTEGER, modified_by_user_flag INTEGER NOT NULL DEFAULT 0,
                is_gold INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("""CREATE TABLE extracted_items (language_tag TEXT NOT NULL, item_type TEXT NOT NULL,
                expression TEXT NOT NULL, kanji TEXT NOT NULL, kana TEXT NOT NULL, meaning TEXT NOT NULL,
                level_badge TEXT, corpus_rank INTEGER, anki_account_id TEXT, deck_id INTEGER,
                deck_name TEXT, anki_note_id INTEGER, is_hidden INTEGER NOT NULL DEFAULT 0,
                visibility_state TEXT NOT NULL DEFAULT 'ACTIVE', sentence_ranks TEXT,
                PRIMARY KEY (language_tag, item_type, expression))""")
            db.execSQL("""CREATE TABLE item_occurrences (id INTEGER PRIMARY KEY AUTOINCREMENT,
                language_tag TEXT NOT NULL, item_type TEXT NOT NULL, expression TEXT NOT NULL,
                photo_id INTEGER NOT NULL, sentence TEXT NOT NULL, created_at INTEGER NOT NULL,
                state TEXT NOT NULL, modified_by_user_flag INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("PRAGMA user_version = 10")
            db.execSQL(
                "INSERT INTO scraped_photos (media_store_id, content_uri, date_added, language_tag, extracted_text, status)" +
                    " VALUES (901, 'content://old/901', 5, 'JAPANESE', '古い文。', 'KEEP')",
            )
        }
        val migrated = MiningRepository(context, MIG_DB)
        try {
            val db = migrated.readableDatabase
            assertEquals(MiningRepository.expectedUserVersion(), db.version)
            assertTrue(
                "undo_log missing after migration",
                db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='undo_log'", null)
                    .use { it.moveToFirst() },
            )
            assertEquals("pre-migration corpus row untouched",
                "古い文。", migrated.getPhoto(901L)?.extractedText)
            // undo works on the migrated file too
            migrated.setPhotoIsGold(901L, true)
            assertEquals(UndoOutcome.UNDONE, migrated.inTransaction { UndoManager.undoLatest(migrated) })
            assertFalse(migrated.undoGold(901L))
        } finally {
            migrated.close()
        }
    }

    // ---- per-action round trips ----

    @Test
    fun markSeen_undo_restoresState_consumesEntry() {
        repo.upsertPhoto(photo(1L, text = "猫だ。"))
        val occId = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 1L, "猫だ。")
        repo.markOccurrenceSeen(occId)
        assertEquals(OccurrenceState.SEEN.name, repo.undoOccState(occId))
        assertNotNull(repo.latestUndo())
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertEquals(OccurrenceState.UNSEEN.name, repo.undoOccState(occId))
        assertNull(repo.latestUndo())
    }

    @Test
    fun deleteOccurrence_undo_restoresState() {
        repo.upsertPhoto(photo(2L, text = "猫だ。"))
        val occId = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 2L, "猫だ。")
        repo.deleteOccurrence(occId)
        assertEquals(OccurrenceState.DELETED.name, repo.undoOccState(occId))
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertEquals(OccurrenceState.UNSEEN.name, repo.undoOccState(occId))
    }

    @Test
    fun hideItem_undo_restoresVisibilityAndFlippedRows() {
        repo.upsertPhoto(photo(3L, text = "猫だ。猫だ。"))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 3L, "猫だ。")
        repo.upsertExtractedItem(LanguageTag.JAPANESE, ItemType.WORD, "猫", "猫", "ねこ", "cat")
        repo.markOccurrenceSeen(repo.undoOccRowsForPhoto(3L).first().id) // one reviewed row stays
        val seenBefore = repo.undoOccRowsForPhoto(3L).first { it.state == OccurrenceState.SEEN.name }.id
        repo.hideItem(LanguageTag.JAPANESE, ItemType.WORD, "猫")
        val (vis, hidden) = repo.undoItemVisibility("JAPANESE", "WORD", "猫")!!
        assertEquals("HIDDEN", vis)
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        val (vis2, hidden2) = repo.undoItemVisibility("JAPANESE", "WORD", "猫")!!
        assertEquals("ACTIVE", vis2)
        assertFalse(hidden2)
        assertEquals(OccurrenceState.SEEN.name, repo.undoOccState(seenBefore)) // was SEEN before hide: stays
    }

    @Test
    fun deletePhoto_undo_restoresFullRowIncludingGoldAndFlag() {
        repo.upsertPhoto(photo(4L, text = "猫だ。", gold = true))
        repo.setPhotoFlag(4L, "red")
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 4L, "猫だ。")
        val before = repo.undoPhotoRow(4L)!!
        val beforeRows = repo.undoOccRowsForPhoto(4L)
        repo.markPhotoDeleted(4L, "content://test/4", 1004L)
        assertEquals(PhotoStatus.DELETED.name, repo.undoPhotoStatus(4L))
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        val after = repo.undoPhotoRow(4L)!!
        assertEquals(before, after) // full row byte-identical, incl. gold + flag + text
        assertEquals(beforeRows, repo.undoOccRowsForPhoto(4L))
    }

    @Test
    fun toggleGold_undo_restoresMembership() {
        repo.upsertPhoto(photo(5L, text = "金なし。"))
        repo.setPhotoIsGold(5L, true)
        assertTrue(repo.undoGold(5L))
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertFalse(repo.undoGold(5L))
    }

    @Test
    fun textReprocess_undo_restoresTextRowsAndStates() {
        repo.upsertPhoto(photo(6L, text = "猫がいた。"))
        repo.upsertExtractedItem(LanguageTag.JAPANESE, ItemType.WORD, "猫", "猫", "ねこ", "cat")
        val curatedId = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 6L, "猫がいた。", isManual = true)
        repo.setOccurrenceStateForExpression(6L, "猫がいた。", "猫", OccurrenceState.SEEN)
        val wrongId = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "いた", 6L, "猫がいた。")
        val textBefore = repo.undoPhotoRow(6L)!!
        val rowsBefore = repo.undoOccRowsForPhoto(6L)

        runner.reprocessPhotoText(6L, "犬がいた。", reprocessWords = true)
        assertEquals("犬がいた。", repo.undoPhotoText(6L))

        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertEquals(textBefore, repo.undoPhotoRow(6L))
        assertEquals(rowsBefore, repo.undoOccRowsForPhoto(6L))
        assertEquals(OccurrenceState.SEEN.name, repo.undoOccState(curatedId))
        assertNotNull(repo.undoOccState(wrongId))
    }

    @Test
    fun wordsEdit_undo_restoresRowSet() {
        repo.upsertPhoto(photo(7L, text = "猫だ。"))
        repo.upsertExtractedItem(LanguageTag.JAPANESE, ItemType.WORD, "猫", "猫", "ねこ", "cat")
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 7L, "猫だ。")
        val before = repo.undoOccRowsForPhoto(7L)

        runner.saveSentenceWords(
            7L, "猫だ。",
            listOf(
                MiningPipelineRunner.WordEditorRow(ItemType.WORD, "猫", "猫"),
                MiningPipelineRunner.WordEditorRow(null, null, "みお"),
            ),
        )
        assertEquals(2, repo.undoOccRowsForPhoto(7L).size)

        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertEquals(before, repo.undoOccRowsForPhoto(7L))
    }

    @Test
    fun nameplateRuling_undo_restoresClaimTextAndRows() {
        repo.upsertPhoto(photo(8L, text = "マルス率いる。\n戦いだ。"))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "戦い", 8L, "戦いだ。")
        // core as production stores it: gateCjkOnly strips the punctuation from the plate line
        repo.insertNameplateClaims(8L, setOf("マルス率いる"))
        val claim = repo.pendingNameplateClaims().first { it.text == "マルス率いる" }
        val ruling = MiningRepository.NameplateClaim(claim.claimId, claim.text, claim.contextSentence, null)
        val textBefore = repo.undoPhotoRow(8L)!!
        val rowsBefore = repo.undoOccRowsForPhoto(8L)

        NameplateRuling.apply(context, repo, runner, 8L, ruling, correct = true)
        assertEquals(1, repo.undoPhotoText(8L)!!.lineSequence().count { it.isNotBlank() })

        // ONE memento for the ruling (the stripped text + dropped rows + verdict all in one undo)
        assertEquals(UndoAction.NAMEPLATE_RULING, repo.latestUndo()?.action)
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertEquals(textBefore, repo.undoPhotoRow(8L))
        assertEquals(rowsBefore, repo.undoOccRowsForPhoto(8L))
        assertNull("claim is pending again", repo.readableDatabase.rawQuery(
            "SELECT verdict FROM nameplate_claims WHERE id = ?", arrayOf(claim.claimId.toString()),
        ).use { c -> c.moveToFirst(); if (c.isNull(0)) null else c.getString(0) })
    }

    @Test
    fun reorder_undo_restoresPriorRanks() {
        repo.upsertPhoto(photo(9L, text = "文一。\n文二。"))
        repo.upsertExtractedItem(LanguageTag.JAPANESE, ItemType.WORD, "文", "文", "ぶん", "sentence")
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "文", 9L, "文一。")
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "文", 9L, "文二。")
        val ranksBefore = repo.undoSentenceRanks("JAPANESE", "WORD", "文")
        assertEquals(null, ranksBefore)

        repo.saveSentenceOrder(LanguageTag.JAPANESE, ItemType.WORD, "文", listOf("文二。", "文一。"))
        assertNotNull(repo.undoSentenceRanks("JAPANESE", "WORD", "文"))

        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertNull(repo.undoSentenceRanks("JAPANESE", "WORD", "文"))
    }

    // ---- guards & stack behavior ----

    @Test
    fun guard_divergentChangeAfterwards_refusesUndoAndKeepsEntry() {
        repo.upsertPhoto(photo(10L, text = "猫だ。"))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 10L, "猫だ。")
        runner.reprocessPhotoText(10L, "犬だ。", reprocessWords = false)
        // something else moved the text after the logged action (external write / re-edit):
        repo.writableDatabase.execSQL("UPDATE scraped_photos SET extracted_text = 'ウサギだ。' WHERE media_store_id = 10")
        val entryBefore = repo.latestUndo()
        assertEquals(UndoOutcome.GUARD_FAILED, UndoManager.undoLatest(repo))
        assertEquals("ウサギだ。", repo.undoPhotoText(10L)) // nothing clobbered
        assertEquals(entryBefore?.id, repo.latestUndo()?.id) // entry preserved
    }

    @Test
    fun stack_lifoUndo_restoresEachPriorStateInOrder() {
        repo.upsertPhoto(photo(11L, text = "一。"))
        repo.setPhotoIsGold(11L, true)      // entry A
        runner.reprocessPhotoText(11L, "二。", reprocessWords = false) // entry B
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo)) // undo B first
        assertEquals("一。", repo.undoPhotoText(11L))
        assertTrue("gold untouched by B undo", repo.undoGold(11L))
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo)) // undo A
        assertFalse(repo.undoGold(11L))
        assertEquals(UndoOutcome.NOTHING_LEFT, UndoManager.undoLatest(repo))
    }

    @Test
    fun delivery_announcesOnce() {
        repo.upsertPhoto(photo(12L, text = "猫だ。"))
        repo.setPhotoIsGold(12L, true)
        val entry = repo.latestUnannouncedUndo()
        assertNotNull(entry)
        repo.markUndoAnnounced(entry!!.id)
        assertNull(repo.latestUnannouncedUndo())
        assertNotNull(repo.latestUndo()) // announced but still undoable
    }

    @Test
    fun log_prunesToNewestFifty() {
        repo.upsertPhoto(photo(13L, text = "猫だ。"))
        repeat(60) { i -> repo.setPhotoIsGold(13L, i % 2 == 0) }
        val count = repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM undo_log", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(50, count)
    }

    @Test
    fun noOpWrites_logNothing() {
        repo.upsertPhoto(photo(14L, text = "猫だ。"))
        repo.setPhotoIsGold(14L, false) // already false
        val occId = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 14L, "猫だ。")
        repo.markOccurrenceSeen(occId)
        repo.markOccurrenceSeen(occId) // re-mark is a no-op: no second entry
        val count = repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM undo_log", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, count)
    }

    companion object {
        private const val DB_NAME = "mining_undo_test.db"
        private const val MIG_DB = "test_mig_v10_undo.db"
    }
}
