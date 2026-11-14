package com.derricklee.ankidict

import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore

/**
 * READ side of the mining corpus (roadmap D2, 2026-11-14: mechanical read/write split).
 * Photo/occurrence/item/claim/dashboard queries moved VERBATIM from MiningRepository as
 * extension functions, so every call site and every test body is unchanged. Writes,
 * reconcile rules, and the nameplate ruling transaction stay in MiningRepository.kt /
 * MiningNameplateWrites.kt; DDL in MiningSchema.kt. Result data classes remain nested in
 * MiningRepository (referenced here as MiningRepository.X) so existing imports hold.
 * `readableDatabase`/`writableDatabase` are SQLiteOpenHelper members; `appContext` is
 * internal now that extensions read it.
 */

/** Returns the [limit] most recently added scraped photos with status KEEP. */
fun MiningRepository.getRecentPhotos(limit: Int = 3): List<ScrapedPhoto> =
    queryScrapedPhotos(
        selection = "status = ?",
        selectionArgs = arrayOf(PhotoStatus.KEEP.name),
        orderBy = "date_added DESC, media_store_id DESC",
        limit = limit.toString(),
    )

/** Returns all scraped photos with status KEEP, newest first. */
fun MiningRepository.getAllScrapedPhotos(): List<ScrapedPhoto> =
    queryScrapedPhotos(
        selection = "status = ?",
        selectionArgs = arrayOf(PhotoStatus.KEEP.name),
        orderBy = "date_added DESC, media_store_id DESC",
        limit = null,
    )

/**
 * Library grid filters (roadmap B3, user spec 2026-11-14: 未読 / 金 / 言語 facets — the
 * stale facet was explicitly CUT: judging staleness grid-wide means re-running each photo's
 * pipeline, which is exactly what the per-photo chip already does lazily). Facets AND-join;
 * an unchecked facet means "no constraint". 未読 mirrors the inbox's ACTIVE-occurrence
 * semantics verbatim (getActiveOccurrences): an UNSEEN occurrence row of an active, non-hidden
 * canonical item — word or grammar, either language — on a KEEP photo.
 */
fun MiningRepository.getScrapedPhotosFiltered(unseenOnly: Boolean, goldOnly: Boolean, tags: Set<LanguageTag>): List<ScrapedPhoto> {
    val selection = StringBuilder("status = ?")
    val args = mutableListOf(PhotoStatus.KEEP.name)
    if (unseenOnly) {
        selection.append(
            " AND EXISTS (SELECT 1 FROM item_occurrences o" +
                " JOIN extracted_items i" +
                " ON o.language_tag = i.language_tag AND o.item_type = i.item_type AND o.expression = i.expression" +
                " WHERE o.photo_id = scraped_photos.media_store_id" +
                " AND o.state = 'UNSEEN' AND i.visibility_state = 'ACTIVE' AND i.is_hidden = 0)",
        )
    }
    if (goldOnly) selection.append(" AND is_gold = 1")
    val tagList = tags.toList()
    if (tagList.isNotEmpty() && tagList.size < LanguageTag.entries.size) {
        selection.append(" AND language_tag IN (").append(tagList.joinToString(",") { "?" }).append(")")
        args.addAll(tagList.map { it.name })
    }
    return queryScrapedPhotos(selection.toString(), args.toTypedArray(), "date_added DESC, media_store_id DESC", null)
}

/** Returns the [limit] most recently scraped KEEP photos for [languageTag]. */
fun MiningRepository.getRecentKeepPhotosByTag(languageTag: LanguageTag, limit: Int): List<ScrapedPhoto> =
    queryScrapedPhotos(
        selection = "status = ? AND language_tag = ?",
        selectionArgs = arrayOf(PhotoStatus.KEEP.name, languageTag.name),
        orderBy = "date_added DESC, media_store_id DESC",
        limit = limit.toString(),
    )

/** Returns all `media_store_id`s currently recorded in `scraped_photos` regardless of status. */
fun MiningRepository.getAllProcessedMediaStoreIds(): Set<Long> {
    val ids = HashSet<Long>()
    readableDatabase.rawQuery(
        "SELECT media_store_id FROM scraped_photos",
        emptyArray(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            ids.add(cursor.getLong(0))
        }
    }
    return ids
}

