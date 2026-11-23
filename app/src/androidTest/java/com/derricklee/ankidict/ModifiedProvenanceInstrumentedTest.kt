package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provenance-semantics fixes from the 2026-11-14 modified_by_user_flag audit, approved by the
 * user 2026-11-23 (docs/roadmap-audit-notes.md "Follow-ups"):
 *  FU1 — nameplate rulings are user-APPROVED machine edits, never authorship (flag set nowhere);
 *  FU2 — editor state flips (delete/restore) are review, not authorship; the flag is sticky
 *        (raised only when asked, never cleared by a flip);
 *  FU3 — re-derivation flags rows per CHANGED SENTENCE, not per photo — a historic photo edit
 *        may no longer blanket-flag rows of sentences nobody typed.
 */
@RunWith(AndroidJUnit4::class)
class ModifiedProvenanceInstrumentedTest {
    private lateinit var repository: MiningRepository

    private val words = mutableMapOf(
        "第一。" to listOf("語A", "語B"),
        "第二。" to listOf("語C"),
        "第二改。" to listOf("語C", "語D"),
    )

    private val runner: MiningPipelineRunner by lazy {
        MiningPipelineRunner(
            miningRepository = repository,
            photoSource = object : PhotoSource {
                override fun findUnprocessedPhotos(config: ScanDiscoveryConfig) = emptyList<PhotoCandidate>()
            },
            imagePreprocessor = object : ImagePreprocessor {
                override fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage? = null
            },
            imageTagger = object : ImageTagger {
                override fun classify(image: PreparedImage) = TagResult(LanguageTag.JAPANESE, 1f)
            },
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to PipelineConfig(
                tag = LanguageTag.JAPANESE,
                textExtractor = object : TextExtractor {
                    override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) =
                        OcrDocument("", emptyList())
                },
                duplicateDetector = object : DuplicatePhotoDetector {
                    override fun checkDuplicate(extractedText: String, tag: LanguageTag, config: DuplicateDetectionConfig) =
                        DuplicateCheckResult(isDuplicate = false)
                },
                sentenceParser = CjkSentenceParser(),
                wordAnalyzer = object : WordAnalyzer {
                    override fun extractWords(sentence: String): List<NormalizedCandidate> =
                        (words[sentence] ?: emptyList()).map { NormalizedCandidate(it, it) }
                },
                grammarAnalyzer = object : GrammarAnalyzer {
                    override fun extractGrammar(sentence: String) = emptyList<NormalizedCandidate>()
                },
                dictionaryProviders = emptyList(),
            )),
        )
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        repository = MiningRepository(context, TEST_DB_NAME)
        repository.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = PHOTO_ID,
                contentUri = "content://media/external/images/media/$PHOTO_ID",
                dateAdded = 1700000000L,
                languageTag = LanguageTag.JAPANESE,
                extractedText = "第一。\n第二。",
                status = PhotoStatus.KEEP,
            ),
        )
        insertWord("語A", "第一。")
        insertWord("語B", "第一。")
        insertWord("語C", "第二。")
    }

    @After
    fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB_NAME)
    }

    private fun insertWord(expression: String, sentence: String, flag: Boolean = false) {
        if (!repository.hasExtractedItem(LanguageTag.JAPANESE, ItemType.WORD, expression)) {
            repository.upsertExtractedItem(
                languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
                expression = expression, kanji = expression, kana = expression,
                meaning = "test meaning",
            )
        }
        repository.insertOccurrence(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
            expression = expression, photoId = PHOTO_ID, sentence = sentence,
            createdAt = 1700000000L, modifiedByUserFlag = flag,
        )
    }

    private fun photoFlag(): Boolean = repository.getPhoto(PHOTO_ID)!!.modifiedByUserFlag

    private fun rowFlags(sentence: String): Map<String, Int> =
        repository.readableDatabase.rawQuery(
            "SELECT expression, modified_by_user_flag FROM item_occurrences " +
                "WHERE photo_id = ? AND sentence = ?",
            arrayOf(PHOTO_ID.toString(), sentence),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    // ---- FU1: ruling ≠ authorship, flag set nowhere --------------------------------------

    @Test
    fun rulingPath_nonAuthorshipReprocess_setsFlagAtNoLevel() {
        // Same call shape NameplateRuling uses for an inline 正しい ruling (authorship = false):
        // sentence two replaced, words rebuilt.
        val amended = "第一。\n第二改。"
        runner.reprocessPhotoText(PHOTO_ID, amended, reprocessWords = true, authorship = false)

        assertFalse("a ruling must never make the photo user-authored", photoFlag())
        val newRows = rowFlags("第二改。")
        assertTrue("re-derivation must have produced rows: $newRows", newRows.isNotEmpty())
        assertTrue(
            "ruling-derived rows must not inherit authorship: $newRows",
            newRows.values.all { it == 0 },
        )
        // untouched human rows keep whatever they had (nothing here set them; rows survive)
        assertEquals(mapOf("語A" to 0, "語B" to 0), rowFlags("第一。"))
    }

    // ---- FU2: state flip is review, not authorship; sticky ---------------------------------

    @Test
    fun stateFlip_isNotAuthorship_andNeverClearsExistingFlag() {
        insertWord("語Z", "第二。", flag = true) // genuinely edited row

        repository.setOccurrenceStateForExpression(PHOTO_ID, "第二。", "語C", OccurrenceState.DELETED)
        repository.setOccurrenceStateForExpression(PHOTO_ID, "第二。", "語Z", OccurrenceState.SEEN)

        val c = rowFlags("第二。")
        assertEquals("delete-only flip must not mark authorship", 0, c["語C"])
        assertEquals("edited row keeps its flag through a restore flip (sticky)", 1, c["語Z"])
        // is_manual remains the protection for flipped rows
        val manual = repository.readableDatabase.rawQuery(
            "SELECT is_manual FROM item_occurrences WHERE photo_id = ? AND sentence = ? AND expression = ?",
            arrayOf(PHOTO_ID.toString(), "第二。", "語C"),
        ).use { it.moveToFirst() && it.getInt(0) == 1 }
        assertTrue("state flip keeps the row reconcile-protected via is_manual", manual)
    }

    // ---- FU3: narrowing — machine-provenance sentences no longer inherit the photo flag -----

    @Test
    fun staleRowsReprocess_typedSentencesKeepAuthorship_machineSentencesDoNot() {
        // Photo with a real machine baseline: raw OCR produced 第一。 and 第二。.
        repository.upsertPhoto(
            repository.getPhoto(PHOTO_ID)!!.copy(
                ocrBlocksJson = OcrDocument("第一。\n第二。", emptyList()).toJsonString(),
            ),
        )
        // Human types a new line via a TEXT-ONLY save (rows go stale); photo carries provenance.
        repository.updatePhotoExtractedText(PHOTO_ID, "第一。\n第二改。\n真実を見る。")
        assertTrue("a real human text edit DOES flag the photo", photoFlag())
        // All rows are dropped (stale keys) — re-derivation rebuilds every sentence fresh.
        repository.writableDatabase.delete("item_occurrences", "photo_id = ?", arrayOf(PHOTO_ID.toString()))
        runner.reprocessPhotoText(PHOTO_ID, "第一。\n第二改。\n真実を見る。", reprocessWords = true)

        val typed = rowFlags("第二改。")
        assertTrue("typed sentence must exist: $typed", typed.isNotEmpty())
        assertTrue("human-typed sentence keeps authorship: $typed", typed.values.all { it == 1 })
        val machine = rowFlags("第一。")
        assertTrue("machine sentence must have been re-derived: $machine", machine.isNotEmpty())
        assertTrue(
            "re-derived raw-OCR sentence must NOT blanket-inherit the photo flag: $machine",
            machine.values.all { it == 0 },
        )
    }

    @Test
    fun humanTextEdit_flagsChangedSentenceRows_only() {
        // Authorship path (default): editing sentence 2 flags the rows of the NEW sentence
        // (that text was typed) — and only its rows.
        runner.reprocessPhotoText(PHOTO_ID, "第一。\n第二改。", reprocessWords = true)

        assertTrue("genuine human text edit flags the photo", photoFlag())
        val edited = rowFlags("第二改。")
        assertTrue("edited sentence rows carry authorship: $edited", edited.values.all { it == 1 })
        assertEquals("untouched sentence rows untouched", mapOf("語A" to 0, "語B" to 0), rowFlags("第一。"))
    }

    companion object {
        private const val TEST_DB_NAME = "mining_provenance_test.db"
        private const val PHOTO_ID = 977_001L
    }
}
