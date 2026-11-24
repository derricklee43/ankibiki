package com.derricklee.ankidict

import android.content.ContentValues
import org.json.JSONObject

/**
 * Stremio Mining Console storage (design §6; extensions follow the MiningNameplateWrites.kt
 * pattern). Tables land in schema v14. NOTHING here touches photo machinery: stremio
 * occurrence rows are scoped by the negative photo_id namespace (§6.1 stage-1 shape) and
 * every helper here filters on photo_id < 0 so a positive id can never be deleted by
 * accident.
 */

data class StremioSettingsRow(
    val scopeKey: String,
    val subtitleLang: String,
    val subtitlePattern: String?,
    val subtitlePin: String?,
    val audioTrack: Int?,
    val offsetMs: Int,
) {
    /** Settings generation: content hash (design 6.1: never an increment). */
    fun generationSeed(): String =
        "$subtitleLang|$subtitlePattern|$subtitlePin|$audioTrack|$offsetMs"
}

data class StremioLedgerRow(
    val videoId: String,
    val generation: String,
    val status: String,
    val docId: Long?,
    val cueCount: Int,
    val error: String?,
)

object StremioLedgerStatus {
    const val QUEUED = "queued"
    const val FETCHING = "fetching"
    const val PARSING = "parsing"
    const val MINED = "mined"
    const val FAILED = "failed"
}