/** Returns true if a canonical `extracted_items` row already exists for `(languageTag, itemType, expression)`. */
fun MiningRepository.hasExtractedItem(
    languageTag: LanguageTag,
    itemType: ItemType,
    expression: String,
): Boolean {
    readableDatabase.rawQuery(
        "SELECT 1 FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ? LIMIT 1",
        arrayOf(languageTag.name, itemType.name, expression),
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

/** Returns true if the canonical item already has a non-null `anki_note_id`. */
fun MiningRepository.hasLinkedAnkiCard(
    languageTag: LanguageTag,
    itemType: ItemType,
    expression: String,
): Boolean {
    readableDatabase.rawQuery(
        "SELECT 1 FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ? AND anki_note_id IS NOT NULL LIMIT 1",
        arrayOf(languageTag.name, itemType.name, expression),
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

/**
 * Queries the device camera roll from [MediaStore.Images.Media.EXTERNAL_CONTENT_URI] ordered
 * newest first, excluding any photo the user explicitly marked as [PhotoStatus.DELETED] in
 * AnkiBiki, while preserving extracted text if the photo was already scraped.
 */
fun MiningRepository.getCameraRollPhotos(): List<ScrapedPhoto> {
    val dbByMediaId = mutableMapOf<Long, ScrapedPhoto>()
    readableDatabase.rawQuery(
        "SELECT media_store_id, content_uri, date_added, language_tag, extracted_text, " +
            "ocr_blocks_json, status, duplicate_of_photo_id, modified_by_user_flag, is_gold FROM scraped_photos",
        emptyArray(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val photo = cursor.toScrapedPhoto()
            dbByMediaId[photo.mediaStoreId] = photo
        }
    }

    val results = mutableListOf<ScrapedPhoto>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
    )
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

    runCatching {
        appContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dateAdded = cursor.getLong(dateCol)
                val existing = dbByMediaId[id]
                if (existing?.status == PhotoStatus.DELETED) continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                results.add(
                    existing ?: ScrapedPhoto(
                        mediaStoreId = id,
                        contentUri = uri,
                        dateAdded = dateAdded,
                        languageTag = LanguageTag.NA,
                        extractedText = "",
                        ocrBlocksJson = null,
                        status = PhotoStatus.IGNORED,
                        duplicateOfPhotoId = null,
                    ),
                )
            }
        }
    }
    return results
}

/** Looks up a photo by [mediaStoreId] in `mining.db`, falling back to `MediaStore` if unscraped. */
fun MiningRepository.getPhoto(mediaStoreId: Long): ScrapedPhoto? {
    val fromDb = queryScrapedPhotos(
        selection = "media_store_id = ?",
        selectionArgs = arrayOf(mediaStoreId.toString()),
        orderBy = null,
        limit = "1",
    ).firstOrNull()
    if (fromDb != null) return fromDb

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
    )
    return runCatching {
        appContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media._ID} = ?",
            arrayOf(mediaStoreId.toString()),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            val dateAdded = cursor.getLong(1)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
            ScrapedPhoto(
                mediaStoreId = id,
                contentUri = uri,
                dateAdded = dateAdded,
                languageTag = LanguageTag.NA,
                extractedText = "",
                ocrBlocksJson = null,
                status = PhotoStatus.IGNORED,
                duplicateOfPhotoId = null,
            )
        }
    }.getOrNull()
}

/** Gold-set membership straight from the flag (what build_gold_fixtures.py selects on). */
fun MiningRepository.getGoldPhotoIds(): List<Long> =
    readableDatabase.rawQuery(
        "SELECT media_store_id FROM scraped_photos WHERE is_gold = 1 ORDER BY media_store_id",
        null,
    ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }

