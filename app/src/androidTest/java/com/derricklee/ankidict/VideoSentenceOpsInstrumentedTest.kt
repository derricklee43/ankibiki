package com.derricklee.ankidict

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Media-anchored sentence ops (user request 2026-11-26): the single-sentence view for
 * video-cue sentences gets the word editor (rename/add/remove) and whole-sentence
 * delete, each undoable. Scope is (media_refs.id, sentence); the photo-keyed twins are
 * pinned by SentenceWordEditorInstrumentedTest and stay untouched.
 */
@RunWith(AndroidJUnit4::class)
class VideoSentenceOpsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository
    private lateinit var runner: MiningPipelineRunner

    private class EveryWord : WordAnalyzer {
        override fun extractWords(sentence: String) = listOf(NormalizedCandidate(sentence, sentence))
    }

    private val sentence = "猫と犬。"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, DB_NAME)
        val config = PipelineConfig(
            tag = LanguageTag.JAPANESE,
            textExtractor = object : TextExtractor {
                override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) =
                    error("unused")
            },
            duplicateDetector = object : DuplicatePhotoDetector {
                override fun checkDuplicate(
                    extractedText: String,
                    tag: LanguageTag,
                    config: DuplicateDetectionConfig,
                ) = DuplicateCheckResult(false)
            },
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
                override fun classify(image: PreparedImage) = error("unused")
            },
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to config),
        )
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
    }

    /** Seed a video_cues ref with two word occurrences of one sentence (photo_id NULL). */
    private fun seedVideoSentence(): Long {
        val ref = repo.getOrCreateMediaRef("video_cues", 7L, "tt9335498:1:1")
        for (expr in listOf("猫", "犬")) {
            repo.insertOccurrence(
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.WORD,
                expression = expr,
                photoId = null,
                sentence = sentence,
                mediaId = ref,
            )
        }
        return ref
    }

    private fun exprs(ref: Long) =
        repo.getSentenceCandidatesForMedia(ref, sentence).map { it.expression }

    @Test
    fun mediaEditorRenameAndAdd_roundTripsThroughUndo() {
        val ref = seedVideoSentence()
        // sort contract mirrors the shipped photo twin getSentenceCandidates:
        // words before grammar, then expression ASC (犬 U+72AC precedes 猫 U+732B)
        assertEquals(listOf("犬", "猫"), exprs(ref))
        val changes = runner.saveSentenceWordsForMedia(
            ref, LanguageTag.JAPANESE, sentence,
            listOf(
                MiningPipelineRunner.WordEditorRow(ItemType.WORD, "猫", "猫"),
                MiningPipelineRunner.WordEditorRow(ItemType.WORD, "犬", "イヌ"),
                MiningPipelineRunner.WordEditorRow(null, null, "ねこ"),
            ),
        )
        assertEquals(2, changes) // one rename + one add
        // membership after one rename + one add (natural order would be
        // [ねこ, イヌ, 猫] — hiragana U+304x sorts before katakana U+30Ax)
        assertEquals(setOf("イヌ", "猫", "ねこ"), exprs(ref).toSet())

        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        // byte-scope restore: 犬 returns with its row, ねこ (added after capture) drops,
        // photo_id stays NULL and media_id survives on the restored video rows
        assertEquals(listOf("犬", "猫"), exprs(ref))
    }

    @Test
    fun mediaSentenceDelete_hidesRows_andUndoRestoresStates() {
        val ref = seedVideoSentence()
        val changes = runner.deleteSentence(ref, sentence)
        assertEquals(2, changes)
        assertTrue(exprs(ref).isEmpty())
        assertTrue(
            repo.occurrenceStatesForExpressionForMedia(ref, sentence, "猫")
                .contains(OccurrenceState.DELETED.name),
        )

        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertEquals(listOf("犬", "猫"), exprs(ref))
    }

    @Test
    fun retiredMediaRef_blocksUndoRestore() {
        val ref = seedVideoSentence()
        runner.deleteSentence(ref, sentence)
        // v15 I1: re-mining the episode retires the old ref; an undo must NOT resurrect
        // rows onto a dead source
        repo.writableDatabase.execSQL("UPDATE media_refs SET active = 0 WHERE id = ?", arrayOf(ref.toString()))
        assertEquals(UndoOutcome.GUARD_FAILED, UndoManager.undoLatest(repo))
        assertTrue(exprs(ref).isEmpty())
    }

    companion object {
        private const val DB_NAME = "mining_video_sentence_test.db"
    }
}
