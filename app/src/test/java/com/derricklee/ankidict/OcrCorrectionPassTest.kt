package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Application-mechanics tests for the auto-correction pass: which islands get rewritten into
 * the local document, what the audit rows say, and how the rebuilt document preserves geometry.
 * The gate's statistical behavior is pinned separately (OcrCorrectionGateParityTest) against
 * the spike's real measurements.
 */
class OcrCorrectionPassTest {

    private val fakeLexicon = GateLexicon(
        headwords = setOf("陛下", "猳猳"),
        bigrams = setOf("陛下", "猳猳", "陸下"),
        radicals = mapOf('陸' to setOf("阜(阝)"), '陛' to setOf("阜(阝)")),
    )

    private fun pass() = OcrCorrectionPass(
        gateProvider = { OcrCorrectionGate(fakeLexicon) },
        reader = CloudReedReader { null },
    )

    private fun doc(vararg lines: String, provider: String? = "on-device") = docWith(*lines.map { it to 100 }.toTypedArray(), provider = provider)

    private fun docWith(vararg lineAndHeight: Pair<String, Int>, provider: String? = "on-device") = OcrDocument(
        fullText = lineAndHeight.joinToString("\n") { it.first },
        blocks = lineAndHeight.map { (text, h) -> OcrBlock(text = text, lines = listOf(OcrLine(text = text, boundingBoxHeightPx = h))) },
        provider = provider,
    )

    private val candidate = PhotoCandidate(mediaStoreId = 42L, contentUri = "content://x", dateAdded = 1L)

    @Test
    fun difflibPort_matchesPythonOpcodesAndRatio() {
        assertEquals(0.75, DifflibSequenceMatcher.ratio("abcd", "bcde"), 1e-9)
        val ops = DifflibSequenceMatcher.Matcher("qabxcd", "abycdf").opcodes()
        assertEquals(
            listOf(
                DifflibSequenceMatcher.Opcode("delete", 0, 1, 0, 0),
                DifflibSequenceMatcher.Opcode("equal", 1, 3, 0, 2),
                DifflibSequenceMatcher.Opcode("replace", 3, 4, 2, 3),
                DifflibSequenceMatcher.Opcode("equal", 4, 6, 3, 5),
                DifflibSequenceMatcher.Opcode("insert", 6, 6, 5, 6),
            ),
            ops,
        )
    }

    @Test
    fun appliedIsland_rewritesLineAndRebuildsDocument() {
        val local = doc("陸という方々だ")
        val cloud = doc("陛という方々だ")
        val outcome = pass().correctWithCloud(candidate, local, cloud)
        assertEquals(1, outcome.appliedCount)
        val fixed = outcome.document.blocks.single().lines.single().text
        assertEquals("陛という方々だ", fixed)
        assertEquals("陛という方々だ", outcome.document.fullText)
        assertEquals(100, outcome.document.blocks.single().lines.single().boundingBoxHeightPx) // geometry survives
        assertTrue(outcome.document.note.orEmpty().contains("cloud-gate: 1"))
        val row = outcome.evidence.single()
        assertEquals("applied", row.decision)
        assertEquals("陸という方々だ", row.lineBefore)
        assertEquals("陛という方々だ", row.lineAfter)
        assertEquals(42L, row.photoId)
    }

    @Test
    fun projectionOnlyIsland_isRejectedAsNotContiguousAndTextUntouched() {
        // The island 陸下->猳猳 exists only after CJK-projection strips the digit: nothing to
        // splice into the raw line, so the gate must leave the text alone and say why.
        val local = doc("お陸1下様です")
        val cloud = doc("お猳猳様です")
        val outcome = pass().correctWithCloud(candidate, local, cloud)
        assertEquals(0, outcome.appliedCount)
        assertEquals(local, outcome.document)
        val applied = outcome.evidence.filter { it.decision == "applied" }
        assertEquals(emptyList<CorrectionEvidenceRow>(), applied)
        val row = outcome.evidence.first { it.reason == "not-contiguous" }
        assertEquals("陸下", row.aText)
        assertEquals("猳猳", row.bText)
    }

    @Test
    fun kanaStylingIsland_isRecordedNeverApplied() {
        val local = doc("やるり")
        val cloud = doc("やるリ") // リ/り: rendering difference, never a content fix
        val outcome = pass().correctWithCloud(candidate, local, cloud)
        assertEquals(0, outcome.appliedCount)
        assertEquals(local, outcome.document)
        assertTrue(outcome.evidence.isNotEmpty())
        assertTrue(outcome.evidence.all { it.decision == "rejected" && it.reason == "not-kanji" })
    }

    @Test
    fun cloudAuthoredDocument_isNeverCorrected() {
        val outcome = pass().correctWithCloud(candidate, doc("陸という方々だ", provider = "cloud-vision"), doc("陛という方々だ"))
        assertEquals(0, outcome.appliedCount)
        assertEquals(outcome.document.blocks.single().lines.single().text, "陸という方々だ")
    }

    @Test
    fun anchoredBranch_beatsPlainBigramOverlap() {
        // 陛下 is a dictionary headword covering the variant; 陸下 is covered by nothing.
        val islands = OcrCorrectionGate(fakeLexicon).extractIslands(listOf("陸下という方々だ"), listOf("陛下という方々だ"))
        val isl = islands.single { it.a == "陸" }
        assertTrue("anchoredB", isl.anchoredB)
        assertFalse("anchoredA", isl.anchoredA)
        assertTrue("S003-D' accepts", OcrCorrectionGate(fakeLexicon).accepts(isl, nameplateExcluded = false))
    }

    @Test
    fun nameplateProxy_draftsShortKatakanaNoHiraganaLines() {
        val document = doc("エリス", "世界でただ1人の。", "神剣ファルシオン")
        val np = OcrCorrectionPass.draftNameplateStrings(document)
        assertTrue("short katakana leak is drafted", np.contains(gateCjkOnly("エリス")))
        assertTrue("katakana item-name line is drafted too (over-exclusion = safe direction)", np.contains(gateCjkOnly("神剣ファルシオン")))
        assertFalse("punctuated utterance core is never drafted", np.any { it.contains(gateCjkOnly("世界")) })
        // Inline-merged nameplate leak (photo 1136 shape): drafting must not need parse output.
        val merged = doc("モ口ドフ", "はい。タリス王は", "王子のお父上の古い友人。")
        val np2 = OcrCorrectionPass.draftNameplateStrings(merged)
        assertTrue("katakana+kanji nameplate line drafted even when it merges inline", np2.contains(gateCjkOnly("モ口ドフ")))
        assertFalse("hiragana utterance lines are not drafted (coverage protection)", np2.any { it.contains(gateCjkOnly("王子")) })
        // Real short utterance with hiragana stays correctable.
        val thanks = doc("ありがとう", "そういうことです。")
        assertTrue(OcrCorrectionPass.draftNameplateStrings(thanks).isEmpty())
    }
}