package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Romaji transliteration + word-list matcher (pure JVM). */
class RomajiSearchTest {

    @Test
    fun monographs_and_bigrams() {
        assertEquals("shitei", Romaji.toRomaji("してい"))
        assertEquals("sensei", Romaji.toRomaji("せんせい"))
        assertEquals("sha", Romaji.toRomaji("しゃ"))
        assertEquals("kya", Romaji.toRomaji("きゃ"))
        assertEquals("toukyou", Romaji.toRomaji("とうきょう"))
        assertEquals("tokyo", Romaji.compact("toukyou"))
    }

    @Test
    fun gemination_doubles_next_onset() {
        assertEquals("matte", Romaji.toRomaji("まって"))
        assertEquals("kitte", Romaji.toRomaji("きって"))
        assertEquals("isshi", Romaji.toRomaji("いっし"))
        assertEquals("gakkou", Romaji.toRomaji("がっこう"))
        assertEquals("gakko", Romaji.compact("gakkou"))
    }

    @Test
    fun long_vowel_mark_extends_previous_vowel() {
        assertEquals("kaa", Romaji.toRomaji("かー"))
        assertEquals("puraa", Romaji.toRomaji("ぷらー"))
        assertEquals("pura", Romaji.compact("puraa"))
    }

    @Test
    fun pinyin_normalization_strips_tones_numbers_and_marks() {
        assertEquals("hao", Romaji.normalizePinyin("hǎo"))
        assertEquals("lu", Romaji.normalizePinyin("lǚ"))
        assertEquals("shao", Romaji.normalizePinyin("shào"))
        assertEquals("nihao", Romaji.normalizePinyin("ni3 hao"))
        assertEquals("yu", Romaji.normalizePinyin("yǔ"))
    }

    @Test
    fun variants_cover_full_and_compact_spellings() {
        val v = Romaji.variants("とうきょう")
        assertTrue("full: $v", v.contains("toukyou"))
        assertTrue("compact: $v", v.contains("tokyo"))
        val p = Romaji.variants("xuéxiào") // no kana -> pinyin path
        assertTrue("pinyin: $p", p.contains("xuexiao"))
    }

    @Test
    fun itemSearch_kanji_kana_romaji_all_match() {
        assertTrue(ItemSearch.matches("指定", "してい", "指定"))
        assertTrue(ItemSearch.matches("指定", "してい", "してい"))
        assertTrue(ItemSearch.matches("指定", "してい", "shitei"))
        assertTrue(ItemSearch.matches("指定", "してい", "SHI"))   // case-insensitive
        assertTrue(ItemSearch.matches("指定", "してい", "ite"))   // contains, not prefix
        assertFalse(ItemSearch.matches("指定", "してい", "kite"))
        assertFalse(ItemSearch.matches("指定", null, "shitei"))   // no reading -> expression only
        assertTrue(ItemSearch.matches("勉強", "べんきょう", "benkyou"))
        assertTrue(ItemSearch.matches("東京", "とうきょう", "tokyo")) // compact variant
        assertTrue(ItemSearch.matches("好", "hǎo", "hao"))           // tone marks
        assertTrue(ItemSearch.matches("好", "hǎo", "HAO"))
        assertTrue(ItemSearch.matches("緑", "lǜ", "lv"))             // v-style typing folds to u-form
    }

    @Test
    fun itemSearch_blank_query_matches_everything() {
        assertTrue(ItemSearch.matches("何", null, "  "))
        assertTrue(ItemSearch.matches("何", "なに", ""))
    }

    @Test
    fun fuzzy_tiers_only_for_ascii_queries_and_stays_bounded() {
        // exact still tier 0
        assertEquals(0, ItemSearch.searchTier("海賊", "かいぞく", "kaizoku"))
        // 1 typo (s/z): within budget for a 7-char query
        assertEquals(1, ItemSearch.searchTier("海賊", "かいぞく", "kaisoku"))
        // doubled letter typo
        assertEquals(1, ItemSearch.searchTier("海賊", "かいぞく", "kaizokku"))
        // CJK query with one wrong char does NOT fuzzy (expression path is exact)
        assertEquals(null, ItemSearch.searchTier("海賊", "かいぞく", "海赋"))
        // short queries still work through EXACT containment (tier 0), just no fuzzy budget
        assertEquals(0, ItemSearch.searchTier("海賊", "かいぞく", "kai"))
        assertEquals(null, ItemSearch.searchTier("海賊", "かいぞく", "kal"))
    }
}
