package com.derricklee.ankidict

/**
 * .srt / .vtt / .ass subtitle parser -> cues (design §5 M5/M7; .ass added by
 * spike 007a 2026-11-23 — Jimaku/fansub rips ship ASS and shipped code stored
 * 0 cues on them). Deliberately dependency-free:
 * the repo carries zero new runtime deps (AppIo.kt header). Handles CRLF, BOM, VTT
 * headers/NOTE blocks, inline <i>/<b>/<font>/<c> tags, "<00:01:02.345>" timing tags,
 * alt timestamps (arrows), and dot-vs-comma ms separators. ASS: [Events] Format
 * column order (default order when absent), Dialogue rows only (Comment etc.
 * ignored), comma-bearing Text fields, {\...} override blocks, \N/\h line breaks.
 */
object SubtitleParser {

    data class RawCue(val startMs: Long, val endMs: Long, val textLines: List<String>)

    fun parse(fileName: String, content: String): List<SubtitleCue> =
        toCues(
            if (looksLikeAss(content)) parseAss(content)
            else if (looksLikeVtt(content)) parseVtt(content)
            else parseSrt(content)
        )

    private fun looksLikeAss(content: String): Boolean =
        content.trimStart('\uFEFF').replace("\r\n", "\n").let {
            it.trimStart().startsWith("[Script Info]") || it.contains("\n[Events]")
        }

    private fun looksLikeVtt(content: String): Boolean =
        content.trimStart('\uFEFF').startsWith("WEBVTT")

    private fun toCues(raw: List<RawCue>): List<SubtitleCue> = raw.mapNotNull { r ->
        val text = cleanLines(r.textLines)
        if (text.isBlank()) null else SubtitleCue(r.startMs, r.endMs, text)
    }

    /** cue text normalization: strip timing/format tags, join multi-line cues with space. */
    fun cleanLines(lines: List<String>): String = lines
        .joinToString(" ") { stripTags(it) }
        .trim()
        .replace(Regex("\\s+"), " ")

    private val TAG_REGEX = Regex("<[^>]*>")
    private fun stripTags(line: String): String =
        line.replace(TAG_REGEX, "").replace("&nbsp;", " ").trim()

    private val TS = Regex(
        """(\d{1,2}:)?(\d{1,2}):(\d{2})[.,](\d{1,3})"""
    )

    private fun parseTiming(line: String): Pair<Long, Long>? {
        if (!line.contains("-->")) return null
        val halves = line.split("-->")
        if (halves.size < 2) return null
        val start = parseStamp(halves[0]) ?: return null
        // right side may carry position/layout extras; first timestamp wins
        val end = parseStamp(halves[1].substringBefore(" ")) ?: parseStamp(halves[1]) ?: return null
        return start to end
    }

    private fun parseStamp(chunk: String): Long? {
        val m = TS.find(chunk.trim()) ?: return null
        val g = m.groupValues
        val hours = g[1].removeSuffix(":").toLongOrNull() ?: 0L
        val minutes = g[2].toLong()
        val seconds = g[3].toLong()
        val millis = g[4].let { (it + "000").substring(0, 3) }.toLong()
        return ((hours * 3600 + minutes * 60 + seconds) * 1000) + millis
    }

    private fun parseSrt(content: String): List<RawCue> {
        val blocks = content.trimStart('\uFEFF').replace("\r\n", "\n").split(Regex("\n{2,}"))
        val out = ArrayList<RawCue>()
        for (block in blocks) {
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue
            val timing = parseTiming(lines[timingIdx]) ?: continue
            out += RawCue(timing.first, timing.second, lines.drop(timingIdx + 1))
        }
        return out
    }

