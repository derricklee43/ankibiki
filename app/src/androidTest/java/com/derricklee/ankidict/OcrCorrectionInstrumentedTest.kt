package com.derricklee.ankidict

import android.graphics.Bitmap
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration spec for the shipping auto-correction loop (user decision 2026-11-13;
 * spike 003 S003-D'): pipeline wiring, schema v6 `ocr_corrections` persistence, and the
 * cloud-unavailable no-op path. The gate's statistical parity lives in JVM
 * OcrCorrectionGateParityTest; this spec uses the REAL bundled lexicon over synthetic documents
 * so only integration behavior is under test. The corpus under test is a named sandbox DB —
 * never mining.db.
 */
@RunWith(AndroidJUnit4::class)
class OcrCorrectionInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository

    private class FakeExtractor(private val doc: OcrDocument) : TextExtractor {
        override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) = doc
    }

    private class NoDupDetector : DuplicatePhotoDetector {
        override fun checkDuplicate(extractedText: String, tag: LanguageTag, config: DuplicateDetectionConfig) =
            DuplicateCheckResult(isDuplicate = false)
    }

    private class FakeWordAnalyzer : WordAnalyzer {
        override fun extractWords(sentence: String) = listOf(NormalizedCandidate(sentence, sentence))
    }

    private fun prepared(): PreparedImage {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return PreparedImage(bitmap = bmp, jpegBytes = ByteArray(8))
    }

    private fun doc(vararg lines: String, provider: String? = "on-device") = OcrDocument(
        fullText = lines.joinToString("\n"),
        blocks = lines.map { OcrBlock(text = it, lines = listOf(OcrLine(it, 100))) },
        provider = provider,
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, DB_NAME)
        repo.writableDatabase.delete("scraped_photos", null, null)
        repo.writableDatabase.delete("item_occurrences", null, null)
        repo.writableDatabase.delete("ocr_corrections", null, null)
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun schema_current_createsOcrCorrectionsTable() {
        val db = repo.readableDatabase
        val uv = db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(7, uv) // the gate table arrived in v6; assert the CURRENT version so a bump fails loudly
        val found = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ocr_corrections'", null
        ).use { c -> c.moveToFirst() }
        assertTrue("ocr_corrections table missing after v6 migration", found)
        val indexed = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_ocr_corrections_photo'", null
        ).use { c -> c.moveToFirst() }
        assertTrue("photo_id index missing", indexed)
    }

    private fun configWith(cloudDoc: OcrDocument?, localDoc: OcrDocument): PipelineConfig {
        val pass = OcrCorrectionPass(
            gateProvider = { OcrCorrectionGate(GateLexiconProvider.lexicon(context)) },
            reader = CloudReedReader { cloudDoc },
        )
        return PipelineConfig(
            tag = LanguageTag.JAPANESE,
            textExtractor = FakeExtractor(localDoc),
            duplicateDetector = NoDupDetector(),
            sentenceParser = CjkSentenceParser(),
            wordAnalyzer = FakeWordAnalyzer(),
            grammarAnalyzer = NoOpGrammarAnalyzer(),
            dictionaryProviders = emptyList(),
            correctionPass = pass,
            ocrCorrectionEnabled = true,
        )
    }

    @Test
    fun miningThroughPipeline_autoAppliesAnchoredFix_andPersistsEvidence() {
        val candidate = PhotoCandidate(777_001L, "content://test/777001", 1000L)
        val local = doc("陸下という方々だ。")
        val cloud = doc("陛下という方々だ。")
        val outcome = MiningPipelineRunner(repo, MediaStoreNoopSource, FakePrep, NoopTagger, mapOf(LanguageTag.JAPANESE to configWith(cloud, local)))
            .processOcrDocument(candidate, configWith(cloud, local), local, checkDuplicates = true, prepared = prepared())

        assertEquals(PhotoStatus.KEEP, outcome.status)
        val photo = repo.getPhoto(candidate.mediaStoreId)!!
        assertTrue(
            "stored text should carry the anchored fix 陛下: ${photo.extractedText}",
            photo.extractedText.contains("陛下") && !photo.extractedText.contains("陸下"),
        )
        val persisted = OcrDocument.fromJsonString(photo.ocrBlocksJson)!!
        assertTrue(persisted.blocks.flatMap { it.lines }.any { it.text.contains("陛下") })
        assertTrue(persisted.note.orEmpty().contains("cloud-gate: 1"))

        repo.readableDatabase.rawQuery(
            "SELECT a_text, b_text, decision FROM ocr_corrections WHERE photo_id = ?",
            arrayOf(candidate.mediaStoreId.toString()),
        ).use { c ->
            val rows = generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null }.toList()
            assertTrue("expected an applied 陸->陛 island, got $rows", rows.any { it.first == "陸" && it.second == "陛" && it.third == "applied" })
        }
    }

    @Test
    fun cloudUnavailable_storesTextUnchanged_withNoEvidence() {
        val candidate = PhotoCandidate(777_002L, "content://test/777002", 1000L)
        val local = doc("陸下という方々だ。")
        val config = configWith(null, local)
        MiningPipelineRunner(repo, MediaStoreNoopSource, FakePrep, NoopTagger, mapOf(LanguageTag.JAPANESE to config))
            .processOcrDocument(candidate, config, local, checkDuplicates = false, prepared = prepared())
        val photo = repo.getPhoto(candidate.mediaStoreId)!!
        assertTrue("no cloud read must leave text untouched", photo.extractedText.contains("陸下"))
        val n = repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM ocr_corrections WHERE photo_id = ?", arrayOf("777002")).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, n)
    }

    @Test
    fun rescanCleanup_helperDropsPriorEvidence() {
        repo.insertCorrectionRows(
            listOf(CorrectionEvidenceRow(777_003L, 0, 0, 1, "陸", "陛", "kanji", 0.5, false, true, false, false, "applied", null, "x", "y", 1L))
        )
        val before = repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM ocr_corrections WHERE photo_id = ?", arrayOf("777003")).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, before)
        repo.deleteCorrectionsForPhoto(777_003L)
        val after = repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM ocr_corrections WHERE photo_id = ?", arrayOf("777003")).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, after)
        assertNotNull(repo)
    }

    @Test
    fun migration_v5toCurrent_preservesRows_andAddsCorrectionsTable() {
        // Build a real v5 corpus file (tables as they stood at schema 5, user_version=5, one row
        // in each), then open it through MiningRepository so SQLiteOpenHelper.onUpgrade(5->6)
        // runs exactly as on a user device.
        repo.close()
        val dbFile = context.getDatabasePath("test_mig_v5.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        val v5 = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v5.use { db ->
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
            db.execSQL("PRAGMA user_version = 5")
            db.execSQL(
                "INSERT INTO scraped_photos (media_store_id, content_uri, date_added, language_tag, extracted_text, status) " +
                    "VALUES (999, 'content://old/999', 1, 'JAPANESE', '古い文。', 'KEEP')",
            )
        }
        val migrated = MiningRepository(context, "test_mig_v5.db")
        try {
            val db = migrated.readableDatabase
            val uv = db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals("onUpgrade must land the file at the current version", 7, uv)
            val table = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='ocr_corrections'", null)
                .use { c -> c.moveToFirst() }
            assertTrue("v6 migration did not add ocr_corrections", table)
            val photo = migrated.getPhoto(999L)
            assertNotNull("pre-existing row lost in migration", photo)
            assertEquals("古い文。", photo!!.extractedText)
        } finally {
            migrated.close()
            context.deleteDatabase("test_mig_v5.db")
        }
        repo = MiningRepository(context, DB_NAME) // restore for tearDown
    }

    // Minimal collaborators so only the correction loop is under test.
    private object MediaStoreNoopSource : PhotoSource {
        override fun findUnprocessedPhotos(config: ScanDiscoveryConfig) = emptyList<PhotoCandidate>()
    }

    private object FakePrep : ImagePreprocessor {
        override fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage? = null
    }

    private object NoopTagger : ImageTagger {
        override fun classify(image: PreparedImage) = TagResult(LanguageTag.JAPANESE, 1f, null)
    }

    companion object {
        private const val DB_NAME = "test_ocr_correction.db"
    }
}
