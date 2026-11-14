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
        db.execSQL(cardTemplatesDdl())
        createOcrCorrectionsSchema(db)
        createNameplateClaimsSchema(db)
    }

    private fun createNameplateClaimsSchema(db: SQLiteDatabase) {
        // Audit ledger for speaker-nameplate guesses (user 2026-11-13): the mining proxy DRAFTS
        // a plate per leaked sentence; NOTHING is hidden until the user rules on it in the photo
        // view. verdict NULL = pending (line still visible), 'correct' = genuine plate -> hidden
        // for good (sentence stripped from text + occurrences), 'incorrect' = real content ->
        // kept and never re-claimed for this photo. Rows are derivable: the raw ocr_blocks_json
        // stays authoritative (roadmap guardrail G3), so claims are a UI/audit convenience and a
        // label stream for the exclusion spike — deleting the table loses no truth.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS nameplate_claims (
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
            "CREATE INDEX IF NOT EXISTS idx_nameplate_claims_photo ON nameplate_claims(photo_id, verdict)",
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
    }

    /**
     * Per-island audit log of the S003-D' OCR correction gate (spikes/003-gate-upgrades).
     * One row for EVERY disagreement island — applied or not — so annotation feedback can
     * score gate precision and every auto-write is reversible (line_before/line_after).
     */
    private fun createOcrCorrectionsSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ocr_corrections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_id INTEGER NOT NULL,
                line_index INTEGER NOT NULL,
                a_start INTEGER NOT NULL,
                a_end INTEGER NOT NULL,
                a_text TEXT NOT NULL,
                b_text TEXT NOT NULL,
                kind TEXT NOT NULL,
                margin REAL NOT NULL,
                anchored_a INTEGER NOT NULL,
                anchored_b INTEGER NOT NULL,
                confusable INTEGER NOT NULL,
                nameplate_excluded INTEGER NOT NULL,
                decision TEXT NOT NULL,
                reason TEXT,
                line_before TEXT NOT NULL,
                line_after TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_ocr_corrections_photo
                ON ocr_corrections(photo_id)
            """.trimIndent(),
        )
    }

    private fun cardTemplatesDdl(): String =
        """
        CREATE TABLE IF NOT EXISTS card_templates (
            deck_id INTEGER NOT NULL,
            model_id INTEGER NOT NULL,
            ordinal INTEGER NOT NULL,
            bound_field TEXT,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (deck_id, model_id, ordinal)
        )
        """.trimIndent()

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

    /** Gold-set membership straight from the flag (what build_gold_fixtures.py selects on). */
    fun getGoldPhotoIds(): List<Long> =
        readableDatabase.rawQuery(
            "SELECT media_store_id FROM scraped_photos WHERE is_gold = 1 ORDER BY media_store_id",
            null,
        ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }

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

    fun nameplateClaims(mediaStoreId: Long): List<NameplateClaim> =
        readableDatabase.rawQuery(
            "SELECT id, text, context_sentence, verdict FROM nameplate_claims WHERE photo_id = ? ORDER BY id",
            arrayOf(mediaStoreId.toString()),
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) NameplateClaim(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null
            }.toList()
        }

    /** CJK projections of confirmed STANDALONE plates — the sentence filter for future mine/rescan passes. */
    fun hiddenNameplateCores(mediaStoreId: Long): Set<String> =
        readableDatabase.rawQuery(
            "SELECT text FROM nameplate_claims WHERE photo_id = ? AND verdict = 'correct' AND context_sentence = ''",
            arrayOf(mediaStoreId.toString()),
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }

    /** Confirmed INLINE cuts (plate, sentence): rescan re-excises these before display/persistence. */
    fun hiddenInlineClaims(mediaStoreId: Long): List<NameplateClaim> =
        readableDatabase.rawQuery(
            "SELECT id, text, context_sentence, verdict FROM nameplate_claims " +
                "WHERE photo_id = ? AND verdict = 'correct' AND context_sentence != ''",
            arrayOf(mediaStoreId.toString()),
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) NameplateClaim(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null
            }.toList()
        }

    /**
     * Records the user's verdict and settles the plate "for good":
     * correct -> the matching display lines are stripped from the photo text and their
     * occurrence rows deleted (raw ocr_blocks_json untouched; later mine/rescan passes
     * filter the sentence list through hiddenNameplateCores, so it never comes back);
     * incorrect -> nothing is removed and the claim is never re-suggested for this photo.
     * Returns the number of display lines removed (0 for incorrect or no-match).
     */
    fun applyNameplateVerdict(claimId: Long, correct: Boolean, now: Long = System.currentTimeMillis()): Int {
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
    fun backfillNameplateClaims(inlineLexicon: GateLexicon? = null): Int {
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

    /**
     * Nameplate strings the cloud-gate should treat as plate-adjacent (roadmap shipping note,
     * spike 004 verdict): the recurrence lexicon (H3 — the ONLY zero-casualty signal measured)
     * plus every ledger-confirmed plate. REPLACES the A0 structural proxy as the gate's upstream
     * exclusion input: A0 flagged 4 genuine content lines (ヘヘヘ-class), which as gate input
     * silently suppressed correct fixes near content. Derived + recomputable (G3): pure function
     * of stored texts and user verdicts.
     */
    fun nameplateGateStrings(): Set<String> {
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

    /** Saved field mapping for one (deck, card type); absent rows read as unbound. */
    fun loadCardTemplate(deckId: Long, modelId: Long): List<CardTemplateMapping> =
        readableDatabase.rawQuery(
            "SELECT ordinal, bound_field FROM card_templates WHERE deck_id = ? AND model_id = ? ORDER BY ordinal ASC",
            arrayOf(deckId.toString(), modelId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(CardTemplateMapping(c.getInt(0), c.getStringOrNull(1))) } }

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

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    companion object {
        private const val DB_NAME = "mining.db"
        private const val DB_VERSION = 9

        /** For RecoveryManager: schema expectations of the live corpus db. */
        internal fun expectedDbName(): String = DB_NAME
        internal fun expectedUserVersion(): Int = DB_VERSION
    }
}