fun MiningRepository.nameplateClaims(mediaStoreId: Long): List<MiningRepository.NameplateClaim> =
    readableDatabase.rawQuery(
        "SELECT id, text, context_sentence, verdict FROM nameplate_claims WHERE photo_id = ? ORDER BY id",
        arrayOf(mediaStoreId.toString()),
    ).use { c ->
        generateSequence {
            if (c.moveToNext()) MiningRepository.NameplateClaim(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null
        }.toList()
    }

fun MiningRepository.pendingNameplateClaims(): List<MiningRepository.PendingNameplateClaim> =
    readableDatabase.rawQuery(
        "SELECT c.id, c.text, c.context_sentence, c.source, c.photo_id, p.content_uri, p.date_added, p.language_tag " +
            "FROM nameplate_claims c JOIN scraped_photos p ON p.media_store_id = c.photo_id " +
            "WHERE c.verdict IS NULL ORDER BY p.date_added DESC, c.id ASC",
        null,
    ).use { c ->
        generateSequence {
            if (c.moveToNext()) {
                MiningRepository.PendingNameplateClaim(
                    claimId = c.getLong(0),
                    text = c.getString(1),
                    contextSentence = c.getString(2),
                    source = c.getString(3),
                    mediaStoreId = c.getLong(4),
                    contentUri = c.getString(5),
                    dateAdded = c.getLong(6),
                    languageTag = runCatching { LanguageTag.valueOf(c.getString(7)) }.getOrNull()
                        ?: LanguageTag.JAPANESE,
                )
            } else null
        }.toList()
    }

/** Badge count for the queue entry — the same `verdict IS NULL` set the queue lists. */
fun MiningRepository.countPendingNameplateClaims(): Int =
    readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM nameplate_claims WHERE verdict IS NULL",
        null,
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

fun MiningRepository.getDashboardStats(): MiningRepository.DashboardStats {
    val db = readableDatabase
    fun count(sql: String): Int =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    return MiningRepository.DashboardStats(
        totalPhotos = count("SELECT COUNT(*) FROM scraped_photos"),
        keepPhotos = count("SELECT COUNT(*) FROM scraped_photos WHERE status = 'KEEP'"),
        goldPhotos = count("SELECT COUNT(*) FROM scraped_photos WHERE is_gold = 1"),
        goldUserEditedPhotos =
            count("SELECT COUNT(*) FROM scraped_photos WHERE is_gold = 1 AND modified_by_user_flag = 1"),
        correctionsApplied = count("SELECT COUNT(*) FROM ocr_corrections WHERE decision = 'applied'"),
        correctionsRejected = count("SELECT COUNT(*) FROM ocr_corrections WHERE decision = 'rejected'"),
        correctionsExcluded = count("SELECT COUNT(*) FROM ocr_corrections WHERE decision = 'excluded'"),
    )
}

/** CJK projections of confirmed STANDALONE plates — the sentence filter for future mine/rescan passes. */
fun MiningRepository.hiddenNameplateCores(mediaStoreId: Long): Set<String> =
    readableDatabase.rawQuery(
        "SELECT text FROM nameplate_claims WHERE photo_id = ? AND verdict = 'correct' AND context_sentence = ''",
        arrayOf(mediaStoreId.toString()),
    ).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
    }

