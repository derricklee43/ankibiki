package com.derricklee.ankidict

import android.content.Context
import android.graphics.Bitmap
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
 * Nameplate audit claims (schema v8): the mining proxy drafts pending rows for leaked plates,
 * the user's verdict is the only destructive action, and confirmed plates stay hidden across
 * future mine/rescan passes. Sandbox DB only — never mining.db. (User workflow 2026-11-13:
 * render the plate in the photo view with 正しい/違う; once ruled, hidden for good.)
 */
@RunWith(AndroidJUnit4::class)
class NameplateClaimsInstrumentedTest {

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

    private object MediaStoreNoopSource : PhotoSource {
        override fun findUnprocessedPhotos(config: ScanDiscoveryConfig) = emptyList<PhotoCandidate>()
    }

    private object FakePrep : ImagePreprocessor {
        override fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage? = null
    }

    private object NoopTagger : ImageTagger {
        override fun classify(image: PreparedImage) = TagResult(LanguageTag.JAPANESE, 1f, null)
    }

    private fun doc(vararg lines: String) = OcrDocument(
        fullText = lines.joinToString("\n"),
        blocks = lines.map { OcrBlock(text = it, lines = listOf(OcrLine(it, 100))) },
        provider = "on-device",
    )

    private fun config(localDoc: OcrDocument) = PipelineConfig(
        tag = LanguageTag.JAPANESE,
        textExtractor = FakeExtractor(localDoc),
        duplicateDetector = NoDupDetector(),
        sentenceParser = CjkSentenceParser(),
        wordAnalyzer = FakeWordAnalyzer(),
        grammarAnalyzer = NoOpGrammarAnalyzer(),
        dictionaryProviders = emptyList(),
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, "mining_test_nameplate.db")
        for (t in listOf("scraped_photos", "item_occurrences", "ocr_corrections", "nameplate_claims")) {
            repo.writableDatabase.delete(t, null, null)
        }
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase("mining_test_nameplate.db")
    }

    private fun runner(config: PipelineConfig) =
        MiningPipelineRunner(repo, MediaStoreNoopSource, FakePrep, NoopTagger, mapOf(LanguageTag.JAPANESE to config))

    @Test
    fun schema_v8_createsNameplateClaimsTable() {
        val db = repo.readableDatabase
        assertTrue(
            "nameplate_claims table missing at current version",
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='nameplate_claims'", null)
                .use { c -> c.moveToFirst() },
        )
        assertTrue(
            "photo_id index missing",
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_nameplate_claims_photo'", null)
                .use { c -> c.moveToFirst() },
        )
        assertEquals(MiningRepository.expectedUserVersion(), 8)
    }

    @Test
    fun mining_standalonePlate_createsPendingClaim_andLineStaysVisible() {
        val candidate = PhotoCandidate(888_001L, "content://test/888001", 1000L)
        val local = doc("マルス。", "ぼくは絶対に忘れない。")
        val outcome = runner(config(local))
            .processOcrDocument(candidate, config(local), local, checkDuplicates = false, prepared = prepared())
        assertEquals(PhotoStatus.KEEP, outcome.status)
        // Non-destructive until ruled: the plate line is STILL in the display text...
        val photo = repo.getPhoto(candidate.mediaStoreId)!!
        assertTrue("pending plate must stay visible: ${photo.extractedText}", photo.extractedText.contains("マルス"))
        // ...and exactly one PENDING claim exists (katakana-only short line drafted by proxy).
        val claims = repo.nameplateClaims(candidate.mediaStoreId)
        assertEquals("expected the leaked plate as a claim, got $claims", 1, claims.size)
        assertEquals("マルス", claims[0].text)
        assertNull("draft must not pre-judge", claims[0].verdict)
    }

    @Test
    fun applyVerdict_correct_hidesLineStripsOccurrences_andSurvivesRescan() {
        val candidate = PhotoCandidate(888_002L, "content://test/888002", 1000L)
        val local = doc("マルス。", "ぼくは絶対に忘れない。")
        runner(config(local)).processOcrDocument(candidate, config(local), local, checkDuplicates = false, prepared = prepared())
        val claim = repo.nameplateClaims(candidate.mediaStoreId).single()
        val removed = repo.applyNameplateVerdict(claim.id, correct = true)
        assertEquals("one display line stripped", 1, removed)
        val photo = repo.getPhoto(candidate.mediaStoreId)!!
        assertFalse("plate hidden for good: ${photo.extractedText}", photo.extractedText.contains("マルス"))
        assertTrue("utterance kept: ${photo.extractedText}", photo.extractedText.contains("絶対に忘れない"))
        repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM item_occurrences WHERE photo_id = ? AND sentence LIKE 'マルス%'",
            arrayOf(candidate.mediaStoreId.toString()),
        ).use { c ->
            c.moveToFirst(); assertEquals("plate occurrences deleted", 0, c.getInt(0))
        }
        // Rescan the same photo: the confirmed plate never returns and never re-pends.
        runner(config(local)).processOcrDocument(candidate, config(local), local, checkDuplicates = false, prepared = prepared())
        val after = repo.getPhoto(candidate.mediaStoreId)!!
        assertFalse("rescan must not resurrect the plate: ${after.extractedText}", after.extractedText.contains("マルス"))
        val claims = repo.nameplateClaims(candidate.mediaStoreId)
        assertEquals(1, claims.size)
        assertEquals("correct", claims.single().verdict) // CONFLICT_IGNORE kept the verdict row
    }

    @Test
    fun applyVerdict_incorrect_keepsLine_andNeverReSuggests() {
        val candidate = PhotoCandidate(888_003L, "content://test/888003", 1000L)
        val local = doc("マルス。", "ぼくは絶対に忘れない。")
        val cfg = config(local)
        runner(cfg).processOcrDocument(candidate, cfg, local, checkDuplicates = false, prepared = prepared())
        val claim = repo.nameplateClaims(candidate.mediaStoreId).single()
        val removed = repo.applyNameplateVerdict(claim.id, correct = false)
        assertEquals(0, removed)
        val before = repo.getPhoto(candidate.mediaStoreId)!!.extractedText
        assertTrue("kept line intact: $before", before.contains("マルス"))
        // Re-mine: the rejected draft must not spawn a fresh pending claim.
        runner(cfg).processOcrDocument(candidate, cfg, local, checkDuplicates = false, prepared = prepared())
        val claims = repo.nameplateClaims(candidate.mediaStoreId)
        assertEquals(1, claims.size)
        assertEquals("incorrect", claims.single().verdict)
        assertTrue(repo.getPhoto(candidate.mediaStoreId)!!.extractedText.contains("マルス"))
    }

    @Test
    fun inlineMergedPlate_getsNoClaim_v1ScopeIsStandaloneOnly() {
        // "モ口ドフはい。"-style merges are spike 004/H9 territory; the claim flow must not
        // pretend to handle them: no standalone plate sentence => no claims at all.
        val candidate = PhotoCandidate(888_004L, "content://test/888004", 1000L)
        val local = doc("モ口ドフはい。", "ぼくは絶対に忘れない。")
        val cfg = config(local)
        runner(cfg).processOcrDocument(candidate, cfg, local, checkDuplicates = false, prepared = prepared())
        assertEquals(emptyList<Any>(), repo.nameplateClaims(candidate.mediaStoreId))
    }

    @Test
    fun hiddenCores_ignoresUnjudgedAndRejected() {
        repo.insertNameplateClaims(888_005L, setOf("カイン", "グラ軍隊長"), 1L)
        assertEquals(emptySet<String>(), repo.hiddenNameplateCores(888_005L))
        val claims = repo.nameplateClaims(888_005L)
        assertEquals(2, claims.size)
        repo.applyNameplateVerdict(claims.first { it.text == "カイン" }.id, correct = false)
        assertEquals(emptySet<String>(), repo.hiddenNameplateCores(888_005L))
        repo.applyNameplateVerdict(claims.first { it.text == "カイン" }.id, correct = true)
        // flipping verdicts on the same row is legal; only 'correct' hides
        assertEquals(setOf("カイン"), repo.hiddenNameplateCores(888_005L))
    }

    @Test
    fun backfill_createsPendingOnlyForLinesStillInText() {
        // Simulate a pre-v8 photo: row + leaked plate already in text, no claims table rows.
        val candidate = PhotoCandidate(888_006L, "content://test/888006", 1000L)
        val local = doc("ジュリアン。", "その傷は、何だ。")
        runner(config(local)).processOcrDocument(candidate, config(local), local, checkDuplicates = false, prepared = prepared())
        // Fresh pipeline run already created the claim; wipe it to emulate pre-v8 storage:
        repo.writableDatabase.delete("nameplate_claims", "photo_id = ?", arrayOf(candidate.mediaStoreId.toString()))
        val inserted = repo.backfillNameplateClaims()
        assertTrue("backfill must re-derive the leaked plate", inserted >= 1)
        val claims = repo.nameplateClaims(candidate.mediaStoreId)
        assertTrue("backfilled claim pending: $claims", claims.any { it.text == "ジュリアン" && it.verdict == null })
        // Edited photo (plate removed by user): intersection with current text is empty -> no row.
        val edited = PhotoCandidate(888_007L, "content://test/888007", 1000L)
        repo.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = edited.mediaStoreId, contentUri = edited.contentUri,
                dateAdded = edited.dateAdded, languageTag = LanguageTag.JAPANESE,
                extractedText = "その傷は、何だ。", ocrBlocksJson = local.toJsonString(),
                status = PhotoStatus.KEEP, duplicateOfPhotoId = null, modifiedByUserFlag = true,
            ),
        )
        repo.backfillNameplateClaims()
        assertEquals("user-cleaned text must not spawn claims", emptyList<Any>(), repo.nameplateClaims(edited.mediaStoreId))
    }
}
