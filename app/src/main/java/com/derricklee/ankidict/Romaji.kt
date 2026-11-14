package com.derricklee.ankidict

import java.text.Normalizer

/**
 * Kana -> romaji for search matching (word list, user request 2026-11-13).
 *
 * Deliberately NOT a dependency (Kanayanize/ICU4J territory): those earn their keep when a
 * READING must be inferred from kanji. Our corpus never infers — extracted_items stores the
 * kana reading next to every expression — so the only missing bridge is a deterministic
 * kana->romaji table. Pure, JVM-tested, matching-oriented: [variants] returns several
 * acceptable spellings ("toukyou" AND "tokyo", "gakkou" AND "gakko"); a query matches when
 * ANY variant contains it. Generous by design — search noise, never corpus writes.
 */
object Romaji {

    // mora -> romaji; digraphs (y-on) are matched before monographs.
    private val DIGRAPHS: Map<String, String> = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
    )

    private val MONOGRAPHS: Map<Char, String> = buildMap {
        val rows = """
            あ,a い,i う,u え,e お,o
            か,ka き,ki く,ku け,ke こ,ko
            さ,sa し,shi す,su せ,se そ,so
            た,ta ち,chi つ,tsu て,te と,to
            な,na に,ni ぬ,nu ね,ne の,no
            は,ha ひ,hi ふ,fu へ,he ほ,ho
            ま,ma み,mi む,mu め,me も,mo
            や,ya ゆ,yu よ,yo
            ら,ra り,ri る,ru れ,re ろ,ro
            わ,wa を,wo ん,n
            が,ga ぎ,gi ぐ,gu げ,ge ご,go
            ざ,za じ,ji ず,zu ぜ,ze ぞ,zo
            だ,da ぢ,ji づ,zu で,de ど,do
            ば,ba び,bi ぶ,bu べ,be ぼ,bo
            ぱ,pa ぴ,pi ぷ,pu ぺ,pe ぽ,po
            ゃ,ya ゅ,yu ょ,yo
            ぁ,a ぃ,i ぅ,u ぇ,e ぉ,o
            ャ,ya ュ,yu ヨ,yo
        """.trimIndent()
        rows.lines().flatMap { it.trim().split(" ") }.forEach { cell ->
            val parts = cell.split(",")
            if (parts.size == 2 && parts[0].length == 1) put(parts[0][0], parts[1])
        }
        put('っ', "")      // gemination handled structurally below
        put('ー', "")      // vowel extension handled structurally below
        put('：', ":")
        put('？', "?"); put('！', "!"); put('。', "."); put('、', ",")
        put('　', " "); put(' ', " ")
    }

    /** Full romanization: y-on digraphs, っ-gemination (doubles next mora onset), ー-extends vowel. */
    fun toRomaji(kana: String): String {
        val out = StringBuilder()
        var lastVowel: Char = 'a'
        var i = 0
        while (i < kana.length) {
            val ch = kana[i]
            val two = if (i + 1 < kana.length) kana.substring(i, i + 2) else null
            when {
                ch == 'っ' -> {
                    val next = nextRomajiAt(kana, i + 1)
                    if (next.isNotEmpty()) out.append(next.first()) // double the onset
                    i++
                }
                ch == 'ー' -> {
                    out.append(lastVowel)
                    i++
                }
                two != null && DIGRAPHS.containsKey(two) -> {
                    out.append(DIGRAPHS.getValue(two))
                    lastVowel = DIGRAPHS.getValue(two).lastVowelOf() ?: lastVowel
                    i += 2
                }
                else -> {
                    val r = MONOGRAPHS[ch] ?: ch.lowercase()
                    out.append(r)
                    lastVowel = r.lastVowelOf() ?: lastVowel
                    i++
                }
            }
        }
        return out.toString().lowercase()
    }

    private fun nextRomajiAt(kana: String, at: Int): String {
        if (at >= kana.length) return ""
        val two = if (at + 1 < kana.length) kana.substring(at, at + 2) else null
        return two?.let { DIGRAPHS[it] } ?: MONOGRAPHS[kana[at]] ?: kana[at].lowercase()
    }

    /**
     * Search spellings for a reading. Japanese kana readings -> [full, compact];
     * kana-less readings (Chinese items store tone-mark pinyin) -> [normalized, compact].
     */
    fun variants(reading: String): List<String> {
        if (reading.isBlank()) return emptyList()
        val isKana = reading.any { it in '\u3040'..'\u30ff' }
        val full = if (isKana) toRomaji(reading) else normalizePinyin(reading)
        return listOf(full, compact(full)).filter { it.isNotBlank() }.distinct()
    }

    /** Collapse long-vowel spellings: "toukyou"->"tokyo", "gakkou"->"gakko", "sensei"->"sensi". */
    internal fun compact(romaji: String): String = buildString {
        var prev: Char? = null
        for (ch in romaji) {
            val drop = prev != null && ((ch == 'u' && prev == 'o') || (ch == 'i' && prev == 'e') ||
                (ch == prev && ch in "aiueo"))
            if (!drop) append(ch)
            prev = ch
        }
    }

    /** Strip tone marks (NFD), tone numbers, spaces; ü/v -> u; lowercase. */
    fun normalizePinyin(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (ch in decomposed) {
            if (java.lang.Character.getType(ch) == java.lang.Character.NON_SPACING_MARK.toInt()) continue
            val c = ch.lowercaseChar()
            when {
                c in 'a'..'z' -> sb.append(c)
                c == 'ü' -> sb.append('u')
                c == 'v' -> sb.append('u') // tone numbers & non-ASCII dropped
            }
        }
        return sb.toString()
    }

    private fun String.lastVowelOf(): Char? = lastOrNull { it in "aiueo" }
}

