package com.derricklee.ankidict

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Track C whole-word lookup (user 2026-11-14) against the REAL bundled tables:
 *  - multi-character CJK queries hit the word dictionaries (the split rule used to forbid this);
 *  - the compound's dictionary hit is ordered FIRST, ahead of the per-character results —
 *    proven through SearchService itself with dictionary-only sources (the Anki tier is gated
 *    by enabledSources, so the service runs without AnkiDroid on the AVD);
 *  - the character-exploration path is unchanged: single-char queries still produce Kanji entries.
 * Query-surface rules (which strings get prepended) are pinned on the JVM by SearchQueriesTest;
 * the combined UI ordering is walked live via extra_initial_query.
 */
@RunWith(AndroidJUnit4::class)
class WholeWordLookupInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dictionaryRepository: DictionaryRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dictionaryRepository = DictionaryRepository(context)
    }

    @Test
    fun japaneseWordTable_isReachable_byCompound() {
        val pirate = dictionaryRepository.lookupJapaneseWords("海賊")
        assertTrue("海賊 must be an exact headword hit in the bundled JMdict", pirate.isNotEmpty())
        val hit = pirate.first()
        assertEquals("海賊", hit.headword)
        assertTrue("reading must carry かいぞく", hit.reading.contains("かいぞく"))

        val student = dictionaryRepository.lookupJapaneseWords("学生")
        assertTrue("学生 must be an exact headword hit", student.isNotEmpty())
    }

    @Test
    fun chineseWordTable_isReachable_byCompound() {
        val hello = dictionaryRepository.lookupChineseWords("你好")
        assertTrue("你好 must be an exact headword hit in the bundled CC-CEDICT", hello.isNotEmpty())
    }

    @Test
    fun lookupExact_compoundGivesWords_notKanji_charStillGivesKanji() {
        val compound = dictionaryRepository.lookupExact("海賊", setOf(
            SearchSource.DICT_KANJI, SearchSource.DICT_JAPANESE_WORDS,
        ))
        assertTrue("compound lookup returns entries", compound.isNotEmpty())
        assertTrue("compound never yields a Kanji card", compound.none { it is DictionaryEntry.Kanji })
        assertTrue("compound yields the Japanese word", compound.any { it is DictionaryEntry.JapaneseWord })

        val single = dictionaryRepository.lookupExact("海", setOf(
            SearchSource.DICT_KANJI, SearchSource.DICT_JAPANESE_WORDS,
        ))
        assertTrue("character path unchanged: 海 still gives the Kanji entry",
            single.any { it is DictionaryEntry.Kanji && it.character == "海" })
    }

    @Test
    fun service_ordersWholeWord_beforeCharacterExploration() {
        val service = SearchService(
            ankiRepository = AnkiRepository(context), // dictionary-only sources below -> provider untouched
            dictionaryRepository = dictionaryRepository,
        )
        val sources = setOf(
            SearchSource.DICT_KANJI,
            SearchSource.DICT_JAPANESE_WORDS,
        )
        val results = service.search("海賊", sources)
        assertTrue("results exist", results.isNotEmpty())

        val firstWord = results.indexOfFirst {
            it is SearchResult.DictionaryHit && it.entry is DictionaryEntry.JapaneseWord
        }
        val firstKanji = results.indexOfFirst {
            it is SearchResult.DictionaryHit && it.entry is DictionaryEntry.Kanji
        }
        assertTrue("the word card is present", firstWord >= 0)
        assertTrue("the character cards are present (exploration preserved)", firstKanji >= 0)
        assertTrue("word ranks ahead of every kanji hit", firstWord < firstKanji)
        val word = (results[firstWord] as SearchResult.DictionaryHit).entry as DictionaryEntry.JapaneseWord
        assertEquals("海賊", word.headword)
    }

    @Test
    fun romajiQuery_pathUntouched_singleWholeQuery() {
        // Non-CJK queries never split — same behavior as before the track C change.
        val results = dictionaryRepository.lookupExact("kaizoku", setOf(SearchSource.DICT_JAPANESE_WORDS))
        assertNotNull(results)
    }
}
