package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitle parser contract (design S0-C pass criteria: parse OK, stable cue keys). */
class SubtitleParserTest {

    @Test
    fun parsesBasicSrt() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            こんにちは世界

            2
            00:00:05,500 --> 00:00:08,250
            これは
            二行の字幕です
        """.trimIndent()
        val cues = SubtitleParser.parse("a.srt", srt)
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
        assertEquals("こんにちは世界", cues[0].text)
        // multi-line cues join with a single space (cue text normalization)
        assertEquals("これは 二行の字幕です", cues[1].text)
    }

    @Test
    fun handlesVttWithHeaderAndTags() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<i>Hello</i> <b>world</b>\n\nNOTE x\n"
        val cues = SubtitleParser.parse("a.vtt", vtt)
        assertEquals(1, cues.size)
        assertEquals("Hello world", cues[0].text)
    }

    @Test
    fun handlesCrlfAndBomAndDotMillis() {
        val srt = "\uFEFF1\r\n00:00:02.345 --> 00:00:03.000\r\nOK\r\n\r\n"
        val cues = SubtitleParser.parse("x", srt)
        assertEquals(1, cues.size)
        assertEquals(2_345L, cues[0].startMs)
    }

    @Test
    fun blankAndTimingOnlyBlocksProduceNoCues() {
        val cues = SubtitleParser.parse("x", "1\n00:00:01,000 --> 00:00:02,000\n   \n")
        assertTrue(cues.isEmpty())
    }

    @Test
    fun offsetShiftOnlyForMediaPhase() {
        val cues = listOf(SubtitleCue(1000, 2000, "a"), SubtitleCue(0, 500, "b"))
        val shifted = SubtitleParser.shifted(cues, 300)
        assertEquals(1300L, shifted[0].startMs)
        assertEquals(300L, shifted[1].startMs)
        assertEquals(800L, shifted[1].endMs)
        assertEquals("text is never touched", cues[0].text, shifted[0].text)
        // negative offsets clamp windows at zero, never produce negatives
        val back = SubtitleParser.shifted(cues, -1000)
        assertEquals(0L, back[0].startMs)
        assertEquals(0L, back[1].endMs)
    }
}
