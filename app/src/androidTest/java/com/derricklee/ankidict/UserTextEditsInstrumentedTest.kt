package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserTextEditsInstrumentedTest {
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

    @Test
    fun photoEdits_setPersistentStickyFlag_onlyWhenTextChanges() {
        val original = ScrapedPhoto(
            mediaStoreId = 900101L,
            contentUri = "content://media/external/images/media/900101",
            dateAdded = 1700000000L,
            languageTag = LanguageTag.JAPANESE,
            extractedText = "original text",
            status = PhotoStatus.KEEP,
        )
        repository.upsertPhoto(original)
        assertEquals(0, photoFlag())
        repository.updatePhotoExtractedText(original.mediaStoreId, original.extractedText)
        assertEquals(0, photoFlag())
        repository.updatePhotoExtractedText(original.mediaStoreId, "corrected text")
        assertEquals(1, photoFlag())
        repository.updatePhotoExtractedText(original.mediaStoreId, original.extractedText)
        assertEquals(1, photoFlag())
        repository.upsertPhoto(original)
        assertEquals(1, photoFlag())
        repository.close()
        repository = MiningRepository(InstrumentationRegistry.getInstrumentation().targetContext, TEST_DB_NAME)
        assertEquals(1, photoFlag())
    }

    @Test
    fun sentenceEdits_flagAllMatchingOccurrencesAndTheirPhoto_butNotOtherSentencesOrPhotos() {
        seedPhoto(900101L, "証拠がある。\n真実がある。")
        seedPhoto(900102L, "証拠がある。")
        repository.updateSentenceText(900101L, "証拠がある。", "証拠がある。")
        assertEquals(0, sentenceFlag(900101L, "証拠がある。"))
        assertEquals(0, photoFlag())

        repository.updateSentenceText(900101L, "証拠がある。", "証拠がない。")
        assertEquals(1, sentenceFlag(900101L, "証拠がない。"))
        assertEquals(0, sentenceFlag(900101L, "真実がある。"))
        assertEquals(0, sentenceFlag(900102L, "証拠がある。"))
        assertEquals(1, photoFlag())
        assertEquals("証拠がない。\n真実がある。", repository.getPhoto(900101L)?.extractedText)
        repository.updateSentenceText(900101L, "証拠がない。", "証拠がある。")
        assertEquals(1, sentenceFlag(900101L, "証拠がある。"))
    }

    @Test
    fun reprocessing_preservesSentenceFlags_andMarksNewSentencesFromEditedPhotoText() {
        seedPhoto(900101L, "証拠がある。\n真実がある。")
        val runner = textRunner()
        runner.reprocessAmendedSentence(900101L, "証拠がある。", "証拠がない。", reprocessWords = true)
        assertEquals(1, sentenceFlag(900101L, "証拠がない。"))
        assertEquals(0, sentenceFlag(900101L, "真実がある。"))
        runner.reprocessPhotoText(900101L, "証拠がない。\n真実がある。", reprocessWords = true)
        assertEquals(1, sentenceFlag(900101L, "証拠がない。"))
        assertEquals(0, sentenceFlag(900101L, "真実がある。"))
        runner.reprocessPhotoText(900101L, "証拠がない。\n真実が見える。", reprocessWords = true)
        assertEquals(1, sentenceFlag(900101L, "真実が見える。"))
        val detail = repository.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "真実")!!
        assertEquals(true, detail.photos.single().modifiedByUserFlag)
        assertEquals(true, detail.matchedSentences.single().modifiedByUserFlag)
    }

    @Test
    fun editingToAnExistingSentence_keepsSharedSentenceProvenanceConsistent() {
        seedPhoto(900101L, "証拠がある。\n真実がある。")
        repository.updateSentenceText(900101L, "証拠がある。", "真実がある。")
        assertEquals(1, sentenceFlag(900101L, "真実がある。"))
    }

    @Test
    fun unchangedReprocessing_doesNotFlagMachineGeneratedText() {
        val text = "証拠がある。"
        seedPhoto(900101L, text)
        val runner = textRunner()
        runner.reprocessPhotoText(900101L, text, reprocessWords = true)
        assertEquals(0, photoFlag())
        assertEquals(0, sentenceFlag(900101L, text))
        runner.reprocessAmendedSentence(900101L, text, text, reprocessWords = true)
        assertEquals(0, photoFlag())
        assertEquals(0, sentenceFlag(900101L, text))
    }

    @Test
    fun textOnlySaves_persistModelFlags_andLaterReprocessingRetainsThem() {
        seedPhoto(900101L, "証拠がある。")
        val runner = textRunner()
        runner.reprocessAmendedSentence(900101L, "証拠がある。", "証拠がない。", reprocessWords = false)
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "ある", 900101L, "証拠がない。")
        assertEquals(1, sentenceFlag(900101L, "証拠がない。"))
        repository.close()
        repository = MiningRepository(InstrumentationRegistry.getInstrumentation().targetContext, TEST_DB_NAME)
        for (type in ItemType.entries) {
            val expression = if (type == ItemType.WORD) "ある" else "がある"
            val detail = repository.getItemDetail(LanguageTag.JAPANESE, type, expression)!!
            assertEquals(true, detail.matchedSentences.all { it.modifiedByUserFlag })
            assertEquals(true, detail.photos.single().modifiedByUserFlag)
        }
        assertEquals(true, repository.getRecentPhotos().single().modifiedByUserFlag)
        assertEquals(true, repository.getAllScrapedPhotos().single().modifiedByUserFlag)
        textRunner().reprocessPhotoText(900101L, "真実が見える。", reprocessWords = false)
        assertEquals(1, photoFlag())
        textRunner().reprocessPhotoText(900101L, "真実が見える。", reprocessWords = true)
        assertEquals(1, sentenceFlag(900101L, "真実が見える。"))
    }

    @Test
    fun version2Migration_preservesExistingRowsAndDefaultsBothFlagsToFalse() {
        seedPhoto(900101L, "証拠がある。")
        repository.markOccurrenceSeen(
            repository.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "ある")!!.matchedSentences.single().occurrenceId,
        )
        val before = listOf("scraped_photos", "extracted_items", "item_occurrences").associateWith(::rowsWithoutFlags)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository.close()
        context.openOrCreateDatabase(TEST_DB_NAME, 0, null).use { db ->
            db.execSQL("ALTER TABLE scraped_photos DROP COLUMN modified_by_user_flag")
            db.execSQL("ALTER TABLE item_occurrences DROP COLUMN modified_by_user_flag")
            db.version = 2
        }
        repository = MiningRepository(context, TEST_DB_NAME)
        assertEquals(3, repository.readableDatabase.version)
        for ((table, rows) in before) assertEquals(table, rows, rowsWithoutFlags(table))
        assertEquals(0, photoFlag())
        assertEquals(0, sentenceFlag(900101L, "証拠がある。"))
        assertEquals(false, repository.getPhoto(900101L)!!.modifiedByUserFlag)
        repository.updateSentenceText(900101L, "証拠がある。", "証拠がない。")
        assertEquals(1, sentenceFlag(900101L, "証拠がない。"))
    }

    private fun rowsWithoutFlags(table: String): List<List<String?>> = repository.readableDatabase.rawQuery(
        "SELECT * FROM $table ORDER BY rowid", emptyArray(),
    ).use { cursor ->
        val columns = cursor.columnNames.indices.filter { cursor.columnNames[it] != "modified_by_user_flag" }
        buildList {
            while (cursor.moveToNext()) add(columns.map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }

    @Test
    fun photoEditCollision_withReprocessing_marksChangedSentenceButNotUntouchedText() {
        checkPhotoEditCollision(reprocessImmediately = true)
    }

    @Test
    fun photoEditCollision_textOnlySave_retainsProvenanceForLaterReprocessing() {
        checkPhotoEditCollision(reprocessImmediately = false)
    }

    private fun checkPhotoEditCollision(reprocessImmediately: Boolean) {
        seedPhoto(900101L, "証拠がある。\n真実がある。\n運命を待つ。")
        val amended = "真実がある。\n真実がある。\n運命を待つ。"
        textRunner().reprocessPhotoText(900101L, amended, reprocessWords = reprocessImmediately)
        assertEquals(1, sentenceFlag(900101L, "真実がある。"))
        assertEquals(0, sentenceFlag(900101L, "運命を待つ。"))
        textRunner().reprocessPhotoText(900101L, amended, reprocessWords = true)
        assertEquals(1, sentenceFlag(900101L, "真実がある。"))
        assertEquals(0, sentenceFlag(900101L, "運命を待つ。"))
    }

    private fun textRunner(): MiningPipelineRunner {
        val config = PipelineConfig(
            tag = LanguageTag.JAPANESE,
            textExtractor = object : TextExtractor {
                override fun extract(image: PreparedImage, preflightDocument: OcrDocument?): OcrDocument =
                    error("Text-edit tests must not invoke OCR")
            },
            duplicateDetector = LevenshteinDuplicatePhotoDetector(repository),
            sentenceParser = CjkSentenceParser(),
            wordAnalyzer = KuromojiWordAnalyzer(),
            grammarAnalyzer = JlptGrammarAnalyzer(),
            dictionaryProviders = emptyList(),
        )
        return MiningPipelineRunner(
            miningRepository = repository,
            photoSource = object : PhotoSource {
                override fun findUnprocessedPhotos(config: ScanDiscoveryConfig): List<PhotoCandidate> = emptyList()
            },
            imagePreprocessor = object : ImagePreprocessor {
                override fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage? =
                    error("Text-edit tests must not load device photos")
            },
            imageTagger = object : ImageTagger {
                override fun classify(image: PreparedImage): TagResult = error("No image tagging in text-edit tests")
            },
            pipelineConfigs = mapOf(LanguageTag.JAPANESE to config),
        )
    }

    private fun seedPhoto(id: Long, text: String) {
        repository.upsertPhoto(
            ScrapedPhoto(id, "content://media/external/images/media/$id", 1700000000L,
                LanguageTag.JAPANESE, text, status = PhotoStatus.KEEP),
        )
        for (sentence in text.lines()) {
            for ((type, expression) in listOf(ItemType.WORD to "ある", ItemType.GRAMMAR to "がある")) {
                repository.upsertExtractedItem(LanguageTag.JAPANESE, type, expression, "", "ある", "exists")
                repository.insertOccurrence(LanguageTag.JAPANESE, type, expression, id, sentence)
            }
        }
    }

    private fun sentenceFlag(photoId: Long, sentence: String): Int = repository.readableDatabase.rawQuery(
        "SELECT MIN(modified_by_user_flag), MAX(modified_by_user_flag) FROM item_occurrences WHERE photo_id = ? AND sentence = ?",
        arrayOf(photoId.toString(), sentence),
    ).use { cursor ->
        check(cursor.moveToFirst() && !cursor.isNull(0))
        assertEquals("all occurrences of a sentence share its edit provenance", cursor.getInt(0), cursor.getInt(1))
        cursor.getInt(0)
    }

    private fun photoFlag(): Int = repository.readableDatabase.rawQuery(
        "SELECT modified_by_user_flag FROM scraped_photos WHERE media_store_id = 900101",
        emptyArray(),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    companion object {
        private const val TEST_DB_NAME = "mining_user_edits_test.db"
    }
}
