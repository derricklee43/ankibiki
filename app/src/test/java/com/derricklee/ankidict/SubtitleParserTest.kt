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

    // ---- ASS (spike 007a, 2026-11-23: Jimaku fansub corpus is .ass; shipped
    // parser stored 0 cues on it) ----

    @Test
    fun parsesBasicAss() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+
            Collisions: Normal

            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:03.30,0:00:12.31,Default,,0,0,0,,{\an8\pos(960,40)}風の音
            Dialogue: 0,0:00:12.31,0:01:02.50,Default,,0,0,0,,一行目\N二行目 {\i1 ital\i0} 続き
            Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,should be skipped
        """.trimIndent()
        val cues = SubtitleParser.parse("a.ass", ass)
        assertEquals(2, cues.size)
        assertEquals(3_300L, cues[0].startMs)
        assertEquals(12_310L, cues[0].endMs)
        assertEquals("風の音", cues[0].text) // override block stripped, no residue
        assertEquals("一行目 二行目 続き", cues[1].text) // \N -> single space join
        assertEquals(62_500L, cues[1].endMs) // hour carry
    }

    @Test
    fun assWithoutFormatLineUsesSsaDefaultColumnOrder() {
        val ass = "[Script Info]\n\n[Events]\nDialogue: Marked,0:01:00.00,0:01:01.00," +
            "Default,,0000,0000,0000,,こんにちは\n"
        val cues = SubtitleParser.parse("x.ass", ass)
        assertEquals(1, cues.size)
        assertEquals(60_000L, cues[0].startMs)
        assertEquals("こんにちは", cues[0].text)
    }

    @Test
    fun assTextFieldKeepsEmbeddedCommas() {
        val ass = "[Script Info]\n[Events]\n" +
            "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello, world, again\n"
        val cues = SubtitleParser.parse("x.ass", ass)
        assertEquals(1, cues.size)
        assertEquals("Hello, world, again", cues[0].text)
    }

    @Test
    fun assWithBomAndCrlfStillDispatches() {
        val ass = "\uFEFF[Script Info]\r\n[Events]\r\nDialogue: 0,0:00:01.00,0:00:02.00," +
            "Default,,0,0,0,,BOM\r\n OK\r\n"
        val cues = SubtitleParser.parse("x.ass", ass)
        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMs)
    }

    @Test
    fun srtAndVttStillDispatchedNotAss() {
        // guard against over-eager ASS sniffing
        val srt = "1\n00:00:01,000 --> 00:00:02,000\ntext\n"
        assertEquals(1, SubtitleParser.parse("a.srt", srt).size)
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\ntext\n"
        assertEquals(1, SubtitleParser.parse("a.vtt", vtt).size)
    }

    @Test
    fun invisibleBidiAndZeroWidthControlsStrippedFromCueText() {
        // device-found 2026-11-26 (Demon Slayer E4): a fan-sub line wrapped in bidi
        // isolates broke the user-dictionary exact-substring match while Kuromoji
        // tokenized AROUND the invisible chars -> fragments in the word list.
        // Write the characters ESCAPED only - raw ones in source are the hazard.
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n" +
            "\u202A" + "竈門炭治郎\u202Cです\u200B～！\u200E" + "\n"
        val cues = SubtitleParser.parse("a.srt", srt)
        assertEquals("竈門炭治郎です～！", cues[0].text)
    }
}
