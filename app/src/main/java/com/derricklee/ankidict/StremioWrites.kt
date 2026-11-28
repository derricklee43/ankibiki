package com.derricklee.ankidict

import android.content.ContentValues
import org.json.JSONObject

/**
 * Stremio Mining Console storage (design §6; extensions follow the MiningNameplateWrites.kt
 * pattern). Tables land in schema v14. NOTHING here touches photo machinery: v15
 * (docs/sentence-media-refs-design.md) gives video occurrence rows a media_refs id of
 * kind 'video_cues' with photo_id NULL — the stage-1 negative-photo_id namespace was
 * retired by the v15 migration, and every helper stays kind-scoped so a photo row can
 * never be touched from the video path.
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

/**
 * Subtitle-capable addons, RANKED per user preference (2026-11-27):
 *   1. Anime Kitsu (`community.anime.kitsu`)
 *   [Builtin Jimaku runs immediately after Kitsu in StremioExtractionEngine]
 *   2. OpenSubtitles (`org.stremio.opensubtitles`)
 *   3. OpenSubtitles v3 (`org.stremio.opensubtitlesv3`)
 *   4. Other pure-subtitle addons
 *   5. Other mixed-resource subtitle addons
 *   6. Buta No Subs (`com.buta.no.subs.stremio.addon`) — LAST resort
 */
fun List<StremioAddon>.subtitleAddonCandidates(): List<StremioAddon> =
    mapNotNull { addon ->
        val subs = addon.resourceTypes.count { it.lowercase().startsWith("subtitle") }
        if (subs == 0) return@mapNotNull null
        val rank = subtitleAddonRank(addon, isPureSubtitle = subs == addon.resourceTypes.size)
        addon to rank
    }.sortedBy { it.second }
     .map { it.first }

internal fun subtitleAddonRank(addon: StremioAddon, isPureSubtitle: Boolean): Int = when (addon.addonId) {
    SUBTITLE_ADDON_KITSU -> 0
    SUBTITLE_ADDON_OPENSUBS -> 2
    SUBTITLE_ADDON_OPENSUBS_V3 -> 3
    SUBTITLE_ADDON_BUTA -> 6
    else -> if (isPureSubtitle) 4 else 5
}

internal const val SUBTITLE_ADDON_KITSU = "community.anime.kitsu"
internal const val SUBTITLE_ADDON_OPENSUBS = "org.stremio.opensubtitles"
internal const val SUBTITLE_ADDON_OPENSUBS_V3 = "org.stremio.opensubtitlesv3"
internal const val SUBTITLE_ADDON_BUTA = "com.buta.no.subs.stremio.addon"

fun List<StremioAddon>.firstMetaAddon(): StremioAddon? = firstOrNull { addon ->
    addon.resourceTypes.any { it.lowercase() == "meta" }
}

/** Stream-capable addons ranked with non-local addons (e.g. Torrentio RD) before org.stremio.local. */
fun List<StremioAddon>.streamAddonCandidates(): List<StremioAddon> =
    filter { addon -> addon.resourceTypes.any { it.lowercase().startsWith("stream") } }
        .sortedBy { if (it.addonId == "org.stremio.local") 1 else 0 }

fun List<StremioAddon>.firstStreamAddon(): StremioAddon? =
    streamAddonCandidates().firstOrNull()

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
 * Guarded removal of the occurrence rows a cue doc attested to. v15 shape:
 * the kind-scoped media_ref lookup is the lock — photo rows point at
 * kind='photo' refs, so a video_cues ref id can never select them.
 */
fun MiningRepository.deleteStremioOccurrencesForDoc(docId: Long): Int =
    writableDatabase.delete(
        "item_occurrences",
        "media_id = (SELECT id FROM media_refs WHERE kind = 'video_cues' AND ref_key = ?)",
        arrayOf(docId.toString()),
    )

/**
 * I1 dedupe (stage-2 plan; all-kinds inbox ruling D3): when a NEW generation
 * mines the same video_id, the previous docs' refs go inactive. Their
 * occurrence rows stay for audit (D6 coexistence kept) but every reader
 * filters media_refs.active = 1, so one episode counts once. Returns retired
 * ref count.
 */
