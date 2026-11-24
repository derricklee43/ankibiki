package com.derricklee.ankidict

/**
 * .srt / .vtt subtitle parser -> cues (design §5 M5/M7). Deliberately dependency-free:
 * the repo carries zero new runtime deps (AppIo.kt header). Handles CRLF, BOM, VTT
 * headers/NOTE blocks, inline <i>/<b>/<font>/<c> tags, "<00:01:02.345>" timing tags,
 * alt timestamps (arrows), and dot-vs-comma ms separators.
 */
object SubtitleParser {

    data class RawCue(val startMs: Long, val endMs: Long, val textLines: List<String>)

    fun parse(fileName: String, content: String): List<SubtitleCue> =
        toCues(if (looksLikeVtt(content)) parseVtt(content) else parseSrt(content))

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
