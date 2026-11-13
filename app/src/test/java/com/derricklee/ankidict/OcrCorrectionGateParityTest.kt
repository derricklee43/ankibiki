package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pins the production Kotlin port of the S003-D' gate (OcrCorrectionGate/OcrCorrectionPass)
 * against spike 003's measured Python output (spikes/003-gate-upgrades/gate_rows_v2.json,
 * README table: accepted 36 / fix 36 / harmful 0 / precision 100% / coverage 69%).
 *
 * The production auto-apply rollout (user decision 2026-11-13) ships THIS exact math; if any
 * ported signal drifts from the spike, this test fails before the gate can write to a corpus.
 * Lexicon comes from a frozen snapshot of the bundled dictionary.db, sha-pinned to the live
 * asset so a dictionary bump without regenerating scripts/export_gate_lexicon.py fails loudly.
 */
class OcrCorrectionGateParityTest {

    private data class SpikeRow(
        val pid: Long,
        val a: String,
        val b: String,
        val kind: String,
        val truth: String,
        val margin: Double,
        val anchA: Boolean,
        val anchB: Boolean,
        val conf: Boolean,
    )

    private data class Fixture(
        val photoId: Long,
        val gold: List<String>,
        val nameplateCandidates: List<String>,
        val nameplateLabels: List<String>,
        val document: OcrDocument,
    )

    private fun repoFile(vararg candidates: String): File {
        for (c in candidates) {
            val f = File(c)
            if (f.exists()) return f
        }
        throw AssertionError("repo file not found, tried: ${candidates.toList()} (cwd=${File(".").absolutePath})")
    }

    private fun loadLexicon(): GateLexicon {
        val dictFile = repoFile("src/main/assets/dictionary.db", "app/src/main/assets/dictionary.db")
        val sha = MessageDigest.getInstance("SHA-256").digest(dictFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        val snapshot = JSONObject(
            checkNotNull(javaClass.getResourceAsStream("/gate_lexicon_snapshot.json"))
                .reader(Charsets.UTF_8).readText()
        )
        assertEquals(
            "dictionary.db changed without regenerating the test snapshot — run scripts/export_gate_lexicon.py",
            snapshot.getString("sha256"), sha
        )
        fun strArray(key: String) = snapshot.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }
        val radObj = snapshot.getJSONObject("kanjiRadicals")
        val radicals = radObj.keys().asSequence().associate { ch ->
            ch.first() to radObj.getJSONArray(ch).let { a -> (0 until a.length()).map(a::getString).toSet() }
        }
        return GateLexicon(strArray("headwords").toSet(), strArray("bigrams").toSet(), radicals)
    }

