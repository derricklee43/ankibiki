package com.derricklee.ankidict

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.MediaStore

class MiningRepository(
    context: Context,
    dbName: String = DB_NAME,
) : SQLiteOpenHelper(
    context.applicationContext,
    dbName,
    null,
    DB_VERSION,
) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE scraped_photos (
                media_store_id INTEGER PRIMARY KEY,
                content_uri TEXT NOT NULL,
                date_added INTEGER NOT NULL,
                language_tag TEXT NOT NULL,
                extracted_text TEXT NOT NULL,
                ocr_blocks_json TEXT,
                status TEXT NOT NULL,
                duplicate_of_photo_id INTEGER,
                modified_by_user_flag INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_scraped_photos_status_date
                ON scraped_photos(status, date_added DESC)
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE extracted_items (
                language_tag TEXT NOT NULL,
                item_type TEXT NOT NULL,
                expression TEXT NOT NULL,
                kanji TEXT NOT NULL,
                kana TEXT NOT NULL,
                meaning TEXT NOT NULL,
                level_badge TEXT,
                corpus_rank INTEGER,
                anki_account_id TEXT,
                deck_id INTEGER,
                deck_name TEXT,
                anki_note_id INTEGER,
                is_hidden INTEGER NOT NULL DEFAULT 0,
                visibility_state TEXT NOT NULL DEFAULT 'ACTIVE',
                sentence_ranks TEXT,
                PRIMARY KEY (language_tag, item_type, expression)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE item_occurrences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language_tag TEXT NOT NULL,
                item_type TEXT NOT NULL,
                expression TEXT NOT NULL,
                photo_id INTEGER NOT NULL,
                sentence TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                modified_by_user_flag INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_occurrences_active
                ON item_occurrences(language_tag, item_type, state, created_at DESC)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_occurrences_item
                ON item_occurrences(language_tag, item_type, expression, state)
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE extracted_items ADD COLUMN visibility_state TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
            db.execSQL("UPDATE extracted_items SET visibility_state = 'HIDDEN' WHERE is_hidden != 0")
            cleanPureKanaKanjiBackfill(db)
        }
        if (oldVersion < 3) {
            // Prior versions did not track edit provenance; do not guess which legacy rows were edited.
            db.execSQL("ALTER TABLE scraped_photos ADD COLUMN modified_by_user_flag INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE item_occurrences ADD COLUMN modified_by_user_flag INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            // Per-word manual sentence ordering (rank lives on the word entity; null = date order).
            runCatching { db.execSQL("ALTER TABLE extracted_items ADD COLUMN sentence_ranks TEXT") }
        }
    }

    private fun cleanPureKanaKanjiBackfill(db: SQLiteDatabase) {
        val toClear = mutableListOf<Triple<String, String, String>>()
        db.rawQuery(
            "SELECT language_tag, item_type, expression, kanji FROM extracted_items WHERE kanji != ''",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) {
                val lang = c.getString(0)
                val type = c.getString(1)
                val expr = c.getString(2)
                val kanji = c.getString(3)
                if (!expr.any { MlKitImageTagger.isCjkIdeograph(it) } ||
                    !kanji.any { MlKitImageTagger.isCjkIdeograph(it) }
                ) {
                    toClear.add(Triple(lang, type, expr))
                }
            }
        }
        for ((lang, type, expr) in toClear) {
            val values = ContentValues().apply { put("kanji", "") }
            db.update(
                "extracted_items",
                values,
                "language_tag = ? AND item_type = ? AND expression = ?",
                arrayOf(lang, type, expr),
            )
        }
    }

    /** Returns the [limit] most recently added scraped photos with status KEEP. */
    fun getRecentPhotos(limit: Int = 3): List<ScrapedPhoto> =
        queryScrapedPhotos(
            selection = "status = ?",
            selectionArgs = arrayOf(PhotoStatus.KEEP.name),
            orderBy = "date_added DESC, media_store_id DESC",
            limit = limit.toString(),
        )

    /** Returns all scraped photos with status KEEP, newest first. */
    fun getAllScrapedPhotos(): List<ScrapedPhoto> =
        queryScrapedPhotos(
            selection = "status = ?",
            selectionArgs = arrayOf(PhotoStatus.KEEP.name),
            orderBy = "date_added DESC, media_store_id DESC",
            limit = null,
        )

    /** Returns the [limit] most recently scraped KEEP photos for [languageTag]. */
    fun getRecentKeepPhotosByTag(languageTag: LanguageTag, limit: Int): List<ScrapedPhoto> =
        queryScrapedPhotos(
            selection = "status = ? AND language_tag = ?",
            selectionArgs = arrayOf(PhotoStatus.KEEP.name, languageTag.name),
            orderBy = "date_added DESC, media_store_id DESC",
            limit = limit.toString(),
        )

    /** Returns all `media_store_id`s currently recorded in `scraped_photos` regardless of status. */
    fun getAllProcessedMediaStoreIds(): Set<Long> {
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
    fun hasExtractedItem(
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
    fun hasLinkedAnkiCard(
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
    fun getCameraRollPhotos(): List<ScrapedPhoto> {
        val dbByMediaId = mutableMapOf<Long, ScrapedPhoto>()
        readableDatabase.rawQuery(
            "SELECT media_store_id, content_uri, date_added, language_tag, extracted_text, " +
                "ocr_blocks_json, status, duplicate_of_photo_id, modified_by_user_flag FROM scraped_photos",
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
    fun getPhoto(mediaStoreId: Long): ScrapedPhoto? {
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

    /** Preserves edit provenance. Automated callers must check the flag before replacing user text. */
    fun upsertPhoto(photo: ScrapedPhoto) {
        val values = ContentValues().apply {
            put("media_store_id", photo.mediaStoreId)
            put("content_uri", photo.contentUri)
            put("date_added", photo.dateAdded)
            put("language_tag", photo.languageTag.name)
            put("extracted_text", photo.extractedText)
            put("ocr_blocks_json", photo.ocrBlocksJson)
            put("status", photo.status.name)
            if (photo.duplicateOfPhotoId != null) {
                put("duplicate_of_photo_id", photo.duplicateOfPhotoId)
            } else {
                putNull("duplicate_of_photo_id")
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previouslyModified = db.rawQuery(
                "SELECT modified_by_user_flag FROM scraped_photos WHERE media_store_id = ?",
                arrayOf(photo.mediaStoreId.toString()),
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }
            values.put("modified_by_user_flag", photo.modifiedByUserFlag || previouslyModified)
            db.insertWithOnConflict("scraped_photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** User-edit write path: marks actual text changes without clearing prior edit provenance. */
    fun updatePhotoExtractedText(mediaStoreId: Long, amendedText: String) {
        val existing = getPhoto(mediaStoreId)
        if (existing != null) {
            val tag = if (existing.languageTag == LanguageTag.NA && amendedText.isNotBlank()) {
                LanguageTag.JAPANESE
            } else {
                existing.languageTag
            }
            val status = if (amendedText.isNotBlank()) PhotoStatus.KEEP else existing.status
            upsertPhoto(
                existing.copy(
                    languageTag = tag,
                    extractedText = amendedText,
                    modifiedByUserFlag = existing.modifiedByUserFlag || existing.extractedText != amendedText,
                    status = status,
                ),
            )
        }
    }

    /** Only a successful, explicitly authorized image rescan may clear photo edit protection. */
    internal fun clearPhotoUserModificationFlag(mediaStoreId: Long) {
        val values = ContentValues().apply { put("modified_by_user_flag", false) }
        writableDatabase.update("scraped_photos", values, "media_store_id = ?", arrayOf(mediaStoreId.toString()))
    }

    /** Marks existing sentence objects changed through the whole-photo text editor. */
    fun markSentencesModifiedByUser(photoId: Long, sentences: Set<String>) {
        val values = ContentValues().apply { put("modified_by_user_flag", true) }
        for (sentence in sentences) {
            writableDatabase.update(
                "item_occurrences", values, "photo_id = ? AND sentence = ?",
                arrayOf(photoId.toString(), sentence),
            )
        }
    }

    /** Sentence provenance is shared by every word/grammar occurrence of the same photo text. */
    fun getSentenceModificationFlags(photoId: Long): Map<String, Boolean> {
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
    fun getOccurrenceSentenceRows(photoId: Long): List<ExtractionStatus.SentenceRows> {
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

    /** Hard-deletes all `item_occurrences` rows belonging to [photoId] before reprocessing its text. */
    fun deleteOccurrencesForPhoto(photoId: Long) {
        writableDatabase.delete(
            "item_occurrences",
            "photo_id = ?",
            arrayOf(photoId.toString()),
        )
    }

    /** User-edit write path: updates and flags the sentence across occurrences and in its photo text. */
    fun updateSentenceText(photoId: Long, oldSentence: String, newSentence: String) {
        if (oldSentence == newSentence) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val occValues = ContentValues().apply {
                put("sentence", newSentence)
                put("modified_by_user_flag", true)
            }
            db.update(
                "item_occurrences",
                occValues,
                "photo_id = ? AND (sentence = ? OR sentence = ?)",
                arrayOf(photoId.toString(), oldSentence, newSentence),
            )

            val photo = getPhoto(photoId)
            if (photo != null && oldSentence.isNotEmpty() && photo.extractedText.contains(oldSentence)) {
                val updatedPhotoText = photo.extractedText.replace(oldSentence, newSentence)
                val photoValues = ContentValues().apply {
                    put("extracted_text", updatedPhotoText)
                    put("modified_by_user_flag", true)
                }
                db.update(
                    "scraped_photos",
                    photoValues,
                    "media_store_id = ?",
                    arrayOf(photoId.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Hard-deletes all `item_occurrences` rows matching `(photoId, sentence)` before re-extracting an amended sentence. */
    fun deleteOccurrencesForSentence(photoId: Long, sentence: String) {
        writableDatabase.delete(
            "item_occurrences",
            "photo_id = ? AND sentence = ?",
            arrayOf(photoId.toString(), sentence),
        )
    }

    /**
     * Runs [block] inside one explicit transaction on the writable connection. Nested
     * repository writes on the same thread join it (Android ref-counts transactions per
     * thread), so editor-driven rebuilds commit all-or-nothing like the rescan path.
     */
    internal fun <R> inTransaction(block: () -> R): R {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    internal data class OccurrenceRow(
        val id: Long,
        val itemType: String,
        val expression: String,
        val state: String,
        val createdAt: Long,
    )

    /** Parsed word/grammar candidates of one sentence instance (photo, sentence), DELETED excluded. */
    data class SentenceCandidate(val occurrenceId: Long, val itemType: ItemType, val expression: String)

    fun getSentenceCandidates(photoId: Long, sentence: String): List<SentenceCandidate> =
        readableDatabase.rawQuery(
            """SELECT MAX(id), item_type, expression FROM item_occurrences
               WHERE photo_id = ? AND sentence = ? AND state != 'DELETED'
               GROUP BY item_type, expression""",
            arrayOf(photoId.toString(), sentence),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val type = runCatching { ItemType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                    add(SentenceCandidate(c.getLong(0), type, c.getString(2)))
                }
            }
        }.sortedWith(
            compareBy<MiningRepository.SentenceCandidate> { if (it.itemType == ItemType.WORD) 0 else 1 }
                .thenBy { it.expression },
        )

    /** Rows for one `(photo, sentence)` pair, newest first — the unit of reprocessing reconciliation. */
    internal fun occurrenceRowsForSentence(photoId: Long, sentence: String): List<OccurrenceRow> =
        readableDatabase.query(
            "item_occurrences",
            arrayOf("id", "item_type", "expression", "state", "created_at"),
            "photo_id = ? AND sentence = ?",
            arrayOf(photoId.toString(), sentence),
            null, null, "created_at DESC, id DESC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(OccurrenceRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
                }
            }
        }

    internal fun deleteOccurrenceById(id: Long): Int =
        writableDatabase.delete("item_occurrences", "id = ?", arrayOf(id.toString()))

    /**
     * Marks a photo as [PhotoStatus.DELETED] in AnkiBiki (so it is hidden and ignored by future
     * scans without deleting the file from device storage) and unlinks any occurrences from it.
     */
    fun markPhotoDeleted(mediaStoreId: Long, contentUri: String, dateAdded: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("media_store_id", mediaStoreId)
                put("content_uri", contentUri)
                put("date_added", dateAdded)
                put("language_tag", LanguageTag.NA.name)
                put("extracted_text", "")
                putNull("ocr_blocks_json")
                put("status", PhotoStatus.DELETED.name)
                put("modified_by_user_flag", getPhoto(mediaStoreId)?.modifiedByUserFlag == true)
                putNull("duplicate_of_photo_id")
            }
            db.insertWithOnConflict("scraped_photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)

            val occValues = ContentValues().apply {
                put("state", OccurrenceState.DELETED.name)
            }
            db.update(
                "item_occurrences",
                occValues,
                "photo_id = ?",
                arrayOf(mediaStoreId.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Returns occurrences matching [query]. Each sentence occurrence is its own
     * row, while `occurrenceCount` reflects the total non-deleted occurrences of that canonical item.
     */
    fun getActiveOccurrences(query: OccurrenceQuery): List<ExtractedOccurrenceRow> {
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
                o.created_at
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
                    ),
                )
            }
        }
        return results
    }

    /**
     * Loads the full detail for a canonical word/grammar entry along with all linked photos and
     * matched original sentences from non-deleted occurrences (`UNSEEN` or `SEEN`).
     */
    fun getItemDetail(
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

    /**
     * Marks a single occurrence as `SEEN` (`既読`). Removes it from the active occurrence queue
     * while keeping it linked to the item's photos/sentences, and allowing future occurrences to appear.
     */
    fun markOccurrenceSeen(occurrenceId: Long) {
        val values = ContentValues().apply {
            put("state", OccurrenceState.SEEN.name)
        }
        writableDatabase.update(
            "item_occurrences",
            values,
            "id = ?",
            arrayOf(occurrenceId.toString()),
        )
    }

    /** The word's manual sentence ordering map (unset / unparsable -> empty = pure date order). */
    private fun sentenceRankMap(
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
    private fun distinctSentencesByDate(
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

    /**
     * Moves [sentence] one step up/down in the word's manual sentence order (word-view 例文 and the
     * wizard's Sentence 1/2/3 dropdown read this order). The first move materializes the full current
     * order into the rank map; stale keys (sentences since edited away) are dropped on rewrite.
     */
    fun moveSentenceRank(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        sentence: String,
        up: Boolean,
    ): Boolean {
        val db = writableDatabase
        val base = distinctSentencesByDate(db, languageTag, itemType, expression)
        if (base.size < 2 || sentence !in base) return false
        val rankMap = sentenceRankMap(db, languageTag, itemType, expression)
        val ordered = (base.sortedBy { rankMap[it] ?: Int.MAX_VALUE }).distinct()
        val from = ordered.indexOf(sentence)
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in ordered.indices) return false
        val moved = ordered.toMutableList().apply { add(to, removeAt(from)) }
        val values = ContentValues().apply {
            put(
                "sentence_ranks",
                org.json.JSONObject(moved.mapIndexed { i, s -> s to i }.toMap() as Map<String, Any>).toString(),
            )
        }
        val updated = db.update(
            "extracted_items", values,
            "language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        )
        return updated == 1
    }

    /**
     * Updates the visibility partition of a canonical item:
     * - [ItemVisibility.ACTIVE]: normal active review item
     * - [ItemVisibility.HIDDEN]: recognized/known word (`非表示`)
     * - [ItemVisibility.ARCHIVED]: clutter, OCR noise, or proper noun (`アーカイブ`)
     */
    fun setItemVisibility(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        visibility: ItemVisibility,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val isHiddenFlag = if (visibility == ItemVisibility.ACTIVE) 0 else 1
            val itemValues = ContentValues().apply {
                put("is_hidden", isHiddenFlag)
                put("visibility_state", visibility.name)
            }
            db.update(
                "extracted_items",
                itemValues,
                "language_tag = ? AND item_type = ? AND expression = ?",
                arrayOf(languageTag.name, itemType.name, expression),
            )

            if (visibility != ItemVisibility.ACTIVE) {
                val occValues = ContentValues().apply {
                    put("state", OccurrenceState.SEEN.name)
                }
                db.update(
                    "item_occurrences",
                    occValues,
                    "language_tag = ? AND item_type = ? AND expression = ? AND state = ?",
                    arrayOf(languageTag.name, itemType.name, expression, OccurrenceState.UNSEEN.name),
                )
            } else {
                val occValues = ContentValues().apply {
                    put("state", OccurrenceState.UNSEEN.name)
                }
                db.update(
                    "item_occurrences",
                    occValues,
                    "language_tag = ? AND item_type = ? AND expression = ? AND state = ?",
                    arrayOf(languageTag.name, itemType.name, expression, OccurrenceState.SEEN.name),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Hides the canonical item (`非表示` — recognized word) so current occurrences leave the active
     * queue and all future occurrences automatically skip the active queue.
     */
    fun hideItem(languageTag: LanguageTag, itemType: ItemType, expression: String) {
        setItemVisibility(languageTag, itemType, expression, ItemVisibility.HIDDEN)
    }

    /**
     * Archives the canonical item (`アーカイブ` — clutter, proper nouns) so it is hidden from almost
     * all views unless the user explicitly filters for archived items.
     */
    fun archiveItem(languageTag: LanguageTag, itemType: ItemType, expression: String) {
        setItemVisibility(languageTag, itemType, expression, ItemVisibility.ARCHIVED)
    }

    /**
     * Deletes/unlinks a single occurrence (`削除`). It will not show up in any list, photo strip,
     * or sentence list, while leaving the canonical entry and any other occurrences untouched.
     */
    fun deleteOccurrence(occurrenceId: Long) {
        val values = ContentValues().apply {
            put("state", OccurrenceState.DELETED.name)
        }
        writableDatabase.update(
            "item_occurrences",
            values,
            "id = ?",
            arrayOf(occurrenceId.toString()),
        )
    }

    fun upsertExtractedItem(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        kanji: String,
        kana: String,
        meaning: String,
        levelBadge: String? = null,
        corpusRank: Int? = null,
        ankiAccountId: String? = null,
        deckId: Long? = null,
        deckName: String? = null,
        ankiNoteId: Long? = null,
        isHidden: Boolean = false,
        visibility: ItemVisibility = if (isHidden) ItemVisibility.HIDDEN else ItemVisibility.ACTIVE,
    ) {
        val sanitizedKanji = if (expression.any { MlKitImageTagger.isCjkIdeograph(it) } &&
            kanji.any { MlKitImageTagger.isCjkIdeograph(it) }
        ) {
            kanji
        } else {
            ""
        }
        val values = ContentValues().apply {
            put("language_tag", languageTag.name)
            put("item_type", itemType.name)
            put("expression", expression)
            put("kanji", sanitizedKanji)
            put("kana", kana)
            put("meaning", meaning)
            put("level_badge", levelBadge)
            if (corpusRank != null) put("corpus_rank", corpusRank) else putNull("corpus_rank")
            put("anki_account_id", ankiAccountId)
            if (deckId != null) put("deck_id", deckId) else putNull("deck_id")
            put("deck_name", deckName)
            if (ankiNoteId != null) put("anki_note_id", ankiNoteId) else putNull("anki_note_id")
            put("is_hidden", if (isHidden || visibility != ItemVisibility.ACTIVE) 1 else 0)
            put("visibility_state", visibility.name)
        }
        writableDatabase.insertWithOnConflict("extracted_items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Updates the cached Anki card match (`anki_account_id`, `deck_id`, `deck_name`, `anki_note_id`) for an item. */
    fun updateExtractedItemAnkiMatch(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        match: AnkiCardMatch,
    ) {
        val values = ContentValues().apply {
            put("anki_account_id", match.accountId)
            put("deck_id", match.deckId)
            put("deck_name", match.deckName)
            put("anki_note_id", match.noteId)
        }
        writableDatabase.update(
            "extracted_items",
            values,
            "language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        )
    }

    /** Updates the cached display `deck_name` for all items matching `(accountId, deckId)`. */
    fun updateCachedDeckName(accountId: String?, deckId: Long, deckName: String) {
        val values = ContentValues().apply {
            put("deck_name", deckName)
        }
        val whereClause = if (accountId != null) {
            "anki_account_id = ? AND deck_id = ?"
        } else {
            "deck_id = ?"
        }
        val whereArgs = if (accountId != null) {
            arrayOf(accountId, deckId.toString())
        } else {
            arrayOf(deckId.toString())
        }
        writableDatabase.update("extracted_items", values, whereClause, whereArgs)
    }

    fun insertOccurrence(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        photoId: Long,
        sentence: String,
        createdAt: Long = System.currentTimeMillis() / 1000L,
        modifiedByUserFlag: Boolean = false,
    ): Long {
        val isHidden = readableDatabase.rawQuery(
            "SELECT is_hidden, visibility_state FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        ).use { c ->
            if (c.moveToFirst()) {
                val hiddenFlag = c.getInt(0) != 0
                val vis = c.getStringOrNull(1)
                hiddenFlag || (vis != null && vis != ItemVisibility.ACTIVE.name)
            } else {
                false
            }
        }

        val sentenceModified = modifiedByUserFlag || readableDatabase.rawQuery(
            "SELECT 1 FROM item_occurrences WHERE photo_id = ? AND sentence = ? AND modified_by_user_flag = 1 LIMIT 1",
            arrayOf(photoId.toString(), sentence),
        ).use { it.moveToFirst() }
        val initialState = if (isHidden) OccurrenceState.SEEN else OccurrenceState.UNSEEN
        val values = ContentValues().apply {
            put("language_tag", languageTag.name)
            put("item_type", itemType.name)
            put("expression", expression)
            put("photo_id", photoId)
            put("sentence", sentence)
            put("created_at", createdAt)
            put("state", initialState.name)
            put("modified_by_user_flag", sentenceModified)
        }
        return writableDatabase.insert("item_occurrences", null, values)
    }

    private fun queryScrapedPhotos(
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
        )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    companion object {
        private const val DB_NAME = "mining.db"
        private const val DB_VERSION = 4

        /** For RecoveryManager: schema expectations of the live corpus db. */
        internal fun expectedDbName(): String = DB_NAME
        internal fun expectedUserVersion(): Int = DB_VERSION
    }
}
