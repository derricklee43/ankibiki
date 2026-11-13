package com.derricklee.ankidict

/**
 * Word-level OCR correction gate — production port of the spike-003 S003-D' configuration
 * (spikes/003-gate-upgrades/README.md). Ships as auto-apply by user decision 2026-11-13,
 * overriding the spike's suggestion-first rollout recommendation: the annotation loop
 * (in-app corrections + the ocr_corrections audit table) is the scoreboard; a harmful
 * write is always visible because raw text is recoverable from each island's evidence row.
 *
 * Evidence classes (all must be pure — identical math to the Python spike, which the JVM
 * parity test pins against spikes/003-gate-upgrades/gate_rows_v2.json):
 *  - dictionary anchoring: some JMdict headword occurs in the substituted ±4-char window
 *    covering the island, and none covering the island occurs in the original window
 *    (陛下∈辞書 vs 陸下∉).
 *  - glyph confusability: 1-char kanji island whose glyphs share a KANJIDIC radical (陸↔陛).
 *  - S003-D' decision: (anchoredB && !anchoredA) || (margin >= 0 && confusable), with
 *    nameplate-adjacent islands excluded (the parked nameplate policy's noise must never
 *    reach the correction path).
 * The line pairing / island extraction mirrors the spike exactly: python-difflib
 * SequenceMatcher (autojunk=false) over CJK-projected line text, similarity in
 * [0.55, 0.999), 'replace' opcodes with both spans <= 4.
 */

/** CJK = kana + kanji ranges used by the spike's is_cjk + 々 (iteration marks). */
fun isGateCjk(ch: Char): Boolean =
    ch in '\u3040'..'\u309f' || ch in '\u30a0'..'\u30ff' ||
        ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf' || ch == '\u3005'

fun gateCjkOnly(s: String): String = buildString(s.length) { for (ch in s) if (isGateCjk(ch)) append(ch) }

/**
 * Faithful port of the subset of python difflib.SequenceMatcher the spike used:
 * isjunk=null, autojunk=false. Implements find_longest_match -> get_matching_blocks
 * -> get_opcodes/ratio with identical tie-breaking (earliest in a, then earliest in b).
 */
object DifflibSequenceMatcher {

    class Matcher(a: String, b: String) {
        private val a = a
        private val b = b
        private val b2j: HashMap<Char, IntArray> = run {
            val counts = HashMap<Char, Int>()
            for (ch in b) counts[ch] = (counts[ch] ?: 0) + 1
            val lists = HashMap<Char, ArrayList<Int>>(counts.size * 2)
            counts.forEach { ch, n -> lists[ch] = ArrayList<Int>(n) }
            b.forEachIndexed { i, ch -> lists.getValue(ch).add(i) }
            HashMap<Char, IntArray>(lists.size * 2).apply {
                lists.forEach { (ch, l) -> put(ch, l.toIntArray()) }
            }
        }

        /** Port of find_longest_match(alo, ahi, blo, bhi); junk logic is a no-op here (isjunk=null). */
        private fun findLongestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
            var besti = alo; var bestj = blo; var bestsize = 0
            var j2len: HashMap<Int, Int> = HashMap()
            for (i in alo until ahi) {
                val positions = b2j[a[i]]
                val newJ2len = HashMap<Int, Int>()
                if (positions != null) {
                    for (j in positions) {
                        if (j < blo) continue
                        if (j >= bhi) break
                        val k = (j2len[j - 1] ?: 0) + 1
                        newJ2len[j] = k
                        if (k > bestsize) { besti = i - k + 1; bestj = j - k + 1; bestsize = k }
                    }
                }
                j2len = newJ2len
            }
            // extend left (non-junk; bjunk is empty so the isbjunk guard is always true)
            while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
                besti--; bestj--; bestsize++
            }
            while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) {
                bestsize++
            }
            // trailing junk-extension loops are no-ops with isjunk=null
            return Triple(besti, bestj, bestsize)
        }

        /** Port of get_matching_blocks (queue recursion, sort, adjacency collapse, sentinel). */
        fun matchingBlocks(): List<Triple<Int, Int, Int>> {
            val la = a.length; val lb = b.length
            data class Region(val alo: Int, val ahi: Int, val blo: Int, val bhi: Int)
            val queue = ArrayDeque<Region>().apply { addLast(Region(0, la, 0, lb)) }
            val blocks = ArrayList<Triple<Int, Int, Int>>()
            while (queue.isNotEmpty()) {
                val r = queue.removeLast()
                val (i, j, k) = findLongestMatch(r.alo, r.ahi, r.blo, r.bhi)
                if (k > 0) {
                    blocks.add(Triple(i, j, k))
                    if (r.alo < i && r.blo < j) queue.addLast(Region(r.alo, i, r.blo, j))
                    if (i + k < r.ahi && j + k < r.bhi) queue.addLast(Region(i + k, r.ahi, j + k, r.bhi))
                }
            }
            blocks.sortWith(compareBy({ it.first }, { it.second }, { it.third }))
            val nonAdjacent = ArrayList<Triple<Int, Int, Int>>()
            var i1 = 0; var j1 = 0; var k1 = 0
            for ((i2, j2, k2) in blocks) {
                if (i1 + k1 == i2 && j1 + k1 == j2) {
                    k1 += k2
                } else {
                    if (k1 != 0) nonAdjacent.add(Triple(i1, j1, k1))
                    i1 = i2; j1 = j2; k1 = k2
                }
            }
            if (k1 != 0) nonAdjacent.add(Triple(i1, j1, k1))
            nonAdjacent.add(Triple(la, lb, 0))
            return nonAdjacent
        }

        /** Port of get_opcodes; tag order (replace/delete/insert) identical. */
        fun opcodes(): List<Opcode> {
            val answer = ArrayList<Opcode>()
            var i = 0; var j = 0
            for ((ai, bj, size) in matchingBlocks()) {
                val tag = when {
                    i < ai && j < bj -> "replace"
                    i < ai -> "delete"
                    j < bj -> "insert"
                    else -> ""
                }
                if (tag.isNotEmpty()) answer.add(Opcode(tag, i, ai, j, bj))
                i = ai + size; j = bj + size
                if (size > 0) answer.add(Opcode("equal", ai, i, bj, j))
            }
            return answer
        }

        /** Port of ratio(): 2.0 * M / T. */
        fun ratio(): Double {
            val matches = matchingBlocks().sumOf { it.third }
            val total = a.length + b.length
            return if (total == 0) 1.0 else 2.0 * matches / total
        }
    }

    data class Opcode(val tag: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    fun ratio(a: String, b: String): Double = Matcher(a, b).ratio()

    fun replaceOpcodes(a: String, b: String): List<Opcode> = Matcher(a, b).opcodes().filter { it.tag == "replace" }
}

