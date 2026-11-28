package com.derricklee.ankidict

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest

/**
 * Permanently saved video screenshot + audio slice (schema v17).
 *
 * Files live under `filesDir/saved_video_clips/` (never auto-evicted by Android's cache cleaner)
 * and are referenced by portable relative filenames so corpus backup/restore works across devices.
 */
data class SavedVideoClip(
    val id: Long,
    val mediaId: Long,
    val sentence: String,
    val docId: Long,
    val videoId: String,
    val startMs: Long,
    val endMs: Long,
    val offsetMs: Int,
    val audioTrack: Int?,
    val imageFileName: String,
    val audioFileName: String,
    val languageTag: LanguageTag,
    val provenanceLabel: String,
    val savedAt: Long,
) {
    fun imageFile(context: Context): File = File(SavedVideoClipStorage.dir(context), imageFileName)
    fun audioFile(context: Context): File = File(SavedVideoClipStorage.dir(context), audioFileName)

    fun hasValidFiles(context: Context): Boolean {
        val img = imageFile(context)
        val aud = audioFile(context)
        return img.isFile && img.length() > 0L && aud.isFile && aud.length() > 0L
    }
}

/**
 * Pure, JVM-testable file naming and copy/delete helpers for permanent saved video clips.
 */
object SavedVideoClipStorage {

    const val DIR_NAME = "saved_video_clips"

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME)

    /** Deterministic, filesystem-safe base name keyed by `(mediaId, sentence)`. */
    fun fileBaseName(mediaId: Long, sentence: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sentence.toByteArray(Charsets.UTF_8))
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        return "clip_m${mediaId}_$hex"
    }

    /**
     * Copies [srcImage] and [srcAudio] into [dir] under deterministic filenames.
     * Safe to call even if `srcImage` / `srcAudio` are already pointing at the destination files.
     */
    fun copyFilesToPermanent(
        dir: File,
        baseName: String,
        srcImage: File,
        srcAudio: File,
    ): Pair<String, String> {
        require(srcImage.isFile && srcImage.length() > 0L) { "source image missing or empty" }
        require(srcAudio.isFile && srcAudio.length() > 0L) { "source audio missing or empty" }
        dir.mkdirs()

        val imgName = "$baseName.webp"
        val audName = "$baseName.m4a"
        val destImg = File(dir, imgName)
        val destAud = File(dir, audName)

        if (srcImage.canonicalFile != destImg.canonicalFile) {
            val tmpImg = File(dir, "$imgName.tmp")
            srcImage.copyTo(tmpImg, overwrite = true)
            if (!tmpImg.renameTo(destImg)) {
                tmpImg.copyTo(destImg, overwrite = true)
                tmpImg.delete()
            }
        }
        if (srcAudio.canonicalFile != destAud.canonicalFile) {
            val tmpAud = File(dir, "$audName.tmp")
            srcAudio.copyTo(tmpAud, overwrite = true)
            if (!tmpAud.renameTo(destAud)) {
                tmpAud.copyTo(destAud, overwrite = true)
                tmpAud.delete()
            }
        }
        return imgName to audName
    }

    fun deleteFiles(dir: File, imageFileName: String, audioFileName: String) {
        if (imageFileName.isNotBlank()) File(dir, File(imageFileName).name).delete()
        if (audioFileName.isNotBlank()) File(dir, File(audioFileName).name).delete()
    }
}

/**
 * Permanently saves (or updates) a video screenshot + audio clip in `filesDir/saved_video_clips/`
 * and records its metadata row in `saved_video_clips`.
 */
fun MiningRepository.saveVideoClip(
    context: Context,
    mediaId: Long,
    sentence: String,
    docId: Long,
    videoId: String,
    startMs: Long,
    endMs: Long,
    offsetMs: Int,
    audioTrack: Int?,
    languageTag: LanguageTag,
    provenanceLabel: String,
    srcImage: File,
    srcAudio: File,
    nowMs: Long = System.currentTimeMillis(),
): SavedVideoClip {
    val dir = SavedVideoClipStorage.dir(context)
    val baseName = SavedVideoClipStorage.fileBaseName(mediaId, sentence)
    val (imgName, audName) = SavedVideoClipStorage.copyFilesToPermanent(dir, baseName, srcImage, srcAudio)

    val values = ContentValues().apply {
        put("media_id", mediaId)
        put("sentence", sentence)
        put("doc_id", docId)
        put("video_id", videoId)
        put("start_ms", startMs)
        put("end_ms", endMs)
        put("offset_ms", offsetMs)
        if (audioTrack != null) put("audio_track", audioTrack) else putNull("audio_track")
        put("image_file", imgName)
        put("audio_file", audName)
        put("language_tag", languageTag.name)
        put("provenance_label", provenanceLabel)
        put("saved_at", nowMs)
    }
    writableDatabase.insertWithOnConflict(
        "saved_video_clips",
        null,
        values,
        SQLiteDatabase.CONFLICT_REPLACE,
    )
    return requireNotNull(getSavedVideoClip(mediaId, sentence)) {
        "saved_video_clips row missing immediately after upsert"
    }
}

