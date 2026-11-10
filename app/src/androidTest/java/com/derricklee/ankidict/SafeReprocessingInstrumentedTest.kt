package com.derricklee.ankidict

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap §1 "Safe reprocessing" acceptance: editor-driven reprocessing is atomic
 * (injected failure leaves the previous corpus intact) and preserves triage history
 * for unchanged occurrences instead of deleting and recreating them unseen.
 */
@RunWith(AndroidJUnit4::class)
class SafeReprocessingInstrumentedTest {
    private lateinit var repository: MiningRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        repository = MiningRepository(context, TEST_DB_NAME)
    }

    @After
    fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB_NAME)
    }

    // ---- fixture ---------------------------------------------------------

    private inner class Fixture {
        var failWords = false
        var words = mutableMapOf(
            "第一。" to listOf("語A", "語B"),
            "第二。" to listOf("語C"),
            "第一改。" to listOf("語B", "語D"),
            "第二改。" to listOf("語C"),
        )
        val runner: MiningPipelineRunner by lazy {
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
                        override fun extractWords(sentence: String): List<NormalizedCandidate> {
                            check(!failWords) { "fixture word-extraction failure" }
                            return (words[sentence] ?: emptyList()).map { NormalizedCandidate(it, it) }
                        }
                    },
                    grammarAnalyzer = object : GrammarAnalyzer {
                        override fun extractGrammar(sentence: String) = emptyList<NormalizedCandidate>()
                    },
                    dictionaryProviders = emptyList(),
                )),
            )
        }

        fun seedPhoto(text: String = "第一。\n第二。") {
            repository.upsertPhoto(
                ScrapedPhoto(
                    mediaStoreId = PHOTO_ID,
                    contentUri = "content://media/external/images/media/$PHOTO_ID",
                    dateAdded = 1700000000L,
                    languageTag = LanguageTag.JAPANESE,
                    extractedText = text,
                    status = PhotoStatus.KEEP,
                ),
            )
            insertWord("語A", "第一。")
            insertWord("語B", "第一。")
            insertWord("語C", "第二。")
        }

        fun insertWord(expression: String, sentence: String) {
            repository.insertOccurrence(
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.WORD,
                expression = expression,
                photoId = PHOTO_ID,
                sentence = sentence,
                createdAt = 1700000000L,
            )
        }

        fun setState(expression: String, sentence: String, state: OccurrenceState) {
            val values = android.content.ContentValues().apply { put("state", state.name) }
            repository.writableDatabase.update(
                "item_occurrences", values,
                "photo_id = ? AND expression = ? AND sentence = ?",
                arrayOf(PHOTO_ID.toString(), expression, sentence),
            )
        }

        fun rows(sentence: String? = null): List<Triple<String, String, Int>> {
            val where = if (sentence == null) "photo_id = ?" else "photo_id = ? AND sentence = ?"
            val args = if (sentence == null) arrayOf(PHOTO_ID.toString())
            else arrayOf(PHOTO_ID.toString(), sentence)
            return repository.readableDatabase.rawQuery(
                "SELECT expression, state, modified_by_user_flag FROM item_occurrences WHERE $where", args,
            ).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getInt(2))) }
            }
        }

        fun rowId(expression: String, sentence: String): Long =
            repository.readableDatabase.rawQuery(
                "SELECT id FROM item_occurrences WHERE photo_id = ? AND expression = ? AND sentence = ?",
                arrayOf(PHOTO_ID.toString(), expression, sentence),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }

    // ---- acceptance tests ------------------------------------------------

    @Test
    fun unchangedReprocess_preservesTriageAndRowIdentity_noResurrection() {
        val f = Fixture()
        f.seedPhoto()
        f.setState("語A", "第一。", OccurrenceState.SEEN)
        f.setState("語C", "第二。", OccurrenceState.DELETED)
        val idBeforeA = f.rowId("語A", "第一。")

        val summary = f.runner.reprocessPhotoText(PHOTO_ID, "第一。\n第二。", reprocessWords = true)

        assertEquals(PhotoTextReprocessSummary(true, true, keptSentences = 2), summary)
        // Same rows, same ids, same states — deleted entries are NOT resurrected as UNSEEN.
        assertEquals(idBeforeA, f.rowId("語A", "第一。"))
        assertEquals(
            setOf("語A" to "SEEN", "語B" to "UNSEEN", "語C" to "DELETED"),
            f.rows().map { it.first to it.second }.toSet(),
        )
    }

    @Test
    fun editedSentence_reconcilesAtCandidateLevel_sharedWordKeepsState() {
        val f = Fixture()
        f.seedPhoto()
        f.setState("語B", "第一。", OccurrenceState.SEEN)

        val summary = f.runner.reprocessAmendedSentence(PHOTO_ID, "第一。", "第一改。", reprocessWords = true)

        // 語B shared old/new: renamed row KEPT with SEEN state (no delete/recreate).
        // 語A stale: dropped. 語D new: inserted UNSEEN. 第二。 untouched.
        assertEquals(SentenceReprocessSummary(true, true, keptCandidates = 1, addedCandidates = 1, removedCandidates = 1), summary)
        assertEquals(
            setOf("語B" to "SEEN", "語D" to "UNSEEN", "語C" to "UNSEEN"),
            f.rows().map { it.first to it.second }.toSet(),
        )
        assertEquals(0, f.rows("第一。").size)
    }

    @Test
    fun extractionFailure_leavesWholeCorpusIntact() {
        val f = Fixture()
        f.seedPhoto()
        f.setState("語A", "第一。", OccurrenceState.SEEN)
        val textBefore = repository.getPhoto(PHOTO_ID)?.extractedText
        val rowsBefore = f.rows().sortedWith(compareBy({ it.first }, { it.second }, { it.third }))

        f.failWords = true
        val failure = runCatching {
            f.runner.reprocessPhotoText(PHOTO_ID, "第一。\n第二。\n第三。", reprocessWords = true)
        }.exceptionOrNull()
        assertEquals("fixture word-extraction failure", failure?.message)

        // Nothing written: text, rows, states and ids all as before.
        assertEquals(textBefore, repository.getPhoto(PHOTO_ID)?.extractedText)
        assertEquals(rowsBefore, f.rows().sortedWith(compareBy({ it.first }, { it.second }, { it.third })))
    }

    @Test
    fun removedSentence_dropsItsRows_onlyThatHistoryIsLost() {
        val f = Fixture()
        f.seedPhoto()
        f.setState("語C", "第二。", OccurrenceState.SEEN)

        val summary = f.runner.reprocessPhotoText(PHOTO_ID, "第一。", reprocessWords = true)

        assertEquals(1, summary.removedSentences)
        assertEquals(0, summary.addedSentences)
        assertEquals(1, summary.keptSentences)
        assertEquals(setOf("語A" to "UNSEEN", "語B" to "UNSEEN"), f.rows().map { it.first to it.second }.toSet())
        assertEquals(0, f.rows("第二。").size)
    }

    @Test
    fun sentenceCollision_mergesDuplicates_keepingMostTriaged() {
        val f = Fixture()
        f.seedPhoto()
        // 語C exists in 第二。 (marked SEEN there); editing 第一。→第二改。 produces 語C again.
        // updateSentenceText renames 第一。 rows; collision path is edited sentence landing ON an
        // existing sentence: 第二改。 == 第二。 in words, so rename 第一。→ 第二。 directly.
        f.words["第二。"] = listOf("語C", "語B") // extraction of 第二。 now shares 語B with old 第一。
        f.insertWord("語B", "第二。") // pre-existing UNSEEN duplicate target
        f.setState("語B", "第一。", OccurrenceState.SEEN) // the better row comes from the renamed stream
        f.setState("語C", "第二。", OccurrenceState.SEEN)
        f.setState("語A", "第一。", OccurrenceState.DELETED)

        val summary = f.runner.reprocessAmendedSentence(PHOTO_ID, "第一。", "第二。", reprocessWords = true)

        // 語B existed twice after the rename (renamed SEEN + existing UNSEEN): merge keeps SEEN.
        assertEquals(1, summary.mergedDuplicates)
        val exprs = f.rows("第二。").map { it.first }
        assertEquals(exprs.size, exprs.distinct().size)
        assertEquals("SEEN", f.rows("第二。").first { it.first == "語B" }.second)
        // 語A is not produced by 第二。 anymore -> dropped, and never resurrected.
        assertTrue("語A" !in exprs)
        assertEquals("SEEN", f.rows("第二。").first { it.first == "語C" }.second)
    }

    @Test
    fun textOnlySave_touchesNoOccurrences() {
        val f = Fixture()
        f.seedPhoto()
        f.setState("語A", "第一。", OccurrenceState.SEEN)
        val before = f.rows().sortedWith(compareBy({ it.first }, { it.second }, { it.third }))

        val summary = f.runner.reprocessPhotoText(PHOTO_ID, "第一。\n第二。\n追加。", reprocessWords = false)

        assertEquals(false, summary.wordsReprocessed)
        assertEquals(before, f.rows().sortedWith(compareBy({ it.first }, { it.second }, { it.third })))
        assertEquals("第一。\n第二。\n追加。", repository.getPhoto(PHOTO_ID)?.extractedText)
    }

    companion object {
        private const val TEST_DB_NAME = "mining_reprocess_test.db"
        private const val PHOTO_ID = 910001L
    }
}