/** Confirmed INLINE cuts (plate, sentence): rescan re-excises these before display/persistence. */
fun MiningRepository.hiddenInlineClaims(mediaStoreId: Long): List<MiningRepository.NameplateClaim> =
    readableDatabase.rawQuery(
        "SELECT id, text, context_sentence, verdict FROM nameplate_claims " +
            "WHERE photo_id = ? AND verdict = 'correct' AND context_sentence != ''",
        arrayOf(mediaStoreId.toString()),
    ).use { c ->
        generateSequence {
            if (c.moveToNext()) MiningRepository.NameplateClaim(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null
        }.toList()
    }

/**
 * Nameplate strings the cloud-gate should treat as plate-adjacent (roadmap shipping note,
 * spike 004 verdict): the recurrence lexicon (H3 — the ONLY zero-casualty signal measured)
 * plus every ledger-confirmed plate. REPLACES the A0 structural proxy as the gate's upstream
 * exclusion input: A0 flagged 4 genuine content lines (ヘヘヘ-class), which as gate input
 * silently suppressed correct fixes near content. Derived + recomputable (G3): pure function
 * of stored texts and user verdicts.
 */
fun MiningRepository.nameplateGateStrings(): Set<String> {
    val counts = HashMap<String, MutableSet<Long>>()
    for (photo in getAllScrapedPhotos()) {
        for (line in photo.extractedText.split('\n')) {
            val proj = gateCjkOnly(line)
            if (proj.length !in 2..8) continue
            if (line.any { it in "。．！？!?.…" }) continue
            counts.getOrPut(proj) { HashSet() }.add(photo.mediaStoreId)
        }
    }
    val recurring = counts.filterValues { it.size >= 2 }.keys
    val confirmed = readableDatabase.rawQuery(
        "SELECT DISTINCT text FROM nameplate_claims WHERE verdict = 'correct'", null,
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
    return recurring + confirmed
}

/** Sentence provenance is shared by every word/grammar occurrence of the same photo text. */
fun MiningRepository.getSentenceModificationFlags(photoId: Long): Map<String, Boolean> {
    val flags = mutableMapOf<String, Boolean>()
    readableDatabase.rawQuery(
        "SELECT sentence, MAX(modified_by_user_flag) FROM item_occurrences WHERE photo_id = ? GROUP BY sentence",
        arrayOf(photoId.toString()),
    ).use { cursor ->
        while (cursor.moveToNext()) flags[cursor.getString(0)] = cursor.getInt(1) != 0
    }
    return flags
}

/** Aggregated occurrence rows per sentence key, for staleness detection and reprocess previews. */
fun MiningRepository.getOccurrenceSentenceRows(photoId: Long): List<ExtractionStatus.SentenceRows> {
    val rows = mutableListOf<ExtractionStatus.SentenceRows>()
    readableDatabase.rawQuery(
        "SELECT sentence, COUNT(*), SUM(state = 'SEEN'), SUM(state = 'DELETED'), MAX(modified_by_user_flag)" +
            " FROM item_occurrences WHERE photo_id = ? GROUP BY sentence",
        arrayOf(photoId.toString()),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            rows.add(
                ExtractionStatus.SentenceRows(
                    sentence = cursor.getString(0),
                    rowCount = cursor.getInt(1),
                    reviewedCount = cursor.getInt(2),
                    deletedCount = cursor.getInt(3),
                    userEdited = cursor.getInt(4) != 0,
                ),
            )
        }
    }
    return rows
}

fun MiningRepository.getSentenceCandidates(photoId: Long, sentence: String): List<MiningRepository.SentenceCandidate> =
    readableDatabase.rawQuery(
        """SELECT MAX(id), item_type, expression FROM item_occurrences
           WHERE photo_id = ? AND sentence = ? AND state != 'DELETED'
           GROUP BY item_type, expression""",
        arrayOf(photoId.toString(), sentence),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val type = runCatching { ItemType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                add(MiningRepository.SentenceCandidate(c.getLong(0), type, c.getString(2)))
            }
        }
    }.sortedWith(
        compareBy<MiningRepository.SentenceCandidate> { if (it.itemType == ItemType.WORD) 0 else 1 }
            .thenBy { it.expression },
    )

/** Rows for one `(photo, sentence)` pair, newest first — the unit of reprocessing reconciliation. */
internal fun MiningRepository.occurrenceRowsForSentence(photoId: Long, sentence: String): List<MiningRepository.OccurrenceRow> =
    readableDatabase.query(
        "item_occurrences",
        arrayOf("id", "item_type", "expression", "state", "created_at", "modified_by_user_flag", "is_manual"),
        "photo_id = ? AND sentence = ?",
        arrayOf(photoId.toString(), sentence),
        null, null, "created_at DESC, id DESC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    MiningRepository.OccurrenceRow(
                        id = c.getLong(0), itemType = c.getString(1), expression = c.getString(2),
                        state = c.getString(3), createdAt = c.getLong(4),
                        modifiedByUserFlag = c.getInt(5) != 0,
                        isManual = c.getInt(6) != 0,
                    ),
                )
            }
        }
    }

/** States present for (photo, sentence, expression): DELETED-only means a recoverable add. */
fun MiningRepository.occurrenceStatesForExpression(photoId: Long, sentence: String, expression: String): Set<String> =
    readableDatabase.rawQuery(
        "SELECT DISTINCT state FROM item_occurrences WHERE photo_id = ? AND sentence = ? AND expression = ?",
        arrayOf(photoId.toString(), sentence, expression),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }

/**
 * Returns occurrences matching [query]. Each sentence occurrence is its own
 * row, while `occurrenceCount` reflects the total non-deleted occurrences of that canonical item.
 */