/**
 * Pure matcher behind the word-list search box: expression (kanji/kana as typed), stored kana
 * reading, romaji-of-kana (any variant), or toneless pinyin for Chinese items. Gloss search is
 * deliberately out of scope — the user asked for kanji/kana/romaji.
 */
object ItemSearch {
    fun matches(expression: String, kana: String?, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (expression.lowercase().contains(q)) return true
        if (!kana.isNullOrBlank()) {
            if (kana.lowercase().contains(q)) return true
            // v->u fold on the query side too: users type "lv" for lü; stored form normalizes ü->u.
            val qv = q.replace('v', 'u')
            if (Romaji.variants(kana).any { it.contains(q) || it.contains(qv) }) return true
        }
        return false
    }

    /**
     * Bounded Levenshtein with early exit: returns a distance <= [max] if within budget,
     * else null. O(len*max) rows, not the full matrix.
     */
    internal fun editDistanceWithin(a: String, b: String, max: Int): Int? {
        if (kotlin.math.abs(a.length - b.length) > max) return null
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            var rowMin = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1))
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > max) return null
            prev = cur
        }
        val d = prev[b.length]
        return if (d <= max) d else null
    }

    /**
     * Match TIER for word-list search: 0 = exact (kanji/kana containment or a romaji/pinyin
     * variant contains it), then 1/2 = typo-tolerant romaji only (ASCII queries of sufficient
     * length; kanji and kana stay exact — sub-string fuzzy there is noise, not help).
     * Returns null for no match.
     */
    fun searchTier(expression: String, kana: String?, query: String): Int? {
        if (matches(expression, kana, query)) return 0
        val q = query.trim().lowercase().replace('v', 'u')
        if (q.length < 4) return null
        if (!q.all { it in 'a'..'z' }) return null // fuzzy is romaji/pinyin-only
        val max = if (q.length >= 8) 2 else 1
        if (kana.isNullOrBlank()) return null
        // Windowed distance: a mistyped query need only approximate SOME window of the full
        // spelling (the query usually covers part of a longer reading); the TIER returned is the
        // true minimum distance, so ordering reflects confidence.
        var best: Int? = null
        for (variant in Romaji.variants(kana)) {
            if (variant == q) return 0
            if (variant.length >= q.length - max) {
                val winMin = minOf(q.length, variant.length)
                for (len in maxOf(winMin - max, 2)..winMin) {
                    for (i in 0..variant.length - len) {
                        val d = editDistanceWithin(variant.substring(i, i + len), q, max)
                        if (d != null) best = minOf(best ?: Int.MAX_VALUE, d)
                    }
                }
            } else {
                val d = editDistanceWithin(variant, q, max)
                if (d != null) best = minOf(best ?: Int.MAX_VALUE, d)
            }
        }
        return best
    }
}