fun MiningRepository.getSavedVideoClip(mediaId: Long, sentence: String): SavedVideoClip? {
    if (mediaId < 0L) return null
    return readableDatabase.rawQuery(
        "SELECT id, media_id, sentence, doc_id, video_id, start_ms, end_ms, offset_ms, " +
            "audio_track, image_file, audio_file, language_tag, provenance_label, saved_at " +
            "FROM saved_video_clips WHERE media_id = ? AND sentence = ? LIMIT 1",
        arrayOf(mediaId.toString(), sentence),
    ).use { c -> if (c.moveToFirst()) c.toSavedVideoClip() else null }
}

/**
 * Deletes the `saved_video_clips` row and its underlying `.webp` + `.m4a` files.
 * Returns true if a saved row existed and was removed.
 */
fun MiningRepository.deleteSavedVideoClip(
    context: Context,
    mediaId: Long,
    sentence: String,
): Boolean {
    val existing = getSavedVideoClip(mediaId, sentence) ?: return false
    val deleted = writableDatabase.delete(
        "saved_video_clips",
        "media_id = ? AND sentence = ?",
        arrayOf(mediaId.toString(), sentence),
    )
    SavedVideoClipStorage.deleteFiles(
        SavedVideoClipStorage.dir(context),
        existing.imageFileName,
        existing.audioFileName,
    )
    return deleted > 0
}

/**
 * Lists saved video clips for the dedicated video-clips section of すべての写真
 * (`PhotoLibraryActivity`), honoring the `未処理` and language filter chips.
 */
fun MiningRepository.getSavedVideoClipsFiltered(
    unseenOnly: Boolean = false,
    tags: Set<LanguageTag> = emptySet(),
): List<SavedVideoClip> {
    val selection = StringBuilder("1 = 1")
    val args = mutableListOf<String>()
    if (unseenOnly) {
        selection.append(
            " AND EXISTS (SELECT 1 FROM item_occurrences o" +
                " JOIN extracted_items i" +
                " ON o.language_tag = i.language_tag AND o.item_type = i.item_type AND o.expression = i.expression" +
                " WHERE o.media_id = saved_video_clips.media_id" +
                " AND o.sentence = saved_video_clips.sentence" +
                " AND o.state = 'UNSEEN' AND i.visibility_state = 'ACTIVE' AND i.is_hidden = 0)",
        )
    }
    val tagList = tags.toList()
    if (tagList.isNotEmpty() && tagList.size < LanguageTag.entries.size) {
        selection.append(" AND language_tag IN (").append(tagList.joinToString(",") { "?" }).append(")")
        args.addAll(tagList.map { it.name })
    }
    val results = mutableListOf<SavedVideoClip>()
    readableDatabase.rawQuery(
        "SELECT id, media_id, sentence, doc_id, video_id, start_ms, end_ms, offset_ms, " +
            "audio_track, image_file, audio_file, language_tag, provenance_label, saved_at " +
            "FROM saved_video_clips WHERE $selection ORDER BY saved_at DESC, id DESC",
        args.toTypedArray(),
    ).use { c ->
        while (c.moveToNext()) {
            results.add(c.toSavedVideoClip())
        }
    }
    return results
}

private fun Cursor.toSavedVideoClip(): SavedVideoClip = SavedVideoClip(
    id = getLong(0),
    mediaId = getLong(1),
    sentence = getString(2),
    docId = getLong(3),
    videoId = getString(4),
    startMs = getLong(5),
    endMs = getLong(6),
    offsetMs = getInt(7),
    audioTrack = if (isNull(8)) null else getInt(8),
    imageFileName = getString(9),
    audioFileName = getString(10),
    languageTag = runCatching { LanguageTag.valueOf(getString(11)) }.getOrDefault(LanguageTag.JAPANESE),
    provenanceLabel = getString(12) ?: "",
    savedAt = getLong(13),
)
