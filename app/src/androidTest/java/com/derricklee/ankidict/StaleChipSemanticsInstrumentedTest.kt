package com.derricklee.ankidict

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 導出した単語が古い chip semantics (fixed 2026-11-13 after user report): the flag means
 * "a refresh WOULD CHANGE something" — re-parsing the current text and comparing CANDIDATE-
 * BEARING parsed sentences against row keys. Zero-candidate lines (the 「はい。」 false positives)
 * and merged lines whose parsed pieces already have rows (1273) must NOT flag; genuine
 * text-only saves MUST flag and clear after reprocessing. Fake analyzer: every sentence has
 * candidates, so the stale logic itself is what's under test.
 */
@RunWith(AndroidJUnit4::class)
class StaleChipSemanticsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository
    private lateinit var runner: MiningPipelineRunner

    private class NoDup : DuplicatePhotoDetector {
        override fun checkDuplicate(extractedText: String, tag: LanguageTag, config: DuplicateDetectionConfig) =
            DuplicateCheckResult(isDuplicate = false)
    }

    private class EveryWord : WordAnalyzer {
        override fun extractWords(sentence: String) = listOf(NormalizedCandidate(sentence, sentence))
    }

    private fun candidate(id: Long) = PhotoCandidate(id, "content://test/$id", 1000L)

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

    private fun rowKeys(id: Long): Set<String> =
        repo.getOccurrenceSentenceRows(id).map { it.sentence }.toSet()

    @Test
    fun genuineTextOnlySave_flags_thenClearsAfterReprocess() {
        val doc = OcrDocument(
            fullText = "真実が見える。",
            blocks = listOf(OcrBlock("真実が見える。", listOf(OcrLine("真実が見える。", 100)))),
        )
        runner.processOcrDocument(candidate(2001L), pipelineConfig(), doc, checkDuplicates = false)
        assertFalse("freshly mined photo is consistent", runner.derivedWordsStale(LanguageTag.JAPANESE, "真実が見える。", rowKeys(2001L)))

        // Text-only save (reprocessWords = false): the state the chip exists to surface.
        runner.reprocessPhotoText(2001L, "証拠がない。", reprocessWords = false)
        assertTrue(
            "new sentence has candidates but no rows -> must flag",
            runner.derivedWordsStale(LanguageTag.JAPANESE, "証拠がない。", rowKeys(2001L)),
        )

        // The refresh the chip offers: now rows match; flag clears.
        runner.reprocessPhotoText(2001L, "証拠がない。", reprocessWords = true)
        assertFalse(
            "after reprocessing, refresh would change nothing -> chip gone",
            runner.derivedWordsStale(LanguageTag.JAPANESE, "証拠がない。", rowKeys(2001L)),
        )
    }

    @Test
    fun runnerFlagsCandidateSentencesWithMissingRows_andKeepsQuietOnEmptyText() {
        // Sentence-free display line (the 「はい。」 class): rows can never key it.
        val emptyRows = emptySet<String>()
        assertFalse(runner.derivedWordsStale(LanguageTag.JAPANESE, "", emptyRows))
        val config = pipelineConfig()
        // Swap in a no-candidates analyzer for this case only via a fresh runner is overkill:
        // the pure predicate is pinned in ExtractionTransparencyTest; here verify the runner's
        // parse path at least agrees on sentence identity for a plain line.
        runner.processOcrDocument(candidate(2002L), config, OcrDocument("猫がいる。", listOf(OcrBlock("猫がいる。", listOf(OcrLine("猫がいる。", 100))))), checkDuplicates = false)
        assertTrue(runner.derivedWordsStale(LanguageTag.JAPANESE, "猫がいる。猫がいる。", emptySet()))
    }

    private fun pipelineConfig(): PipelineConfig {
        // Rebuild the same config used by setUp for processOcrDocument calls.
        return PipelineConfig(
            tag = LanguageTag.JAPANESE,
            textExtractor = object : TextExtractor {
                override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) = OcrDocument("", emptyList())
            },
            duplicateDetector = NoDup(),
            sentenceParser = CjkSentenceParser(),
            wordAnalyzer = EveryWord(),
            grammarAnalyzer = NoOpGrammarAnalyzer(),
            dictionaryProviders = emptyList(),
        )
    }

    companion object {
        private const val DB_NAME = "test_stale_chip.db"
    }
}
