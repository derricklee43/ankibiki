package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Words (definition) tab contract (user spec 2026-11-13): one row per canonical item,
 * HIDDEN included, ARCHIVED excluded, gojūon order (pinyin alphabet for Chinese), frequency
 * counted over live occurrences on KEPT photos only, and the tap-target occurrenceId is the
 * newest live occurrence — -1 when there is none.
 */
@RunWith(AndroidJUnit4::class)
class WordDefinitionsInstrumentedTest {
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

    private fun photo(id: Long, tag: LanguageTag, status: PhotoStatus) = repository.upsertPhoto(
        ScrapedPhoto(
            mediaStoreId = id,
            contentUri = "content://media/external/images/media/$id",
            dateAdded = 1700000000L,
            languageTag = tag,
            extractedText = "テスト",
            status = status,
        ),
    )

    private fun item(tag: LanguageTag, type: ItemType, expr: String, kana: String, meaning: String) =
        repository.upsertExtractedItem(
            languageTag = tag, itemType = type,
            expression = expr, kanji = expr, kana = kana, meaning = meaning,
        )

    @Test
    fun japanese_definitions_are_unique_hidden_kept_and_gojun_ordered() {
        photo(930001L, LanguageTag.JAPANESE, PhotoStatus.KEEP)
        photo(930002L, LanguageTag.JAPANESE, PhotoStatus.KEEP)
        photo(930003L, LanguageTag.JAPANESE, PhotoStatus.IGNORED)

        item(LanguageTag.JAPANESE, ItemType.WORD, "木", "き", "tree")
        item(LanguageTag.JAPANESE, ItemType.WORD, "水", "みず", "water")
        item(LanguageTag.JAPANESE, ItemType.WORD, "猫", "ねこ", "cat")
        item(LanguageTag.JAPANESE, ItemType.WORD, "風", "かぜ", "wind")
        item(LanguageTag.JAPANESE, ItemType.WORD, "泥", "どろ", "mud")
        item(LanguageTag.JAPANESE, ItemType.WORD, "雷", "いかずち", "thunder")

        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "木", 930001L, "木々。", 1700000100L)
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "水", 930001L, "水。", 1700000101L)
        val newestWater = repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "水", 930002L, "水！", 1700000102L)
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "風", 930001L, "風。", 1700000103L)
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "泥", 930001L, "泥。", 1700000104L)
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "雷", 930003L, "雷。", 1700000105L)

        repository.hideItem(LanguageTag.JAPANESE, ItemType.WORD, "風")
        repository.archiveItem(LanguageTag.JAPANESE, ItemType.WORD, "泥")

        val rows = repository.getCanonicalItemDefinitions(LanguageTag.JAPANESE, ItemType.WORD)

        // 泥 archived -> gone; 風 hidden -> stays; one row per expression; い<か<き<ね<み.
        assertEquals(listOf("雷", "風", "木", "猫", "水"), rows.map { it.expression })

        val wind = rows.first { it.expression == "風" }
        assertEquals(ItemType.WORD, wind.itemType)
        assertEquals(1, wind.occurrenceCount) // SEEN still counts as a live occurrence

        val water = rows.first { it.expression == "水" }
        assertEquals(2, water.occurrenceCount)
        assertEquals(newestWater, water.occurrenceId)

        val cat = rows.first { it.expression == "猫" } // item with zero occurrences
        assertEquals(0, cat.occurrenceCount)
        assertEquals(-1L, cat.occurrenceId)

        val thunder = rows.first { it.expression == "雷" } // only occurrence on an IGNORED photo
        assertEquals(0, thunder.occurrenceCount)
        assertEquals(-1L, thunder.occurrenceId)

        assertTrue(rows.all { it.meaning.isNotBlank() })
    }

    @Test
    fun chinese_definitions_sort_by_toneless_pinyin_alphabet() {
        photo(931001L, LanguageTag.CHINESE, PhotoStatus.KEEP)
        item(LanguageTag.CHINESE, ItemType.WORD, "马", "mǎ", "horse")
        item(LanguageTag.CHINESE, ItemType.WORD, "妈", "mā", "mother")
        item(LanguageTag.CHINESE, ItemType.WORD, "老", "lǎo", "old")
        item(LanguageTag.CHINESE, ItemType.WORD, "离", "lí", "leave")
        for ((expr, _) in listOf("马" to 0, "妈" to 0, "老" to 0, "离" to 0)) {
            repository.insertOccurrence(LanguageTag.CHINESE, ItemType.WORD, expr, 931001L, "句子$expr", 1700000200L)
        }

        val rows = repository.getCanonicalItemDefinitions(LanguageTag.CHINESE, ItemType.WORD)
        assertEquals(listOf("老", "离", "妈", "马"), rows.map { it.expression })
    }

    @Test
    fun grammar_items_get_their_own_definition_tab() {
        photo(932001L, LanguageTag.JAPANESE, PhotoStatus.KEEP)
        item(LanguageTag.JAPANESE, ItemType.GRAMMAR, "について", "について", "about")
        item(LanguageTag.JAPANESE, ItemType.GRAMMAR, "から", "から", "from/because")
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.GRAMMAR, "について", 932001L, "ことについて。", 1700000300L)
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.GRAMMAR, "から", 932001L, "ここから。", 1700000301L)

        val words = repository.getCanonicalItemDefinitions(LanguageTag.JAPANESE, ItemType.WORD)
        val grammar = repository.getCanonicalItemDefinitions(LanguageTag.JAPANESE, ItemType.GRAMMAR)
        assertTrue(words.isEmpty())
        assertEquals(listOf("から", "について"), grammar.map { it.expression }) // か(5..) before つ(17)
    }

    /** The inbox tab is unchanged: hiding 風 keeps it out of ACTIVE occurrences but the Words tab still lists it. */
    @Test
    fun hidden_word_stays_out_of_inbox_but_appears_in_words_tab() {
        photo(933001L, LanguageTag.JAPANESE, PhotoStatus.KEEP)
        item(LanguageTag.JAPANESE, ItemType.WORD, "雨", "あめ", "rain")
        repository.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "雨", 933001L, "雨。", 1700000400L)
        repository.hideItem(LanguageTag.JAPANESE, ItemType.WORD, "雨")

        val inbox = repository.getActiveOccurrences(
            OccurrenceQuery(
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.WORD,
                visibilities = setOf(ItemVisibility.ACTIVE),
            ),
        )
        assertTrue(inbox.isEmpty())
        assertEquals(listOf("雨"), repository.getCanonicalItemDefinitions(LanguageTag.JAPANESE, ItemType.WORD).map { it.expression })
    }

    companion object {
        private const val TEST_DB_NAME = "mining_word_definitions_test.db"
    }
}
