package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Words-tab ordering (user spec 2026-11-13: "alphabetical according to Japanese alphabet
 * standards"): kana gojūon for Japanese, toneless pinyin alphabet for Chinese. The table in
 * ItemOrdering is pinned here — hand-rolled weights must keep matching these expectations on
 * every JVM, independent of host collation data.
 */
class ItemOrderingTest {

    private fun jp(vararg exprKana: Pair<String, String>): List<String> =
        exprKana.toList()
            .sortedWith { a, b -> ItemOrdering.compare(LanguageTag.JAPANESE, a.second, a.first, b.second, b.first) }
            .map { it.first }

    @Test
    fun `rows come out in vowel order across the main rows`() {
        assertEquals(
            listOf("かぜ", "き", "ねこ", "ひ", "みず", "やま", "らいく", "わ", "を", "ん"),
            jp(
                "みず" to "みず", "ひ" to "ひ", "き" to "き", "ねこ" to "ねこ", "かぜ" to "かぜ",
                "ん" to "ん", "わ" to "わ", "を" to "を", "やま" to "やま", "らいく" to "らいく",
            ),
        )
    }

    @Test
    fun `voiced sorts after its plain partner at the same slot`() {
        assertEquals(listOf("ka", "ga", "ki", "gi", "ku", "gu"),
            jp("ka" to "か", "ga" to "が", "ki" to "き", "gi" to "ぎ", "ku" to "く", "gu" to "ぐ"))
    }

    @Test
    fun `ha-row orders plain then voiced then half-voiced before ma-row`() {
        assertEquals(listOf("ha", "ba", "pa", "ma"),
            jp("ma" to "ま", "pa" to "ぱ", "ba" to "ば", "ha" to "は"))
    }

    @Test
    fun `di and du sort as voiced chi and tsu while ji stays in the sa-row`() {
        assertEquals(listOf("し", "じ", "す", "ず", "ち", "ぢ", "つ", "づ"),
            jp("つ" to "つ", "づ" to "づ", "ぢ" to "ぢ", "ち" to "ち", "ず" to "ず", "す" to "す", "じ" to "じ", "し" to "し"))
    }

    @Test
    fun `small kana sort right after their full-size counterpart`() {
        assertEquals(listOf("あ", "ぁ"), jp("ぁ" to "あぁ", "あ" to "ああ"))
        // yōon: きや (full や) precedes きゃ (small ゃ at the same slot)
        assertEquals(listOf("kiya", "kya"), jp("kya" to "きゃ", "kiya" to "きや"))
    }

    @Test
    fun `katakana folds to the hiragana reading and the expression breaks ties`() {
        assertEquals(listOf("A", "B"),
            jp("B" to "カ", "A" to "か")) // equal kana weights -> raw expression tie-break
    }

    @Test
    fun `choonpu comes after the whole gojūon block including n`() {
        assertEquals(listOf("さ", "さあ", "さお", "さん", "さー"),
            jp("さー" to "さー", "さん" to "さん", "さお" to "さお", "さあ" to "さあ", "さ" to "さ"))
    }

    @Test
    fun `blank reading falls back to the expression and kanji land after kana`() {
        val sorted = jp("水" to "みず", "語" to "", "愛" to "あい")
        assertEquals(listOf("愛", "水", "語"), sorted) // あい < みず; 語 (kanji, unmapped) sorts last
    }

    @Test
    fun `spaces in the reading are ignored`() {
        // Same key with and without the space, so the raw expression breaks the tie: "x" < "y".
        assertTrue(ItemOrdering.compare(LanguageTag.JAPANESE, "ね こ", "x", "ねこ", "y") < 0)
    }

    @Test
    fun `chinese sorts toneless-alphabetic with tone marks only breaking letter ties`() {
        val sorted = listOf(
            "mǎ" to "马", "mā" to "妈", "lǎo" to "老", "lí" to "离",
        ).sortedWith { a, b -> ItemOrdering.compare(LanguageTag.CHINESE, a.first, a.second, b.first, b.second) }
            .map { it.second }
        assertEquals(listOf("老", "离", "妈", "马"), sorted) // lao < li < ma; mā < mǎ within the tie
    }

    @Test
    fun `pinyin ü folds to u for the toneless key`() {
        // 女 "nǚ" and 奴 "nu" share the toneless key "nu"; the raw string tie-break puts plain "nu" first.
        assertTrue(ItemOrdering.compare(LanguageTag.CHINESE, "nǚ", "女", "nu", "奴") > 0)
    }
}
