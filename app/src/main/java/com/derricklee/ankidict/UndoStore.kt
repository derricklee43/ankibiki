package com.derricklee.ankidict

import android.content.ContentValues

/**
 * Undo log persistence + restore primitives (cross-view undo, user go 2026-11-15).
 * All functions use the repository's current connection; when a mutation site calls
 * [recordUndo] inside its [MiningRepository.inTransaction], the log row commits atomically
 * with the mutation it describes.
 */

internal data class UndoEntry(
    val id: Long,
    val action: UndoAction,
    val label: String,
    val payload: String,
    val createdAt: Long,
    val announced: Boolean,
)

private const val UNDO_KEEP = 50

/** Insert the memento and trim the log to the newest [UNDO_KEEP] entries. */
internal fun MiningRepository.recordUndo(m: UndoMemento, now: Long = System.currentTimeMillis()) {
    val values = ContentValues().apply {
        put("action_type", m.action.name)
        put("label", m.label)
        put("payload", m.encode())
        put("created_at", now)
        put("announced", 0)
    }
    writableDatabase.insert("undo_log", null, values)
    writableDatabase.execSQL(
        "DELETE FROM undo_log WHERE id NOT IN (SELECT id FROM undo_log ORDER BY id DESC LIMIT ?)",
        arrayOf(UNDO_KEEP),
    )
}

internal fun MiningRepository.latestUndo(): UndoEntry? =
    readUndoRows("SELECT id, action_type, label, payload, created_at, announced FROM undo_log ORDER BY id DESC LIMIT 1")
        .firstOrNull()

internal fun MiningRepository.latestUnannouncedUndo(): UndoEntry? =
    readUndoRows(
        "SELECT id, action_type, label, payload, created_at, announced FROM undo_log " +
            "WHERE announced = 0 ORDER BY id DESC LIMIT 1",
    ).firstOrNull()

internal fun MiningRepository.recentUndos(limit: Int = 20): List<UndoEntry> =
    readUndoRows("SELECT id, action_type, label, payload, created_at, announced FROM undo_log ORDER BY id DESC LIMIT $limit")

private fun MiningRepository.readUndoRows(query: String): List<UndoEntry> =
    readableDatabase.rawQuery(query, null).use { c ->
        buildList {
            while (c.moveToNext()) {
                val action = UndoAction.values().firstOrNull { it.name == c.getString(1) } ?: continue
                add(UndoEntry(c.getLong(0), action, c.getString(2), c.getString(3), c.getLong(4), c.getInt(5) != 0))
            }
        }
    }

internal fun MiningRepository.markUndoAnnounced(id: Long) {
    writableDatabase.update("undo_log", ContentValues().apply { put("announced", 1) }, "id = ?", arrayOf(id.toString()))
}

internal fun MiningRepository.deleteUndo(id: Long) {
    writableDatabase.delete("undo_log", "id = ?", arrayOf(id.toString()))
}

// ---- snapshot readers (before-state capture) ----

internal fun MiningRepository.undoPhotoRow(mediaStoreId: Long): UndoPhotoRow? =
    readableDatabase.rawQuery(
        "SELECT media_store_id, extracted_text, language_tag, status, modified_by_user_flag, " +
            "is_gold, flag_color, ocr_blocks_json FROM scraped_photos WHERE media_store_id = ?",
        arrayOf(mediaStoreId.toString()),
    ).use { c ->
        if (!c.moveToFirst()) return null
        UndoPhotoRow(
            mediaStoreId = c.getLong(0), extractedText = c.getString(1), languageTag = c.getString(2),
            status = c.getString(3), modifiedByUserFlag = c.getInt(4) != 0, isGold = c.getInt(5) != 0,
            flagColor = c.getString(6), ocrBlocksJson = c.getString(7),
        )
    }

private const val OCC_COLS =
    "id, language_tag, item_type, expression, photo_id, sentence, created_at, state, modified_by_user_flag, is_manual, media_id"