fun MiningRepository.retireStaleVideoMediaRefs(videoId: String, keepDocId: Long): Int {
    val c = ContentValues().apply { put("active", 0) }
    return writableDatabase.update(
        "media_refs", c,
        "kind = 'video_cues' AND active = 1 AND ref_int IN " +
            "(SELECT id FROM stremio_cue_documents WHERE video_id = ? AND id <> ?)",
        arrayOf(videoId, keepDocId.toString()),
    )
}

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

/** Load stored cues for a mined doc (Stage 3 slice harness / preview). */
fun MiningRepository.loadStremioCues(docId: Long): List<SubtitleCue> =
    readableDatabase.rawQuery(
        "SELECT start_ms, end_ms, text FROM stremio_cues WHERE doc_id = ? ORDER BY start_ms",
        arrayOf(docId.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(SubtitleCue(c.getLong(0), c.getLong(1), c.getString(2)))
        }
    }

/** Read stored (infoHash, fileIdx) identity for a cue doc. */
fun MiningRepository.loadStremioDocIdentity(docId: Long): Pair<String?, Int?> =
    readableDatabase.rawQuery(
        "SELECT info_hash, file_idx FROM stremio_cue_documents WHERE id = ?",
        arrayOf(docId.toString()),
    ).use { c ->
        if (!c.moveToFirst()) null to null
        else c.getStringOrNull(0) to if (c.isNull(1)) null else c.getInt(1)
    }

/** Backfill (infoHash, fileIdx) onto an already-mined cue doc once resolved. */
fun MiningRepository.updateStremioDocIdentity(docId: Long, infoHash: String?, fileIdx: Int?) {
    if (infoHash.isNullOrBlank()) return
    writableDatabase.execSQL(
        "UPDATE stremio_cue_documents SET info_hash = ?, file_idx = ? WHERE id = ?",
        arrayOf(infoHash.lowercase(), fileIdx, docId),
    )
}

/** Resolved episode + cue timing for a `video_cues` sentence (Stage 3 Step 3 lazy slicer). */
data class VideoCueContext(
    val docId: Long,
    val videoId: String,
    val baseId: String,
    val metaType: String,
    val infoHash: String?,
    val fileIdx: Int?,
    val cue: SubtitleCue,
)

