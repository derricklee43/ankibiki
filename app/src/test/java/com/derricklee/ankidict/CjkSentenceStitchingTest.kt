package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Test

class CjkSentenceStitchingTest {
    @Test
    fun adjacentDialogueBlocks_stitchAnUnfinishedSentence_butStopAtTheQuestionMark() {
        // Recorded photo-953 OCR text, line heights, and bounds from a production rescan.
        val document = OcrDocument(
            "マルス\nその傷で\nグラから脱中してきたのか?\n早く手当階·.",
            listOf(
                OcrBlock("マルス\nその傷で", listOf(OcrLine("マルス", 141), OcrLine("その傷で", 163)),
                    OcrBounds(424, 1222, 1188, 1582)),
                OcrBlock("グラから脱中してきたのか?", listOf(OcrLine("グラから脱中してきたのか?", 161)),
                    OcrBounds(503, 1563, 1871, 1724)),
                OcrBlock("早く手当階·.", listOf(OcrLine("早く手当階·.", 149)),
                    OcrBounds(497, 1743, 1098, 1892)),
            ),
        )
        assertEquals(
            listOf("マルスその傷でグラから脱中してきたのか?", "早く手当階·."),
            CjkSentenceParser().parseSentences(document),
        )
    }

    @Test
    fun unrelatedRegions_doNotBecomeOneSentence() {
        val first = OcrBlock("証拠がまだ", listOf(OcrLine("証拠がまだ", 40)), OcrBounds(10, 10, 210, 50))
        val unrelated = listOf(
            // Different column, large paragraph gap, overlapping row, or different font size.
            OcrBlock("足りない。", listOf(OcrLine("足りない。", 40)), OcrBounds(400, 60, 600, 100)),
            OcrBlock("足りない。", listOf(OcrLine("足りない。", 40)), OcrBounds(10, 300, 210, 340)),
            OcrBlock("足りない。", listOf(OcrLine("足りない。", 40)), OcrBounds(10, 10, 210, 50)),
            OcrBlock("足りない。", listOf(OcrLine("足りない。", 15)), OcrBounds(10, 60, 210, 75)),
            // Legacy/synthetic blocks lack sufficient layout information; stay conservative.
            OcrBlock("足りない。", listOf(OcrLine("足りない。", 40))),
        )
        for (next in unrelated) {
            assertEquals(next.toString(), listOf("証拠がまだ", "足りない。"),
                CjkSentenceParser().parseSentences(OcrDocument("", listOf(first, next))))
        }
    }

    @Test
    fun adjacentBlocks_canWrapMidWord_withoutRemovingSentencePunctuation() {
        val blocks = listOf(
            OcrBlock("決定的な証", listOf(OcrLine("決定的な証", 40)), OcrBounds(10, 10, 210, 50)),
            OcrBlock("拠がある！", listOf(OcrLine("拠がある！", 40)), OcrBounds(10, 60, 210, 100)),
            OcrBlock("真実だ。", listOf(OcrLine("真実だ。", 40)), OcrBounds(10, 110, 210, 150)),
        )
        assertEquals(listOf("決定的な証拠がある！", "真実だ。"),
            CjkSentenceParser().parseSentences(OcrDocument("", blocks)))
    }

    @Test
    fun cloudOcr_preservesBlockBounds_includingImplicitZeroCoordinates() {
        val response = """{"responses":[{"fullTextAnnotation":{"text":"証拠がある。","pages":[{"blocks":[{"boundingBox":{"vertices":[{}, {"x":200}, {"x":200,"y":40}, {"y":40}]},"paragraphs":[{"words":[{"boundingBox":{"vertices":[{}, {"x":200}, {"x":200,"y":40}, {"y":40}]},"symbols":[{"text":"証拠がある。","property":{"detectedBreak":{"type":"LINE_BREAK"}}}]}]}]}]}]}}]}"""
        val block = CloudVisionTextExtractor.parseResponseJson(response).blocks.single()
        assertEquals(OcrBounds(0, 0, 200, 40), block.bounds)
        assertEquals("証拠がある。", block.lines.single().text)
    }

    @Test
    fun ocrGeometry_roundTripsWithoutBreakingLegacyDocuments() {
        val block = OcrBlock("証拠がある。", listOf(OcrLine("証拠がある。", 40)), OcrBounds(10, 20, 200, 60))
        val document = OcrDocument(block.text, listOf(block))
        assertEquals(document, OcrDocument.fromJsonString(document.toJsonString()))
        val legacy = """{"fullText":"証拠がある。","blocks":[{"text":"証拠がある。","lines":[{"text":"証拠がある。","height":40}]}]}"""
        assertEquals(null, OcrDocument.fromJsonString(legacy)!!.blocks.single().bounds)
    }
}