private fun MiningRepository.readOccRows(query: String, args: Array<String>): List<UndoOccRow> =
    readableDatabase.rawQuery(query, args).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(UndoOccRow(
                    id = c.getLong(0), languageTag = c.getString(1), itemType = c.getString(2),
                    expression = c.getString(3), photoId = c.getLong(4), sentence = c.getString(5),
                    createdAt = c.getLong(6), state = c.getString(7),
                    modifiedByUserFlag = c.getInt(8) != 0, isManual = c.getInt(9) != 0,
                    mediaId = if (c.isNull(10)) null else c.getLong(10),
                ))
            }
        }
    }

/** Batch capture by primary key (chunked well under SQLite's 999 bind limit). */
internal fun MiningRepository.undoOccRowsByIds(ids: Collection<Long>): List<UndoOccRow> {
    if (ids.isEmpty()) return emptyList()
    val out = ArrayList<UndoOccRow>(ids.size)
    for (chunk in ids.distinct().chunked(500)) {
        val placeholders = chunk.joinToString(",") { "?" }
        out += readOccRows(
            "SELECT $OCC_COLS FROM item_occurrences WHERE id IN ($placeholders) ORDER BY id",
            chunk.map { it.toString() }.toTypedArray(),
        )
    }
    return out
}

/** True if any occurrence id in [ids] currently exists (stale guard for delta undo). */
internal fun MiningRepository.anyOccurrenceIdExists(ids: Collection<Long>): Boolean {
    if (ids.isEmpty()) return false
    for (chunk in ids.distinct().chunked(500)) {
        val placeholders = chunk.joinToString(",") { "?" }
        val hit = readableDatabase.rawQuery(
            "SELECT 1 FROM item_occurrences WHERE id IN ($placeholders) LIMIT 1",
            chunk.map { it.toString() }.toTypedArray(),
        ).use { it.moveToFirst() }
        if (hit) return true
    }
    return false
}

/** All rows of one photo (the wide-capture scope for destructive actions: ruling, photo delete). */
internal fun MiningRepository.undoOccRowsForPhoto(photoId: Long): List<UndoOccRow> =
    readOccRows("SELECT $OCC_COLS FROM item_occurrences WHERE photo_id = ? ORDER BY id", arrayOf(photoId.toString()))

/** Media-scope capture (media_refs.id + sentence) for MEDIA_WORDS_EDIT / SENTENCE_DELETE. */
internal fun MiningRepository.undoOccRowsForMedia(
    mediaRefId: Long,
    sentence: String,
): List<UndoOccRow> = readOccRows(
    "SELECT $OCC_COLS FROM item_occurrences WHERE media_id = ? AND sentence = ? ORDER BY id",
    arrayOf(mediaRefId.toString(), sentence),
)

/** True while the media ref is the ACTIVE one for its source (v15 I1: re-mine retires
 *  the old ref, which must block restoring occurrence rows onto it). */
internal fun MiningRepository.mediaRefActive(mediaRefId: Long): Boolean =
    readableDatabase.rawQuery(
        "SELECT 1 FROM media_refs WHERE id = ? AND active = 1", arrayOf(mediaRefId.toString()),
    ).use { it.moveToFirst() }

/** Rows of one canonical item currently in [state] — the SET_ITEM_VISIBILITY flip capture. */
internal fun MiningRepository.undoItemOccurrenceIdsInState(
    languageTag: String, itemType: String, expression: String, state: String,
): List<UndoOccRow> =
    readOccRows(
        "SELECT $OCC_COLS FROM item_occurrences " +
            "WHERE language_tag = ? AND item_type = ? AND expression = ? AND state = ? ORDER BY id",
        arrayOf(languageTag, itemType, expression, state),
    )

/** Rows of the given sentence keys on one photo (the targeted-scope capture for edits). */
internal fun MiningRepository.undoOccRowsForSentences(photoId: Long, sentences: Collection<String>): List<UndoOccRow> {
    if (sentences.isEmpty()) return emptyList()
    val placeholders = sentences.joinToString(",") { "?" }
    return readOccRows(
        "SELECT $OCC_COLS FROM item_occurrences WHERE photo_id = ? AND sentence IN ($placeholders) ORDER BY id",
        arrayOf(photoId.toString()) + sentences.map { it },
    )
}

