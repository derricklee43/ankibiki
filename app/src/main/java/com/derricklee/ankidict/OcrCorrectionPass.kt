package com.derricklee.ankidict

import android.content.Context

/**
 * Second provider read (Cloud Vision at mining time) that feeds [OcrCorrectionPass].
 * Production impl hits the network; JVM tests supply canned documents. Returning null
 * (missing key, offline, failure) means "no correction this photo" — never a partial pass.
 */
fun interface CloudReedReader {
    fun readCloud(image: PreparedImage): OcrDocument?
}

/** One per-island audit row persisted to ocr_corrections (schema sketched in spikes/003 README). */
data class CorrectionEvidenceRow(
    val photoId: Long,
    val lineIndex: Int,
    val aStart: Int,
    val aEnd: Int,
    val aText: String,
    val bText: String,
    val kind: String,
    val margin: Double,
    val anchoredA: Boolean,
    val anchoredB: Boolean,
    val confusable: Boolean,
    val nameplateExcluded: Boolean,
    val decision: String, // applied | rejected | excluded
    val reason: String?, // not-kanji | gate-failed | not-contiguous | nameplate-adjacent
    val lineBefore: String,
    val lineAfter: String,
    val createdAt: Long,
)

/**
 * Applies the S003-D' gate to one photo: diffs the stored (on-device) document against a
 * second Cloud read, rewrites only ACCEPTED single-island substitutions into the local
 * line texts (local segmentation stays authoritative — Cloud geometry never enters the
 * corpus), and emits one evidence row per disagreement island so annotation can score
 * the gate and every write stays reversible (line_before/after pin the exact text).
 */
class OcrCorrectionPass(
    private val gateProvider: () -> OcrCorrectionGate,
    private val reader: CloudReedReader,
    /**
     * Nameplate strings the gate treats as plate-adjacent (islands near these never auto-apply).
     * Production wires the spike-004 replacement — recurrence lexicon ∪ ledger-confirmed plates
     * (`MiningRepository.nameplateGateStrings`); the A0 structural proxy stays only as the
     * fallback default, never the shipping input (its 4 content casualties, spike README).
     */
    private val nameplateStrings: (OcrDocument) -> Set<String> = ::draftNameplateStrings,
) {

    data class Outcome(
        val document: OcrDocument,
        val evidence: List<CorrectionEvidenceRow>,
        val appliedCount: Int,
        val cloudAvailable: Boolean,
    )

    /** No-op outcome when cloud is unavailable or the document is already a cloud read. */
    fun correct(candidate: PhotoCandidate, document: OcrDocument, prepared: PreparedImage): Outcome {
        if (document.provider == "cloud-vision") {
            return Outcome(document, emptyList(), 0, cloudAvailable = true)
        }
        val cloudDoc = reader.readCloud(prepared)
            ?: return Outcome(document, emptyList(), 0, cloudAvailable = false)
        return correctWithCloud(candidate, document, cloudDoc)
    }

    /** Pure half of [correct] (test seam): applies the gate given both documents in hand. */
    fun correctWithCloud(
        candidate: PhotoCandidate,
        document: OcrDocument,
        cloudDoc: OcrDocument,
    ): Outcome {
        // A cloud-authored document has no local twin to gate against — never correct it in place.
        if (document.provider == "cloud-vision") return Outcome(document, emptyList(), 0, cloudAvailable = true)
        if (cloudDoc.fullText.isBlank()) return Outcome(document, emptyList(), 0, cloudAvailable = true)

        val gate = gateProvider()
        val nameplateStrings = this.nameplateStrings(document)
        val lines = document.blocks.flatMap { it.lines }
        val cloudLines = cloudDoc.blocks.flatMap { it.lines }.map { it.text }
        val islands = gate.extractIslands(lines.map { it.text }, cloudLines)

        val ts = System.currentTimeMillis() / 1000L
        val lineTexts = lines.map { it.text }.toMutableList()
        val evidence = ArrayList<CorrectionEvidenceRow>()
        var applied = 0
        for (island in islands) {
            val np = gate.nameplateAdjacent(island, nameplateStrings)
            val accepted = gate.accepts(island, nameplateExcluded = np)
            val before = lineTexts[island.lineIndex]
            var decision = "rejected"
            var reason: String? = when {
                island.kind != "kanji" -> "not-kanji"
                np -> "nameplate-adjacent"
                !accepted -> "gate-failed"
                else -> null
            }
            var after = before
            if (accepted && !np) {
                if (!before.contains(island.a)) {
                    decision = "rejected"; reason = "not-contiguous"
                } else {
                    after = before.replace(island.a, island.b)
                    decision = "applied"
                    applied++
                }
            } else if (np && island.kind == "kanji") {
                decision = "excluded"
            }
            lineTexts[island.lineIndex] = after
            evidence.add(
                CorrectionEvidenceRow(
                    photoId = candidate.mediaStoreId, lineIndex = island.lineIndex,
                    aStart = island.aStart, aEnd = island.aEnd, aText = island.a, bText = island.b,
                    kind = island.kind, margin = island.margin,
                    anchoredA = island.anchoredA, anchoredB = island.anchoredB, confusable = island.confusable,
                    nameplateExcluded = np, decision = decision, reason = reason,
                    lineBefore = before, lineAfter = after, createdAt = ts,
                )
            )
        }
        if (applied == 0) return Outcome(document, evidence, 0, cloudAvailable = true)

        // Rebuild the document preserving geometry: rewrite line texts, then block texts, then fullText.
        var cursor = 0
        val newBlocks = document.blocks.map { block ->
            val blockLines = block.lines.map { line ->
                val text = lineTexts[cursor++]
                OcrLine(text = text, boundingBoxHeightPx = line.boundingBoxHeightPx)
            }
            OcrBlock(
                text = blockLines.joinToString("\n") { it.text },
                lines = blockLines,
                bounds = block.bounds,
            )
        }
        val rebuilt = OcrDocument(
            fullText = newBlocks.joinToString("\n") { it.text },
            blocks = newBlocks,
            provider = document.provider,
            note = (document.note?.plus(" | ") ?: "") + "cloud-gate: $applied substitutions",
        )
        return Outcome(rebuilt, evidence, applied, cloudAvailable = true)
    }

    companion object {
        private const val TERMINAL_PUNCT = "。！？!?.…"
        private val STRIP_CHARS = setOf(
            '\t', '、', '・', '。', '「', '」', '『', '』', '（', '）', '(', ')', '【', '】',
            '〈', '〉', '—', '–', '_', '-', '*', ':', '/', '\\', '~', '^',
        )

        internal fun contentChars(s: String): String =
            s.filterNot { it.isWhitespace() || it in STRIP_CHARS }

        /**
         * Provisional structural nameplate proxy (the parked nameplate policy's stand-in,
         * as anticipated by spikes/003 README). A line is nameplate-shaped when its
         * punctuation-free core is short (<= 8 content chars), carries no terminal
         * punctuation, contains AT LEAST ONE katakana, and contains NO hiragana at all —
         * utterances virtually always carry hiragana particles/inflections, while names,
         * menus and buttons are katakana/kanji only. Standalone-ness is NOT required:
         * photo 1136's nameplate merges inline ("モ口ドフはい。"), so line-local signals
         * must not depend on parse output. Font size proved unusable too (1136: nameplate
         * 214px == dialogue median). Over-drafting costs coverage (dropped fixes), never
         * a corrupting write — the safe direction until the exclusion policy ships.
         */
        fun draftNameplateStrings(document: OcrDocument): Set<String> {
            val out = HashSet<String>()
            for (line in document.blocks.flatMap { it.lines }) {
                val norm = line.text.filterNot(Char::isWhitespace)
                if (norm.isEmpty()) continue
                val core = norm.trimEnd(*TERMINAL_PUNCT.toCharArray())
                if (core.isEmpty() || core.any { it in TERMINAL_PUNCT }) continue
                if (contentChars(core).length > 8) continue
                val hasKatakana = core.any { it in '\u30a0'..'\u30ff' }
                val hasHiragana = core.any { it in '\u3040'..'\u309f' }
                if (!hasKatakana || hasHiragana) continue
                val proj = gateCjkOnly(core)
                if (proj.length >= 2) out.add(proj)
            }
            return out
        }
    }
}

