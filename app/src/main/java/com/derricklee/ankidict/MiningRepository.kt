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
                is_gold INTEGER NOT NULL DEFAULT 0,
                flag_color TEXT
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
                user_definition TEXT,
                user_notes TEXT,
                folder_id INTEGER,
                picked_headword TEXT,
                picked_reading TEXT,
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
        createWordFoldersSchema(db)
        createUndoLogSchema(db)
        createStremioSchema(db)
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
        if (oldVersion < 11) {
            // v11 (user 2026-11-15 laundry-list batch): photo flag_color, per-word
            // user_definition/user_notes/folder_id, and the word_folders table.
            // Every ALTER is guarded by the table+column check (addColumnIfMissing) — same
            // partial/downgraded-harness hazard the v10 migration documented.
            addColumnIfMissing(db, "scraped_photos", "flag_color", "TEXT")
            addColumnIfMissing(db, "extracted_items", "user_definition", "TEXT")
            addColumnIfMissing(db, "extracted_items", "user_notes", "TEXT")
            addColumnIfMissing(db, "extracted_items", "folder_id", "INTEGER")
            createWordFoldersSchema(db)
        }
        if (oldVersion < 12) {
            // v12 (user go 2026-11-15): cross-view undo log. A new table, not columns —
            // CREATE IF NOT EXISTS is inherently guarded; bundles pre-v12 simply carry no
            // undo_log table and it materializes on first open after restore.
            createUndoLogSchema(db)
        }
        if (oldVersion < 13) {
            // v13 (user go 2026-11-23, docs/item-meaning-reselect-proposal.md): entry pick
            // columns on extracted_items. Same guarded-ALTER discipline as v11; pre-v13
            // bundles restore fine and the columns materialize on first open (deliberately
            // NOT in REQUIRED_COLUMNS/MINING_COLUMNS so old bundles keep validating).
            addColumnIfMissing(db, "extracted_items", "picked_headword", "TEXT")
            addColumnIfMissing(db, "extracted_items", "picked_reading", "TEXT")
        }
        if (oldVersion < 14) {
            // v14 (user go 2026-11-23, docs/stremio-mining-console-design.md §6): Stremio
            // Mining Console tables. ALL are new CREATE-IF-NOT-EXISTS tables — nothing in
            // item_occurrences/scraped_photos is rebuilt (stage-1 landing shape, §6.1
            // resolution: stremio occurrence rows carry NEGATIVE photo_id =
            // -stremio_cue_documents.id; MediaStore ids are always positive, so the
            // namespaces cannot collide and every photo-scoped guard naturally excludes
            // them). Not added to REQUIRED_COLUMNS/MINING_COLUMNS/SUPPORTED sets of old
            // versions; pre-v14 bundles restore and the tables materialize on first open.
            createStremioSchema(db)
        }
    }

    /** Stremio Mining Console tables (v14). CREATE IF NOT EXISTS = self-guarding. */
    private fun createStremioSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stremio_library (
                base_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                poster TEXT,
                video_id TEXT,
                time_offset INTEGER NOT NULL DEFAULT 0,
                duration INTEGER NOT NULL DEFAULT 0,
                watched_blob TEXT,
                times_watched INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stremio_addons (
                addon_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                transport_url TEXT NOT NULL,
                types_csv TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stremio_settings (
                scope_key TEXT PRIMARY KEY,
                subtitle_lang TEXT NOT NULL DEFAULT 'jpn',
                subtitle_pattern TEXT,
                subtitle_pin TEXT,
                audio_track INTEGER,
                offset_ms INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stremio_cue_documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_id TEXT NOT NULL,
                generation TEXT NOT NULL,
                subtitle_addon_id TEXT NOT NULL,
                subtitle_id TEXT NOT NULL,
                subtitle_name TEXT NOT NULL,
                subtitle_file_hash TEXT NOT NULL,
                cue_count INTEGER NOT NULL DEFAULT 0,
                info_hash TEXT,
                file_idx INTEGER,
                created_at INTEGER NOT NULL,
                UNIQUE(video_id, generation, subtitle_file_hash)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stremio_cues (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                text TEXT NOT NULL,
                UNIQUE(doc_id, start_ms, text)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_stremio_cues_doc ON stremio_cues(doc_id, start_ms)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stremio_ledger (
                video_id TEXT NOT NULL,
                generation TEXT NOT NULL,
                status TEXT NOT NULL,
                doc_id INTEGER,
                cue_count INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                updated_at INTEGER NOT NULL,
                UNIQUE(video_id, generation)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stremio_watch_snapshots (
                base_id TEXT PRIMARY KEY,
                video_id TEXT,
                time_offset INTEGER NOT NULL DEFAULT 0,
                watched_blob TEXT,
                observed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
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
            val previously = db.rawQuery(
                "SELECT modified_by_user_flag, flag_color FROM scraped_photos WHERE media_store_id = ?",
                arrayOf(photo.mediaStoreId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    (cursor.getInt(0) != 0) to cursor.getStringOrNull(1)
                } else {
                    false to null
                }
            }
            values.put("modified_by_user_flag", photo.modifiedByUserFlag || previously.first)
            values.put("is_gold", photo.isGold)
            // Flag is user annotation (v11): a re-mining/REPLACE never clears it unless the
            // caller carries an explicit value; setPhotoFlag is the only other writer.
            values.put("flag_color", photo.flagColor ?: previously.second)
            db.insertWithOnConflict("scraped_photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** User-edit write path: marks actual text changes without clearing prior edit provenance.
     *  [markModified]=false is for approved-machine edits (nameplate rulings — audit FU1
     *  2026-11-23): the text changes but a ruling is not authorship, so the photo flag keeps
     *  whatever provenance it already had. */
    fun updatePhotoExtractedText(mediaStoreId: Long, amendedText: String, markModified: Boolean = true) {
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
                    modifiedByUserFlag = existing.modifiedByUserFlag ||
                        (markModified && existing.extractedText != amendedText),
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
        // Undo (user 2026-11-15): membership flips are reversible one step at a time.
        val oldGold = undoGold(mediaStoreId)
        inTransaction {
            val values = ContentValues().apply { put("is_gold", gold) }
            writableDatabase.update("scraped_photos", values, "media_store_id = ?", arrayOf(mediaStoreId.toString()))
            if (oldGold != gold) {
                recordUndo(UndoMemento(
                    UndoAction.TOGGLE_GOLD,
                    appContext.getString(if (gold) R.string.undo_label_gold_on else R.string.undo_label_gold_off),
                    listOf(mediaStoreId.toString(), if (oldGold) "1" else "0"),
                ))
            }
        }
    }

    /** v11 photo flagger (user 2026-11-15): the ONLY writer of flag_color besides upsertPhoto.
     * null clears the flag. Value is an opaque color string (palette key or #RRGGBB) — the
     * storage does not interpret it, so a future color picker needs no schema change. */
    fun setPhotoFlag(mediaStoreId: Long, color: String?) {
        val values = ContentValues().apply {
            if (color == null) putNull("flag_color") else put("flag_color", color)
        }
        writableDatabase.update("scraped_photos", values, "media_store_id = ?", arrayOf(mediaStoreId.toString()))
    }

    // ---- v11 user definitions, notes & folders (user 2026-11-15 laundry-list batch) ----

    /** Targeted UPDATE (never REPLACE) — user data, survives re-mining. */
    fun setUserDefinition(languageTag: LanguageTag, itemType: ItemType, expression: String, text: String?) {
        val values = ContentValues().apply {
            if (text.isNullOrEmpty()) putNull("user_definition") else put("user_definition", text)
        }
        writableDatabase.update(
            "extracted_items", values,
            "language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        )
    }

    fun setUserNotes(languageTag: LanguageTag, itemType: ItemType, expression: String, text: String?) {
        val values = ContentValues().apply {
            if (text.isNullOrEmpty()) putNull("user_notes") else put("user_notes", text)
        }
        writableDatabase.update(
            "extracted_items", values,
            "language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        )
    }

    /** Create-or-reuse by unique name; returns the folder id. */
    fun createWordFolder(name: String, skipInbox: Boolean): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        val db = writableDatabase
        db.insertWithOnConflict(
            "word_folders", null,
            ContentValues().apply {
                put("name", trimmed)
                put("skip_inbox", if (skipInbox) 1 else 0)
                put("created_at", System.currentTimeMillis() / 1000L)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return db.rawQuery(
            "SELECT id FROM word_folders WHERE name = ?", arrayOf(trimmed),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }

    fun listWordFolders(): List<WordFolder> = readableDatabase.rawQuery(
        """
        SELECT f.id, f.name, f.skip_inbox,
               (SELECT COUNT(*) FROM extracted_items i WHERE i.folder_id = f.id)
        FROM word_folders f ORDER BY f.name COLLATE NOCASE ASC
        """.trimIndent(),
        emptyArray(),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(WordFolder(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3)))
            }
        }
    }

    /** Deleting a folder only UNASSIGNS its words (occurrence history untouched). */
    fun deleteWordFolder(folderId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE extracted_items SET folder_id = NULL WHERE folder_id = ?",
                arrayOf(folderId.toString()),
            )
            db.delete("word_folders", "id = ?", arrayOf(folderId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Move an item into a folder (null = remove from any folder). When moving INTO a
     * skip-inbox folder, existing UNSEEN occurrences are marked SEEN (user answer 2026-11-15:
     * "Yes. ... moving a word actually clears your inbox"). Removal never un-sees anything:
     * SEEN is a triage state, not a punishment.
     */
    fun moveItemToFolder(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        folderId: Long?,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                if (folderId == null) putNull("folder_id") else put("folder_id", folderId)
            }
            db.update(
                "extracted_items", values,
                "language_tag = ? AND item_type = ? AND expression = ?",
                arrayOf(languageTag.name, itemType.name, expression),
            )
            if (folderId != null) {
                db.execSQL(
                    """
                    UPDATE item_occurrences SET state = 'SEEN'
                    WHERE language_tag = ? AND item_type = ? AND expression = ? AND state = 'UNSEEN'
                    """.trimIndent(),
                    arrayOf(languageTag.name, itemType.name, expression),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
        /** Audit FU4 (2026-11-23): gold ∧ never typed — marks made without editing, the clean
         *  side of the effort proxy (151-129 on the 2026-11-14 measurement). */
        val goldUntouchedPhotos: Int,
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

    /** Text-reprocess removal of a sentence whose key vanished: manual rows survive
     *  (gold-reconcile rule 3, user 2026-11-15) — same protection as the candidate reconcile.
     *  Image rescans keep using the blanket [deleteOccurrencesForSentence] (authorized replace). */
    fun deleteNonManualOccurrencesForSentence(photoId: Long, sentence: String) {
        writableDatabase.delete(
            "item_occurrences",
            "photo_id = ? AND sentence = ? AND is_manual = 0",
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
    data class SentenceCandidate(
        val occurrenceId: Long,
        val itemType: ItemType,
        val expression: String,
        val visibility: ItemVisibility = ItemVisibility.ACTIVE,
    )



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

    /** Soft state flip for one expression within one sentence (editor delete/restore).
     *  Audit FU2 (2026-11-23, user-approved): review is NOT authorship — the flag is no
     *  longer set by default, and it is written STICKILY (only ever raised, never cleared,
     *  so restoring a genuinely renamed/edited row keeps its authorship). is_manual stays
     *  the reconcile-protection this flip has always owned. */
    fun setOccurrenceStateForExpression(
        photoId: Long,
        sentence: String,
        expression: String,
        state: OccurrenceState,
        modifiedByUserFlag: Boolean = false,
    ): Int {
        val values = ContentValues().apply {
            put("state", state.name)
            if (modifiedByUserFlag) put("modified_by_user_flag", true)
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
        // Undo (user 2026-11-15): capture the WHOLE prior row + all occurrence rows — this
        // REPLACE drops text, blocks, gold and flag; the memento restores every column.
        val prior = undoPhotoRow(mediaStoreId)
        val priorRows = undoOccRowsForPhoto(mediaStoreId)
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
            if (prior != null) {
                recordUndo(UndoMemento(
                    UndoAction.DELETE_PHOTO,
                    appContext.getString(R.string.undo_label_photo_deleted),
                    listOf(mediaStoreId.toString()),
                    photo = prior,
                    occurrences = priorRows,
                ))
            }
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
        // Undo (user 2026-11-15): capture the prior state; no-op re-marks log nothing.
        val oldState = undoOccState(occurrenceId) ?: return
        if (oldState == OccurrenceState.SEEN.name) return
        inTransaction {
            val values = ContentValues().apply {
                put("state", OccurrenceState.SEEN.name)
            }
            writableDatabase.update(
                "item_occurrences",
                values,
                "id = ?",
                arrayOf(occurrenceId.toString()),
            )
            recordUndo(UndoMemento(
                UndoAction.MARK_OCCURRENCE_SEEN,
                appContext.getString(R.string.undo_label_seen),
                listOf(occurrenceId.toString(), oldState),
            ))
        }
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
        return saveSentenceOrder(languageTag, itemType, expression, moved)
    }

    /** Persists an explicit sentence ordering (used by drag-and-drop reorder in word detail). */
    fun saveSentenceOrder(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        orderedSentences: List<String>,
    ): Boolean {
        if (orderedSentences.isEmpty()) return false
        val distinct = orderedSentences.distinct()
        // Undo (user 2026-11-15): the prior ranks blob ("" = none) makes drags reversible.
        val oldRanks = undoSentenceRanks(languageTag.name, itemType.name, expression)
        val values = ContentValues().apply {
            put(
                "sentence_ranks",
                org.json.JSONObject(distinct.mapIndexed { i, s -> s to i }.toMap() as Map<String, Any>).toString(),
            )
        }
        return inTransaction {
            val updated = writableDatabase.update(
                "extracted_items", values,
                "language_tag = ? AND item_type = ? AND expression = ?",
                arrayOf(languageTag.name, itemType.name, expression),
            )
            if (updated == 1) {
                recordUndo(UndoMemento(
                    UndoAction.REORDER_SENTENCES,
                    appContext.getString(R.string.undo_label_order),
                    listOf(languageTag.name, itemType.name, expression, oldRanks ?: ""),
                ))
            }
            updated == 1
        }
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
        // Undo (user 2026-11-15): capture the item's prior visibility AND the exact rows this
        // flip is about to move (UNSEEN→SEEN when hiding/archiving, SEEN→UNSEEN on restore).
        val prior = undoItemVisibility(languageTag.name, itemType.name, expression)
        val flipFrom = if (visibility == ItemVisibility.ACTIVE) OccurrenceState.SEEN.name else OccurrenceState.UNSEEN.name
        val captured = prior?.let { undoItemOccurrenceIdsInState(languageTag.name, itemType.name, expression, flipFrom) } ?: emptyList()
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
            if (prior != null) {
                recordUndo(UndoMemento(
                    UndoAction.SET_ITEM_VISIBILITY,
                    appContext.getString(
                        when (visibility) {
                            ItemVisibility.HIDDEN -> R.string.undo_label_hidden
                            ItemVisibility.ARCHIVED -> R.string.undo_label_archived
                            else -> R.string.undo_label_unhidden
                        },
                    ),
                    // header: lang, type, expr, expectedNewVisibility, oldVisibility, oldIsHidden
                    listOf(languageTag.name, itemType.name, expression, visibility.name, prior.first, if (prior.second) "1" else "0"),
                    occurrences = captured,
                ))
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
        // Undo (user 2026-11-15): soft delete records its prior state.
        val oldState = undoOccState(occurrenceId) ?: return
        if (oldState == OccurrenceState.DELETED.name) return
        inTransaction {
            val values = ContentValues().apply {
                put("state", OccurrenceState.DELETED.name)
            }
            writableDatabase.update(
                "item_occurrences",
                values,
                "id = ?",
                arrayOf(occurrenceId.toString()),
            )
            recordUndo(UndoMemento(
                UndoAction.DELETE_OCCURRENCE,
                appContext.getString(R.string.undo_label_occurrence_deleted),
                listOf(occurrenceId.toString(), oldState),
            ))
        }
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
        // v13 entry picks (docs/item-meaning-reselect-proposal.md): when the row carries a
        // manual pick, the caller's AUTO-picked definition fields are overridden — resolved
        // fresh from the current bundled dictionary (re-resolve semantics) or, if the picked
        // key no longer exists in it, carried from the stored row (the stale-pick fallback).
        // Same REPLACE hazard the user-field carry below documents.
        val existing = readableDatabase.rawQuery(
            "SELECT user_definition, user_notes, folder_id, sentence_ranks, picked_headword, picked_reading, " +
                "meaning, kanji, kana, level_badge, corpus_rank " +
                "FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        ).use { c ->
            if (!c.moveToFirst()) null else PickedRowState(
                userDefinition = c.getStringOrNull(0), userNotes = c.getStringOrNull(1),
                folderId = c.getLongOrNull(2), sentenceRanks = c.getStringOrNull(3),
                pickedHeadword = c.getStringOrNull(4), pickedReading = c.getStringOrNull(5),
                meaning = c.getString(6), kanji = c.getString(7), kana = c.getString(8),
                levelBadge = c.getStringOrNull(9), corpusRank = c.getIntOrNull(10),
            )
        }
        val pickKey = existing?.pickedHeadword?.let { h -> existing.pickedReading?.let { r -> h to r } }
        val effectiveMeaning: String
        val effectiveKana: String
        val effectiveLevel: String?
        val effectiveRank: Int?
        val effectiveKanji: String
        if (pickKey == null) {
            effectiveMeaning = meaning; effectiveKana = kana; effectiveLevel = levelBadge
            effectiveRank = corpusRank; effectiveKanji = sanitizedKanji
        } else {
            val resolved = resolveItemPick(languageTag, pickKey.first, pickKey.second)
            effectiveMeaning = resolved?.meaning ?: existing!!.meaning
            effectiveKana = resolved?.kana ?: existing!!.kana
            effectiveLevel = resolved?.levelBadge ?: existing!!.levelBadge
            effectiveRank = resolved?.corpusRank ?: existing!!.corpusRank
            val rawKanji = resolved?.kanji ?: existing!!.kanji
            effectiveKanji = if (expression.any { MlKitImageTagger.isCjkIdeograph(it) } &&
                rawKanji.any { MlKitImageTagger.isCjkIdeograph(it) }
            ) rawKanji else ""
        }
        val values = ContentValues().apply {
            put("language_tag", languageTag.name)
            put("item_type", itemType.name)
            put("expression", expression)
            put("kanji", effectiveKanji)
            put("kana", effectiveKana)
            put("meaning", effectiveMeaning)
            put("level_badge", effectiveLevel)
            if (effectiveRank != null) put("corpus_rank", effectiveRank) else putNull("corpus_rank")
            put("anki_account_id", ankiAccountId)
            if (deckId != null) put("deck_id", deckId) else putNull("deck_id")
            put("deck_name", deckName)
            if (ankiNoteId != null) put("anki_note_id", ankiNoteId) else putNull("anki_note_id")
            put("is_hidden", if (isHidden || visibility != ItemVisibility.ACTIVE) 1 else 0)
            put("visibility_state", visibility.name)
            // CONFLICT_REPLACE deletes + reinserts, so USER data on this row must be carried
            // across or re-mining silently erases it (same hazard upsertPhoto documents for
            // flag_color). Targeted setters below remain the only writers.
            existing?.let { q ->
                if (q.userDefinition != null) put("user_definition", q.userDefinition) else putNull("user_definition")
                if (q.userNotes != null) put("user_notes", q.userNotes) else putNull("user_notes")
                if (q.folderId != null) put("folder_id", q.folderId) else putNull("folder_id")
                if (q.sentenceRanks != null) put("sentence_ranks", q.sentenceRanks) else putNull("sentence_ranks")
                if (q.pickedHeadword != null) put("picked_headword", q.pickedHeadword) else putNull("picked_headword")
                if (q.pickedReading != null) put("picked_reading", q.pickedReading) else putNull("picked_reading")
            }
        }
        writableDatabase.insertWithOnConflict("extracted_items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private data class PickedRowState(
        val userDefinition: String?,
        val userNotes: String?,
        val folderId: Long?,
        val sentenceRanks: String?,
        val pickedHeadword: String?,
        val pickedReading: String?,
        val meaning: String,
        val kanji: String,
        val kana: String,
        val levelBadge: String?,
        val corpusRank: Int?,
    )

    // ---- v13 entry picks (docs/item-meaning-reselect-proposal.md, user go 2026-11-23) ----

    private val pickDictionaryRepository by lazy { DictionaryRepository(appContext) }
    private val pickHskRepository by lazy { runCatching { HskRepository(appContext) }.getOrNull() }

    private fun resolveItemPick(languageTag: LanguageTag, headword: String, reading: String): DictionaryMetadata? =
        runCatching {
            BundledDictionaryProvider(languageTag, pickDictionaryRepository, pickHskRepository)
                .metadataForPick(headword, reading)
        }.getOrNull()

    /**
     * Applies (or resets) the user's system-definition pick for one item. Fields are copied
     * write-through — meaning/kanji/kana/level_badge/corpus_rank become the chosen entry's, so
     * every consumer (word view, wizard, export) reads the pick without knowing about it — and
     * the pick columns record WHY. Null/null resets to the automatic first-match behavior
     * (recomputed immediately so the stored row reflects the auto winner again).
     * One undo step (ITEM_MEANING_PICK, seven prior columns). Grammar items have no candidate
     * list: for them only the RESET path is meaningful; picking requires a WORD resolution and
     * returns false when the key is not in the bundled dictionary.
     */
    fun pickItemDefinition(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        pickedHeadword: String?,
        pickedReading: String?,
    ): Boolean {
        val db = writableDatabase
        val prior = db.rawQuery(
            "SELECT meaning, kanji, kana, level_badge, corpus_rank, picked_headword, picked_reading " +
                "FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag.name, itemType.name, expression),
        ).use { c ->
            if (!c.moveToFirst()) return false
            PriorItemFields(
                c.getString(0), c.getString(1), c.getString(2),
                c.getStringOrNull(3), c.getIntOrNull(4), c.getStringOrNull(5), c.getStringOrNull(6),
            )
        }
        val hasPick = pickedHeadword != null && pickedReading != null
        val fields: DictionaryMetadata? = if (hasPick) {
            resolveItemPick(languageTag, pickedHeadword!!, pickedReading!!) ?: return false
        } else {
            runCatching {
                BundledDictionaryProvider(languageTag, pickDictionaryRepository, pickHskRepository)
                    .lookup(expression, itemType, null)
            }.getOrNull()
        }
        val newMeaning = fields?.meaning ?: prior.meaning
        val newKana = fields?.kana ?: prior.kana
        val newLevel = if (fields != null) fields.levelBadge else prior.levelBadge
        val newRank = if (fields != null) fields.corpusRank else prior.corpusRank
        val rawKanji = fields?.kanji ?: prior.kanji
        val newKanji = if (expression.any { MlKitImageTagger.isCjkIdeograph(it) } &&
            rawKanji.any { MlKitImageTagger.isCjkIdeograph(it) }
        ) rawKanji else ""

        inTransaction {
            recordUndo(UndoMemento(
                UndoAction.ITEM_MEANING_PICK,
                appContext.getString(R.string.undo_label_definition_pick),
                // header: key, expected-after guard, then the seven prior column values
                // ("" = NULL sentinel for the nullable ones).
                listOf(
                    languageTag.name, itemType.name, expression, newMeaning,
                    prior.meaning, prior.kanji, prior.kana,
                    prior.levelBadge ?: "", prior.corpusRank?.toString() ?: "",
                    prior.pickedHeadword ?: "", prior.pickedReading ?: "",
                ),
            ))
            val values = ContentValues().apply {
                put("meaning", newMeaning)
                put("kanji", newKanji)
                put("kana", newKana)
                if (newLevel != null) put("level_badge", newLevel) else putNull("level_badge")
                if (newRank != null) put("corpus_rank", newRank) else putNull("corpus_rank")
                if (hasPick) {
                    put("picked_headword", pickedHeadword)
                    put("picked_reading", pickedReading)
                } else {
                    putNull("picked_headword")
                    putNull("picked_reading")
                }
            }
            db.update(
                "extracted_items", values,
                "language_tag = ? AND item_type = ? AND expression = ?",
                arrayOf(languageTag.name, itemType.name, expression),
            )
        }
        return true
    }

    private data class PriorItemFields(
        val meaning: String,
        val kanji: String,
        val kana: String,
        val levelBadge: String?,
        val corpusRank: Int?,
        val pickedHeadword: String?,
        val pickedReading: String?,
    )

    internal fun undoItemMeaningCurrent(languageTag: String, itemType: String, expression: String): String? =
        readableDatabase.rawQuery(
            "SELECT meaning FROM extracted_items WHERE language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag, itemType, expression),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    internal fun restoreItemMeaningFields(
        languageTag: String,
        itemType: String,
        expression: String,
        meaning: String,
        kanji: String,
        kana: String,
        levelBadge: String?,
        corpusRank: Int?,
        pickedHeadword: String?,
        pickedReading: String?,
    ) {
        val values = ContentValues().apply {
            put("meaning", meaning)
            put("kanji", kanji)
            put("kana", kana)
            if (levelBadge != null) put("level_badge", levelBadge) else putNull("level_badge")
            if (corpusRank != null) put("corpus_rank", corpusRank) else putNull("corpus_rank")
            if (pickedHeadword != null) put("picked_headword", pickedHeadword) else putNull("picked_headword")
            if (pickedReading != null) put("picked_reading", pickedReading) else putNull("picked_reading")
        }
        writableDatabase.update(
            "extracted_items", values,
            "language_tag = ? AND item_type = ? AND expression = ?",
            arrayOf(languageTag, itemType, expression),
        )
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
        // v11 folder skip (user 2026-11-15): an item filed in a skip-inbox folder is born SEEN —
        // its occurrences never enter the 出現 inbox. runCatching: harness-built partial DBs may
        // lack word_folders; the folder check degrades to "no skip", never crashes the insert.
        val folderSkipsInbox = runCatching {
            readableDatabase.rawQuery(
                """
                SELECT 1 FROM extracted_items i JOIN word_folders f ON i.folder_id = f.id
                WHERE i.language_tag = ? AND i.item_type = ? AND i.expression = ? AND f.skip_inbox = 1
                """.trimIndent(),
                arrayOf(languageTag.name, itemType.name, expression),
            ).use { it.moveToFirst() }
        }.getOrDefault(false)

        val sentenceModified = modifiedByUserFlag || readableDatabase.rawQuery(
            "SELECT 1 FROM item_occurrences WHERE photo_id = ? AND sentence = ? AND modified_by_user_flag = 1 LIMIT 1",
            arrayOf(photoId.toString(), sentence),
        ).use { it.moveToFirst() }
        val initialState = if (isHidden || folderSkipsInbox) OccurrenceState.SEEN else OccurrenceState.UNSEEN
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
        private const val DB_VERSION = 14

        /** For RecoveryManager: schema expectations of the live corpus db. */
        internal fun expectedDbName(): String = DB_NAME
        internal fun expectedUserVersion(): Int = DB_VERSION
    }
}