internal fun MiningRepository.undoOccRow(id: Long): UndoOccRow? =
    readOccRows("SELECT $OCC_COLS FROM item_occurrences WHERE id = ?", arrayOf(id.toString())).firstOrNull()

internal fun MiningRepository.undoItemVisibility(languageTag: String, itemType: String, expression: String): Pair<String, Boolean>? =
    readableDatabase.rawQuery(
        "SELECT visibility_state, is_hidden FROM extracted_items " +
            "WHERE language_tag = ? AND item_type = ? AND expression = ?",
        arrayOf(languageTag, itemType, expression),
    ).use { c -> if (c.moveToFirst()) Pair(c.getString(0), c.getInt(1) != 0) else null }

internal fun MiningRepository.undoSentenceRanks(languageTag: String, itemType: String, expression: String): String? =
    readableDatabase.rawQuery(
        "SELECT sentence_ranks FROM extracted_items " +
            "WHERE language_tag = ? AND item_type = ? AND expression = ?",
        arrayOf(languageTag, itemType, expression),
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null } // null = no ranks (fresh or undone)

internal fun MiningRepository.undoPhotoStatus(mediaStoreId: Long): String? =
    readableDatabase.rawQuery("SELECT status FROM scraped_photos WHERE media_store_id = ?", arrayOf(mediaStoreId.toString()))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

internal fun MiningRepository.undoPhotoText(mediaStoreId: Long): String? =
    readableDatabase.rawQuery("SELECT extracted_text FROM scraped_photos WHERE media_store_id = ?", arrayOf(mediaStoreId.toString()))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

internal fun MiningRepository.undoGold(mediaStoreId: Long): Boolean =
    readableDatabase.rawQuery("SELECT is_gold FROM scraped_photos WHERE media_store_id = ?", arrayOf(mediaStoreId.toString()))
        .use { c -> c.moveToFirst() && c.getInt(0) != 0 }

internal fun MiningRepository.undoOccState(id: Long): String? =
    readableDatabase.rawQuery("SELECT state FROM item_occurrences WHERE id = ?", arrayOf(id.toString()))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

// ---- restore primitives ----

/** Restore a whole photo row exactly as captured (all user columns carried, per v11 rule 7). */
internal fun MiningRepository.restorePhotoRow(p: UndoPhotoRow) {
    val values = ContentValues().apply {
        put("extracted_text", p.extractedText)
        put("language_tag", p.languageTag)
        put("status", p.status)
        put("modified_by_user_flag", p.modifiedByUserFlag)
        put("is_gold", p.isGold)
        if (p.flagColor == null) putNull("flag_color") else put("flag_color", p.flagColor)
        if (p.ocrBlocksJson == null) putNull("ocr_blocks_json") else put("ocr_blocks_json", p.ocrBlocksJson)
    }
    writableDatabase.update("scraped_photos", values, "media_store_id = ?", arrayOf(p.mediaStoreId.toString()))
}

