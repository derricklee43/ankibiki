package com.derricklee.ankidict

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
 * Word-view sentence ordering (roadmap secondary, 2026-11-12): default = date ascending,
 * manual rank lives on the word entity, unset falls back to date order. The wizard's
 * Sentence 1/2/3 dropdown (docs/card-creation-wizard-plan.md Q8) consumes this same order.
 */
@RunWith(AndroidJUnit4::class)
class SentenceOrderingInstrumentedTest {
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

    private fun seed() {
        repository.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = 920001L,
                contentUri = "content://media/external/images/media/920001",
                dateAdded = 1700000000L,
                languageTag = LanguageTag.JAPANESE,
                extractedText = "第一。\n第二。\n第三。",
                status = PhotoStatus.KEEP,
            ),
        )
        repository.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
            expression = "語X", kanji = "語X", kana = "語X", meaning = "test",
        )
        for ((i, s) in listOf("第一。", "第二。", "第三。").withIndex()) {
            repository.insertOccurrence(
                languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
                expression = "語X", photoId = 920001L, sentence = s, createdAt = 1700000100L + i,
            )
        }
    }

    private fun sentences() =
        repository.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "語X")?.matchedSentences
            .orEmpty().map { it.sentence }

    @Test
    fun defaultOrder_isDateAscending() {
        seed()
        assertEquals(listOf("第一。", "第二。", "第三。"), sentences())
    }

    @Test
    fun moveDown_swapsNext_andPersistsRankOnItem() {
        seed()
        assertTrue(repository.moveSentenceRank(LanguageTag.JAPANESE, ItemType.WORD, "語X", "第一。", up = false))
        assertEquals(listOf("第二。", "第一。", "第三。"), sentences())
    }

    @Test
    fun moveUp_atTop_isRejected() {
        seed()
        assertFalse(repository.moveSentenceRank(LanguageTag.JAPANESE, ItemType.WORD, "語X", "第一。", up = true))
    }

    @Test
    fun sentenceAddedAfterManualOrder_appendsInDateOrder() {
        seed()
        repository.moveSentenceRank(LanguageTag.JAPANESE, ItemType.WORD, "語X", "第一。", up = false)
        // new occurrence, newer date, never seen by the rank map
        repository.insertOccurrence(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
            expression = "語X", photoId = 920001L, sentence = "第四。", createdAt = 1700000999L,
        )
        assertEquals(listOf("第二。", "第一。", "第三。", "第四。"), sentences())
    }

    companion object {
        private const val TEST_DB_NAME = "mining_sentence_order_test.db"
    }
}