/** Replace the local mirror of the cloud library (refresh = full replace, it is a cache). */
fun MiningRepository.replaceStremioLibrary(items: List<StremioLibraryItem>, now: Long) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.delete("stremio_library", null, null)
        for (item in items) {
            db.insert(
                "stremio_library",
                null,
                ContentValues().apply {
                    put("base_id", item.baseId)
                    put("name", item.name)
                    put("type", item.type)
                    put("poster", item.poster)
                    put("video_id", item.state?.videoId)
                    put("time_offset", item.state?.timeOffset ?: 0L)
                    put("duration", item.state?.duration ?: 0L)
                    put("watched_blob", item.state?.watchedBlob)
                    put("times_watched", item.state?.timesWatched ?: 0)
                    put("last_seen", now)
                },
            )
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

fun MiningRepository.loadStremioLibrary(): List<StremioLibraryItem> =
    readableDatabase.rawQuery(
        "SELECT base_id, name, type, poster, video_id, time_offset, duration, watched_blob," +
        " times_watched FROM stremio_library ORDER BY name",
        null,
    ).use { c ->
        val out = ArrayList<StremioLibraryItem>()
        while (c.moveToNext()) {
            out += StremioLibraryItem(
                baseId = c.getString(0),
                type = c.getString(2),
                name = c.getString(1),
                poster = c.getStringOrNull(3),
                state = StremioItemState(
                    videoId = c.getStringOrNull(4),
                    timeOffset = c.getLong(5),
                    duration = c.getLong(6),
                    lastWatched = null,
                    timesWatched = c.getInt(8),
                    flaggedWatched = 0,
                    watchedBlob = c.getStringOrNull(7),
                ),
            )
        }
        out.sortedBy { it.name }
    }

fun MiningRepository.replaceStremioAddons(addons: List<StremioAddon>, now: Long) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.delete("stremio_addons", null, null)
        for (addon in addons) {
            db.insert(
                "stremio_addons",
                null,
                ContentValues().apply {
                    put("addon_id", addon.addonId)
                    put("name", addon.name)
                    put("transport_url", addon.transportUrl)
                    put("types_csv", addon.resourceTypes.joinToString(","))
                    put("updated_at", now)
                },
            )
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

fun MiningRepository.loadStremioAddons(): List<StremioAddon> =
    readableDatabase.rawQuery(
        "SELECT addon_id, name, transport_url, types_csv FROM stremio_addons ORDER BY addon_id",
        null,
    ).use { c ->
        val out = ArrayList<StremioAddon>()
        while (c.moveToNext()) {
            out += StremioAddon(
                addonId = c.getString(0),
                name = c.getString(1),
                version = "",
                transportUrl = c.getString(2),
                resourceTypes = c.getString(3).split(',').filter { it.isNotEmpty() }.toSet(),
            )
        }
        out
    }

/** The addon whose resources mention a subtitle family (protocol name variants tolerated). */
fun List<StremioAddon>.firstSubtitleAddon(): StremioAddon? = firstOrNull { addon ->
    addon.resourceTypes.any { it.lowercase().startsWith("subtitle") }
}

fun List<StremioAddon>.firstMetaAddon(): StremioAddon? = firstOrNull { addon ->
    addon.resourceTypes.any { it.lowercase() == "meta" }
}

fun List<StremioAddon>.firstStreamAddon(): StremioAddon? = firstOrNull { addon ->
    addon.resourceTypes.any { it.lowercase().startsWith("stream") }
}

/** D1 scope chain: episode row > show row > global > built-in default. */
fun MiningRepository.stremioSettingsFor(videoId: String, baseId: String): StremioSettingsRow {
    for (scope in listOf(videoId, baseId, "global")) {
        readStremioSettings(scope)?.let { return it }
    }
    return StremioSettingsRow("global", "jpn", null, null, null, 0)
}

fun MiningRepository.readStremioSettings(scopeKey: String): StremioSettingsRow? =
    readableDatabase.rawQuery(
        "SELECT scope_key, subtitle_lang, subtitle_pattern, subtitle_pin, audio_track, offset_ms" +
            " FROM stremio_settings WHERE scope_key = ?",
        arrayOf(scopeKey),
    ).use { c ->
        if (!c.moveToFirst()) return null
        StremioSettingsRow(
            scopeKey = c.getString(0),
            subtitleLang = c.getString(1),
            subtitlePattern = c.getStringOrNull(2),
            subtitlePin = c.getStringOrNull(3),
            audioTrack = if (c.isNull(4)) null else c.getInt(4),
            offsetMs = c.getInt(5),
        )
    }

fun MiningRepository.saveStremioSettings(row: StremioSettingsRow, now: Long) {
    writableDatabase.insertWithOnConflict(
        "stremio_settings",
        null,
        ContentValues().apply {
            put("scope_key", row.scopeKey)
            put("subtitle_lang", row.subtitleLang)
            put("subtitle_pattern", row.subtitlePattern)
            put("subtitle_pin", row.subtitlePin)
            if (row.audioTrack == null) putNull("audio_track") else put("audio_track", row.audioTrack)
            put("offset_ms", row.offsetMs)
            put("updated_at", now)
        },
        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
}

fun MiningRepository.upsertStremioLedger(
    videoId: String,
    generation: String,
    status: String,
    docId: Long? = null,
    cueCount: Int = 0,
    error: String? = null,
    now: Long = System.currentTimeMillis(),
) {
    writableDatabase.insertWithOnConflict(
        "stremio_ledger",
        null,
        ContentValues().apply {
            put("video_id", videoId)
            put("generation", generation)
            put("status", status)
            if (docId == null) putNull("doc_id") else put("doc_id", docId)
            put("cue_count", cueCount)
            if (error == null) putNull("error") else put("error", error)
            put("updated_at", now)
        },
        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
}

/** Latest ledger state per episode, for the episode-list chips (map videoId -> row). */
fun MiningRepository.loadStremioLedgerByEpisode(): Map<String, StremioLedgerRow> =
    readableDatabase.rawQuery(
        "SELECT video_id, generation, status, doc_id, cue_count, error FROM stremio_ledger" +
            " ORDER BY updated_at DESC",
        null,
    ).use { c ->
        val out = LinkedHashMap<String, StremioLedgerRow>()
        while (c.moveToNext()) {
            val videoId = c.getString(0)
            if (out.containsKey(videoId)) continue // newest wins
            out[videoId] = StremioLedgerRow(
                videoId = videoId,
                generation = c.getString(1),
                status = c.getString(2),
                docId = if (c.isNull(3)) null else c.getLong(3),
                cueCount = c.getInt(4),
                error = c.getStringOrNull(5),
            )
        }
        out
    }

/**
 * Find-or-create the cue document for (videoId, generation, fileHash). Re-extraction with
 * identical settings + identical file returns the SAME doc id: the caller then replaces
 * its occurrences + cues in place (idempotency, design §6) instead of piling generations
 * of byte-identical rows.
 */
fun MiningRepository.findStremioDocId(videoId: String, generation: String, fileHash: String): Long? =
    readableDatabase.rawQuery(
        "SELECT id FROM stremio_cue_documents WHERE video_id = ? AND generation = ?" +
            " AND subtitle_file_hash = ?",
        arrayOf(videoId, generation, fileHash),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

fun MiningRepository.createStremioDoc(
    videoId: String,
    generation: String,
    choice: SubtitleChoice,
    fileHash: String,
    infoHash: String?,
    fileIdx: Int?,
    now: Long,
): Long = writableDatabase.insert(
    "stremio_cue_documents",
    null,
    ContentValues().apply {
        put("video_id", videoId)
        put("generation", generation)
        put("subtitle_addon_id", choice.addonId)
        put("subtitle_id", choice.subId)
        put("subtitle_name", choice.releaseName)
        put("subtitle_file_hash", fileHash)
        put("info_hash", infoHash)
        if (fileIdx == null) putNull("file_idx") else put("file_idx", fileIdx)
        put("created_at", now)
    },
)

/** Replace all cues for a doc (delete-then-insert keeps re-runs deterministic). */
fun MiningRepository.replaceStremioCues(docId: Long, cues: List<SubtitleCue>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.delete("stremio_cues", "doc_id = ?", arrayOf(docId.toString()))
        for (cue in cues) {
            db.insertWithOnConflict(
                "stremio_cues",
                null,
                ContentValues().apply {
                    put("doc_id", docId)
                    put("start_ms", cue.startMs)
                    put("end_ms", cue.endMs)
                    put("text", cue.text)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    db.execSQL(
        "UPDATE stremio_cue_documents SET cue_count =" +
            " (SELECT COUNT(*) FROM stremio_cues WHERE doc_id = ?) WHERE id = ?",
        arrayOf(docId.toString(), docId.toString()),
    )
}

/**
 * Guarded removal of the occurrence rows a cue doc attested to. The negative-only WHERE
 * makes it IMPOSSIBLE to hit photo rows: photo_id = -docId AND photo_id < 0 double lock.
 */
fun MiningRepository.deleteStremioOccurrencesForDoc(docId: Long): Int =
    writableDatabase.delete(
        "item_occurrences",
        "photo_id = ? AND photo_id < 0",
        arrayOf((-docId).toString()),
    )

fun MiningRepository.saveStremioWatchSnapshot(
    baseId: String,
    state: StremioItemState?,
    now: Long,
) {
    writableDatabase.insertWithOnConflict(
        "stremio_watch_snapshots",
        null,
        ContentValues().apply {
            put("base_id", baseId)
            put("video_id", state?.videoId)
            put("time_offset", state?.timeOffset ?: 0L)
            put("watched_blob", state?.watchedBlob)
            put("observed_at", now)
        },
        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
}

/** JSON dump of a doc's cues for debugging/inspection from any screen. */
fun MiningRepository.stremioDocCuesJson(docId: Long): String {
    val arr = org.json.JSONArray()
    readableDatabase.rawQuery(
        "SELECT start_ms, end_ms, text FROM stremio_cues WHERE doc_id = ? ORDER BY start_ms",
        arrayOf(docId.toString()),
    ).use { c ->
        while (c.moveToNext()) {
            arr.put(JSONObject().put("start", c.getLong(0)).put("end", c.getLong(1)).put("text", c.getString(2)))
        }
    }
    return arr.toString()
}
