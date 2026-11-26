package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the user-dictionary span-forcing decorator
 * (docs/user-dictionary-design.md §3.2, RULED D1a) and the v16 ladder decision
 * (docs/definition-model-design.md §3.1). The fake base analyzer emits exact
 * token/candidate layouts so every conflict rule is isolated from Kuromoji.
 */
class UserDictionaryAnalyzerTest {

    /** sentence = concatenation of surfaces; each token optionally a candidate. */
    private class FakeBase(private val tokens: List<Triple<String, String?, String?>>) : WordAnalyzer {
        data class Tok(val surface: String, val expr: String?, val reading: String?)
        val layout = tokens.map { Tok(it.first, it.second, it.third) }
        override fun extractWords(sentence: String): List<NormalizedCandidate> =
            extractTokens(sentence).mapNotNull { it.candidate }
        override fun extractTokens(sentence: String): List<ExtractedToken> {
            var cursor = 0
            return layout.map { t ->
                val start = cursor
                cursor += t.surface.length
                ExtractedToken(
                    t.surface,
                    start..(start + t.surface.length - 1),
                    t.expr?.let { NormalizedCandidate(it, t.surface, t.reading) },
                )
            }
        }
    }

    private fun word(expr: String, reading: String? = null, meaning: String = "m") =
        UserWord(expr, reading, meaning)

    private fun candidates(base: WordAnalyzer, words: List<UserWord>, sentence: String): List<String> =
        UserDictionaryWordAnalyzer(base, { words }).extractWords(sentence).map { it.expression }

    @Test
    fun emptyIndex_returnsBaseOutput_unchanged() {
        val base = FakeBase(listOf(
            Triple("猫", "猫", "ねこ"),
            Triple("を", null, null),
            Triple("追う", "追う", "おう"),
        ))
        val sentence = "猫を追う"
        val decorated = UserDictionaryWordAnalyzer(base, { emptyList() }).extractWords(sentence)
        assertEquals(base.extractWords(sentence), decorated)
    }

    @Test
    fun tokenEqualMatch_replacesReadingAndKeepsPosition() {
        val base = FakeBase(listOf(
            Triple("アオイ", "アオイ", null),
            Triple("ちゃん", null, null),
        ))
        val out = UserDictionaryWordAnalyzer(base, { listOf(word("アオイ", "あおい")) })
            .extractWords("アオイちゃん")
        assertEquals(listOf("アオイ"), out.map { it.expression })
        assertEquals("あおい", out.first().readingKana)
    }

    @Test
    fun multiTokenSpan_forcesWholeName_andSuppressesFragments() {
        // 竈門炭治郎 split by IPADIC into 竈門 + 炭 + 治郎 + の
        val base = FakeBase(listOf(
            Triple("竈門", "竈門", "かまど"),
            Triple("炭", "炭", "すみ"),
            Triple("治郎", "治郎", "じろう"),
            Triple("の", null, null),
            Triple("少年", "少年", "しょうねん"),
        ))
        val out = UserDictionaryWordAnalyzer(base, { listOf(word("竈門炭治郎", "たんじろう")) })
            .extractWords("竈門炭治郎の少年")
        // fragments 竈門/炭/治郎 suppressed; 少年 (outside the span) survives
        assertEquals(listOf("少年", "竈門炭治郎").sorted(), out.map { it.expression }.sorted())
        val name = out.first { it.expression == "竈門炭治郎" }
        assertEquals("たんじろう", name.readingKana)
        assertEquals("竈門炭治郎", name.surfaceForm)
    }

    @Test
    fun strictSubstringOfOneToken_neverFires() {
        // 花子 registered; the only occurrence sits inside the legit token 花生まれ
        val base = FakeBase(listOf(
            Triple("花生まれ", "花生まれ", "はなうまれ"),
        ))
        assertEquals(
            listOf("花生まれ"),
            candidates(base, listOf(word("花子")), "花生まれ"),
        )
    }

    @Test
    fun firesWhereForceable_evenIfAnotherOccurrenceIsSubstring() {
        // 女子 registered; sentence has 女子力 (substring occurrence, IGNORE) and
        // a standalone 女子 token (token-equal, fires)
        val base = FakeBase(listOf(
            Triple("女子力", "女子力", "じょしりき"),
            Triple("を", null, null),
            Triple("持つ", "持つ", "もつ"),
            Triple("女子", "女子", "じょし"),
        ))
        val out = candidates(base, listOf(word("女子", "じょし")), "女子力を持つ女子")
        assertTrue(out.contains("女子"))
        // the substring token survives untouched (only the standalone matched)
        assertTrue(out.contains("女子力"))
        assertTrue(out.contains("持つ"))
        // suppressed candidate replaced in place by the user voice (user reading)
        val joined = UserDictionaryWordAnalyzer(base, { listOf(word("女子", "じょし")) })
            .extractWords("女子力を持つ女子")
        assertEquals("じょし", joined.first { it.expression == "女子" }.readingKana)
    }

