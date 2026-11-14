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
    internal val appContext = context.applicationContext

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
                modified_by_user_flag INTEGER NOT NULL DEFAULT 0,
                is_gold INTEGER NOT NULL DEFAULT 0
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
                modified_by_user_flag INTEGER NOT NULL DEFAULT 0,
                is_manual INTEGER NOT NULL DEFAULT 0
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
        db.execSQL(cardTemplatesDdl())
        createOcrCorrectionsSchema(db)
        createNameplateClaimsSchema(db)
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
        if (oldVersion < 5) {
            db.execSQL(cardTemplatesDdl())
        }
        if (oldVersion < 6) {
            createOcrCorrectionsSchema(db)
        }
        if (oldVersion < 7) {
            // Gold membership becomes an EXPLICIT mark (user 2026-11-13), replacing the
            // "touched = gold" assumption. NOT seeded (user decision 2026-11-13: "flip them
            // all off") - the gold era starts from zero marks and every membership is a
            // deliberate 金 tap; the committed benchmark fixtures keep scoring the pipeline
            // until re-marked photos drive the next regeneration.
            runCatching { db.execSQL("ALTER TABLE scraped_photos ADD COLUMN is_gold INTEGER NOT NULL DEFAULT 0") }
        }
        if (oldVersion < 8) {
            createNameplateClaimsSchema(db)
        }
        if (oldVersion < 9) {
            // v9: inline plate claims need per-occurrence identity (カイン may be a plate on one
            // line and a vocative on another in the SAME photo) — rebuild with context_sentence.
            db.execSQL(
                """
                CREATE TABLE nameplate_claims_v9 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    photo_id INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    context_sentence TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL DEFAULT 'proxy',
                    verdict TEXT,
                    created_at INTEGER NOT NULL,
                    verdict_at INTEGER,
                    UNIQUE(photo_id, text, context_sentence)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO nameplate_claims_v9 (id, photo_id, text, context_sentence, source, verdict, created_at, verdict_at)
                SELECT id, photo_id, text, '', source, verdict, created_at, verdict_at FROM nameplate_claims
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE nameplate_claims")
            db.execSQL("ALTER TABLE nameplate_claims_v9 RENAME TO nameplate_claims")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_nameplate_claims_photo ON nameplate_claims(photo_id, verdict)",
            )
        }
        if (oldVersion < 10) {
            // v10: sentence word editor (user 2026-11-13). is_manual marks rows the editor
            // created/renamed — distinct provenance from modified_by_user_flag (which means
            // "user-authored TEXT" and does NOT protect against candidate reconciliation).
            // Defensive on two axes: test harnesses hand-build partial or current-schema-then-
            // downgraded databases, so check the table AND the column before altering.
            val hasTable = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'item_occurrences'", null,
            ).use { it.moveToFirst() }
            val hasColumn = hasTable && db.rawQuery(
                "SELECT 1 FROM pragma_table_info('item_occurrences') WHERE name = 'is_manual'", null,
            ).use { it.moveToFirst() }
            if (hasTable && !hasColumn) {
                db.execSQL(
                    "ALTER TABLE item_occurrences ADD COLUMN is_manual INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
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
            values.put("is_gold", photo.isGold)
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

    /** Explicit gold-set membership toggle (Mark as gold button; user 2026-11-13). Never touches
     * edit provenance — marking is verification, not an edit. Full image rescans clear the mark
     * because they replace the text it attests to. The v7 migration does NOT seed marks:
     * membership starts at zero by user decision (2026-11-13), every 金 is declared. */
    fun setPhotoIsGold(mediaStoreId: Long, gold: Boolean) {
        val values = ContentValues().apply { put("is_gold", gold) }
        writableDatabase.update("scraped_photos", values, "media_store_id = ?", arrayOf(mediaStoreId.toString()))
    }


    // ---- Nameplate audit claims (v8; see createNameplateClaimsSchema for semantics) ----

    data class NameplateClaim(
        val id: Long,
        val text: String,
        val contextSentence: String,
        val verdict: String?,
    )

    /** Idempotent draft insert (UNIQUE photo+text+context): called from the mining path per leaked line. */
    fun insertNameplateClaims(mediaStoreId: Long, cores: Set<String>, now: Long = System.currentTimeMillis()) {
        for (core in cores) {
            if (core.isBlank()) continue
            val values = ContentValues().apply {
                put("photo_id", mediaStoreId)
                put("text", core)
                put("context_sentence", "")
                put("source", "proxy")
                put("created_at", now)
            }
            writableDatabase.insertWithOnConflict(
                "nameplate_claims", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    /**
     * H9v2 merge proposals (suggestion-grade, spike 004): one row per (plate, sentence) the rule
     * fires on. NEVER destructive — the sentence keeps its plate until the user rules 正しい in
     * the photo view (which excises it through the normal text-reconciliation path).
     */
    fun insertInlineNameplateClaims(
        mediaStoreId: Long,
        proposals: Collection<Pair<String, String>>, // plate to context sentence
        now: Long = System.currentTimeMillis(),
    ) {
        for ((plate, sentence) in proposals) {
            if (plate.isBlank() || sentence.isBlank()) continue
            val values = ContentValues().apply {
                put("photo_id", mediaStoreId)
                put("text", plate)
                put("context_sentence", sentence)
                put("source", "h9")
                put("created_at", now)
            }
            writableDatabase.insertWithOnConflict(
                "nameplate_claims", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }


    /**
     * One unruled claim for the claims queue (roadmap B1), with the owning photo's fields needed
     * to deep-link [PhotoDetailActivity] and render the source line. Newest photos first
     * (triage follows the capture stream), then draft order within a photo.
     */
    data class PendingNameplateClaim(
        val claimId: Long,
        val text: String,
        val contextSentence: String,
        val source: String,
        val mediaStoreId: Long,
        val contentUri: String,
        val dateAdded: Long,
        val languageTag: LanguageTag,
    )



    /**
     * Aggregates for the gold dashboard (roadmap B2): corpus size, 金 marks, the correction-effort
     * proxy (gold photos whose text was user-edited before marking), and the auto-apply gate's
     * audit-ledger counts. All single-pass SQL over the corpus DB; the dashboard renders these
     * plus the static recorded benchmark — NEVER a live OCR run.
     */
    data class DashboardStats(
        val totalPhotos: Int,
        val keepPhotos: Int,
        val goldPhotos: Int,
        val goldUserEditedPhotos: Int,
        val correctionsApplied: Int,
        val correctionsRejected: Int,
        val correctionsExcluded: Int,
    )







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



    /** Hard-deletes all `item_occurrences` rows belonging to [photoId] before reprocessing its text. */
    fun deleteOccurrencesForPhoto(photoId: Long) {
        writableDatabase.delete(
            "item_occurrences",
            "photo_id = ?",
            arrayOf(photoId.toString()),
        )
    }

    /** Appends gate audit rows (one per evaluated island) for [photoId] reprocessing. */
    fun insertCorrectionRows(rows: List<CorrectionEvidenceRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val values = ContentValues().apply {
                    put("photo_id", row.photoId)
                    put("line_index", row.lineIndex)
                    put("a_start", row.aStart)
                    put("a_end", row.aEnd)
                    put("a_text", row.aText)
                    put("b_text", row.bText)
                    put("kind", row.kind)
                    put("margin", row.margin)
                    put("anchored_a", row.anchoredA)
                    put("anchored_b", row.anchoredB)
                    put("confusable", row.confusable)
                    put("nameplate_excluded", row.nameplateExcluded)
                    put("decision", row.decision)
                    put("reason", row.reason)
                    put("line_before", row.lineBefore)
                    put("line_after", row.lineAfter)
                    put("created_at", row.createdAt)
                }
                db.insert("ocr_corrections", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Rescans replace prior gate decisions for the photo: drop its audit rows with the old OCR. */
    fun deleteCorrectionsForPhoto(photoId: Long) {
        writableDatabase.delete(
            "ocr_corrections",
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
        /** Manual occurrences survive candidate-granularity reprocessing (user decision 2026-11-13). */
        val modifiedByUserFlag: Boolean = false,
        /** Word-editor provenance (schema v10): the ONLY flag that protects a row from reconcile. */
        val isManual: Boolean = false,
    )

    /** Parsed word/grammar candidates of one sentence instance (photo, sentence), DELETED excluded. */
    data class SentenceCandidate(val occurrenceId: Long, val itemType: ItemType, val expression: String)



    // ---- sentence word editor (user feature 2026-11-13) ----

    /** Re-key an occurrence's expression (chip rename), preserving review state; marks it user-modified. */
    fun updateOccurrenceExpression(photoId: Long, sentence: String, oldExpr: String, newExpr: String): Int {
        val values = ContentValues().apply {
            put("expression", newExpr)
            put("modified_by_user_flag", true)
            put("is_manual", true)
        }
        return writableDatabase.update(
            "item_occurrences", values,
            "photo_id = ? AND sentence = ? AND expression = ?",
            arrayOf(photoId.toString(), sentence, oldExpr),
        )
    }

    /** Soft state flip for one expression within one sentence (editor delete/restore). */
    fun setOccurrenceStateForExpression(
        photoId: Long,
        sentence: String,
        expression: String,
        state: OccurrenceState,
        modifiedByUserFlag: Boolean = true,
    ): Int {
        val values = ContentValues().apply {
            put("state", state.name)
            put("modified_by_user_flag", modifiedByUserFlag)
            put("is_manual", true)
        }
        return writableDatabase.update(
            "item_occurrences", values,
            "photo_id = ? AND sentence = ? AND expression = ?",
            arrayOf(photoId.toString(), sentence, expression),
        )
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





    /** Replace-all write: the template IS the submitted row set (null bound = unbound row). */
    fun saveCardTemplate(deckId: Long, modelId: Long, rows: List<CardTemplateMapping>) {
        inTransaction {
            writableDatabase.delete(
                "card_templates", "deck_id = ? AND model_id = ?",
                arrayOf(deckId.toString(), modelId.toString()),
            )
            val now = System.currentTimeMillis() / 1000L
            for (row in rows) {
                val values = android.content.ContentValues().apply {
                    put("deck_id", deckId)
                    put("model_id", modelId)
                    put("ordinal", row.ordinal)
                    if (row.fieldLabel == null) putNull("bound_field") else put("bound_field", row.fieldLabel)
                    put("updated_at", now)
                }
                writableDatabase.insert("card_templates", null, values)
            }
        }
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
        isManual: Boolean = false,
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
            put("is_manual", isManual)
        }
        return writableDatabase.insert("item_occurrences", null, values)
    }






    companion object {
        private const val DB_NAME = "mining.db"
        private const val DB_VERSION = 10

        /** For RecoveryManager: schema expectations of the live corpus db. */
        internal fun expectedDbName(): String = DB_NAME
        internal fun expectedUserVersion(): Int = DB_VERSION
    }
}
