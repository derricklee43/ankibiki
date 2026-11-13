package com.derricklee.ankidict

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction-quality benchmark over human-verified fixtures (docs/roadmap.md §2).
 *
 * Two layers:
 * - locked regressions: photos whose segmentation is verified-correct behavior and must not drift;
 * - baseline report: per-fixture segmentation/recognition metrics printed as BENCHMARK_* lines,
 *   informational only until explicit quality targets are set.
 *
 * Fixtures carry OCR blocks as stored at mining time, so the JVM layer measures the CURRENT
 * parser against frozen recognition input — deterministic, no device, no corpus writes.
 */
class ExtractionBenchmarkTest {
    private val parser = CjkSentenceParser()

    private data class Fixture(
        val photoId: Long,
        val category: String,
        val expected: List<String>,
        val assertSegmentation: Boolean,
        val mustContainWords: List<String>,
        val storedText: String,
        val document: OcrDocument,
    )

    private fun loadFixtures(): List<Fixture> {
        val raw = checkNotNull(javaClass.getResourceAsStream("/extraction_benchmark_fixtures.json"))
            .reader(Charsets.UTF_8).readText()
        val array = JSONObject(raw).getJSONArray("fixtures")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Fixture(
                photoId = o.getLong("photoId"),
                category = o.getString("category"),
                expected = o.getJSONArray("expected").let { a -> (0 until a.length()).map(a::getString) },
                assertSegmentation = o.getBoolean("assert_segmentation"),
                mustContainWords = o.getJSONArray("must_contain_words").let { a -> (0 until a.length()).map(a::getString) },
                storedText = o.getString("storedText"),
                document = checkNotNull(OcrDocument.fromJsonString(o.getJSONObject("ocrDocument").toString())),
            )
        }
    }

    /**
     * Locked photos with user gold (round-3 adjudicated): the lock guards SEGMENTATION SHAPE only —
     * sentence count and per-position correspondence. Content is governed by GoldBenchmarkTest
     * against the user's 金 text (recognition differences vs frozen blocks are expected and the
     * strings will never be equal, e.g. 953 parsed マルス…脱中 vs gold ...脱出). Count equality
     * catches merge/split regressions; per-pair similarity >= 0.6 catches boundary migrations
     * (a sentence sliding to the wrong neighbor drops one pair's similarity sharply).
     */
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

    private fun similarity(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        return if (m == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / m
    }

    private fun loadGold(photoId: Long): List<String>? {
        val stream = javaClass.getResourceAsStream("/gold_fixtures.json") ?: return null
        val array = JSONObject(stream.reader(Charsets.UTF_8).readText()).getJSONArray("fixtures")
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (o.getLong("photoId") == photoId) {
                return o.getJSONArray("gold").let { g -> (0 until g.length()).map(g::getString) }
            }
        }
        return null
    }

    @Test
    fun lockedRegressions_currentParserStillProducesVerifiedSegmentation() {
        val goldCovered = mutableListOf<Long>()
        for (fixture in loadFixtures().filter { it.assertSegmentation }) {
            val parsed = parser.parseSentences(fixture.document)
            val gold = loadGold(fixture.photoId)
            if (gold == null) {
                assertEquals(
                    "segmentation drift on locked photo ${fixture.photoId} (${fixture.category})",
                    fixture.expected, parsed,
                )
            } else {
                assertEquals(
                    "sentence count drift on gold-covered locked photo ${fixture.photoId}",
                    gold.size, parsed.size,
                )
                for (i in gold.indices) {
                    val sim = similarity(gold[i], parsed[i])
                    assertTrue(
                        "boundary migration on locked photo ${fixture.photoId} sentence $i " +
                            "(gold「${gold[i]}」vs parsed「${parsed[i]}」similarity ${"%.2f".format(sim)} < 0.6)",
                        sim >= 0.6,
                    )
                }
                goldCovered += fixture.photoId
            }
        }
        assertEquals("both locked photos must be gold-covered after round-3 adjudication", listOf(953L, 1160L), goldCovered.sorted())
    }

    @Test
    fun baselineReport_printsPerFixtureMetricsWithoutGating() {
        val fixtures = loadFixtures()
        var segmentationMatches = 0
        var recognitionSurvivalTotal = 0.0
        var driftFromStored = 0
        for (fixture in fixtures) {
            val parsed = parser.parseSentences(fixture.document)
            val segmentationMatch = parsed == fixture.expected
            if (segmentationMatch) segmentationMatches++
            val joined = parsed.joinToString("")
            val survival = if (fixture.mustContainWords.isEmpty()) 1.0 else
                fixture.mustContainWords.count { joined.contains(it) }.toDouble() / fixture.mustContainWords.size
            recognitionSurvivalTotal += survival
            val drift = parsed.joinToString("\n") != fixture.storedText
            if (drift) driftFromStored++
            println(
                JSONObject()
                    .put("metric", "BENCHMARK_FIXTURE")
                    .put("photoId", fixture.photoId)
                    .put("category", fixture.category)
                    .put("expectedSentences", fixture.expected.size)
                    .put("parsedSentences", parsed.size)
                    .put("segmentationMatch", segmentationMatch)
                    .put("parsedJson", org.json.JSONArray(parsed))
                    .put("contentWordSurvival", survival)
                    .put("differsFromStoredText", drift)
                    .toString(),
            )
        }
        println(
            JSONObject()
                .put("metric", "BENCHMARK_SUMMARY")
                .put("fixtures", fixtures.size)
                .put("segmentationExact", segmentationMatches)
                .put("avgContentWordSurvival", recognitionSurvivalTotal / fixtures.size)
                .put("parserWouldChangeStoredText", driftFromStored)
                .toString(),
        )
    }

    @Test
    fun fixtures_decodeEveryStoredBlockShape() {
        for (fixture in loadFixtures()) {
            assertEquals(fixture.photoId.toString(), fixture.document.blocks.isNotEmpty(), true)
        }
    }
}
