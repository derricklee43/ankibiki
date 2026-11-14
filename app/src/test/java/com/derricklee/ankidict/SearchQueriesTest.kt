package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Track C whole-word lookup: the query-splitting rule that makes compound CJK queries reach the
 * word dictionaries WHILE keeping the character-exploration path. Pure logic, pinned on the JVM;
 * dictionary reachability for multi-character strings is pinned by WholeWordLookupInstrumentedTest
 * against the real bundled tables, and the combined ordering by the 引き walkthrough.
 */
class SearchQueriesTest {

    @Test
    fun cjkCompound_wholeWordFirst_thenCharacters() {
        assertEquals(listOf("海賊", "海", "賊"), searchQueries("海賊"))
        assertEquals(listOf("海盗", "海", "盗"), searchQueries("海盗"))
        assertEquals(listOf("海賊船", "海", "賊", "船"), searchQueries("海賊船"))
    }

    @Test
    fun singleCharacter_andShortForms_unchanged() {
        assertEquals(listOf("海"), searchQueries("海"))
        assertEquals(listOf("a"), searchQueries("a"))
    }

    @Test
    fun nonPureCjk_queriesStayWhole_asBefore() {
        // kana/mixed/romaji were never split — they already ran through the whole-string path
        assertEquals(listOf("わからない"), searchQueries("わからない"))
        assertEquals(listOf("海賊だ"), searchQueries("海賊だ")) // kana tail => not all-CJK
        assertEquals(listOf("akua"), searchQueries("akua"))
        assertEquals(listOf("kaizo"), searchQueries("kaizo"))
    }
}