fun MiningRepository.getActiveOccurrences(query: OccurrenceQuery): List<ExtractedOccurrenceRow> {
    val activeVisibilities = query.visibilities.ifEmpty { setOf(ItemVisibility.ACTIVE) }
    val whereClauses = mutableListOf(
        "o.language_tag = ?",
        "o.item_type = ?",
        "p.status = ?",
    )
    val args = mutableListOf(
        query.languageTag.name,
        query.itemType.name,
        PhotoStatus.KEEP.name,
    )

    val visibilitySubclauses = mutableListOf<String>()
    if (ItemVisibility.ACTIVE in activeVisibilities) {
        visibilitySubclauses.add("(i.visibility_state = 'ACTIVE' AND i.is_hidden = 0 AND o.state = 'UNSEEN')")
    }
    if (ItemVisibility.HIDDEN in activeVisibilities) {
        visibilitySubclauses.add("((i.visibility_state = 'HIDDEN' OR (i.visibility_state = 'ACTIVE' AND i.is_hidden != 0)) AND o.state IN ('UNSEEN', 'SEEN'))")
    }
    if (ItemVisibility.ARCHIVED in activeVisibilities) {
        visibilitySubclauses.add("(i.visibility_state = 'ARCHIVED' AND o.state IN ('UNSEEN', 'SEEN'))")
    }
    whereClauses.add("(${visibilitySubclauses.joinToString(" OR ")})")

    if (OccurrenceFilter.HAS_ANKI_CARD in query.filters) {
        whereClauses.add("i.anki_note_id IS NOT NULL")
    }
    if (OccurrenceFilter.NO_ANKI_CARD in query.filters) {
        whereClauses.add("i.anki_note_id IS NULL")
    }

    val activeSortCriteria = query.sortCriteria.filter { it.second != SortDirection.OFF }
    val orderBySql = if (activeSortCriteria.isNotEmpty()) {
        val parts = mutableListOf<String>()
        for ((field, dir) in activeSortCriteria) {
            val sqlDir = if (dir == SortDirection.ASC) "ASC" else "DESC"
            when (field) {
                SortField.OCCURRENCE_COUNT -> parts.add("occurrence_count $sqlDir")
                SortField.DATE_ADDED -> parts.add("o.created_at $sqlDir")
                SortField.CORPUS_RANK -> {
                    parts.add("(i.corpus_rank IS NULL) ASC")
                    parts.add("i.corpus_rank $sqlDir")
                }
            }
        }
        parts.add("o.created_at DESC")
        parts.add("o.id DESC")
        parts.joinToString(", ")
    } else {
        when (query.sortOrder) {
            OccurrenceSortOrder.NEWEST_FIRST -> "o.created_at DESC, o.id DESC"
            OccurrenceSortOrder.OCCURRENCE_COUNT_DESC -> "occurrence_count DESC, o.created_at DESC, o.id DESC"
            OccurrenceSortOrder.CORPUS_RANK_ASC ->
                "(i.corpus_rank IS NULL) ASC, i.corpus_rank ASC, o.created_at DESC, o.id DESC"
            OccurrenceSortOrder.LEVEL_ASC ->
                "(i.level_badge IS NULL) ASC, i.level_badge ASC, o.created_at DESC, o.id DESC"
        }
    }

    val limitClause = query.limit?.let { " LIMIT $it" }.orEmpty()

    val sql = """
        SELECT
            o.id,
            o.language_tag,
            o.item_type,
            o.expression,
            i.deck_name,
            (
                SELECT COUNT(*)
                FROM item_occurrences o2
                JOIN scraped_photos p2 ON o2.photo_id = p2.media_store_id
                WHERE o2.language_tag = o.language_tag
                  AND o2.item_type = o.item_type
                  AND o2.expression = o.expression
                  AND o2.state IN ('UNSEEN', 'SEEN')
                  AND p2.status = 'KEEP'
            ) AS occurrence_count,
            i.level_badge,
            i.corpus_rank,
            o.created_at,
            i.kana
        FROM item_occurrences o
        JOIN extracted_items i
          ON o.language_tag = i.language_tag
         AND o.item_type = i.item_type
         AND o.expression = i.expression
        JOIN scraped_photos p
          ON o.photo_id = p.media_store_id
        WHERE ${whereClauses.joinToString(" AND ")}
        ORDER BY $orderBySql$limitClause
    """.trimIndent()

    val results = mutableListOf<ExtractedOccurrenceRow>()
    readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
        while (cursor.moveToNext()) {
            results.add(
                ExtractedOccurrenceRow(
                    occurrenceId = cursor.getLong(0),
                    languageTag = LanguageTag.valueOf(cursor.getString(1)),
                    itemType = ItemType.valueOf(cursor.getString(2)),
                    expression = cursor.getString(3),
                    deckName = cursor.getStringOrNull(4),
                    occurrenceCount = cursor.getInt(5),
                    levelBadge = cursor.getStringOrNull(6),
                    corpusRank = cursor.getIntOrNull(7),
                    createdAt = cursor.getLong(8),
                    kana = cursor.getStringOrNull(9),
                ),
            )
        }
    }
    return results
}