/**
 * Builds the [GateLexicon] from the bundled dictionary.db (words + kanji_radicals) exactly as
 * scripts/export_gate_lexicon.py freezes it for JVM tests. Process-wide lazy: the first
 * corrected scan pays the load, everything after shares it.
 */
object GateLexiconProvider {
    @Volatile
    private var cached: GateLexicon? = null

    fun lexicon(context: Context): GateLexicon =
        cached ?: synchronized(this) {
            cached ?: run {
                val db = openBundledDatabase(context, "dictionary.db")
                    ?: error("dictionary.db missing — gate cannot run without the bundled lexicon")
                val lex = GateLexicon.build(
                    headwordRows = db.rawQuery("SELECT headword FROM words", null)
                        .use { c -> c.asSequenceStrings().toList() }.asSequence(),
                    radicalRows = db.rawQuery("SELECT character, radical FROM kanji_radicals", null)
                        .use { c -> c.asSequencePairs().toList() }.asSequence(),
                )
                db.close()
                cached = lex
                lex
            }
        }

    /** Convenience for wiring tests: build directly from collections. */
    fun lexiconFrom(headwords: List<String>, radicals: List<Pair<Char, String>>): GateLexicon =
        GateLexicon.build(headwords.asSequence(), radicals.asSequence())

    private fun android.database.Cursor.asSequenceStrings(): Sequence<String> = generateSequence {
        if (moveToNext()) getString(0) else null
    }

    private fun android.database.Cursor.asSequencePairs(): Sequence<Pair<Char, String>> = generateSequence {
        if (moveToNext()) Pair(getString(0).firstOrNull() ?: '\u0000', getString(1)) else null
    }
}