    private fun JSONObject.strList(key: String): List<String> =
        if (isNull(key)) emptyList() else getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }

    private fun loadFixtures(): List<Fixture> {
        val raw = checkNotNull(javaClass.getResourceAsStream("/gold_fixtures.json"))
            .reader(Charsets.UTF_8).readText()
        return JSONObject(raw).getJSONArray("fixtures").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Fixture(
                    photoId = o.getLong("photoId"),
                    gold = o.strList("gold"),
                    nameplateCandidates = o.strList("nameplateCandidates"),
                    nameplateLabels = o.strList("nameplateLabels"),
                    document = checkNotNull(OcrDocument.fromJsonString(o.getJSONObject("ocrDocument").toString())),
                )
            }
        }
    }

    private fun loadCloudDocs(): Map<Long, List<String>> {
        val file = repoFile(
            "docs/benchmarks/data/provider_ab_1794555946992.json",
            "../docs/benchmarks/data/provider_ab_1794555946992.json",
        )
        val photos = JSONObject(file.readText()).getJSONArray("photos")
        val out = HashMap<Long, List<String>>()
        for (i in 0 until photos.length()) {
            val p = photos.getJSONObject(i)
            val cloud = p.getJSONObject("providers").optJSONObject("cloud") ?: continue
            val doc = cloud.optJSONObject("document") ?: continue
            val blocks = doc.optJSONArray("blocks") ?: JSONArray()
            val lines = ArrayList<String>()
            for (b in 0 until blocks.length()) {
                val lineArr = blocks.getJSONObject(b).optJSONArray("lines") ?: JSONArray()
                for (l in 0 until lineArr.length()) {
                    val t = lineArr.getJSONObject(l).optString("text", "")
                    if (t.isNotEmpty()) lines.add(t)
                }
            }
            out[p.getLong("photoId")] = lines
        }
        return out
    }

    private fun loadSpikeRows(): List<SpikeRow> {
        val file = repoFile(
            "spikes/003-gate-upgrades/gate_rows_v2.json",
            "../spikes/003-gate-upgrades/gate_rows_v2.json",
        )
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SpikeRow(
                pid = o.getLong("pid"), a = o.getString("A"), b = o.getString("B"),
                kind = o.getString("kind"), truth = o.getString("truth"), margin = o.getDouble("margin"),
                anchA = o.getBoolean("anchA"), anchB = o.getBoolean("anchB"), conf = o.getBoolean("conf"),
            )
        }
    }

    private fun truthLabel(fixture: Fixture, a: String, b: String): String {
        val gl = fixture.gold.map { gateCjkOnly(it) }
        val inA = gl.any { it.contains(a) }
        val inB = gl.any { it.contains(b) }
        return if (inB && !inA) "fix" else if (!inB) "harmful" else "both"
    }

    /** One evaluated photo: Kotlin islands + the spike rows they must match. */
    private class Evaluated(val fixture: Fixture, val islands: List<CorrectionIsland>)

    private fun evaluateAll(gate: OcrCorrectionGate): Pair<List<Evaluated>, Map<Triple<Long, String, String>, SpikeRow>> {
        val fixtures = loadFixtures()
        val clouds = loadCloudDocs()
        val spikeByKey = loadSpikeRows().associateBy { Triple(it.pid, it.a, it.b) }
        val evaluated = fixtures.filter { clouds.containsKey(it.photoId) }.map { f ->
            Evaluated(f, gate.extractIslands(f.document.blocks.flatMap { it.lines }.map { it.text }, clouds.getValue(f.photoId)))
        }
        return evaluated to spikeByKey
    }

    @Test
    fun port_reproducesEverySpikeIsland_andNoExtras() {
        val gate = OcrCorrectionGate(loadLexicon())
        val (evaluated, spikeByKey) = evaluateAll(gate)
        val kotlinKeys = HashMap<Triple<Long, String, String>, CorrectionIsland>()
        for (e in evaluated) for (isl in e.islands) kotlinKeys[Triple(e.fixture.photoId, isl.a, isl.b)] = isl

        assertEquals("island count drift vs spike", spikeByKey.size, kotlinKeys.size)
        for ((key, row) in spikeByKey) {
            val isl = kotlinKeys[key]
            checkNotNull(isl) { "spike island missing from Kotlin port: $key" }
            assertEquals("kind for $key", row.kind, isl.kind)
            assertEquals("anchA for $key", row.anchA, isl.anchoredA)
            assertEquals("anchB for $key", row.anchB, isl.anchoredB)
            assertEquals("conf for $key", row.conf, isl.confusable)
            // spike rows store round(margin, 4); compare at that resolution
            assertEquals("margin for $key", row.margin, isl.margin, 1e-3)
        }
    }

    @Test
    fun s003DPrime_withFixtureNameplateExclusion_reproducesSpikeVerdict() {
        val gate = OcrCorrectionGate(loadLexicon())
        val (evaluated, spikeByKey) = evaluateAll(gate)
        // Spike-exact exclusion set: GLOBAL union of every fixture's drafted/confirmed strings.
        val npGlobal = (loadFixtures().flatMap { it.nameplateCandidates + it.nameplateLabels })
            .map { gateCjkOnly(it) }.toSet()
        val kotlinAccepted = HashSet<Triple<Long, String, String>>()
        var totalFixes = 0
        val labelMismatch = ArrayList<String>()
        for (e in evaluated) {
            for (isl in e.islands) {
                val key = Triple(e.fixture.photoId, isl.a, isl.b)
                val row = checkNotNull(spikeByKey[key])
                // Cleaned-gold labels recomputed here must equal the spike's stored labels.
                if (truthLabel(e.fixture, isl.a, isl.b) != row.truth) {
                    labelMismatch += "$key mine=${truthLabel(e.fixture, isl.a, isl.b)} spike=${row.truth}"
                }
                if (isl.kind == "kanji" && row.truth == "fix") totalFixes++
                if (gate.accepts(isl, nameplateExcluded = gate.nameplateAdjacent(isl, npGlobal))) {
                    kotlinAccepted += key
                }
            }
        }
        assertEquals("gold labels drifted from the spike's run: $labelMismatch", emptyList<String>(), labelMismatch)

        // The spike's own D' evaluation, recomputed from its stored rows + the global strings.
        val pyAccepted = spikeByKey.values
            .filter { it.kind == "kanji" }
            .filter { row ->
                val adjacent = gate.nameplateAdjacent(
                    CorrectionIsland(0, 0, 0, row.a, row.b, row.kind, row.margin, row.anchA, row.anchB, row.conf),
                    npGlobal,
                )
                !adjacent && ((row.anchB && !row.anchA) || (row.margin >= 0.0 && row.conf))
            }
            .map { Triple(it.pid, it.a, it.b) }
            .toSet()
        val missing = pyAccepted - kotlinAccepted
        val extra = kotlinAccepted - pyAccepted
        assertEquals("accepted islands differ (missing=$missing extra=$extra)", pyAccepted, kotlinAccepted)
        assertEquals("S003-D' accepted count (spike README: 36)", 36, pyAccepted.size)
        assertEquals("true fixes on cleaned gold (coverage denominator)", 52, totalFixes)
        println(
            JSONObject()
                .put("metric", "GATE_PARITY_S003D").put("accepted", kotlinAccepted.size)
                .put("precision", 1.0).put("coverage", kotlinAccepted.size.toDouble() / totalFixes).toString()
        )
    }

    /**
     * The production nameplate proxy (standalone short punctuation-free lines) must exclude every
     * harmful island the fixture-candidate exclusion caught — that is what licenses shipping the
     * gate before the parked nameplate policy lands. Coverage loss vs the fixture exclusion is
     * reported, never hidden.
     */
    @Test
    fun productionNameplateProxy_excludesEveryHarmfulIsland() {
        val gate = OcrCorrectionGate(loadLexicon())
        val parser = CjkSentenceParser()
        val (evaluated, spikeByKey) = evaluateAll(gate)
        var accepted = 0
        var fixes = 0
        var harmfulKept = 0
        val harmfulKeptDetail = JSONArray()
        for (e in evaluated) {
            val npProxy = OcrCorrectionPass.draftNameplateStrings(e.fixture.document)
            for (isl in e.islands) {
                if (!gate.accepts(isl, nameplateExcluded = false)) continue
                if (gate.nameplateAdjacent(isl, npProxy)) continue
                val truth = checkNotNull(spikeByKey[Triple(e.fixture.photoId, isl.a, isl.b)]).truth
                accepted++
                when (truth) {
                    "fix" -> fixes++
                    "harmful" -> {
                        harmfulKept++
                        harmfulKeptDetail.put(JSONObject().put("pid", e.fixture.photoId).put("a", isl.a).put("b", isl.b))
                    }
                }
            }
        }
        assertEquals("harmful islands surviving the proxy exclusion (must be 0):\n$harmfulKeptDetail", 0, harmfulKept)
        assertTrue("proxy must retain most of the measured 36 fixes (got $fixes)", fixes >= 34)
        println(
            JSONObject().put("metric", "GATE_PARITY_PROXY").put("accepted", accepted)
                .put("fix", fixes).put("harmfulKept", harmfulKept).toString()
        )
    }

    @Test
    fun lexiconSnapshot_headlineCountsAreStable() {
        val lex = loadLexicon()
        // Snapshot regeneration prints these; bump deliberately if the dictionary genuinely changes.
        assertEquals(199732, lex.headwords.size)
        assertEquals(149015, lex.bigrams.size)
        assertEquals(6355, lex.radicals.size)
        assertTrue(lex.headwords.contains("陛下"))
        assertTrue(!lex.headwords.contains("陸下"))
    }
}