    private fun parseVtt(content: String): List<RawCue> {
        val text = content.trimStart('\uFEFF').replace("\r\n", "\n")
        val body = text.substringAfter("\n\n", text)
        val blocks = body.split(Regex("\n{2,}"))
        val out = ArrayList<RawCue>()
        for (block in blocks) {
            val lines = block.split("\n").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("NOTE") && !it.startsWith("WEBVTT") }
            if (lines.isEmpty()) continue
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue
            val timing = parseTiming(lines[timingIdx]) ?: continue
            out += RawCue(timing.first, timing.second, lines.drop(timingIdx + 1))
        }
        return out
    }

    /**
     * ASS/SSA events section -> cues. Field order comes from the [Events] Format:
     * line when present (V4+ inserts "Layer"/"Name" variants); SSA default order
     * otherwise. The Text field is split with a limit so comma-bearing dialogue
     * survives. Comment/DialoguEx/etc lines are skipped (only "Dialogue:" yields).
     * Known limit (fine for text mining): ASS drawing spans (\p) inside override
     * blocks across dialogue lines are stripped with the block, not reconstructed.
     */
    private fun parseAss(content: String): List<RawCue> {
        val text = content.trimStart('\uFEFF').replace("\r\n", "\n")
        var fields = listOf(
            "Marked", "Start", "End", "Style", "Name",
            "MarginL", "MarginR", "MarginV", "Effect", "Text",
        )
        var inEvents = false
        val out = ArrayList<RawCue>()
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents || !line.contains(":")) continue
            val key = line.substringBefore(":")
            val value = line.substringAfter(":")
            if (key.equals("Format", ignoreCase = true)) {
                val f = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (f.isNotEmpty()) fields = f
                continue
            }
            if (!key.equals("Dialogue", ignoreCase = true)) continue
            val idxStart = fields.indexOfFirst { it.equals("Start", ignoreCase = true) }
            val idxEnd = fields.indexOfFirst { it.equals("End", ignoreCase = true) }
            val idxText = fields.indexOfLast { it.equals("Text", ignoreCase = true) }
            if (idxStart < 0 || idxEnd < 0 || idxText < 0) continue
            // No split limit: override blocks like \pos(960,40) carry commas, so a
            // limit=fields.size cut mis-lands the TEXT field itself (caught by
            // SubtitleParserTest.parsesBasicAss on first run). Unsplit, then re-merge
            // any overflow back into Text — ASS convention: Text is the last column.
            val cols = value.split(',')
            if (cols.size <= idxText) continue
            val start = assStamp(cols[idxStart]) ?: continue
            val end = assStamp(cols[idxEnd]) ?: continue
            val text = if (idxText == fields.size - 1)
                cols.drop(idxText).joinToString(",")
            else cols[idxText]
            out += RawCue(start, end, listOf(assText(text)))
        }
        return out
    }

    /** "h:mm:ss.cc(.ms)" ASS stamps -> ms; missing/invalid -> null. */
    private val ASS_TS = Regex("""(\d+):(\d{1,2}):(\d{2})[.](\d{1,3})""")

    private fun assStamp(chunk: String): Long? {
        val m = ASS_TS.find(chunk.trim()) ?: return null
        val g = m.groupValues
        val frac = (g[4] + "000").substring(0, 3).toLong()
        return ((g[1].toLong() * 3600 + g[2].toLong() * 60 + g[3].toLong()) * 1000) + frac
    }

    /**
     * ASS text normalization: override blocks, then \h/\N/simple tags.
     * PATTERN SPELLING IS LOAD-BEARING: a DEVICE-FOUND BUG 2026-11-23 (ledger:
     * PatternSyntaxException near index 8 on "\\{[^}]*}") — desktop JDK accepts
     * the quoted brace, Android's java.util.regex does NOT. Use the [{]
     * character-class form (portable). Same hazard as the Collator JVM/device
     * divergence rule: JVM specs green does NOT prove the device regex engine.
     */
    private fun assText(value: String): String = value
        .replace(Regex("""[{][^}]*}"""), " ")
        .replace(Regex("""\\[a-zA-Z][a-zA-Z0-9]*(?:\([^)]*\))?"""), " ")

    /**
     * Apply a user offset (settings offset_ms; player-side nudge semantics): shifts cue
     * windows without touching text. Irrelevant for whole-episode text mining; consumed
     * later by the media phase (parked doc §C S3.3).
     */
    fun shifted(cues: List<SubtitleCue>, offsetMs: Int): List<SubtitleCue> =
        if (offsetMs == 0) cues else cues.map {
            it.copy(
                startMs = (it.startMs + offsetMs).coerceAtLeast(0),
                endMs = (it.endMs + offsetMs).coerceAtLeast(0),
            )
        }
}
