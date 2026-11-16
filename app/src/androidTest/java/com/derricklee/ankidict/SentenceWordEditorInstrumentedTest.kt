package com.derricklee.ankidict

import android.content.Context
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
 * Sentence word editor + gold semantics (user decisions 2026-11-13, gold reparse 2026-11-15):
 *  - the editor edits occurrence rows of ONE sentence: free-form adds (no grounding rule),
 *    renames preserve review state, deletes are soft and re-adding restores;
 *  - manual rows survive candidate-granularity reprocessing;
 *  - gold is the finalizing stamp against IMAGE reprocessing: rescan refuses. Word
 *    re-derivation on gold now RUNS when the user explicitly chooses 再解析 on a text/sentence
 *    edit (user go 2026-11-15, superseding the v10 text-only coercion); manual rows and review
 *    states are preserved by the same reconcile, and text-only saves stay honored.
 */
@RunWith(AndroidJUnit4::class)
class SentenceWordEditorInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository
    private lateinit var runner: MiningPipelineRunner
    /** Counts any image-OCR entry: reparse paths must leave both at zero (gold rule 1). */
    private val ocrCalls = intArrayOf(0)

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
                override fun extract(image: PreparedImage, preflightDocument: OcrDocument?): OcrDocument {
                    ocrCalls[0]++
                    return OcrDocument("", emptyList())
                }
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
                override fun classify(image: PreparedImage): TagResult {
                    ocrCalls[0]++
                    return TagResult(LanguageTag.JAPANESE, 1f, null)
                }
            },
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to config),
        )
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun freeFormAdd_createsOccurrence_andCanonicalDefinition() {
        repo.upsertPhoto(photo(1L, text = "上産だ。"))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "上", 1L, "上産だ。")
        val changes = runner.saveSentenceWords(
            1L, "上産だ。",
            listOf(
                MiningPipelineRunner.WordEditorRow(ItemType.WORD, "上", "上"),
                MiningPipelineRunner.WordEditorRow(null, null, "みお震わせる"),
            ),
        )
        assertTrue(changes >= 1)
        val exprs = repo.getSentenceCandidates(1L, "上産だ。").map { it.expression }
        assertTrue("idiom added alongside the original", exprs.containsAll(listOf("上", "みお震わせる")))
        // Manual row carries the user-modified flag so reprocessing keeps it.
        val row = repo.occurrenceRowsForSentence(1L, "上産だ。").first { it.expression == "みお震わせる" }
        assertTrue("editor additions carry word-editor provenance", row.isManual)
    }

    @Test
    fun rename_preservesReviewState() {
        repo.upsertPhoto(photo(2L, text = "上産だ。"))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "上", 2L, "上産だ。")
        repo.setOccurrenceStateForExpression(2L, "上産だ。", "上", OccurrenceState.SEEN, modifiedByUserFlag = false)
        runner.saveSentenceWords(
            2L, "上産だ。",
            listOf(MiningPipelineRunner.WordEditorRow(ItemType.WORD, "上", "上産")),
        )
        val rows = repo.occurrenceRowsForSentence(2L, "上産だ。")
        assertEquals(1, rows.size)
        assertEquals("上産", rows[0].expression)
        assertEquals(OccurrenceState.SEEN.name, rows[0].state)
        assertTrue("renamed row is user-modified and survives reprocessing", rows[0].modifiedByUserFlag)
    }

    @Test
    fun delete_isSoft_andReAddRestores() {
        repo.upsertPhoto(photo(3L, text = "身を振るわせる。"))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "身", 3L, "身を振るわせる。")
        // Remove the row entirely from the editor -> soft delete.
        runner.saveSentenceWords(3L, "身を振るわせる。", emptyList())
        assertTrue(repo.getSentenceCandidates(3L, "身を振るわせる。").isEmpty())
        assertEquals(setOf(OccurrenceState.DELETED.name), repo.occurrenceStatesForExpression(3L, "身を振るわせる。", "身"))
        // Re-adding the same word restores instead of duplicating rows.
        runner.saveSentenceWords(
            3L, "身を振るわせる。",
            listOf(MiningPipelineRunner.WordEditorRow(null, null, "身")),
        )
        val rows = repo.occurrenceRowsForSentence(3L, "身を振るわせる。")
        assertEquals("restore must not duplicate rows", 1, rows.size)
        assertEquals(OccurrenceState.UNSEEN.name, rows[0].state)
    }

    @Test
    fun manualRow_survivesCandidateReprocess() {
        repo.upsertPhoto(photo(4L, text = "本を読む。"))
        // Pipeline row: EveryWord produces the sentence itself as the only candidate.
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "本を読む。", 4L, "本を読む。")
        // Manual row the analyzer would never re-produce (word-editor provenance, v10).
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "読書", 4L, "本を読む。", isManual = true)
        runner.reprocessAmendedSentence(4L, "本を読む。", "本を読む。", reprocessWords = true)
        val exprs = repo.getSentenceCandidates(4L, "本を読む。").map { it.expression }
        assertTrue("manual occurrence never accidentally destroyed", "読書" in exprs)
    }

    @Test
    fun goldRescan_isRefused_atCodeLevel() {
        repo.upsertPhoto(photo(5L, text = "もう金。", gold = true))
        // The fake preprocessor returns null (would error if reached); SKIPPED proves the
        // gold guard fired first.
        assertEquals(PhotoRescanResult.SKIPPED, runner.rescanPhoto(candidate(5L), PhotoRescanDecision.ACCEPT))
    }

    @Test
    fun goldAmendedSentence_reprocessesWords_seversUntouchedWrongRow() {
        // GOLD REPARSE (user go 2026-11-15, superseding the v10 coercion contract): fixing a typo
        // on a gold photo WITH 再解析 must re-derive words. Untouched wrong rows are severed;
        // manual rows and review states survive; image OCR is never invoked on this path.
        repo.upsertPhoto(photo(6L, text = "金だ。", gold = true))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, " curated", 6L, "金だ。", modifiedByUserFlag = true)
        val summary = runner.reprocessAmendedSentence(6L, "金だ。", "金だ。!", reprocessWords = true)
        assertTrue("gold sentence edit re-derives words when the user chose reprocess",
            summary.wordsReprocessed)
        assertEquals("untouched wrong occurrence severed", 1, summary.removedCandidates)
        assertEquals("the amended sentence parses fresh", 1, summary.addedCandidates)
        val rows = repo.occurrenceRowsForSentence(6L, "金だ。!")
        assertEquals(listOf("金だ。!"), rows.map { it.expression })
        assertEquals(OccurrenceState.UNSEEN.name, rows[0].state)
        assertTrue("reparse does not clear gold", repo.getPhoto(6L)!!.isGold)
        assertEquals("text-edit reparse never runs image OCR", 0, ocrCalls[0])
    }

    @Test
    fun goldAmendedSentence_manualRowsSurviveReparse() {
        repo.upsertPhoto(photo(8L, text = "金だ。", gold = true))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "厳選", 8L, "金だ。", isManual = true)
        val summary = runner.reprocessAmendedSentence(8L, "金だ。", "金だ。!", reprocessWords = true)
        assertTrue(summary.wordsReprocessed)
        assertEquals("manual row is fair-game-proof", 0, summary.removedCandidates)
        val rows = repo.occurrenceRowsForSentence(8L, "金だ。!")
        assertTrue("manual row survives under the corrected sentence", rows.any { it.expression == "厳選" && it.isManual })
        assertTrue("newly parsed candidate added", rows.any { it.expression == "金だ。!" })
    }

    @Test
    fun goldPhotoTextSave_reprocessesWhenUserChooses() {
        repo.upsertPhoto(photo(7L, text = "古い文。", gold = true))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "手", 7L, "古い文。", modifiedByUserFlag = true)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "厳選", 7L, "古い文。", isManual = true)
        val summary = runner.reprocessPhotoText(
            mediaStoreId = 7L,
            amendedText = "新しい文。",
            reprocessWords = true,
        )
        assertTrue("gold no longer coerces text-only when the user chose 再解析", summary.wordsReprocessed)
        assertEquals("新しい文。", repo.getPhoto(7L)!!.extractedText)
        // Removed sentence key: untouched rows severed, manual rows survive under the old key
        // (they surface via the stale chip / sentence editor — never silently destroyed).
        assertTrue(repo.occurrenceRowsForSentence(7L, "古い文。").map { it.expression } == listOf("厳選"))
        assertEquals(listOf("新しい文。"), repo.getSentenceCandidates(7L, "新しい文。").map { it.expression })
        assertEquals(0, ocrCalls[0])
    }

    @Test
    fun goldPhotoTextSave_textOnlyChoiceStillFreezesRows() {
        // The other rung of the user's choice stays intact: reprocessWords=false is honored
        // for gold exactly as for non-gold — curated rows stay under the old key untouched.
        repo.upsertPhoto(photo(9L, text = "古い文。", gold = true))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "手", 9L, "古い文。", isManual = true)
        val summary = runner.reprocessPhotoText(9L, "新しい文。", reprocessWords = false)
        assertFalse(summary.wordsReprocessed)
        assertEquals(listOf("手"), repo.occurrenceRowsForSentence(9L, "古い文。").map { it.expression })
        assertTrue(repo.getSentenceCandidates(9L, "新しい文。").isEmpty())
    }

    companion object {
        private const val DB_NAME = "mining_editor_test.db"
    }
}