/**
 * Words (definition) tab — user spec 2026-11-13: exactly one row per canonical item,
 * HIDDEN items included, ARCHIVED items excluded. SQLite's BINARY collation cannot do
 * gojūon/pinyin order, so the list comes back unsorted from SQL and [ItemOrdering] is
 * applied here. `occurrenceId` is the newest live occurrence on a KEPT photo (detail-view
 * tap target, mirrors getActiveOccurrences' state filter), -1 when none exists.
 */
fun MiningRepository.getCanonicalItemDefinitions(
    languageTag: LanguageTag,
    itemType: ItemType,
): List<WordDefinitionRow> {
    val sql = """
        SELECT
            i.expression,
            i.kana,
            i.meaning,
            i.level_badge,
            i.corpus_rank,
            (
                SELECT COUNT(*)
                FROM item_occurrences o2
                JOIN scraped_photos p2 ON o2.photo_id = p2.media_store_id
                WHERE o2.language_tag = i.language_tag
                  AND o2.item_type = i.item_type
                  AND o2.expression = i.expression
                  AND o2.state IN ('UNSEEN', 'SEEN')
                  AND p2.status = 'KEEP'
            ) AS occurrence_count,
            IFNULL(l.latest_id, -1)
        FROM extracted_items i
        LEFT JOIN (
            SELECT o.language_tag AS lt, o.item_type AS it, o.expression AS ex,
                   MAX(o.id) AS latest_id
            FROM item_occurrences o
            JOIN scraped_photos p ON o.photo_id = p.media_store_id
            WHERE o.state IN ('UNSEEN', 'SEEN') AND p.status = 'KEEP'
            GROUP BY o.language_tag, o.item_type, o.expression
        ) l
          ON l.lt = i.language_tag AND l.it = i.item_type AND l.ex = i.expression
        WHERE i.language_tag = ? AND i.item_type = ?
          AND i.visibility_state != 'ARCHIVED'
    """.trimIndent()

    val results = mutableListOf<WordDefinitionRow>()
    readableDatabase.rawQuery(sql, arrayOf(languageTag.name, itemType.name)).use { cursor ->
        while (cursor.moveToNext()) {
            results.add(
                WordDefinitionRow(
                    occurrenceId = cursor.getLong(6),
                    languageTag = languageTag,
                    itemType = itemType,
                    expression = cursor.getString(0),
                    kana = cursor.getStringOrNull(1),
                    meaning = cursor.getString(2) ?: "",
                    occurrenceCount = cursor.getInt(5),
                    levelBadge = cursor.getStringOrNull(3),
                    corpusRank = cursor.getIntOrNull(4),
                ),
            )
        }
    }
    return results.sortedWith { a, b ->
        ItemOrdering.compare(
            languageTag, a.kana, a.expression,
            b.kana, b.expression,
        )
    }
}

/**
 * Loads the full detail for a canonical word/grammar entry along with all linked photos and
 * matched original sentences from non-deleted occurrences (`UNSEEN` or `SEEN`).
 */