/** Dictionary sets derived from bundled dictionary.db exactly as the spike built them. */
class GateLexicon(
    val headwords: Set<String>,
    val bigrams: Set<String>,
    val radicals: Map<Char, Set<String>>,
) {
    companion object {
        /** @param headwordRows every `words.headword` value; @param radicalRows `kanji_radicals` (character, radical). */
        fun build(headwordRows: Sequence<String>, radicalRows: Sequence<Pair<Char, String>>): GateLexicon {
            val headwords = HashSet<String>(1 shl 18)
            val bigrams = HashSet<String>(1 shl 16)
            for (hw in headwordRows) {
                val full = gateCjkOnly(hw)
                if (full.length in 2..8) headwords.add(full)
                var prev: Char? = null
                for (ch in hw) {
                    if (prev != null && isGateCjk(prev) && isGateCjk(ch)) {
                        bigrams.add("$prev$ch")
                    }
                    prev = ch
                }
            }
            val radicals = HashMap<Char, HashSet<String>>()
            for ((ch, r) in radicalRows) radicals.getOrPut(ch) { HashSet() }.add(r)
            return GateLexicon(headwords, bigrams, radicals)
        }
    }
}

/** One stored-vs-cloud disagreement island with every evidence signal the gate consults. */
data class CorrectionIsland(
    val lineIndex: Int,
    val aStart: Int,
    val aEnd: Int,
    val a: String,
    val b: String,
    val kind: String,
    val margin: Double,
    val anchoredA: Boolean,
    val anchoredB: Boolean,
    val confusable: Boolean,
)

/**
 * The gate: island extraction + S003-D' decision. Pure over (localLines, cloudLines,
 * lexicon, nameplateStrings); no I/O, no Android — JVM-parity-testable against the spike.
 */
class OcrCorrectionGate(private val lexicon: GateLexicon) {

    // ---- spike helpers, ported 1:1 ----

    private fun longestMatchCoverage(run: String): Double {
        val n = run.length
        if (n == 0) return 1.0
        val cov = BooleanArray(n)
        var i = 0
        while (i < n) {
            var m = 0
            for (len in minOf(8, n - i) downTo 2) {
                if (lexicon.headwords.contains(run.substring(i, i + len))) {
                    for (k in i until i + len) cov[k] = true
                    m = len
                    break
                }
            }
            i += maxOf(m, 1)
        }
        for (k in 0 until n) {
            if (!cov[k] &&
                ((k + 1 < n && lexicon.bigrams.contains(run.substring(k, k + 2))) ||
                    (k - 1 >= 0 && lexicon.bigrams.contains(run.substring(k - 1, k + 1))))
            ) cov[k] = true
        }
        return cov.count { it }.toDouble() / n
    }

    private fun violations(run: String): Int {
        var v = 0
        for (i in 0 until run.length - 1) {
            val a = run[i]; val b = run[i + 1]
            if (isGateCjk(a) && isGateCjk(b) && !lexicon.bigrams.contains("$a$b")) v++
        }
        return v
    }

    private data class WindowScore(val coverage: Double, val violationCount: Int)

