package com.derricklee.ankidict

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRescanInstrumentedTest {
    private lateinit var repository: MiningRepository
    private val candidate = PhotoCandidate(900201L, "content://media/external/images/media/900201", 1700000000L)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        repository = MiningRepository(context, TEST_DB_NAME)
        repository.upsertPhoto(
            ScrapedPhoto(candidate.mediaStoreId, candidate.contentUri, candidate.dateAdded,
                LanguageTag.JAPANESE, "古い文章。", status = PhotoStatus.KEEP),
        )
    }

    @After
    fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB_NAME)
    }

    @Test
    fun rescan_runsOcrAndWordGrammarExtraction_replacesOccurrences_withoutSelfDuplicateDetection() {
        val fixture = Fixture()
        assertEquals(PhotoRescanResult.COMPLETED, fixture.runner.rescanPhoto(candidate))
        assertEquals("証拠があるというわけだ。", repository.getPhoto(candidate.mediaStoreId)?.extractedText)
        assertEquals(PhotoStatus.KEEP, repository.getPhoto(candidate.mediaStoreId)?.status)
        assertFalse(repository.getPhoto(candidate.mediaStoreId)!!.modifiedByUserFlag)
        assertEquals(1, fixture.ocrCalls)
        assertEquals(2, occurrenceCount())
        assertEquals(PhotoRescanResult.COMPLETED, fixture.runner.rescanPhoto(candidate))
        assertEquals(2, occurrenceCount())
        assertEquals(2, fixture.ocrCalls)
    }

    @Test
    fun editedPhoto_requiresConfirmation_beforePreparingImageOrChangingData() {
        repository.updatePhotoExtractedText(candidate.mediaStoreId, "手動の修正。")
        val original = repository.getPhoto(candidate.mediaStoreId)
        val fixture = Fixture()
        assertEquals(PhotoRescanResult.CONFIRMATION_REQUIRED, fixture.runner.rescanPhoto(candidate))
        assertEquals(0, fixture.preparationCalls)
        assertEquals(0, fixture.ocrCalls)
        assertEquals(original, repository.getPhoto(candidate.mediaStoreId))
    }

    @Test
    fun acceptedEditedPhoto_replacesManualText_andClearsPhotoAndRebuiltSentenceFlags() {
        seedManualEdits()
        val fixture = Fixture()
        assertEquals(PhotoRescanResult.COMPLETED, fixture.runner.rescanPhoto(candidate, PhotoRescanDecision.ACCEPT))
        assertEquals(1, fixture.ocrCalls)
        assertFalse(repository.getPhoto(candidate.mediaStoreId)!!.modifiedByUserFlag)
        val detail = repository.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "証拠")!!
        assertEquals("証拠があるというわけだ。", detail.matchedSentences.single().sentence)
        assertFalse(detail.matchedSentences.single().modifiedByUserFlag)
        repository.close()
        repository = MiningRepository(InstrumentationRegistry.getInstrumentation().targetContext, TEST_DB_NAME)
        assertFalse(repository.getPhoto(candidate.mediaStoreId)!!.modifiedByUserFlag)
    }

    @Test
    fun skipAndCancel_neverRunExtraction_andRetainAllManualEditsAndFlags() {
        seedManualEdits()
        val original = snapshot()
        for ((decision, expected) in listOf(
            PhotoRescanDecision.SKIP_TEXT_EXTRACTION to PhotoRescanResult.SKIPPED,
            PhotoRescanDecision.CANCEL to PhotoRescanResult.CANCELLED,
        )) {
            val fixture = Fixture()
            assertEquals(expected, fixture.runner.rescanPhoto(candidate, decision))
            assertEquals(0, fixture.ocrCalls)
            assertEquals(0, fixture.preparationCalls)
            assertEquals(original, snapshot())
        }
    }

    @Test
    fun editDuringOcr_requiresFreshConfirmation_insteadOfOverwritingTheNewerEdit() {
        val fixture = Fixture()
        fixture.duringOcr = { repository.updatePhotoExtractedText(candidate.mediaStoreId, "処理中の新しい修正。") }
        assertEquals(PhotoRescanResult.CONFIRMATION_REQUIRED, fixture.runner.rescanPhoto(candidate))
        assertEquals("処理中の新しい修正。", repository.getPhoto(candidate.mediaStoreId)?.extractedText)
        assertEquals(true, repository.getPhoto(candidate.mediaStoreId)?.modifiedByUserFlag)
    }

    @Test
    fun failedAcceptedRescan_preservesTextOccurrencesAndFlags_evenAfterWritesBegin() {
        seedManualEdits()
        val original = snapshot()
        val fixture = Fixture()
        fixture.failWords = true
        val failure = runCatching { fixture.runner.rescanPhoto(candidate, PhotoRescanDecision.ACCEPT) }.exceptionOrNull()
        assertEquals("fixture word-extraction failure", failure?.message)
        assertEquals(original, snapshot())
    }

    @Test
    fun unreadableImageOcrFailureAndEmptyOcr_preserveEditedCorpus() {
        seedManualEdits()
        val original = snapshot()
        for (mode in listOf("image", "ocr", "blank")) {
            val fixture = Fixture().apply {
                noImage = mode == "image"
                failOcr = mode == "ocr"
                if (mode == "blank") ocrText = "   "
            }
            assertEquals(true, runCatching { fixture.runner.rescanPhoto(candidate, PhotoRescanDecision.ACCEPT) }.isFailure)
            assertEquals(mode, original, snapshot())
        }
    }

    private fun seedManualEdits() {
        repository.updatePhotoExtractedText(candidate.mediaStoreId, "手動の修正。")
        repository.upsertExtractedItem(LanguageTag.JAPANESE, ItemType.WORD, "手動", "手動", "しゅどう", "manual")
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "手動", candidate.mediaStoreId,
            "手動の修正。", modifiedByUserFlag = true)
    }

    private fun snapshot(): List<List<List<String?>>> = listOf("scraped_photos", "item_occurrences", "extracted_items").map { table ->
        repository.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", emptyArray()).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
    }

    private fun occurrenceCount(): Int = repository.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM item_occurrences WHERE photo_id = ?", arrayOf(candidate.mediaStoreId.toString()),
    ).use { it.moveToFirst(); it.getInt(0) }

    private inner class Fixture {
        var ocrCalls = 0
        var preparationCalls = 0
        var failOcr = false
        var failWords = false
        var noImage = false
        var ocrText = "証拠があるというわけだ。"
        var duringOcr: () -> Unit = {}
        val runner = MiningPipelineRunner(
            miningRepository = repository,
            photoSource = object : PhotoSource {
                override fun findUnprocessedPhotos(config: ScanDiscoveryConfig): List<PhotoCandidate> = emptyList()
            },
            imagePreprocessor = object : ImagePreprocessor {
                override fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage? {
                    preparationCalls++
                    if (noImage) return null
                    return PreparedImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), byteArrayOf())
                }
            },
            imageTagger = object : ImageTagger {
                override fun classify(image: PreparedImage) = TagResult(LanguageTag.JAPANESE, 1f)
            },
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to PipelineConfig(
                tag = LanguageTag.JAPANESE,
                textExtractor = object : TextExtractor {
                    override fun extract(image: PreparedImage, preflightDocument: OcrDocument?): OcrDocument {
                        ocrCalls++
                        check(!failOcr) { "fixture OCR failure" }
                        duringOcr()
                        return OcrDocument(ocrText, emptyList())
                    }
                },
                duplicateDetector = object : DuplicatePhotoDetector {
                    override fun checkDuplicate(extractedText: String, tag: LanguageTag, config: DuplicateDetectionConfig): DuplicateCheckResult =
                        error("An explicit same-photo rescan must not mark itself as a duplicate")
                },
                sentenceParser = CjkSentenceParser(),
                wordAnalyzer = object : WordAnalyzer {
                    override fun extractWords(sentence: String): List<NormalizedCandidate> {
                        check(!failWords) { "fixture word-extraction failure" }
                        return listOf(NormalizedCandidate("証拠", "証拠"))
                    }
                },
                grammarAnalyzer = object : GrammarAnalyzer {
                    override fun extractGrammar(sentence: String) = listOf(NormalizedCandidate("というわけだ", "というわけだ"))
                },
                dictionaryProviders = emptyList(),
            )),
        )
    }

    companion object {
        private const val TEST_DB_NAME = "mining_rescan_test.db"
    }
}