fun MiningRepository.getItemDetail(
    languageTag: LanguageTag,
    itemType: ItemType,
    expression: String,
): ExtractedItemDetail? {
    val db = readableDatabase
    val itemCursor = db.rawQuery(
        """
        SELECT kanji, kana, meaning, level_badge, corpus_rank,
               anki_account_id, deck_id, deck_name, anki_note_id, is_hidden, visibility_state
        FROM extracted_items
        WHERE language_tag = ? AND item_type = ? AND expression = ?
        """.trimIndent(),
        arrayOf(languageTag.name, itemType.name, expression),
    )

    val kanji: String
    val kana: String
    val meaning: String
    val levelBadge: String?
    val corpusRank: Int?
    val ankiAccountId: String?
    val deckId: Long?
    val deckName: String?
    val ankiNoteId: Long?
    val isHidden: Boolean
    val visibility: ItemVisibility

    itemCursor.use { c ->
        if (!c.moveToFirst()) return null
        val rawKanji = c.getString(0)
        kanji = if (expression.any { MlKitImageTagger.isCjkIdeograph(it) } &&
            rawKanji.any { MlKitImageTagger.isCjkIdeograph(it) }
        ) {
            rawKanji
        } else {
            ""
        }
        kana = c.getString(1)
        meaning = c.getString(2)
        levelBadge = c.getStringOrNull(3)
        corpusRank = c.getIntOrNull(4)
        ankiAccountId = c.getStringOrNull(5)
        deckId = c.getLongOrNull(6)
        deckName = c.getStringOrNull(7)
        ankiNoteId = c.getLongOrNull(8)
        isHidden = c.getInt(9) != 0
        val rawVis = c.getStringOrNull(10)
        visibility = runCatching {
            if (rawVis != null) ItemVisibility.valueOf(rawVis) else null
        }.getOrNull() ?: if (isHidden) ItemVisibility.HIDDEN else ItemVisibility.ACTIVE
    }

    val photos = mutableListOf<ScrapedPhoto>()
    db.rawQuery(
        """
        SELECT DISTINCT
            p.media_store_id, p.content_uri, p.date_added, p.language_tag,
            p.extracted_text, p.ocr_blocks_json, p.status, p.duplicate_of_photo_id,
            p.modified_by_user_flag
        FROM item_occurrences o
        JOIN scraped_photos p ON o.photo_id = p.media_store_id
        WHERE o.language_tag = ?
          AND o.item_type = ?
          AND o.expression = ?
          AND o.state IN ('UNSEEN', 'SEEN')
          AND p.status = 'KEEP'
        ORDER BY p.date_added DESC, p.media_store_id DESC
        """.trimIndent(),
        arrayOf(languageTag.name, itemType.name, expression),
    ).use { c ->
        while (c.moveToNext()) {
            photos.add(c.toScrapedPhoto())
        }
    }

    val sentences = mutableListOf<String>()
    val matchedSentences = mutableListOf<MatchedSentence>()
    db.rawQuery(
        """
        SELECT o.id, o.photo_id, o.sentence, o.modified_by_user_flag, o.created_at
        FROM item_occurrences o
        JOIN scraped_photos p ON o.photo_id = p.media_store_id
        WHERE o.language_tag = ?
          AND o.item_type = ?
          AND o.expression = ?
          AND o.state IN ('UNSEEN', 'SEEN')
          AND p.status = 'KEEP'
        ORDER BY o.created_at ASC, o.id ASC
        """.trimIndent(),
        arrayOf(languageTag.name, itemType.name, expression),
    ).use { c ->
        while (c.moveToNext()) {
            val occId = c.getLong(0)
            val photoId = c.getLong(1)
            val sentence = c.getString(2).trim()
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
                matchedSentences.add(
                    MatchedSentence(
                        occurrenceId = occId,
                        photoId = photoId,
                        sentence = sentence,
                        modifiedByUserFlag = c.getInt(3) != 0,
                        createdAt = c.getLong(4),
                    ),
                )
            }
        }
    }
    // Manual sentence ordering (word-view 例文 + wizard Sentence 1/2/3 read this same order):
    // ranked sentences first by rank, everything else — including occurrences added after the
    // map was built — keeps date-ascending order at the end.
    val rankMap = sentenceRankMap(db, languageTag, itemType, expression)
    if (rankMap.isNotEmpty()) {
        matchedSentences.sortWith(
            compareBy<MatchedSentence> { rankMap[it.sentence] ?: Int.MAX_VALUE }
                .thenBy { it.createdAt }
                .thenBy { it.occurrenceId },
        )
        val ordered = matchedSentences.map { it.sentence }
        sentences.clear()
        sentences.addAll(ordered)
    }

    return ExtractedItemDetail(
        languageTag = languageTag,
        itemType = itemType,
        expression = expression,
        kanji = kanji,
        kana = kana,
        meaning = meaning,
        levelBadge = levelBadge,
        corpusRank = corpusRank,
        ankiAccountId = ankiAccountId,
        deckId = deckId,
        deckName = deckName,
        ankiNoteId = ankiNoteId,
        isHidden = isHidden || visibility != ItemVisibility.ACTIVE,
        visibility = visibility,
        occurrenceCount = sentences.size,
        photos = photos,
        sentences = sentences,
        matchedSentences = matchedSentences,
    )
}

