package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Opt-in live extraction baseline (docs/roadmap.md §2): runs the CURRENT production OCR +
 * segmentation + candidate stack over the benchmark fixture images WITHOUT touching the
 * corpus (no repository, no writes), and prints per-fixture metrics as BENCHMARK_LIVE lines.
 * Enable with: -e benchmark true
 *
 * The JVM ExtractionBenchmarkTest is the deterministic gate; this test measures what a
 * rescan today would actually produce (fresh recognition on the real images).
 */
@RunWith(AndroidJUnit4::class)
class ExtractionBenchmarkInstrumentedTest {
    @Test
    fun livePipelineBaseline_measuresFixturesWithoutCorpusWrites() {
        assumeTrue("Pass -e benchmark true to run the live extraction baseline",
            InstrumentationRegistry.getArguments().getString("benchmark") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = JSONObject(
            // The fixture lives in the androidTest assets, so read it via the TEST context.
            InstrumentationRegistry.getInstrumentation().context
                .assets.open("extraction_benchmark_fixtures.json").readBytes().toString(Charsets.UTF_8),
        ).getJSONArray("fixtures")
        val preprocessor = AndroidImagePreprocessor(context)
        val extractor = CompositeTextExtractor(
            cloudExtractor = CloudVisionTextExtractor(LanguageTag.JAPANESE),
            fallbackExtractor = MlKitTextExtractor(LanguageTag.JAPANESE),
        )
        val parser = CjkSentenceParser()
        val wordAnalyzer = KuromojiWordAnalyzer()
        val grammarAnalyzer = JlptGrammarAnalyzer()
        var measured = 0
        var segmentationMatches = 0
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val photoId = fixture.getLong("photoId")
            val expected = fixture.getJSONArray("expected").let { a -> (0 until a.length()).map(a::getString) }
            val prepared = preprocessor.prepare(
                PhotoCandidate(photoId, fixture.getString("contentUri"), 0L),
                ImagePreprocessConfig(),
            )
            if (prepared == null) {
                println("""{"metric":"BENCHMARK_LIVE","photoId":$photoId,"skipped":"image unavailable"}""")
                continue
            }
            val document = extractor.extract(prepared, null)
            val parsed = parser.parseSentences(document)
            val segmentationMatch = parsed == expected
            if (segmentationMatch) segmentationMatches++
            val candidates = parsed.flatMap { sentence ->
                wordAnalyzer.extractWords(sentence).map { it.expression } +
                    grammarAnalyzer.extractGrammar(sentence).map { it.expression }
            }.toSet()
            val mustContain = fixture.getJSONArray("must_contain_words").let { a -> (0 until a.length()).map(a::getString) }
            val recall = if (mustContain.isEmpty()) 1.0 else
                mustContain.count { candidates.contains(it) }.toDouble() / mustContain.size
            measured++
            println(
                JSONObject()
                    .put("metric", "BENCHMARK_LIVE")
                    .put("photoId", photoId)
                    .put("category", fixture.getString("category"))
                    .put("expectedSentences", expected.size)
                    .put("liveSentences", parsed.size)
                    .put("segmentationMatch", segmentationMatch)
                    .put("liveSentencesJson", org.json.JSONArray(parsed))
                    .put("candidateCount", candidates.size)
                    .put("mustContainRecall", recall)
                    .toString(),
            )
        }
        println("""{"metric":"BENCHMARK_LIVE_SUMMARY","measured":$measured,"segmentationExact":$segmentationMatches}""")
    }
}
