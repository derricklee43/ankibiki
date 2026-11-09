package com.derricklee.ankidict

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class MiningPipelineInstrumentedTest {

    private lateinit var miningRepository: MiningRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var hskRepository: HskRepository
    private lateinit var shinjitaiRepository: ShinjitaiRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        miningRepository = MiningRepository(context)
        dictionaryRepository = DictionaryRepository(context)
        hskRepository = HskRepository(context)
        shinjitaiRepository = ShinjitaiRepository(context)
        clearTestRows()
    }

    @After
    fun tearDown() {
        clearTestRows()
    }

    private fun clearTestRows() {
        val db = miningRepository.writableDatabase
        db.execSQL("DELETE FROM item_occurrences WHERE photo_id IN (900001, 900002, 900003)")
        db.execSQL("DELETE FROM scraped_photos WHERE media_store_id IN (900001, 900002, 900003)")
        db.execSQL(
            "DELETE FROM extracted_items WHERE expression NOT IN (SELECT DISTINCT expression FROM item_occurrences)",
        )
    }

    @Test
    fun endToEndPipeline_extractsWordsAndGrammar_handlesRetakeDuplicate_andEnrichesFromDictionaryDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val duplicateDetector = LevenshteinDuplicatePhotoDetector(miningRepository)
        val sentenceParser = CjkSentenceParser()
        val dictProvider = BundledDictionaryProvider(
            languageTag = LanguageTag.JAPANESE,
            dictionaryRepository = dictionaryRepository,
            hskRepository = hskRepository,
        )

        val jpConfig = PipelineConfig(
            tag = LanguageTag.JAPANESE,
            textExtractor = MlKitTextExtractor(LanguageTag.JAPANESE),
            duplicateDetector = duplicateDetector,
            sentenceParser = sentenceParser,
            wordAnalyzer = KuromojiWordAnalyzer(),
            grammarAnalyzer = JlptGrammarAnalyzer(),
            dictionaryProviders = listOf(dictProvider),
        )

        val runner = MiningPipelineRunner(
            miningRepository = miningRepository,
            photoSource = object : PhotoSource {
                override fun findUnprocessedPhotos(config: ScanDiscoveryConfig): List<PhotoCandidate> = emptyList()
            },
            imagePreprocessor = AndroidImagePreprocessor(context),
            imageTagger = MlKitImageTagger(dictionaryRepository, shinjitaiRepository),
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to jpConfig),
        )

        // 1. Process Photo 1 (wrapped DS dialogue with furigana noise + 2 sentences containing 証拠)
        val doc1 = OcrDocument(
            fullText = "しょうこ\nしかし決定的な証\n拠がまだ足りない。\nこの証拠品を法廷で\n突きつけるしかない！\nそれが真実というわけだ。",
            blocks = listOf(
                OcrBlock(
                    text = "しょうこ\nしかし決定的な証\n拠がまだ足りない。\nこの証拠品を法廷で\n突きつけるしかない！\nそれが真実というわけだ。",
                    lines = listOf(
                        OcrLine("しょうこ", boundingBoxHeightPx = 16), // Furigana (<55% of 44px)
                        OcrLine("しかし決定的な証", boundingBoxHeightPx = 44),
                        OcrLine("拠がまだ足りない。", boundingBoxHeightPx = 44),
                        OcrLine("この証拠品を法廷で", boundingBoxHeightPx = 44),
                        OcrLine("突きつけるしかない！", boundingBoxHeightPx = 44),
                        OcrLine("それが真実というわけだ。", boundingBoxHeightPx = 44),
                    ),
                ),
            ),
        )
        val candidate1 = PhotoCandidate(
            mediaStoreId = 900001L,
            contentUri = "content://media/external/images/media/900001",
            dateAdded = 1700000100L,
        )
        val outcome1 = runner.processOcrDocument(candidate1, jpConfig, doc1)
        assertEquals(PhotoStatus.KEEP, outcome1.status)
        assertTrue(outcome1.occurrencesCreated >= 5)

        // Verify 証拠 has 2 occurrences (1 per sentence in Photo 1) and is enriched from dictionary.db
        val activeWords = miningRepository.getActiveOccurrences(
            OccurrenceQuery(
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.WORD,
            ),
        )
        val shoukoRows = activeWords.filter { it.expression == "証拠" }
        assertEquals(2, shoukoRows.size)
        assertEquals(2, shoukoRows.first().occurrenceCount)
        assertEquals("N2", shoukoRows.first().levelBadge)
        assertEquals(1420, shoukoRows.first().corpusRank)

        val shoukoDetail = miningRepository.getItemDetail(
            languageTag = LanguageTag.JAPANESE,
            itemType = ItemType.WORD,
            expression = "証拠",
        )
        assertNotNull(shoukoDetail)
        assertEquals("証拠", shoukoDetail?.kanji)
        assertEquals("しょうこ", shoukoDetail?.kana)
        assertTrue(shoukoDetail?.meaning?.contains("evidence") == true)
        assertEquals(2, shoukoDetail?.sentences?.size)

        // Verify grammar patterns ("しかない" and "というわけだ") were extracted and enriched
        val activeGrammar = miningRepository.getActiveOccurrences(
            OccurrenceQuery(
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.GRAMMAR,
            ),
        )
        val grammarExpressions = activeGrammar.map { it.expression }.toSet()
        assertTrue(grammarExpressions.contains("しかない"))
        assertTrue(grammarExpressions.contains("というわけだ"))

        // 2. Process Photo 2 (retake/duplicate of Photo 1)
        val doc2 = OcrDocument(
            fullText = "しかし決定的な証拠がまだ足りない。\nこの証拠品を法廷で突きつけるしかない！\nそれが真実というわけだ。",
            blocks = emptyList(),
        )
        val candidate2 = PhotoCandidate(
            mediaStoreId = 900002L,
            contentUri = "content://media/external/images/media/900002",
            dateAdded = 1700000105L,
        )
        val outcome2 = runner.processOcrDocument(candidate2, jpConfig, doc2)
        assertEquals(PhotoStatus.DUPLICATE, outcome2.status)
        assertEquals(0, outcome2.occurrencesCreated)

        val storedDupPhoto = miningRepository.getPhoto(900002L)
        assertNotNull(storedDupPhoto)
        assertEquals(PhotoStatus.DUPLICATE, storedDupPhoto?.status)
        assertEquals(900001L, storedDupPhoto?.duplicateOfPhotoId)

        // Verify duplicate photo did NOT increment occurrence count of 証拠
        val afterDupDetail = miningRepository.getItemDetail(
            languageTag = LanguageTag.JAPANESE,
            itemType = ItemType.WORD,
            expression = "証拠",
        )
        assertEquals(2, afterDupDetail?.occurrenceCount)
    }

    @Test
    fun onDeviceMlKitTaggerAndExtractor_recognizesRenderedJapaneseDialogueBitmap() {
        val bitmap = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 56f
        }
        canvas.drawText("決定的な証拠がある。", 40f, 120f, paint)
        canvas.drawText("それが真実というわけだ。", 40f, 220f, paint)

        val jpegOut = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpegOut)
        val prepared = PreparedImage(bitmap = bitmap, jpegBytes = jpegOut.toByteArray())

        try {
            val tagger = MlKitImageTagger(dictionaryRepository, shinjitaiRepository)
            val tagResult = tagger.classify(prepared)
            assertEquals(LanguageTag.JAPANESE, tagResult.tag)
            assertNotNull(tagResult.preflightDocument)
            assertTrue(tagResult.preflightDocument!!.fullText.contains("証拠") || tagResult.preflightDocument.fullText.contains("真実"))
        } finally {
            bitmap.recycle()
        }
    }
}
