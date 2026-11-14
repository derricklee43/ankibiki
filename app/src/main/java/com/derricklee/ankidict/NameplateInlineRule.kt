package com.derricklee.ankidict

/**
 * Speaker-nameplate MERGE suggestions (spike 004 H9v2 — user's grammatical-detachment idea,
 * 2026-11-13). Detects an utterance sentence whose leading token is a glued speaker plate
 * (カイン私は…, モ口ドフはい。) so the claims ledger can PROPOSE the excision; the user's
 * 正しい ruling is the only thing that ever cuts (guardrail G1/G2 by construction).
 *
 * Verbatim port of the run of record `spikes/004-nameplate-exclusion/scripts/spike004.py`
 * (cut_candidates(guarded=True) + tier + fire gates): measured 22/27 recall, 0.815 precision,
 * ZERO content casualties on the 126-photo human-truth set. Below the ≥0.95 auto-go bar on
 * purpose — this file must never auto-apply; parity spec: NameplateInlineRuleTest.
 *
 * Tiers: a = case collision (remainder opens its own noun+は/が/も subject — the plate can't
 * be part of it); b = self-contained remainder (はい-class acknowledgments attach to nothing);
 * c = bare dangling noun, licensed ONLY when the token is not an exact JMdict word, plus the
 * corpus guards G1–G3 (title phrases, copulas, absorbed title tails) documented inline.
 */
object NameplateInlineRule {

    data class Suggestion(val plate: String, val sentence: String, val remainder: String, val tier: Char)

    private const val TERM = "。．！？!?.…"
    private val PARTICLES = setOf('は', 'が', 'を', 'に', 'で', 'へ', 'と', 'や', 'ば', 'な', 'も', 'か', 'よ', 'ね', 'ぞ')
    private val KOTO = setOf('、', '・', '，', ',')
    private val SUFFIX_HEADS = listOf("の", "たち", "ども", "ちゃ", "さん", "くん", "様", "王国", "帝国")
    private val TITLES = listOf("軍団長", "継承者", "隊長", "将軍", "皇帝", "王子", "騎士", "姫", "王")
    private val COPULAS = listOf("だと", "なら", "です", "だよ", "である")
    private val ACKS = listOf("はい", "いいえ", "なあに", "うん", "ええ", "フム", "むむ")
    private val BRACKETS = "」『』【】（）()"

    private fun isHira(c: Char) = c in '\u3040'..'\u309f'
    private fun isKata(c: Char) = c in '\u30a0'..'\u30ff' || c == 'ー' || c == 'ヽ' || c == 'ヶ'
    private fun isKanji(c: Char) = c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'
    private fun isCjkChar(c: Char) = isHira(c) || isKata(c) || isKanji(c)
    private fun isNounish(c: Char) = isKanji(c) || isKata(c)

    /** All plate-cut proposals for one parsed sentence; L ascending, deduped by plate.
     * A short proposal swallowed as a strict prefix of a longer tier-a/b one is a mid-name
     * artifact (モロ ⊂ モロドフ|はい。) and is pruned — the fuller evidence wins. */
    fun suggestions(sentence: String, lex: GateLexicon): List<Suggestion> {
        val raw = suggestRaw(sentence, lex)
        return raw.filterNot { h ->
            raw.any { g -> g !== h && g.plate.length > h.plate.length && g.plate.startsWith(h.plate) && g.tier != 'c' }
        }
    }

    private fun suggestRaw(sentence: String, lex: GateLexicon): List<Suggestion> {
        val norm = sentence.filterNot(Char::isWhitespace)
        val proj = gateCjkOnly(norm)
        val out = ArrayList<Suggestion>()
        val seenPlates = HashSet<String>()
        for (l in 2..6) {
            if (proj.length <= l) continue
            val t = proj.substring(0, l)
            if (t.any(::isHira)) continue                                     // plates carry no hiragana
            if (t.all(::isKanji) && t.length < 3) continue                   // 2-char kanji prefixes are word stems
            val rn = norm.substring(projCutIndex(norm, l))
            if (rn.isEmpty()) continue
            var head = rn.trimStart('」', '』', '）', ')')                     // closing bubble quote = plate boundary
            if (head.isEmpty()) continue
            if (head[0] in KOTO) continue                                    // comma-attached = vocative
            if (SUFFIX_HEADS.any { head.startsWith(it) }) continue           // bound suffix/compound tail
            val bare = head.trim { it in TERM || it in BRACKETS || it.isWhitespace() }
            if (isKanji(t.last()) && bare.length <= 3 && head.none(::isHira) &&
                head.all { isCjkChar(it) || it in TERM }
            ) continue                                                       // bare title phrase (マルス王子。)
            if (isHira(head[0]) && head[0] in PARTICLES && ACKS.none { head.startsWith(it) }) continue // attachable particle (ack openers exempt)
            if (isKata(t.last()) && isKata(head[0]) &&
                !ACKS.any { a -> head.startsWith(a) && (head.length == a.length || head[a.length] in TERM || head[a.length] == '、') }
            ) continue                                                       // mid-name katakana run (ack-openers exempt)
            if (isKanji(head[0]) && head[0] !in "私僕君貴俺わ" && head.length > 1 &&
                isHira(head[1]) && head[1] in "はがのもにで"
            ) continue                                                       // single kanji + particle = bound reading
            if (isKanji(t.last()) && isHira(head[0]) && lex.okurigana.contains("${t.last()}${head[0]}")) continue
            // G1: title-phrase remainder (王子。/ 王子よ。/ 継承者として) is content; clause-headed
            // titles (王子の格好…, 王子、ほとんど…) stay proposable.
            val title = TITLES.firstOrNull { head.startsWith(it) }
            if (title != null) {
                val after = head.substring(title.length)
                val punctOnlyTail = after.isNotEmpty() && after.all { it in TERM || it in BRACKETS || it.isWhitespace() }
                if (after.isEmpty() || punctOnlyTail || after[0] in "とよだでな") continue
            }
            // G2: copula openers attach to the preceding token (ジオルだと = "it IS Diorl").
            if (COPULAS.any { head.startsWith(it) }) continue
            val tier = tierOf(rn)
            var fire = tier != 'c' || (t.length <= 6 && !lex.headwords.contains(t))
            // G3: tier-c token that swallowed a title tail (マルス王|よ。) — tier a/b exempt
            // (グラ軍隊長|なあに is a genuine tier-b merge).
            if (fire && tier == 'c' && t.last() in "王姫長帝") fire = false
            if (fire && seenPlates.add(t)) out.add(Suggestion(t, sentence, rn, tier))
        }
        return out
    }

    private fun tierOf(rn: String): Char {
        if (ACKS.any { rn.startsWith(it) }) return 'b'
        var run = 0
        while (run < rn.length && isNounish(rn[run])) run++
        if (run >= 1 && rn.length > run && rn[run] in "はがも") return 'a'
        return 'c'
    }

    /** Index in [s] just past the n-th CJK char (projection coordinates → raw coordinates). */
    private fun projCutIndex(s: String, nchars: Int): Int {
        var seen = 0
        for (i in s.indices) {
            if (isCjkChar(s[i])) {
                seen++
                if (seen == nchars) return i + 1
            }
        }
        return s.length
    }
}
