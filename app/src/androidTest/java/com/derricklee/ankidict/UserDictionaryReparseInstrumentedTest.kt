package com.derricklee.ankidict

import android.content.Context
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
 * Instrumented SQLite specs for retroactive exact-match reparsing on user-word save
 * (docs/user-dictionary-reparse-design.md §6, RULED R1a–R7a):
 *  - cross-media matching (KEEP photos + active video_cues) while ignoring DELETED
 *    photos, retired video refs, and SENTENCE_DELETEd sentences;
 *  - manual row survival, surviving triage preservation, R5a timestamp preservation;
 *  - gold photo safety (no OCR, extracted_text/ocr_blocks_json/is_gold untouched);
 *  - R3a zero-candidate photo & Stremio cue recovery;
 *  - USER_WORD_REPARSE delta undo round-trip + retired media_ref guard;
 *  - idempotency + cross-instance userWordEntries cache invalidation.
 */
@RunWith(AndroidJUnit4::class)
class UserDictionaryReparseInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository
    private lateinit var runner: MiningPipelineRunner

    /** Deterministic Kuromoji-like base analyzer splitting 竈門炭治郎 into 3 fragments
     *  and dropping a stopword-like name アオイ unless UserDictionaryWordAnalyzer forces it. */
    private class StubBaseAnalyzer : WordAnalyzer {
        override fun extractWords(sentence: String): List<NormalizedCandidate> =
            extractTokens(sentence).mapNotNull { it.candidate }

        override fun extractTokens(sentence: String): List<ExtractedToken> {
            val out = ArrayList<ExtractedToken>()
            var i = 0
            while (i < sentence.length) {
                val rem = sentence.substring(i)
                val (surf, expr, reading) = when {
                    rem.startsWith("竈門") -> Triple("竈門", "竈門", "かまど")
                    rem.startsWith("炭") -> Triple("炭", "炭", "すみ")
                    rem.startsWith("治郎") -> Triple("治郎", "治郎", "じろう")
                    rem.startsWith("少年") -> Triple("少年", "少年", "しょうねん")
                    rem.startsWith("走る") -> Triple("走る", "走る", "はしる")
                    rem.startsWith("アオイ") -> Triple("アオイ", null, null) // filtered by base
                    else -> Triple(sentence.substring(i, i + 1), null, null)
                }
                out += ExtractedToken(
                    surf,
                    i..(i + surf.length - 1),
                    expr?.let { NormalizedCandidate(it, surf, reading) },
                )
                i += surf.length
            }
            return out
        }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        repo = MiningRepository(context, DB_NAME)
        val jpConfig = PipelineConfig(
            tag = LanguageTag.JAPANESE,
            textExtractor = object : TextExtractor {
                override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) =
                    error("OCR must never be invoked by user-dictionary reparse")
            },
            duplicateDetector = object : DuplicatePhotoDetector {
                override fun checkDuplicate(
                    extractedText: String,
                    tag: LanguageTag,
                    config: DuplicateDetectionConfig,
                ) = DuplicateCheckResult(isDuplicate = false)
            },
            sentenceParser = CjkSentenceParser(),
            wordAnalyzer = UserDictionaryWordAnalyzer(
                StubBaseAnalyzer(),
                { repo.userWordEntries(LanguageTag.JAPANESE) },
            ),
            grammarAnalyzer = NoOpGrammarAnalyzer(),
            dictionaryProviders = listOf(
                UserDictionaryProvider { repo.userWordEntries(LanguageTag.JAPANESE) },
            ),
            ankiMatchLookup = null,
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
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to jpConfig),
        )
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun seedPhoto(
        id: Long,
        text: String,
        status: PhotoStatus = PhotoStatus.KEEP,
        isGold: Boolean = false,
        ocrJson: String? = null,
        dateAdded: Long = 5000L,
    ) {
        repo.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = id,
                contentUri = "content://test/$id",
                dateAdded = dateAdded,
                languageTag = LanguageTag.JAPANESE,
                extractedText = text,
                ocrBlocksJson = ocrJson,
                status = status,
                isGold = isGold,
            ),
        )
    }

    private fun seedItem(expr: String) {
        repo.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE,
            itemType = ItemType.WORD,
            expression = expr,
            kanji = expr,
            kana = expr,
            meaning = "stub",
        )
    }

    @Test
    fun reparse_crossMedia_replacesFragmentsInKeepPhotoAndActiveVideo_skipsDeletedAndRetired() {
        // 1. KEEP photo with fragmented 竈門炭治郎 + surviving 少年 (SEEN) + manual row
        val photoSentence = "竈門炭治郎の少年。"
        seedPhoto(10L, photoSentence, status = PhotoStatus.KEEP)
        listOf("竈門", "炭", "治郎", "少年", "手入力").forEach { seedItem(it) }
        val photoMediaId = repo.getOrCreateMediaRef("photo", 10L)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = 10L, sentence = photoSentence, createdAt = 1111L, mediaId = photoMediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "炭", photoId = 10L, sentence = photoSentence, createdAt = 1111L, mediaId = photoMediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "治郎", photoId = 10L, sentence = photoSentence, createdAt = 1111L, mediaId = photoMediaId)
        val seenId = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "少年", photoId = 10L, sentence = photoSentence, createdAt = 1111L, mediaId = photoMediaId)
        repo.restoreOccurrenceState(seenId, OccurrenceState.SEEN.name)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "手入力", photoId = 10L, sentence = photoSentence, createdAt = 1111L, isManual = true, mediaId = photoMediaId)

        // 2. DELETED photo -> must be ignored
        val deletedPhotoSentence = "竈門炭治郎が走る。"
        seedPhoto(11L, deletedPhotoSentence, status = PhotoStatus.DELETED)
        val deletedPhotoMediaId = repo.getOrCreateMediaRef("photo", 11L)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = 11L, sentence = deletedPhotoSentence, createdAt = 1200L, mediaId = deletedPhotoMediaId)

        // 3. Active video_cues sentence (photo_id = NULL)
        val videoSentence = "竈門炭治郎が走る"
        val activeDocId = repo.createStremioDoc(
            videoId = "tt1:1:1", generation = "g2",
            choice = SubtitleChoice("a", "s", "jpn", "ep1.srt", "http://x"),
            fileHash = "h2", infoHash = null, fileIdx = null, now = 2000_000L,
        )
        val activeVideoMediaId = repo.getOrCreateMediaRef("video_cues", activeDocId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = null, sentence = videoSentence, createdAt = 2222L, mediaId = activeVideoMediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "炭", photoId = null, sentence = videoSentence, createdAt = 2222L, mediaId = activeVideoMediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "治郎", photoId = null, sentence = videoSentence, createdAt = 2222L, mediaId = activeVideoMediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "走る", photoId = null, sentence = videoSentence, createdAt = 2222L, mediaId = activeVideoMediaId)

        // 4. Retired video_cues ref (active = 0) -> must be ignored
        val retiredDocId = repo.createStremioDoc(
            videoId = "tt1:1:1", generation = "g1",
            choice = SubtitleChoice("a", "s", "jpn", "ep1_old.srt", "http://x"),
            fileHash = "h1", infoHash = null, fileIdx = null, now = 1000_000L,
        )
        val retiredVideoMediaId = repo.getOrCreateMediaRef("video_cues", retiredDocId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = null, sentence = videoSentence, createdAt = 1500L, mediaId = retiredVideoMediaId)
        repo.retireStaleVideoMediaRefs("tt1:1:1", keepDocId = activeDocId)

        // Save user word with reparseMatches = true
        val summary = runner.saveUserWord(
            languageTag = LanguageTag.JAPANESE,
            expression = "竈門炭治郎",
            readingKana = "かまどたんじろう",
            meaning = "Tanjiro",
            notes = null,
            reparseMatches = true,
        )
        assertEquals(2, summary.matchedSentences)
        assertEquals(2, summary.changedSentences)
        assertEquals(2, summary.addedOccurrences)
        assertEquals(6, summary.removedOccurrences)

        // Photo rows: fragments gone, 竈門炭治郎 added with preserved timestamp 1111L & photo_id=10L, 少年 kept SEEN, 手入力 kept manual
        val photoRows = repo.occurrenceRowsForMediaSentence(photoMediaId, photoSentence)
        assertEquals(setOf("竈門炭治郎", "少年", "手入力"), photoRows.map { it.expression }.toSet())
        assertEquals(OccurrenceState.SEEN.name, photoRows.first { it.expression == "少年" }.state)
        assertEquals(1111L, photoRows.first { it.expression == "竈門炭治郎" }.createdAt)
        val photoIdStored = repo.readableDatabase.rawQuery(
            "SELECT photo_id FROM item_occurrences WHERE media_id = ? AND expression = '竈門炭治郎'",
            arrayOf(photoMediaId.toString()),
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        assertEquals(10L, photoIdStored)

        // Active video rows: fragments gone, 竈門炭治郎 added with photo_id NULL and preserved timestamp 2222L
        val videoRows = repo.occurrenceRowsForMediaSentence(activeVideoMediaId, videoSentence)
        assertEquals(setOf("竈門炭治郎", "走る"), videoRows.map { it.expression }.toSet())
        assertEquals(2222L, videoRows.first { it.expression == "竈門炭治郎" }.createdAt)
        val videoPhotoIdNull = repo.readableDatabase.rawQuery(
            "SELECT photo_id FROM item_occurrences WHERE media_id = ? AND expression = '竈門炭治郎'",
            arrayOf(activeVideoMediaId.toString()),
        ).use { c -> c.moveToFirst(); c.isNull(0) }
        assertTrue("video_cues row must keep photo_id NULL (v15)", videoPhotoIdNull)

        // DELETED photo & retired video ref untouched
        assertEquals(listOf("竈門"), repo.occurrenceRowsForMediaSentence(deletedPhotoMediaId, deletedPhotoSentence).map { it.expression })
        assertEquals(listOf("竈門"), repo.occurrenceRowsForMediaSentence(retiredVideoMediaId, videoSentence).map { it.expression })

        // Canonical item registered with USER_DICT provenance
        repo.readableDatabase.rawQuery(
            "SELECT meaning, kana, definition_source FROM extracted_items WHERE expression = '竈門炭治郎'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Tanjiro", c.getString(0))
            assertEquals("かまどたんじろう", c.getString(1))
            assertEquals("USER_DICT", c.getString(2))
        }
    }

    @Test
    fun reparse_goldPhoto_reparsesWordsWithoutTouchingPhotoOrOcr() {
        val sentence = "竈門炭治郎の少年。"
        val ocrJson = """{"fullText":"竈門炭治郎の少年。","blocks":[]}"""
        seedPhoto(20L, sentence, status = PhotoStatus.KEEP, isGold = true, ocrJson = ocrJson)
        val mediaId = repo.getOrCreateMediaRef("photo", 20L)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = 20L, sentence = sentence, createdAt = 3000L, mediaId = mediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "少年", photoId = 20L, sentence = sentence, createdAt = 3000L, mediaId = mediaId)

        val summary = runner.saveUserWord(LanguageTag.JAPANESE, "竈門炭治郎", "かまどたんじろう", "Tanjiro", null, reparseMatches = true)
        assertEquals(1, summary.changedSentences)
        assertEquals(setOf("竈門炭治郎", "少年"), repo.occurrenceRowsForMediaSentence(mediaId, sentence).map { it.expression }.toSet())

        val photo = repo.getPhoto(20L)!!
        assertTrue(photo.isGold)
        assertEquals(sentence, photo.extractedText)
        assertEquals(ocrJson, photo.ocrBlocksJson)
    }

    @Test
    fun reparse_zeroCandidatePhotoAndStremioCue_addsWord_neverResurrectsDeletedSentence() {
        // 1. Zero-candidate KEEP photo (アオイ！ previously produced 0 occurrences, no media_ref yet)
        seedPhoto(30L, "アオイ！", status = PhotoStatus.KEEP, dateAdded = 4000L)

        // 2. Zero-candidate active Stremio cue
        val docId = repo.createStremioDoc(
            videoId = "tt2:1:1", generation = "g1",
            choice = SubtitleChoice("a", "s", "jpn", "ep1.srt", "http://x"),
            fileHash = "h3", infoHash = null, fileIdx = null, now = 6000_000L,
        )
        val videoMediaId = repo.getOrCreateMediaRef("video_cues", docId)
        repo.replaceStremioCues(docId, listOf(SubtitleCue(0L, 1000L, "アオイ！")))

        // 3. Explicitly SENTENCE_DELETEd sentence containing アオイ -> all rows DELETED -> must NOT resurrect
        val deletedSentence = "アオイ少年"
        seedPhoto(31L, deletedSentence, status = PhotoStatus.KEEP, dateAdded = 4100L)
        val deletedMediaId = repo.getOrCreateMediaRef("photo", 31L)
        val delOccId = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "少年", photoId = 31L, sentence = deletedSentence, createdAt = 4100L, mediaId = deletedMediaId)
        repo.restoreOccurrenceState(delOccId, OccurrenceState.DELETED.name)

        val summary = runner.saveUserWord(LanguageTag.JAPANESE, "アオイ", "あおい", "Aoi", null, reparseMatches = true)
        assertEquals(2, summary.matchedSentences)
        assertEquals(2, summary.changedSentences)
        assertEquals(2, summary.addedOccurrences)
        assertEquals(0, summary.removedOccurrences)

        val photo30MediaId = repo.findMediaRefId("photo", 30L)
        assertNotNull(photo30MediaId)
        assertEquals(listOf("アオイ"), repo.occurrenceRowsForMediaSentence(photo30MediaId!!, "アオイ！").map { it.expression })
        assertEquals(4000L, repo.occurrenceRowsForMediaSentence(photo30MediaId, "アオイ！").single().createdAt)

        val cueRows = repo.occurrenceRowsForMediaSentence(videoMediaId, "アオイ！")
        assertEquals(listOf("アオイ"), cueRows.map { it.expression })
        assertEquals(6000L, cueRows.single().createdAt) // ms converted to epoch seconds

        // Deleted sentence stayed untouched (only the original DELETED row, no アオイ added)
        val delRows = repo.occurrenceRowsForMediaSentence(deletedMediaId, deletedSentence)
        assertEquals(listOf("少年"), delRows.map { it.expression })
        assertEquals(OccurrenceState.DELETED.name, delRows.single().state)
    }

    @Test
    fun reparse_undoRoundTrip_restoresExactDeletedRowsAndRemovesInserted_guardsRetiredMediaRef() {
        val sPhoto = "竈門炭治郎の少年。"
        seedPhoto(40L, sPhoto, status = PhotoStatus.KEEP)
        val photoMediaId = repo.getOrCreateMediaRef("photo", 40L)
        val frag1 = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = 40L, sentence = sPhoto, createdAt = 700L, mediaId = photoMediaId)
        val frag2 = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "炭", photoId = 40L, sentence = sPhoto, createdAt = 700L, mediaId = photoMediaId)
        repo.restoreOccurrenceState(frag2, OccurrenceState.SEEN.name)
        val frag3 = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "治郎", photoId = 40L, sentence = sPhoto, createdAt = 700L, mediaId = photoMediaId)

        val sVideo = "竈門炭治郎が走る"
        val docId = repo.createStremioDoc(
            videoId = "tt3:1:1", generation = "g1",
            choice = SubtitleChoice("a", "s", "jpn", "ep.srt", "http://x"),
            fileHash = "h4", infoHash = null, fileIdx = null, now = 800_000L,
        )
        val videoMediaId = repo.getOrCreateMediaRef("video_cues", docId)
        val vFrag = repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = null, sentence = sVideo, createdAt = 800L, mediaId = videoMediaId)

        val summary = runner.saveUserWord(LanguageTag.JAPANESE, "竈門炭治郎", "かまどたんじろう", "Tanjiro", null, reparseMatches = true)
        assertEquals(2, summary.changedSentences)

        // Undo (R6a): occurrences revert byte-identically; user_words entry stays!
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        assertNotNull("R6a: user_words entry stays on reparse undo", repo.userWord(LanguageTag.JAPANESE, "竈門炭治郎"))

        val photoAfterUndo = repo.occurrenceRowsForMediaSentence(photoMediaId, sPhoto)
        assertEquals(setOf(frag1, frag2, frag3), photoAfterUndo.map { it.id }.toSet())
        assertEquals(OccurrenceState.SEEN.name, photoAfterUndo.first { it.id == frag2 }.state)
        assertFalse(photoAfterUndo.any { it.expression == "竈門炭治郎" })

        val videoAfterUndo = repo.occurrenceRowsForMediaSentence(videoMediaId, sVideo)
        assertEquals(listOf(vFrag), videoAfterUndo.map { it.id })
        val videoPhotoIdStillNull = repo.readableDatabase.rawQuery(
            "SELECT photo_id, media_id FROM item_occurrences WHERE id = ?",
            arrayOf(vFrag.toString()),
        ).use { c -> c.moveToFirst(); c.isNull(0) && c.getLong(1) == videoMediaId }
        assertTrue("restored video row must retain photo_id NULL and original media_id", videoPhotoIdStillNull)

        // Re-run reparse, then retire the video ref -> undo must GUARD_FAIL
        runner.reparseUserWord(LanguageTag.JAPANESE, "竈門炭治郎")
        val docId2 = repo.createStremioDoc(
            videoId = "tt3:1:1", generation = "g2",
            choice = SubtitleChoice("a", "s", "jpn", "ep2.srt", "http://x"),
            fileHash = "h5", infoHash = null, fileIdx = null, now = 900_000L,
        )
        repo.retireStaleVideoMediaRefs("tt3:1:1", keepDocId = docId2)
        assertEquals(UndoOutcome.GUARD_FAILED, UndoManager.undoLatest(repo))
    }

    @Test
    fun reparse_isIdempotent_andCacheInvalidatesAcrossRepoInstances() {
        val sentence = "竈門炭治郎の少年。"
        seedPhoto(50L, sentence, status = PhotoStatus.KEEP)
        val mediaId = repo.getOrCreateMediaRef("photo", 50L)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "竈門", photoId = 50L, sentence = sentence, createdAt = 100L, mediaId = mediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "炭", photoId = 50L, sentence = sentence, createdAt = 100L, mediaId = mediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "治郎", photoId = 50L, sentence = sentence, createdAt = 100L, mediaId = mediaId)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "少年", photoId = 50L, sentence = sentence, createdAt = 100L, mediaId = mediaId)

        // Prime cache on repo, then write via a SECOND MiningRepository instance (mirrors Activity + Holder)
        assertTrue(repo.userWordEntries(LanguageTag.JAPANESE).isEmpty())
        val otherRepo = MiningRepository(context, DB_NAME)
        try {
            otherRepo.upsertUserWord(LanguageTag.JAPANESE, "竈門炭治郎", "かまどたんじろう", "Tanjiro", null)
            assertEquals(1, repo.userWordEntries(LanguageTag.JAPANESE).size)
        } finally {
            otherRepo.close()
        }

        val first = runner.reparseUserWord(LanguageTag.JAPANESE, "竈門炭治郎")
        assertEquals(1, first.changedSentences)
        val undoCountBeforeSecond = repo.recentUndos().size

        val second = runner.reparseUserWord(LanguageTag.JAPANESE, "竈門炭治郎")
        assertEquals(1, second.matchedSentences)
        assertEquals(0, second.changedSentences)
        assertFalse(second.hasCorpusChanges)
        assertEquals(undoCountBeforeSecond, repo.recentUndos().size)
    }

    companion object {
        private const val DB_NAME = "mining_userdict_reparse_test.db"
    }
}
