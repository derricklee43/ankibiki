package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the §3 transparency UI: provider/note persistence, staleness, previews. */
class ExtractionTransparencyTest {
    private fun rows(vararg pairs: Pair<String, ExtractionStatus.SentenceRows>) = pairs.map { it.second }

    private fun sentenceRows(
        sentence: String,
        count: Int = 1,
        seen: Int = 0,
        deleted: Int = 0,
        edited: Boolean = false,
    ) = ExtractionStatus.SentenceRows(sentence, count, seen, deleted, edited)

    @Test
    fun ocrDocument_roundTripsProviderAndNote() {
        val document = OcrDocument(
            fullText = "テスト",
            blocks = listOf(OcrBlock("テスト", listOf(OcrLine("テスト", 20)), OcrBounds(0, 0, 10, 20))),
            provider = "cloud-vision",
            note = "Cloud Vision returned no text",
        )
        val restored = OcrDocument.fromJsonString(document.toJsonString())
        assertEquals(document, restored)
    }

    @Test
    fun legacyOcrDocuments_decodeWithoutProviderFields() {
        val legacy = """{"fullText":"abc","blocks":[{"text":"abc","lines":[{"text":"abc","height":10}]}]}"""
        val decoded = OcrDocument.fromJsonString(legacy)
        assertEquals("abc", decoded?.fullText)
        assertNull(decoded?.provider)
        assertNull(decoded?.note)
        // Re-encoding a legacy document must not fabricate keys.
        assertFalse(decoded!!.toJsonString().contains("provider"))
    }

    @Test
    fun staleness_detectedWhenTextMovesOnButRowsKeepOldKeys() {
        val display = listOf("真実が見える。")
        val staleRows = rows("old" to sentenceRows("証拠がない。", count = 2))
        assertTrue(ExtractionStatus.isStale(display, staleRows))
        val freshRows = rows("new" to sentenceRows("真実が見える。", count = 2))
        assertFalse(ExtractionStatus.isStale(display, freshRows))
    }

    @Test
    fun blankDisplaySentences_doNotCountAsStaleAgainstEmptyRows() {
        // A photo whose text is blank and has no occurrences is consistent, not stale.
        assertFalse(ExtractionStatus.isStale(listOf("", "  "), emptyList()))
    }

    @Test
    fun preview_reportsKeptAddedRemovedAndHistoryAtRisk() {
        val kept = sentenceRows("A。", seen = 3)
        val droppedTriaged = sentenceRows("B。", seen = 1, deleted = 2, edited = true)
        val droppedPlain = sentenceRows("C。")
        val preview = ExtractionStatus.preview(
            newSentences = listOf("A。", "D。"),
            rows = listOf(kept, droppedTriaged, droppedPlain),
        )
        assertEquals(1, preview.keptSentences)   // A。
        assertEquals(1, preview.addedSentences)  // D。
        assertEquals(2, preview.removedSentences) // B。, C。
        assertEquals(3, preview.triagedRowsInRemoved) // 1 seen + 2 deleted on B。
        assertEquals(1, preview.editedSentencesInRemoved)
    }

    @Test
    fun preview_reprocessingIdenticalText_keepsTriagedRowsUntouched() {
        val rows = listOf(sentenceRows("A。"), sentenceRows("B。", deleted = 1))
        val preview = ExtractionStatus.preview(listOf("A。", "B。"), rows)
        assertEquals(2, preview.keptSentences)
        assertEquals(0, preview.addedSentences)
        assertEquals(0, preview.removedSentences)
        assertEquals(0, preview.triagedRowsInRemoved)
    }

    @Test
    fun providerLabels_areHumanReadable() {
        fun doc(provider: String?) = OcrDocument("x", emptyList(), provider = provider)
        assertEquals("Cloud Vision", ExtractionStatus.describeProvider(doc("cloud-vision")))
        assertEquals("on-device (ML Kit)", ExtractionStatus.describeProvider(doc("on-device")))
        assertEquals("", ExtractionStatus.describeProvider(doc(null)))
        assertEquals("", ExtractionStatus.describeProvider(null))
    }
}
