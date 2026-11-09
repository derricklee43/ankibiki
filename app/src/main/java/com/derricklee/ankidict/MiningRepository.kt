package com.derricklee.ankidict

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.MediaStore

class MiningRepository(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
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
                duplicate_of_photo_id INTEGER
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
                state TEXT NOT NULL
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
        // v1 schema
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

    /**
     * Queries the device camera roll from [MediaStore.Images.Media.EXTERNAL_CONTENT_URI] ordered
     * newest first, excluding any photo the user explicitly marked as [PhotoStatus.DELETED] in
     * AnkiBiki, while preserving extracted text if the photo was already scraped.
     */
    fun getCameraRollPhotos(): List<ScrapedPhoto> {
        val dbByMediaId = mutableMapOf<Long, ScrapedPhoto>()
        readableDatabase.rawQuery(
            "SELECT media_store_id, content_uri, date_added, language_tag, extracted_text, " +
                "ocr_blocks_json, status, duplicate_of_photo_id FROM scraped_photos",
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
        writableDatabase.insertWithOnConflict("scraped_photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

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
     * Returns active (`UNSEEN`) occurrences matching [query]. Each sentence occurrence is its own
     * row, while `occurrenceCount` reflects the total non-deleted occurrences of that canonical item.
     */
    fun getActiveOccurrences(query: OccurrenceQuery): List<ExtractedOccurrenceRow> {
        val whereClauses = mutableListOf(
            "o.language_tag = ?",
            "o.item_type = ?",
            "o.state = ?",
            "i.is_hidden = 0",
            "p.status = ?",
        )
        val args = mutableListOf(
            query.languageTag.name,
            query.itemType.name,
            OccurrenceState.UNSEEN.name,
            PhotoStatus.KEEP.name,
        )

        if (OccurrenceFilter.HAS_ANKI_CARD in query.filters) {
            whereClauses.add("i.anki_note_id IS NOT NULL")
        }
        if (OccurrenceFilter.NO_ANKI_CARD in query.filters) {
            whereClauses.add("i.anki_note_id IS NULL")
        }

        val orderBySql = when (query.sortOrder) {
            OccurrenceSortOrder.NEWEST_FIRST -> "o.created_at DESC, o.id DESC"
            OccurrenceSortOrder.OCCURRENCE_COUNT_DESC -> "occurrence_count DESC, o.created_at DESC, o.id DESC"
            OccurrenceSortOrder.CORPUS_RANK_ASC ->
                "(i.corpus_rank IS NULL) ASC, i.corpus_rank ASC, o.created_at DESC, o.id DESC"
            OccurrenceSortOrder.LEVEL_ASC ->
                "(i.level_badge IS NULL) ASC, i.level_badge ASC, o.created_at DESC, o.id DESC"
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
                   anki_account_id, deck_id, deck_name, anki_note_id, is_hidden
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

        itemCursor.use { c ->
            if (!c.moveToFirst()) return null
            kanji = c.getString(0)
            kana = c.getString(1)
            meaning = c.getString(2)
            levelBadge = c.getStringOrNull(3)
            corpusRank = c.getIntOrNull(4)
            ankiAccountId = c.getStringOrNull(5)
            deckId = c.getLongOrNull(6)
            deckName = c.getStringOrNull(7)
            ankiNoteId = c.getLongOrNull(8)
            isHidden = c.getInt(9) != 0
        }

        val photos = mutableListOf<ScrapedPhoto>()
        db.rawQuery(
            """
            SELECT DISTINCT
                p.media_store_id, p.content_uri, p.date_added, p.language_tag,
                p.extracted_text, p.ocr_blocks_json, p.status, p.duplicate_of_photo_id
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
        db.rawQuery(
            """
            SELECT o.sentence
            FROM item_occurrences o
            JOIN scraped_photos p ON o.photo_id = p.media_store_id
            WHERE o.language_tag = ?
              AND o.item_type = ?
              AND o.expression = ?
              AND o.state IN ('UNSEEN', 'SEEN')
              AND p.status = 'KEEP'
            ORDER BY o.created_at DESC, o.id DESC
            """.trimIndent(),
            arrayOf(languageTag.name, itemType.name, expression),
        ).use { c ->
            while (c.moveToNext()) {
                val sentence = c.getString(0).trim()
                if (sentence.isNotEmpty()) {
                    sentences.add(sentence)
                }
            }
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
            isHidden = isHidden,
            occurrenceCount = sentences.size,
            photos = photos,
            sentences = sentences,
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

    /**
     * Hides the canonical item (`非表示`) so current occurrences leave the active queue and all
     * future occurrences automatically skip the active queue.
     */
    fun hideItem(languageTag: LanguageTag, itemType: ItemType, expression: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val itemValues = ContentValues().apply {
                put("is_hidden", 1)
            }
            db.update(
                "extracted_items",
                itemValues,
                "language_tag = ? AND item_type = ? AND expression = ?",
                arrayOf(languageTag.name, itemType.name, expression),
            )

            val occValues = ContentValues().apply {
                put("state", OccurrenceState.SEEN.name)
            }
            db.update(
                "item_occurrences",
                occValues,
                "language_tag = ? AND item_type = ? AND expression = ? AND state = ?",
                arrayOf(languageTag.name, itemType.name, expression, OccurrenceState.UNSEEN.name),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
    ) {
        val values = ContentValues().apply {
            put("language_tag", languageTag.name)
            put("item_type", itemType.name)
            put("expression", expression)
            put("kanji", kanji)
            put("kana", kana)
            put("meaning", meaning)
            put("level_badge", levelBadge)
            if (corpusRank != null) put("corpus_rank", corpusRank) else putNull("corpus_rank")
            put("anki_account_id", ankiAccountId)
            if (deckId != null) put("deck_id", deckId) else putNull("deck_id")
            put("deck_name", deckName)
            if (ankiNoteId != null) put("anki_note_id", ankiNoteId) else putNull("anki_note_id")
            put("is_hidden", if (isHidden) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("extracted_items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertOccurrence(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        photoId: Long,
        sentence: String,
        createdAt: Long = System.currentTimeMillis() / 1000L,
    ): Long {
        val isHidden = readableDatabase.rawQuery(
            "SELECT is_hidden FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else false
        }

        val initialState = if (isHidden) OccurrenceState.SEEN else OccurrenceState.UNSEEN
        val values = ContentValues().apply {
            put("language_tag", languageTag.name)
            put("item_type", itemType.name)
            put("expression", expression)
            put("photo_id", photoId)
            put("sentence", sentence)
            put("created_at", createdAt)
            put("state", initialState.name)
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
        )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    companion object {
        private const val DB_NAME = "mining.db"
        private const val DB_VERSION = 1
    }
}
