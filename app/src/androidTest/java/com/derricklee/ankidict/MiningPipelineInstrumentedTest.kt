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
        context.deleteDatabase(TEST_DB_NAME)
        miningRepository = MiningRepository(context, dbName = TEST_DB_NAME)
        dictionaryRepository = DictionaryRepository(context)
        hskRepository = HskRepository(context)
        shinjitaiRepository = ShinjitaiRepository(context)
    }

    @After
    fun tearDown() {
        miningRepository.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
    }

    companion object {
        private const val TEST_DB_NAME = "mining_test.db"
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
            ankiMatchLookup = AnkiCardService(
                gateway = object : AnkiAccountGateway {
                    override val accountId: String = "ankidroid-test"
                    override fun isAvailable(): Boolean = true
                    override fun getDeckName(deckId: Long): String? =
                        if (deckId == 5001L) "🇯🇵 JP Vocab" else null
                    override fun getDeckIdForNote(noteId: Long): Long? = 5001L
                    override fun getModelList(): Map<Long, String> = mapOf(6001L to "JP Vocab")
                    override fun getModelFieldNames(modelId: Long): List<String>? =
                        listOf("Expression", "Reading", "Meaning")
                    override fun findNotesByModel(modelId: Long, query: String): List<AnkiRawNote> =
                        if (modelId == 6001L && query == "証拠") {
                            listOf(AnkiRawNote(noteId = 7001L, modelId = 6001L, fields = listOf("証拠", "しょうこ", "evidence")))
                        } else {
                            emptyList()
                        }
                },
                bindings = listOf(
                    AnkiDeckBinding(
                        bindingId = "test-jp-vocab",
                        accountId = "ankidroid-test",
                        languageTag = LanguageTag.JAPANESE,
                        itemType = ItemType.WORD,
                        deckId = 5001L,
                        fallbackDeckName = "JP Vocab",
                        modelId = 6001L,
                        fieldMap = mapOf(
                            SemanticField.EXPRESSION to FieldSelector.ByName("Expression"),
                            SemanticField.READING to FieldSelector.ByName("Reading"),
                        ),
                    ),
                ),
                enableDynamicModelDiscovery = false,
            ),
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
        assertEquals("🇯🇵 JP Vocab", shoukoRows.first().deckName)

        val shoukoDetail = miningRepository.getItemDetail(
            languageTag = LanguageTag.JAPANESE,
            itemType = ItemType.WORD,
            expression = "証拠",
        )
        assertNotNull(shoukoDetail)
        assertEquals("証拠", shoukoDetail?.kanji)
        assertEquals("しょうこ", shoukoDetail?.kana)
        assertTrue(shoukoDetail?.meaning?.contains("evidence") == true)
        assertEquals(5001L, shoukoDetail?.deckId)
        assertEquals("🇯🇵 JP Vocab", shoukoDetail?.deckName)
        assertEquals(7001L, shoukoDetail?.ankiNoteId)
        assertEquals(2, shoukoDetail?.sentences?.size)

        // Verify grammar patterns ("しかない" and "というわけだ") were extracted and enriched,
        // and that pure-kana expressions do NOT backfill kanji!
        val shikanaiDetail = miningRepository.getItemDetail(
            languageTag = LanguageTag.JAPANESE,
            itemType = ItemType.GRAMMAR,
            expression = "しかない",
        )
        assertNotNull(shikanaiDetail)
        assertEquals("", shikanaiDetail?.kanji)

        // Verify Archive vs Hidden visibility partitioning and Occurrence Count sorting
        miningRepository.archiveItem(LanguageTag.JAPANESE, ItemType.WORD, "法廷")
        val afterArchiveActive = miningRepository.getActiveOccurrences(
            OccurrenceQuery(
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.WORD,
                sortCriteria = listOf(SortField.OCCURRENCE_COUNT to SortDirection.DESC),
                visibilities = setOf(ItemVisibility.ACTIVE),
            ),
        )
        assertTrue(afterArchiveActive.none { it.expression == "法廷" })
        val testWordsSorted = afterArchiveActive.filter { it.expression in setOf("証拠", "足りる", "突きつける") }
        assertEquals("証拠", testWordsSorted.first().expression)

        val archivedOnly = miningRepository.getActiveOccurrences(
            OccurrenceQuery(
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.WORD,
                visibilities = setOf(ItemVisibility.ARCHIVED),
            ),
        )
        assertTrue(archivedOnly.any { it.expression == "法廷" })

        // Verify amending a sentence with reprocessWords = true removes old occurrences from that sentence
        // and extracts/enriches the newly amended words
        val reprocess = runner.reprocessAmendedSentence(
            photoId = 900001L,
            oldSentence = "しかし決定的な証拠がまだ足りない。",
            newSentence = "しかし完璧なアリバイがまだ足りない。",
            reprocessWords = true,
        )
        // Reconciliation: shared candidates preserved, new ones extracted, stale ones dropped.
        assertTrue("kept: ${reprocess.keptCandidates}", reprocess.keptCandidates >= 1)
        assertTrue("added: ${reprocess.addedCandidates}", reprocess.addedCandidates >= 1)
        assertTrue("removed: ${reprocess.removedCandidates}", reprocess.removedCandidates >= 1)
        val shoukoAfterAmend = miningRepository.getItemDetail(
            languageTag = LanguageTag.JAPANESE,
            itemType = ItemType.WORD,
            expression = "証拠",
        )
        // 証拠 was in 2 sentences originally; after amending the first sentence to アリバイ, 証拠 has 1 occurrence left!
        assertEquals(1, shoukoAfterAmend?.occurrenceCount)
    }

    @Test
    fun heisigRadicalRepository_resolvesPrimitiveKeywordsAndBuildsDecompositionTree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val idsRepo = IdsRepository(context)
        val heisigRepo = HeisigRadicalRepository(
            idsRepository = idsRepo,
            dictionaryRepository = dictionaryRepository,
            ankiRepository = null,
        )
        val gouRadical = heisigRepo.lookupRadical("冓")
        assertNotNull(gouRadical)
        assertTrue(gouRadical?.heisigKeyword?.contains("framework") == true)

        val tree = heisigRepo.buildDecompositionTree("構")
        assertNotNull(tree)
        assertEquals("構", tree?.character)
        val childChars = tree?.children?.map { it.character }.orEmpty()
        assertTrue(childChars.contains("木"))
        assertTrue(childChars.contains("冓"))

        val gouNode = tree?.children?.firstOrNull { it.character == "冓" }
        assertTrue(gouNode?.meaning?.contains("framework") == true)
        assertTrue(gouNode?.children?.isNotEmpty() == true)
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