/** Looks up the cue document + timestamp window for a `video_cues` (mediaId, sentence) pair. */
fun MiningRepository.findVideoCueContext(mediaId: Long, sentence: String): VideoCueContext? {
    if (mediaId < 0L || sentence.isBlank()) return null
    return runCatching {
        val docId = readableDatabase.rawQuery(
            "SELECT ref_int FROM media_refs WHERE id = ? AND kind = 'video_cues'",
            arrayOf(mediaId.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else return@runCatching null }

        val doc = readableDatabase.rawQuery(
            "SELECT video_id, info_hash, file_idx FROM stremio_cue_documents WHERE id = ?",
            arrayOf(docId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return@runCatching null
            Triple(c.getString(0), c.getStringOrNull(1), if (c.isNull(2)) null else c.getInt(2))
        }
        val videoId = doc.first
        val baseId = VideoSourceLabel.baseId(videoId) ?: videoId
        val metaType = readableDatabase.rawQuery(
            "SELECT type FROM stremio_library WHERE base_id = ?",
            arrayOf(baseId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "series" }

        val cue = readableDatabase.rawQuery(
            "SELECT start_ms, end_ms, text FROM stremio_cues WHERE doc_id = ? AND text = ? ORDER BY start_ms LIMIT 1",
            arrayOf(docId.toString(), sentence),
        ).use { c ->
            if (!c.moveToFirst()) return@runCatching null
            SubtitleCue(c.getLong(0), c.getLong(1), c.getString(2))
        }
        VideoCueContext(
            docId = docId,
            videoId = videoId,
            baseId = baseId,
            metaType = metaType,
            infoHash = doc.second,
            fileIdx = doc.third,
            cue = cue,
        )
    }.getOrNull()
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

data class EpisodeDeleteSummary(
    val docsDeleted: Int,
    val occurrencesDeleted: Int,
    val clipsDeleted: Int,
)

/**
 * Hard-deletes all mined corpus data for a single Stremio episode ([videoId]):
 *  - all `item_occurrences` belonging to that episode's `video_cues` media refs
 *  - all `saved_video_clips` rows and their permanent `.webp`/`.m4a` files
 *  - all `media_refs`, `stremio_cues`, `stremio_cue_documents`, `stremio_ledger`,
 *    and per-episode `stremio_settings` rows for [videoId]
 *  - evicts warm Media3 sessions & cached slice files for those docs
 *
 * Canonical `extracted_items` rows, Anki links, photos, and other episodes are untouched.
 */
fun MiningRepository.deleteMinedEpisode(
    context: android.content.Context,
    videoId: String,
): EpisodeDeleteSummary {
    val docIds = ArrayList<Long>()
    val mediaIds = ArrayList<Long>()
    val clipFilesToDelete = ArrayList<Pair<String, String>>()
    var occDeleted = 0
    var clipsDeleted = 0
    var docsDeleted = 0

    inTransaction {
        val db = writableDatabase
        db.rawQuery(
            "SELECT id FROM stremio_cue_documents WHERE video_id = ?",
            arrayOf(videoId),
        ).use { c ->
            while (c.moveToNext()) docIds += c.getLong(0)
        }

        for (docId in docIds) {
            db.rawQuery(
                "SELECT id FROM media_refs WHERE kind = 'video_cues' AND (ref_int = ? OR ref_key = ?)",
                arrayOf(docId.toString(), docId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val mid = c.getLong(0)
                    if (mid !in mediaIds) mediaIds += mid
                }
            }
        }

        // Collect saved clip files keyed by video_id or any of the episode's media_ids.
        db.rawQuery(
            "SELECT image_file, audio_file FROM saved_video_clips WHERE video_id = ?",
            arrayOf(videoId),
        ).use { c ->
            while (c.moveToNext()) clipFilesToDelete += c.getString(0) to c.getString(1)
        }
        for (mid in mediaIds) {
            db.rawQuery(
                "SELECT image_file, audio_file FROM saved_video_clips WHERE media_id = ?",
                arrayOf(mid.toString()),
            ).use { c ->
                while (c.moveToNext()) clipFilesToDelete += c.getString(0) to c.getString(1)
            }
        }

        for (mid in mediaIds) {
            occDeleted += db.delete("item_occurrences", "media_id = ?", arrayOf(mid.toString()))
            clipsDeleted += db.delete("saved_video_clips", "media_id = ?", arrayOf(mid.toString()))
            db.delete("media_refs", "id = ?", arrayOf(mid.toString()))
        }
        clipsDeleted += db.delete("saved_video_clips", "video_id = ?", arrayOf(videoId))

        for (docId in docIds) {
            db.delete("stremio_cues", "doc_id = ?", arrayOf(docId.toString()))
        }
        docsDeleted = db.delete("stremio_cue_documents", "video_id = ?", arrayOf(videoId))
        db.delete("stremio_ledger", "video_id = ?", arrayOf(videoId))
        db.delete("stremio_settings", "scope_key = ?", arrayOf(videoId))
    }

    // File and in-memory cache cleanup runs after the DB commit succeeds.
    val permDir = SavedVideoClipStorage.dir(context)
    for ((img, aud) in clipFilesToDelete) {
        SavedVideoClipStorage.deleteFiles(permDir, img, aud)
    }
    val sliceCacheDir = VideoSliceService.sliceDir(context)
    for (docId in docIds) {
        VideoSliceService.evictEpisode(docId)
        val prefix = "doc${docId}_"
        sliceCacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(prefix)) f.delete()
        }
    }

    return EpisodeDeleteSummary(
        docsDeleted = docsDeleted,
        occurrencesDeleted = occDeleted,
        clipsDeleted = clipsDeleted,
    )
}
