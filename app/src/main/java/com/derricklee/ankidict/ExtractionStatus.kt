package com.derricklee.ankidict

/**
 * Pure computations behind the photo-detail transparency UI (docs/roadmap.md §3):
 * lets the user see what happened to a photo's derived state and what a
 * reprocess would replace BEFORE committing, without reading DB JSON or logs.
 */
object ExtractionStatus {
    /** Aggregated occurrence rows for one sentence key on one photo. */
    data class SentenceRows(
        val sentence: String,
        val rowCount: Int,
        val reviewedCount: Int,
        val deletedCount: Int,
        val userEdited: Boolean,
    )

    /**
     * Derived words are stale when refreshing them WOULD CHANGE something:
     * a sentence the current text parses to yields candidates but has no rows
     * (text saved without reprocessing), or rows reference sentences the current
     * text no longer parses to (stale keys). A display line that yields NO
     * candidates at all (e.g. 「はい。」 — nothing to derive) can never have row keys,
     * so it must not flag — the old raw-line-vs-keys set comparison did, which
     * left a permanently-unclearable chip on zero-candidate and merged-line edits
     * (user report 2026-11-13: 1136/1144/1158/1168/970/1156).
     *
     * @param parsedSentences sentences the CURRENT display text re-parses to (refresh target)
     * @param rowSentences distinct sentence keys on this photo's occurrence rows
     * @param sentencesWithCandidates subset of [parsedSentences] that would produce ≥1 word/grammar
     */
    fun derivedWordsStale(
        parsedSentences: Set<String>,
        rowSentences: Set<String>,
        sentencesWithCandidates: Set<String>,
    ): Boolean =
        sentencesWithCandidates.any { it !in rowSentences } || rowSentences.any { it !in parsedSentences }

    /** Legacy raw-lines comparison — kept for reference; prefer [derivedWordsStale] via the runner. */
    fun isStale(displaySentences: List<String>, rows: Collection<SentenceRows>): Boolean =
        displaySentences.filter { it.isNotBlank() }.toSet() != rows.map { it.sentence }.toSet()

    data class Preview(
        val keptSentences: Int,
        val addedSentences: Int,
        val removedSentences: Int,
        val triagedRowsInRemoved: Int,
        val editedSentencesInRemoved: Int,
    )

    /** Impact of a words-reprocess, computed from row state BEFORE any write. */
    fun preview(newSentences: List<String>, rows: Collection<SentenceRows>): Preview {
        val newSet = newSentences.filter { it.isNotBlank() }.toSet()
        val oldSet = rows.map { it.sentence }.toSet()
        val dropped = rows.filter { it.sentence !in newSet }
        return Preview(
            keptSentences = newSet.count { it in oldSet },
            addedSentences = newSet.count { it !in oldSet },
            removedSentences = dropped.map { it.sentence }.distinct().size,
            triagedRowsInRemoved = dropped.sumOf { it.reviewedCount + it.deletedCount },
            editedSentencesInRemoved = dropped.count { it.userEdited },
        )
    }

    fun describeProvider(document: OcrDocument?): String = when (val p = document?.provider) {
        "cloud-vision" -> "Cloud Vision"
        "on-device" -> "on-device (ML Kit)"
        "on-device-preflight" -> "on-device (image pre-scan)"
        null -> ""
        else -> p
    }
}
