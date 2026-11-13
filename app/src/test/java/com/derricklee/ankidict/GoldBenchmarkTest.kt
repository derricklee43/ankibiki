package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gold-set iteration benchmark (docs/benchmarks/gold-set-iteration-plan.md).
 *
 * Gold = user-corrected photo texts; their text is authoritative for content
 * and their \n breaks for segmentation (Q1/Q2 answered 2026-11-12). Speaker
 * nameplates were stripped by the user when correcting, so nameplate-shaped
 * pipeline output is EXCLUDED from headline metrics and reported only as a
 * separate leak diagnostic — measured nameplate leakage never moves the
 * gold scores. Ambiguous short lines are never auto-ignored.
 *
 * REPORT-ONLY by design: nothing here gates until the user confirms a photo's
 * gold as final and a fix makes it pass (then assert_segmentation flips true
 * in the fixtures — same lock mechanism as ExtractionBenchmarkTest).
 */
class GoldBenchmarkTest {
    private val parser = CjkSentenceParser()

    private data class GoldFixture(
        val photoId: Long,
        val category: String,
        val gold: List<String>,
        val assertSegmentation: Boolean,
        val nameplateLabels: List<String>,
        val nameplateCandidates: List<String>,
        val ambiguousShortLines: List<String>,
        val document: OcrDocument,
    )