/** Saved field mapping for one (deck, card type); absent rows read as unbound. */
fun MiningRepository.loadCardTemplate(deckId: Long, modelId: Long): List<CardTemplateMapping> =
    readableDatabase.rawQuery(
        "SELECT ordinal, bound_field FROM card_templates WHERE deck_id = ? AND model_id = ? ORDER BY ordinal ASC",
        arrayOf(deckId.toString(), modelId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(CardTemplateMapping(c.getInt(0), c.getStringOrNull(1))) } }

private fun MiningRepository.queryScrapedPhotos(
    selection: String?,
    selectionArgs: Array<String>?,
    orderBy: String?,
    limit: String?,
): List<ScrapedPhoto> {
    val results = mutableListOf<ScrapedPhoto>()
    readableDatabase.query(
        "scraped_photos",
        arrayOf(
            "media_store_id",
            "content_uri",
            "date_added",
            "language_tag",
            "extracted_text",
            "ocr_blocks_json",
            "status",
            "duplicate_of_photo_id",
            "modified_by_user_flag",
            "is_gold",
        ),
        selection,
        selectionArgs,
        null,
        null,
        orderBy,
        limit,
    ).use { cursor ->
        while (cursor.moveToNext()) {
            results.add(cursor.toScrapedPhoto())
        }
    }
    return results
}

/** The word's manual sentence ordering map (unset / unparsable -> empty = pure date order). */
internal fun sentenceRankMap(
    db: SQLiteDatabase,
    languageTag: LanguageTag,
    itemType: ItemType,
    expression: String,
): Map<String, Int> {
    val json = db.rawQuery(
        "SELECT sentence_ranks FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ?",
        arrayOf(languageTag.name, itemType.name, expression),
    ).use { c -> if (c.moveToFirst()) c.getStringOrNull(0) else null } ?: return emptyMap()
    return runCatching {
        val obj = org.json.JSONObject(json)
        buildMap { obj.keys().forEach { k -> put(k, obj.getInt(k)) } }
    }.getOrDefault(emptyMap())
}

/** Distinct sentences of the word, earliest-added first (pre-rank base order). */
internal fun distinctSentencesByDate(
    db: SQLiteDatabase,
    languageTag: LanguageTag,
    itemType: ItemType,
    expression: String,
): List<String> = db.rawQuery(
    """
    SELECT o.sentence FROM item_occurrences o
    JOIN scraped_photos p ON o.photo_id = p.media_store_id
    WHERE o.language_tag = ? AND o.item_type = ? AND o.expression = ?
      AND o.state IN ('UNSEEN', 'SEEN') AND p.status = 'KEEP'
      AND TRIM(o.sentence) != ''
    GROUP BY o.sentence ORDER BY MIN(o.created_at) ASC, MIN(o.id) ASC
    """.trimIndent(),
    arrayOf(languageTag.name, itemType.name, expression),
).use { c -> buildList { while (c.moveToNext()) add(c.getString(0).trim()) } }

private fun Cursor.toScrapedPhoto(): ScrapedPhoto =
    ScrapedPhoto(
        mediaStoreId = getLong(0),
        contentUri = getString(1),
        dateAdded = getLong(2),
        languageTag = runCatching { LanguageTag.valueOf(getString(3)) }.getOrDefault(LanguageTag.NA),
        extractedText = getString(4).orEmpty(),
        ocrBlocksJson = getStringOrNull(5),
        status = runCatching { PhotoStatus.valueOf(getString(6)) }.getOrDefault(PhotoStatus.IGNORED),
        duplicateOfPhotoId = getLongOrNull(7),
        modifiedByUserFlag = getInt(8) != 0,
        // Name-based read: projections that predate is_gold (word-view photo joins) map it false.
        isGold = getColumnIndex("is_gold").takeIf { it >= 0 }?.let { getInt(it) != 0 } ?: false,
    )

internal fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

internal fun Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

internal fun Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)
