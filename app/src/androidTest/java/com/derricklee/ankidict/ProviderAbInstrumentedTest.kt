package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in provider A/B extraction (user decision 2026-11-12): the production composite
 * extractor prefers the on-device preflight and only calls Cloud Vision when on-device
 * returns nothing — so provider quality has never been compared head-to-head.
 *
 * For every photo in gold_fixtures.json this test runs, WITHOUT touching the corpus:
 *   stored  = the frozen mining-time blocks already in the fixture (what the gold set measures)
 *   mlkit   = a FRESH on-device ML Kit Japanese extraction on the real image
 *   cloud   = a FRESH Cloud Vision DOCUMENT_TEXT_DETECTION on the same image (needs
 *             CLOUD_VISION_API_KEY in local.properties/env; recorded as "not-configured" otherwise)
 * Each is parsed by the current CjkSentenceParser and scored against the gold sentences,
 * printed as PROVIDER_AB lines, and written in full (including per-provider OcrDocuments)
 * to <external-files>/provider_ab_<timestamp>.json for off-device comparison.
 *
 * Enable with: -e ab true
 */
@RunWith(AndroidJUnit4::class)
class ProviderAbInstrumentedTest {

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

    private fun norm(s: String) = s.filterNot(Char::isWhitespace)

    private data class Score(val exact: Int, val charAcc: Double, val sentences: List<String>)

    private fun score(gold: List<String>, parsed: List<String>): Score {
        if (gold.isEmpty()) return Score(0, 1.0, parsed)
        var exact = 0
        var acc = 0.0
        for (g in gold) {
            val gn = norm(g)
            val best = parsed.maxByOrNull { 1.0 - levenshtein(gn, norm(it)).toDouble() / maxOf(gn.length, 1) }
            if (best == null) continue
            val sim = 1.0 - levenshtein(gn, norm(best)).toDouble() / maxOf(gn.length, norm(best).length)
            if (norm(best) == gn) exact++
            acc += sim.coerceAtLeast(0.0)
        }
        return Score(exact, acc / gold.size, parsed)
    }

    @Test
    fun providerAb_scoresStoredVsFreshMlKitVsFreshCloud() {
        assumeTrue("Pass -e ab true to run the provider A/B extraction",
            InstrumentationRegistry.getArguments().getString("ab") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = JSONArray(
            InstrumentationRegistry.getInstrumentation().context
                .assets.open("gold_fixtures.json").readBytes().toString(Charsets.UTF_8)
                .let { JSONObject(it).getJSONArray("fixtures").toString() },
        )
        val preprocessor = AndroidImagePreprocessor(context)
        val parser = CjkSentenceParser()
        val mlkit = MlKitTextExtractor(LanguageTag.JAPANESE)
        val cloud = CloudVisionTextExtractor(LanguageTag.JAPANESE)
        val cloudConfigured = cloud.isConfigured()

        val photoResults = JSONArray()
        val totals = HashMap<String, IntArray>() // provider -> [exact, gold, photos, segExact]
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val photoId = fixture.getLong("photoId")
            val gold = (0 until fixture.getJSONArray("gold").length())
                .map { fixture.getJSONArray("gold").getString(it) }
            val providers = JSONObject()

            // 1. stored (frozen mining-time blocks — deterministic, no image needed)
            val storedDoc = OcrDocument.fromJsonString(fixture.getJSONObject("ocrDocument").toString())
            if (storedDoc != null) {
                val s = score(gold, parser.parseSentences(storedDoc))
                providers.put("stored", JSONObject()
                    .put("sentences", JSONArray(s.sentences))
                    .put("exact", s.exact).put("gold", gold.size).put("charAcc", s.charAcc))
                totals.merge("stored", intArrayOf(s.exact, gold.size, 1, if (s.exact == gold.size) 1 else 0)) { a, b ->
                    intArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]) }
            }

            // 2/3. fresh extraction on the real image
            val prepared = preprocessor.prepare(
                PhotoCandidate(photoId, fixture.getString("contentUri"), 0L),
                ImagePreprocessConfig(),
            )
            if (prepared == null) {
                providers.put("imageStatus", "unavailable")
            } else {
                try {
                    val mlDoc = mlkit.extract(prepared, null)
                    val s = score(gold, parser.parseSentences(mlDoc))
                    providers.put("mlkit", JSONObject()
                        .put("sentences", JSONArray(s.sentences))
                        .put("exact", s.exact).put("gold", gold.size).put("charAcc", s.charAcc)
                        .put("document", JSONObject(mlDoc.toJsonString())))
                    totals.merge("mlkit", intArrayOf(s.exact, gold.size, 1, if (s.exact == gold.size) 1 else 0)) { a, b ->
                        intArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]) }
                } catch (e: Exception) {
                    providers.put("mlkitError", e.javaClass.simpleName)
                }
                if (cloudConfigured) {
                    try {
                        val cDoc = cloud.extract(prepared, null)
                        val s = score(gold, parser.parseSentences(cDoc))
                        providers.put("cloud", JSONObject()
                            .put("sentences", JSONArray(s.sentences))
                            .put("exact", s.exact).put("gold", gold.size).put("charAcc", s.charAcc)
                            .put("document", JSONObject(cDoc.toJsonString())))
                        totals.merge("cloud", intArrayOf(s.exact, gold.size, 1, if (s.exact == gold.size) 1 else 0)) { a, b ->
                            intArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]) }
                    } catch (e: Exception) {
                        providers.put("cloudError", e.javaClass.simpleName)
                    }
                } else {
                    providers.put("cloudStatus", "not-configured")
                }
                if (!prepared.bitmap.isRecycled) prepared.bitmap.recycle()
            }

            // logcat truncates long lines (~4K): print a lite line per photo, full documents
            // live in the artifact file.
            val lite = JSONObject()
            for (key in listOf("stored", "mlkit", "cloud")) {
                if (providers.has(key)) {
                    val liteOne = JSONObject(providers.getJSONObject(key).toString())
                    liteOne.remove("document")
                    liteOne.remove("sentences")
                    lite.put(key, liteOne)
                }
            }
            println(JSONObject().put("metric", "PROVIDER_AB").put("photoId", photoId)
                .put("scores", lite).toString())
            photoResults.put(JSONObject().put("photoId", photoId).put("providers", providers))
        }

        val summary = JSONObject().put("metric", "PROVIDER_AB_SUMMARY")
            .put("cloudConfigured", cloudConfigured)
        for ((provider, t) in totals) {
            summary.put(provider, JSONObject()
                .put("photos", t[2]).put("sentenceExact", "${t[0]}/${t[1]}")
                .put("allExactPhotos", t[3]))
        }
        println(summary.toString())

        val outDir = context.getExternalFilesDir(null)
        val artifact = File(outDir, "provider_ab_${System.currentTimeMillis()}.json")
        artifact.writeText(JSONObject().put("cloudConfigured", cloudConfigured)
            .put("summary", summary).put("photos", photoResults).toString(2))
        println("PROVIDER_AB_ARTIFACT ${artifact.absolutePath}")
    }
}
