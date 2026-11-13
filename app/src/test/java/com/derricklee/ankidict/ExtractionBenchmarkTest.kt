package com.derricklee.ankidict

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
     * Legacy locks: both historical locked photos (953, 1160) were RETIRED 2026-11-13 by the
     * user — their agent-pinned OCR-faithful content was superseded by the 金 gold set, and no
     * content lock on frozen local blocks can reproduce the corrected text. The mechanism stays
     * in place should a legacy fixture ever be re-locked against frozen input; the living
     * regression locks are GoldBenchmarkTest.goldLockedRegressions over user gold.
     */
    @Test
    fun lockedRegressions_currentParserStillProducesVerifiedSegmentation() {
        for (fixture in loadFixtures().filter { it.assertSegmentation }) {
            assertEquals(
                "segmentation drift on locked photo ${fixture.photoId} (${fixture.category})",
                fixture.expected, parser.parseSentences(fixture.document),
            )
        }
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
