package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity spec for the Kotlin port of the spike-004 H9v2 rule (run of record:
 * spikes/004-nameplate-exclusion/scripts/spike004.py, guarded mode — 22/27 recall,
 * 0.815 precision, ZERO casualties on the 126-photo human-truth set).
 *
 * Every assertion below is an instance the spike measured and the README names:
 * fires are tier evidence (a/b/c), blocks are the guard classes (G1 title, G2 copula,
 * G3 absorbed-title) plus the boundary rules. A dictionary word must never fire as
 * tier c — that is the exact-token rule the spike pinned. Synthetic lexicon; the
 * instrumented suite covers the real dictionary.
 */
class NameplateInlineRuleTest {

    private val lex = GateLexiconProvider.lexiconFrom(
        headwords = listOf("村人", "王子", "継承者", "見せる", "海賊団"),
        radicals = emptyList(),
    )

    private fun plates(sentence: String) = NameplateInlineRule.suggestions(sentence, lex).map { it.plate }.toSet()
    private fun one(sentence: String) = NameplateInlineRule.suggestions(sentence, lex).single()

    @Test
    fun fires_tierA_caseCollision_cainWa() {
        val s = one("カイン私は…")
        assertEquals("カイン", s.plate)
        assertEquals('a', s.tier)
        assertEquals("私は…", s.remainder)
    }

    @Test
    fun fires_tierB_ackRemainder_protectsGoldRemainder() {
        // The line yields two proposals like the spike did (short tier-a prefix + full tier-b
        // plate); the ledger shows both, the user rules. The invariant that matters: the tier-b
        // remainder keeps はい。 byte-intact — it is gold content in photo 1136.
        val hits = NameplateInlineRule.suggestions("モ口ドフはい。", lex)
        val b = hits.single { it.tier == 'b' }
        assertEquals("はい。", b.remainder)
        assertEquals("モ口ドフ", b.plate)
    }

    @Test
    fun fires_tierC_danglingNoun_notADictionaryWord() {
        val s = one("マルスぼくは絶対に忘れない。")
        assertEquals("マルス", s.plate)
        assertEquals('c', s.tier)
    }

    @Test
    fun tierC_refusesExactDictionaryWord() {
        val lexWith = GateLexiconProvider.lexiconFrom(listOf("マルス"), emptyList())
        assertTrue(
            "a token that IS a JMdict word must never be excised as tier c",
            NameplateInlineRule.suggestions("マルスぼくは絶対に忘れない。", lexWith).none { it.tier == 'c' },
        )
    }

    @Test
    fun fires_titleHeadedClauseRemainders_keepsBothTrueMerges() {
        // 985-class: title opens the utterance WITH a long clause — proposable.
        assertTrue("ジェイガン" in plates("ジェイガン王子、ほとんどのグラ軍が城内に集中している"));
        assertTrue(
            "モロドフ" in plates("モロドフ王子の格好をした者がたった一人で逃れようとしていた"),
        )
    }

    @Test
    fun fires_graGuntaicho_tierB_keepsTitleTailPlateAlive() {
        // G3 exempts tiers a/b: the true plate itself ends in a title char.
        assertTrue("グラ軍隊長" in plates("グラ軍隊長なあに、そんなことを言っている場合ではないぞ。"))
    }

    @Test
    fun blocks_G1_bareTitlesAndSetPhraseTails() {
        assertEquals(emptySet<String>(), plates("マルス王子。"))
        assertEquals(emptySet<String>(), plates("「マルス王子よ。"))
        assertEquals(emptySet<String>(), plates("、ジェイガ王子!"))
        assertEquals(emptySet<String>(), plates("ファルシオン継承者として"))
    }

    @Test
    fun blocks_G2_copulaAndParticles() {
        assertEquals(emptySet<String>(), plates("ジオルだとくそっ!"))
        assertEquals(emptySet<String>(), plates("シーダならそれも仕方がない。"))
    }

    @Test
    fun blocks_boundaryRules() {
        assertEquals(emptySet<String>(), plates("村人ぐずぐずしてないで、全員先に進んだ方がいい。")) // 2-char kanji stem
        assertEquals(emptySet<String>(), plates("カイン、ぼくも思いは同じだ。"))                    // vocative comma
        assertEquals(emptySet<String>(), plates("タリス王は王子のお父上の古い友人。"))                // 単独 kanji+は bound reading
    }

    @Test
    fun lexicon_buildsOkuriganaPairsFromRawHeadwords() {
        assertTrue(lex.okurigana.contains("見せ")) // 見せる → pair (見,せ)
        assertFalse(lex.okurigana.contains("村せ"))
    }
}
