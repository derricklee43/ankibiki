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
 * Sentence word editor + gold freeze (user decisions 2026-11-13):
 *  - the editor edits occurrence rows of ONE sentence: free-form adds (no grounding rule),
 *    renames preserve review state, deletes are soft and re-adding restores;
 *  - manual rows survive candidate-granularity reprocessing;
 *  - gold is the finalizing stamp: rescan refuses, word re-derivation never runs.
 */
@RunWith(AndroidJUnit4::class)
class SentenceWordEditorInstrumentedTest {

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
    fun goldAmendedSentence_savesTextOnly_neverReDerives() {
        repo.upsertPhoto(photo(6L, text = "金だ。", gold = true))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, " curated", 6L, "金だ。", modifiedByUserFlag = true)
        val summary = runner.reprocessAmendedSentence(6L, "金だ。", "金だ。!", reprocessWords = true)
        assertFalse("word re-derivation is impossible on gold", summary.wordsReprocessed)
        val updated = repo.getPhoto(6L)!!
        assertTrue(updated.extractedText.contains("金だ。!"))
        val rows = repo.getSentenceCandidates(6L, "金だ。!")
        assertEquals("curated occurrence set rode through the text amendment", listOf(" curated"), rows.map { it.expression })
    }

    @Test
    fun goldPhotoTextSave_coercesToTextOnly() {
        repo.upsertPhoto(photo(7L, text = "古い文。", gold = true))
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "手", 7L, "古い文。", modifiedByUserFlag = true)
        val summary = runner.reprocessPhotoText(
            mediaStoreId = 7L,
            amendedText = "新しい文。",
            reprocessWords = true,
        )
        assertFalse("gold photos never re-derive words", summary.wordsReprocessed)
        assertEquals("新しい文。", repo.getPhoto(7L)!!.extractedText)
        // Same semantics as any deliberate text-only save: curated rows stay exactly as they
        // were (old sentence key, untouched). To rebuild a gold photo's words, unmark gold.
        assertEquals(listOf("手"), repo.occurrenceRowsForSentence(7L, "古い文。").map { it.expression })
        assertTrue(repo.getSentenceCandidates(7L, "新しい文。").isEmpty())
    }

    companion object {
        private const val DB_NAME = "mining_editor_test.db"
    }
}