    @Test
    fun longestUserEntryWinsOverShorterInsideItsSpan() {
        val base = FakeBase(listOf(
            Triple("竈門", "竈門", "かまど"),
            Triple("炭治郎", "炭治郎", "たんじろう"),
        ))
        // BOTH 竈門炭治郎 and 炭治郎 registered; only the long one fires inside
        // the covered span — the short one's only occurrence is the name's tail
        val out = candidates(
            base,
            listOf(word("炭治郎", "たんじろう"), word("竈門炭治郎", "かまどたんじろう")),
            "竈門炭治郎",
        )
        assertEquals(listOf("竈門炭治郎"), out)
    }

    @Test
    fun unknownSpans_degradeConservatively() {
        // base WITHOUT positions (default extractTokens): suppression and the
        // substring-ignore are unprovable -> matches force, nothing suppresses
        val plainBase = object : WordAnalyzer {
            override fun extractWords(sentence: String) =
                listOf(NormalizedCandidate("田中", "田中", "たなか"))
        }
        val out = UserDictionaryWordAnalyzer(plainBase, { listOf(word("田中太郎")) })
            .extractWords("田中太郎さん")
        assertEquals(listOf("田中", "田中太郎"), out.map { it.expression })
    }

    @Test
    fun readingNormalization_usesStoredKanaDirectly() {
        val base = FakeBase(listOf(Triple("禰豆子", "禰豆子", null)))
        val out = UserDictionaryWordAnalyzer(base, { listOf(word("禰豆子", "ねずこ")) })
            .extractWords("禰豆子!")
        assertEquals("ねずこ", out.single().readingKana)
    }
}

/** Pure ladder decision table (docs/definition-model-design.md §3.1). */
class DefinitionSourceLadderTest {

    @Test
    fun userVoiceNeverAutoOverwritten_regardlessOfCaller() {
        assertEquals(DefinitionBranch.CARRY_USER, resolveDefinitionBranch("USER", false, false))
        assertEquals(DefinitionBranch.CARRY_USER, resolveDefinitionBranch("USER", true, true))
    }

    @Test
    fun userDictLiveResolvesWhileEntryExists() {
        assertEquals(
            DefinitionBranch.USER_DICT_LIVE,
            resolveDefinitionBranch("USER_DICT", hasPickKey = false, userWordPresent = true),
        )
    }

    @Test
    fun deletedUserDictEntry_fallsBackToPickThenCaller() {
        assertEquals(
            DefinitionBranch.FALLBACK_PICK,
            resolveDefinitionBranch("USER_DICT", hasPickKey = true, userWordPresent = false),
        )
        assertEquals(
            DefinitionBranch.FALLBACK_CALLER,
            resolveDefinitionBranch("USER_DICT", hasPickKey = false, userWordPresent = false),
        )
    }

    @Test
    fun pickKeyOutranksCaller_forAllNonUserStates() {
        // pre-v16 rows: stamp absent (null) or AUTO but key present -> v13 wins
        assertEquals(DefinitionBranch.FALLBACK_PICK, resolveDefinitionBranch(null, true, false))
        assertEquals(DefinitionBranch.FALLBACK_PICK, resolveDefinitionBranch("AUTO", true, false))
        assertEquals(DefinitionBranch.FALLBACK_PICK, resolveDefinitionBranch("PICK", true, false))
    }

    @Test
    fun autoStateWithoutKey_acceptsCaller() {
        assertEquals(DefinitionBranch.ACCEPT_CALLER, resolveDefinitionBranch(null, false, false))
        assertEquals(DefinitionBranch.ACCEPT_CALLER, resolveDefinitionBranch("AUTO", false, false))
        assertEquals(DefinitionBranch.ACCEPT_CALLER, resolveDefinitionBranch("PICK", false, false))
    }
}

/** UserDictionaryProvider precedence + shapes. */
class UserDictionaryProviderTest {

    private fun provider(words: List<UserWord>) = UserDictionaryProvider({ words })

    @Test
    fun registeredWord_returnsUserMetadata_withUserDictSource() {
        val md = provider(listOf(UserWord("竈門炭治郎", "かまどたんじろう", "the protagonist")))
            .lookup("竈門炭治郎", ItemType.WORD, null)!!
        assertEquals("the protagonist", md.meaning)
        assertEquals("かまどたんじろう", md.kana)
        assertEquals("竈門炭治郎", md.kanji)
        assertEquals(DefinitionSource.USER_DICT, md.source)
    }

    @Test
    fun missAndGrammarReturnNull_soBundledChainProceeds() {
        val p = provider(listOf(UserWord("花子", "はなこ", "name")))
        assertEquals(null, p.lookup("太郎", ItemType.WORD, null))
        assertEquals(null, p.lookup("花子", ItemType.GRAMMAR, null))
    }

    @Test
    fun kanaName_hasEmptyKanji() {
        val md = provider(listOf(UserWord("アオイ", "あおい", "Aoi")))
            .lookup("アオイ", ItemType.WORD, null)!!
        assertEquals("", md.kanji)
    }
}