    private fun JSONObject.strList(key: String): List<String> =
        if (isNull(key)) emptyList() else getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }

    private fun loadFixtures(): List<GoldFixture> {
        val raw = checkNotNull(javaClass.getResourceAsStream("/gold_fixtures.json"))
            .reader(Charsets.UTF_8).readText()
        val array = JSONObject(raw).getJSONArray("fixtures")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            GoldFixture(
                photoId = o.getLong("photoId"),
                category = o.getString("category"),
                gold = o.strList("gold"),
                assertSegmentation = o.getBoolean("assert_segmentation"),
                nameplateLabels = o.strList("nameplateLabels"),
                nameplateCandidates = o.strList("nameplateCandidates"),
                ambiguousShortLines = o.strList("ambiguousShortLines"),
                document = checkNotNull(OcrDocument.fromJsonString(o.getJSONObject("ocrDocument").toString())),
            )
        }
    }

    private fun norm(s: String): String = s.filterNot(Char::isWhitespace)

    /** Full label set the scorer may ignore = confirmed labels + unambiguous drafts. */
    private fun ignoreSet(f: GoldFixture): List<String> =
        (f.nameplateLabels + f.nameplateCandidates).map(::norm).distinct()

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** 0..1 similarity; 1.0 == identical. */
    private fun similarity(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        return if (m == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / m
    }

    private data class AlignedPair(val goldIdx: Int, val parsedIdx: Int, val exact: Boolean, val sim: Double, val inlineLeakStripped: String?)

    /**
     * Order-preserving one-to-one alignment maximizing exact matches, then
     * total similarity. Gaps allowed both sides. A pipeline sentence that is
     * (label + gold-looking remainder) aligns via the remainder and records
     * the stripped label as an inline merge leak.
     */
    private fun align(gold: List<String>, candidates: List<Pair<String, String?>>, matchThreshold: Double): List<AlignedPair> {
        // candidates: normalized parsed text + stripped inline label (null if none)
        val n = gold.size; val m = candidates.size
        val score = Array(n + 1) { DoubleArray(m + 1) }
        val choice = Array(n + 1) { IntArray(m + 1) } // 0=skipGold, 1=match, 2=skipParsed
        val exactAt = Array(n) { BooleanArray(m) }
        val simAt = Array(n) { DoubleArray(m) }
        for (i in 0 until n) for (j in 0 until m) {
            val sim = similarity(gold[i], candidates[j].first)
            simAt[i][j] = sim; exactAt[i][j] = sim == 1.0
        }
        for (i in 1..n) for (j in 1..m) {
            val skipG = score[i - 1][j] - 0.05           // missing gold sentence
            val skipP = score[i][j - 1] - 0.05           // extra pipeline sentence
            val match = if (simAt[i - 1][j - 1] >= matchThreshold)
                score[i - 1][j - 1] + (if (exactAt[i - 1][j - 1]) 10.0 else simAt[i - 1][j - 1])
            else Double.NEGATIVE_INFINITY
            when (match) {
                maxOf(skipG, skipP, match) -> { score[i][j] = match; choice[i][j] = 1 }
                skipG -> { score[i][j] = skipG; choice[i][j] = 0 }
                else -> { score[i][j] = skipP; choice[i][j] = 2 }
            }
        }
        val pairs = mutableListOf<AlignedPair>()
        var i = n; var j = m
        while (i > 0 && j > 0) {
            when (choice[i][j]) {
                1 -> { pairs += AlignedPair(i - 1, j - 1, exactAt[i - 1][j - 1], simAt[i - 1][j - 1], candidates[j - 1].second); i--; j-- }
                0 -> i--
                2 -> j--
            }
        }
        return pairs.sortedBy { it.goldIdx }
    }

    private data class FixtureScore(
        val photoId: Long,
        val valCategory: String,
        val goldSentences: Int,
        val exactSentences: Int,
        val charAccuracy: Double,
        val missingGold: Int,
        val falseExtras: Int,
        val nameplateLeaks: Int,
        val inlineNameplateMerges: Int,
        val segmentationExact: Boolean,
        val parsed: List<String>,
        val ignoredTexts: List<String>,
        val inlineMergeTexts: List<String>,
    )

    /** Standalone labels tolerate trailing punctuation (ならずもの。) — a leaked
     * nameplate whose OCR hallucinated a 。 is still a nameplate leak. */
    private fun matchesIgnore(p: String, ignores: List<String>): Boolean =
        ignores.any { it == p || (p.length > it.length && p.removeSuffix("。").removeSuffix("！")
            .removeSuffix("？") == it) }

    private fun scoreFixture(f: GoldFixture): FixtureScore {
        val parsed = parser.parseSentences(f.document).map(::norm)
        val ignores = ignoreSet(f)
        val goldNorm = f.gold.map(::norm)

        val leaksStandalone = parsed.filter { p -> matchesIgnore(p, ignores) }
        val pool = mutableListOf<Pair<String, String?>>() // normalized text + stripped inline label
        var inlineMerges = 0
        val inlineTexts = mutableListOf<String>()
        for (p in parsed) {
            if (matchesIgnore(p, ignores)) continue // standalone nameplate leak, already counted
            val merged = ignores.firstOrNull { label ->
                p.length > label.length && p.startsWith(label) &&
                    goldNorm.any { g -> similarity(p.removePrefix(label), g) >= 0.6 }
            }
            if (merged != null) {
                inlineMerges++
                inlineTexts += p
                pool += p.removePrefix(merged) to merged
            } else {
                pool += p to null
            }
        }
        val pairs = align(goldNorm, pool, matchThreshold = 0.40)
        val matchedParsed = pairs.map { it.parsedIdx }.toSet()
        val falseExtras = pool.indices.count { it !in matchedParsed }
        val exact = pairs.count { it.exact }
        val charAcc = if (pairs.isEmpty()) 0.0 else pairs.sumOf { it.sim } / goldNorm.size

        // segmentation exact (future lock criterion): aligned set equals gold with no false extras
        val segmentationExact = exact == goldNorm.size && falseExtras == 0
        return FixtureScore(
            photoId = f.photoId,
            valCategory = f.category,
            goldSentences = goldNorm.size,
            exactSentences = exact,
            charAccuracy = charAcc,
            missingGold = goldNorm.size - pairs.size,
            falseExtras = falseExtras,
            nameplateLeaks = leaksStandalone.size,
            inlineNameplateMerges = inlineMerges,
            segmentationExact = segmentationExact,
            parsed = parsed,
            ignoredTexts = leaksStandalone,
            inlineMergeTexts = inlineTexts,
        )
    }

    @Test
    fun goldFixtures_decodeAndCarryNonEmptyGold() {
        val fixtures = loadFixtures()
        assertTrue("no gold fixtures loaded", fixtures.isNotEmpty())
        for (f in fixtures) {
            assertTrue("photo ${f.photoId}: empty gold", f.gold.isNotEmpty())
            assertTrue("photo ${f.photoId}: no blocks", f.document.blocks.isNotEmpty())
            assertTrue("photo ${f.photoId}: gold contains an ignored nameplate label",
                f.nameplateLabels.none { label -> f.gold.any { norm(it).contains(norm(label)) && norm(label) == norm(it) } })
        }
    }

    @Test
    fun goldBaseline_reportsNameplateAgnosticMetricsWithoutGating() {
        val scores = loadFixtures().map(::scoreFixture)
        var exactSent = 0; var goldSent = 0; var segExact = 0; var charAccSum = 0.0
        var leaks = 0; var merges = 0; var falseExtras = 0; var missing = 0
        for (s in scores) {
            exactSent += s.exactSentences; goldSent += s.goldSentences
            if (s.segmentationExact) segExact++
            charAccSum += s.charAccuracy
            leaks += s.nameplateLeaks; merges += s.inlineNameplateMerges
            falseExtras += s.falseExtras; missing += s.missingGold
            println(JSONObject().put("metric", "BENCHMARK_GOLD_FIXTURE").put("photoId", s.photoId)
                .put("category", s.valCategory).put("goldSentences", s.goldSentences)
                .put("exactSentences", s.exactSentences).put("charAccuracy", "%.3f".format(s.charAccuracy))
                .put("segmentationExact", s.segmentationExact).put("missingGold", s.missingGold)
                .put("falseExtras", s.falseExtras).put("nameplateLeaksIgnored", s.nameplateLeaks)
                .put("inlineNameplateMerges", s.inlineNameplateMerges)
                .put("ignoredTexts", JSONArray(s.ignoredTexts)).put("inlineMergeTexts", JSONArray(s.inlineMergeTexts)).put("parsed", JSONArray(s.parsed)).toString())
        }
        println(JSONObject().put("metric", "BENCHMARK_GOLD_SUMMARY").put("fixtures", scores.size)
            .put("sentenceExact", "$exactSent/$goldSent").put("sentenceExactRate", exactSent.toDouble() / goldSent)
            .put("avgCharAccuracy", charAccSum / scores.size).put("segmentationExact", segExact)
            .put("nameplateLeaksIgnored", leaks).put("inlineNameplateMerges", merges)
            .put("falseExtras", falseExtras).put("missingGold", missing).toString())
    }

    @Test
    fun goldLockedRegressions() {
        // Empty until the user confirms gold-as-final + a fix makes photos pass;
        // then assert_segmentation flips true in gold_fixtures.json. Mirrors the
        // lock mechanism of ExtractionBenchmarkTest.
        for (f in loadFixtures().filter { it.assertSegmentation }) {
            val s = scoreFixture(f)
            assertEquals("locked gold photo ${f.photoId} drifted", f.gold.map(::norm), s.parsed.filterNot { it in ignoreSet(f).map(::norm) })
        }
    }
}
