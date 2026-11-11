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
     * Derived words are stale when the sentences the display text is built from
     * no longer match the sentence keys stored on the occurrence rows — the state
     * a text-only save intentionally leaves behind ("text saved; words stale").
     */
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
