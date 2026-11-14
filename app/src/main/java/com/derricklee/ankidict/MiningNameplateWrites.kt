package com.derricklee.ankidict

import android.content.ContentValues
import android.database.Cursor

/**
 * Nameplate claim WRITE lifecycle (roadmap D2 split, part 3): the ruling transaction and
 * the one-time startup backfill. The ledger's reads live in MiningQueries.kt; the mining
 * path's drafting inserts stay in MiningRepository.kt. Moved VERBATIM.
 */

/**
 * Records the user's verdict and settles the plate "for good":
 * correct -> the matching display lines are stripped from the photo text and their
 * occurrence rows deleted (raw ocr_blocks_json untouched; later mine/rescan passes
 * filter the sentence list through hiddenNameplateCores, so it never comes back);
 * incorrect -> nothing is removed and the claim is never re-suggested for this photo.
 * Returns the number of display lines removed (0 for incorrect or no-match).
 */
fun MiningRepository.applyNameplateVerdict(claimId: Long, correct: Boolean, now: Long = System.currentTimeMillis()): Int {
    var removed = 0
    inTransaction {
        val db = writableDatabase
        var mediaStoreId = -1L
        var core = ""
        var context = ""
        db.rawQuery(
            "SELECT photo_id, text, context_sentence FROM nameplate_claims WHERE id = ?",
            arrayOf(claimId.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                mediaStoreId = c.getLong(0)
                core = c.getString(1)
                context = c.getString(2)
            }
        }
        if (mediaStoreId < 0) return@inTransaction
        val verdict = if (correct) "correct" else "incorrect"
        db.update(
            "nameplate_claims",
            ContentValues().apply {
                put("verdict", verdict)
                put("verdict_at", now)
            },
            "id = ?",
            arrayOf(claimId.toString()),
        )
        if (!correct) return@inTransaction
        if (context.isNotEmpty()) {
            // INLINE claim: the caller applies the cut through reprocessPhotoText so word
            // occurrences are rebuilt; this method only settles the verdict.
            return@inTransaction
        }
        var text = ""
        db.rawQuery(
            "SELECT extracted_text FROM scraped_photos WHERE media_store_id = ?",
            arrayOf(mediaStoreId.toString()),
        ).use { c -> if (c.moveToFirst()) text = c.getString(0) }
        val lines = text.split("\n")
        val (hidden, kept) = lines.partition { gateCjkOnly(it) == core }
        if (hidden.isNotEmpty()) {
            removed = hidden.size
            db.update(
                "scraped_photos",
                ContentValues().apply { put("extracted_text", kept.filter(String::isNotBlank).joinToString("\n")) },
                "media_store_id = ?",
                arrayOf(mediaStoreId.toString()),
            )
            for (line in hidden) {
                db.delete(
                    "item_occurrences",
                    "photo_id = ? AND sentence = ?",
                    arrayOf(mediaStoreId.toString(), line),
                )
            }
        }
    }
    return removed
}

/**
 * One-time upgrade backfill (v8): photos mined before the claims table existed get pending
 * audit rows for plates their STORED artifacts already show — draft set from the frozen
 * ocr_blocks_json (pure offline proxy, no re-OCR) intersected with leaked lines of the
 * current extracted_text. Verdicts stay with the user; CONFLICT_IGNORE makes this idempotent,
 * so reruns after seeded rounds only ADD genuinely new pending rows.
 */
fun MiningRepository.backfillNameplateClaims(inlineLexicon: GateLexicon? = null): Int {
    var inserted = 0
    for (photo in getAllScrapedPhotos()) {
        val document = OcrDocument.fromJsonString(photo.ocrBlocksJson) ?: continue
        val drafts = runCatching { OcrCorrectionPass.draftNameplateStrings(document) }
            .getOrDefault(emptySet())
        if (drafts.isNotEmpty()) {
            val leaked = photo.extractedText.split('\n')
                .map { gateCjkOnly(it) }
                .filter { it.isNotEmpty() }
                .toSet()
            val cores = drafts intersect leaked
            if (cores.isNotEmpty()) {
                val before = nameplateClaims(photo.mediaStoreId).map { it.text to it.contextSentence }.toSet()
                insertNameplateClaims(photo.mediaStoreId, cores)
                inserted += cores.map { it to "" }.subtract(before).size
            }
        }
        if (inlineLexicon != null) {
            val proposals = photo.extractedText.split('\n').flatMap { line ->
                NameplateInlineRule.suggestions(line, inlineLexicon).map { it.plate to line }
            }
            if (proposals.isNotEmpty()) {
                val before = nameplateClaims(photo.mediaStoreId).map { it.text to it.contextSentence }.toSet()
                insertInlineNameplateClaims(photo.mediaStoreId, proposals)
                inserted += proposals.toSet().subtract(before).size
            }
        }
    }
    return inserted
}