    private fun scoreWindow(text: String): WindowScore {
        val proj = gateCjkOnly(text)
        val runs = Regex("[\\u4e00-\\u9fff\\u3005]{2,}").findAll(proj).map { it.value }.toList()
        if (runs.isEmpty()) return WindowScore(1.0, 0)
        val cov = runs.sumOf { longestMatchCoverage(it) } / runs.size
        return WindowScore(cov, runs.sumOf { violations(it) })
    }

    /** True iff some headword occurs in [windowWithVariant] and covers [variant]. */
    private fun anchored(windowWithVariant: String, variant: String): Boolean {
        val l = windowWithVariant.length
        for (i in 0 until l) {
            for (j in (i + 2)..minOf(i + 9, l)) {
                val sub = windowWithVariant.substring(i, j)
                if (lexicon.headwords.contains(sub) && sub.contains(variant)) return true
            }
        }
        return false
    }

    private val SMALL_KANA = mapOf(
        'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お', 'っ' to 'つ',
        'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ', 'ゎ' to 'わ',
        'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ', 'ッ' to 'ツ',
        'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ', 'ヮ' to 'ワ',
    )

    /** Port of the spike's fold(): NFD, drop combining marks, small-kana map, katakana->hiragana. */
    internal fun fold(s: String): String {
        val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (java.lang.Character.getType(ch) == java.lang.Character.NON_SPACING_MARK.toInt()) continue
            val mapped = SMALL_KANA[ch] ?: ch
            out.append(if (mapped in '\u30a1'..'\u30f6') Char(mapped.code - 0x60) else mapped)
        }
        return out.toString()
    }

    internal fun islandKind(x: String, y: String): String = when {
        fold(x) == fold(y) -> "kana-styling"
        x.all { isGateCjk(it) } && y.all { isGateCjk(it) } -> "kanji"
        else -> "mixed"
    }

    // ---- extraction ----

    /**
     * Extracts disagreement islands between local and cloud OCR lines, exactly as the spike:
     * CJK-project the lines, pair each local line with its best cloud line (ratio in
     * [0.55, 0.999)), and keep 'replace' opcodes whose spans are <= 4 on both sides.
     * [lineIndex] maps back to the caller's line list (raw, unprojected order).
     */
    fun extractIslands(localLines: List<String>, cloudLines: List<String>): List<CorrectionIsland> {
        val st = localLines.map { gateCjkOnly(it) }
        val cl = cloudLines.map { gateCjkOnly(it) }.filter { it.isNotEmpty() }
        val out = ArrayList<CorrectionIsland>()
        for ((lineIdx, sn) in st.withIndex()) {
            if (sn.isEmpty()) continue
            var best: String? = null
            var bestSim = 0.0
            for (cn in cl) {
                val sim = DifflibSequenceMatcher.ratio(sn, cn)
                if (sim > bestSim) { best = cn; bestSim = sim }
            }
            if (best == null || bestSim < 0.55 || bestSim >= 0.999) continue
            for (op in DifflibSequenceMatcher.replaceOpcodes(sn, best)) {
                if (op.i2 - op.i1 > 4 || op.j2 - op.j1 > 4) continue
                val a = sn.substring(op.i1, op.i2)
                val b = best.substring(op.j1, op.j2)
                if (a.isBlank() || b.isBlank() || a == b) continue
                val kind = islandKind(a, b)
                val wA = sn.substring(maxOf(0, op.i1 - 4), minOf(sn.length, op.i2 + 4))
                val wB = best.substring(maxOf(0, op.j1 - 4), minOf(best.length, op.j2 + 4))
                val sa = scoreWindow(wA)
                val sb = scoreWindow(wB)
                val margin = (sb.coverage - sa.coverage) + 0.15 * (sa.violationCount - sb.violationCount)
                val anchB = anchored(wB, b)
                val anchA = anchored(wA, a)
                val conf = a.length == 1 && b.length == 1 && isGateCjk(a[0]) && isGateCjk(b[0]) &&
                    !(lexicon.radicals[a[0]] ?: emptySet()).intersect(lexicon.radicals[b[0]] ?: emptySet()).isEmpty()
                out.add(
                    CorrectionIsland(
                        lineIndex = lineIdx, aStart = op.i1, aEnd = op.i2, a = a, b = b, kind = kind,
                        margin = margin, anchoredA = anchA, anchoredB = anchB, confusable = conf,
                    )
                )
            }
        }
        return out
    }

    /** S003-D': (anchoredB && !anchoredA) || (margin >= 0 && confusable); nameplate islands never qualify. */
    fun accepts(island: CorrectionIsland, nameplateExcluded: Boolean): Boolean {
        if (island.kind != "kanji") return false
        if (nameplateExcluded) return false
        return (island.anchoredB && !island.anchoredA) || (island.margin >= 0.0 && island.confusable)
    }

    /** Spike's exclusion test, exact direction: island A or B is a substring of a nameplate string. */
    fun nameplateAdjacent(island: CorrectionIsland, nameplateStrings: Set<String>): Boolean =
        nameplateStrings.any { w -> w.length >= 2 && (w.contains(island.a) || w.contains(island.b)) }
}
