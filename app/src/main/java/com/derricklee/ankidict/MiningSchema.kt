package com.derricklee.ankidict

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Schema DDL and one-off legacy data fixups invoked from onCreate/onUpgrade (roadmap D2
 * split, part 2). Moved VERBATIM from MiningRepository as extension functions — table
 * semantics and the migration guards inside each are unchanged.
 */

internal fun MiningRepository.createNameplateClaimsSchema(db: SQLiteDatabase) {
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

/**
 * Per-island audit log of the S003-D' OCR correction gate (spikes/003-gate-upgrades).
 * One row for EVERY disagreement island — applied or not — so annotation feedback can
 * score gate precision and every auto-write is reversible (line_before/line_after).
 */
internal fun MiningRepository.createOcrCorrectionsSchema(db: SQLiteDatabase) {
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

internal fun MiningRepository.cardTemplatesDdl(): String =
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

internal fun MiningRepository.cleanPureKanaKanjiBackfill(db: SQLiteDatabase) {
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