/** Delete the current rows of the given keys then re-insert the snapshot with its ORIGINAL ids. */
internal fun MiningRepository.restoreOccurrenceScope(photoId: Long, sentences: Collection<String>, snapshot: List<UndoOccRow>) {
    if (sentences.isNotEmpty()) {
        val placeholders = sentences.joinToString(",") { "?" }
        writableDatabase.delete(
            "item_occurrences", "photo_id = ? AND sentence IN ($placeholders)",
            arrayOf(photoId.toString()) + sentences.toList(),
        )
    }
    for (r in snapshot) {
        val values = ContentValues().apply {
            put("id", r.id)
            put("language_tag", r.languageTag)
            put("item_type", r.itemType)
            put("expression", r.expression)
            put("photo_id", r.photoId)
            put("sentence", r.sentence)
            put("created_at", r.createdAt)
            put("state", r.state)
            put("modified_by_user_flag", r.modifiedByUserFlag)
            put("is_manual", r.isManual)
        }
        writableDatabase.insertWithOnConflict("item_occurrences", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }
}

internal fun MiningRepository.restoreOccurrenceScopeAll(photoId: Long, snapshot: List<UndoOccRow>) {
    writableDatabase.delete("item_occurrences", "photo_id = ?", arrayOf(photoId.toString()))
    for (r in snapshot) {
        writableDatabase.insertWithOnConflict(
            "item_occurrences", null, undoOccValues(r),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

/** Media-scope restore for MEDIA_WORDS_EDIT: the (media_id, sentence) pair is replaced
 *  byte-identically by the snapshot (rows added after the capture drop out; ids return).
 *  photo_id is written NULL for non-photo rows (v15 shape: -1 sentinel never appears in
 *  UndoOccRow because the capture reads 0 for NULL). */
internal fun MiningRepository.restoreOccurrenceScopeByMedia(
    mediaRefId: Long,
    sentence: String,
    snapshot: List<UndoOccRow>,
) {
    writableDatabase.delete(
        "item_occurrences", "media_id = ? AND sentence = ?",
        arrayOf(mediaRefId.toString(), sentence),
    )
    for (r in snapshot) {
        val values = undoOccValues(r).apply { put("media_id", mediaRefId) }
        writableDatabase.insertWithOnConflict(
            "item_occurrences", null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

/** Delta restore for USER_WORD_REPARSE: drop newly inserted ids, re-insert deleted rows
 *  with their exact original ids, media_id, photo_id, and triage state. */
internal fun MiningRepository.restoreReparsedOccurrences(
    insertedIds: List<Long>,
    deletedRows: List<UndoOccRow>,
) {
    for (chunk in insertedIds.distinct().chunked(500)) {
        val placeholders = chunk.joinToString(",") { "?" }
        writableDatabase.delete(
            "item_occurrences",
            "id IN ($placeholders)",
            chunk.map { it.toString() }.toTypedArray(),
        )
    }
    for (r in deletedRows) {
        writableDatabase.insertWithOnConflict(
            "item_occurrences", null, undoOccValues(r),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

private fun undoOccValues(r: UndoOccRow) = ContentValues().apply {
    put("id", r.id)
    put("language_tag", r.languageTag)
    put("item_type", r.itemType)
    put("expression", r.expression)
    if (r.photoId > 0L) put("photo_id", r.photoId) else putNull("photo_id")
    put("sentence", r.sentence)
    put("created_at", r.createdAt)
    put("state", r.state)
    put("modified_by_user_flag", r.modifiedByUserFlag)
    put("is_manual", r.isManual)
    if (r.mediaId != null) put("media_id", r.mediaId)
}

internal fun MiningRepository.restoreItemVisibility(languageTag: String, itemType: String, expression: String, visibilityState: String, isHidden: Boolean) {
    writableDatabase.update(
        "extracted_items",
        ContentValues().apply {
            put("visibility_state", visibilityState)
            put("is_hidden", isHidden)
        },
        "language_tag = ? AND item_type = ? AND expression = ?",
        arrayOf(languageTag, itemType, expression),
    )
}

internal fun MiningRepository.restoreOccurrenceState(id: Long, state: String): Int =
    writableDatabase.update(
        "item_occurrences", ContentValues().apply { put("state", state) }, "id = ?", arrayOf(id.toString()),
    )

internal fun MiningRepository.restoreSentenceRanks(languageTag: String, itemType: String, expression: String, ranksJson: String?) {
    val values = ContentValues().apply {
        if (ranksJson.isNullOrEmpty()) putNull("sentence_ranks") else put("sentence_ranks", ranksJson)
    }
    writableDatabase.update(
        "extracted_items", values, "language_tag = ? AND item_type = ? AND expression = ?",
        arrayOf(languageTag, itemType, expression),
    )
}

internal fun MiningRepository.restoreClaimPending(claimId: Long) {
    writableDatabase.update(
        "nameplate_claims",
        ContentValues().apply { putNull("verdict"); putNull("verdict_at") },
        "id = ?", arrayOf(claimId.toString()),
    )
}

internal fun MiningRepository.restorePhotoGold(mediaStoreId: Long, gold: Boolean) {
    writableDatabase.update(
        "scraped_photos",
        ContentValues().apply { put("is_gold", gold) },
        "media_store_id = ?", arrayOf(mediaStoreId.toString()),
    )
}
