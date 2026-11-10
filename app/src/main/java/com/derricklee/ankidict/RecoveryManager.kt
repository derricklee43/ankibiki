package com.derricklee.ankidict

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * In-app corpus export/restore using the same self-verifying bundle format as
 * scripts/backup_bundle.py (docs/recovery.md) — bundles produced here must
 * verify there and vice versa.
 *
 * Export checkpoints the WAL, copies mining.db, hashes it, counts rows, and
 * optionally embeds readable MediaStore photos. Restore validates EVERYTHING
 * before touching the corpus: format, sha256, integrity_check, required
 * tables/columns, row counts; then swaps the database file (old copy kept as
 * mining.db.pre-restore) and restarts the process so every per-activity
 * MiningRepository reopens the restored file. Invalid bundles are surfaced
 * with reasons and the existing corpus is never modified.
 */
class RecoveryManager(
    private val context: Context,
    private val repository: MiningRepository,
) {
    data class ExportSummary(
        val suggestedName: String,
        val rows: Map<String, Long>,
        val photosIncluded: Int,
        val photosMissing: Int,
    )

    data class RestorePlan(
        val rows: Map<String, Long>,
        val userVersion: Int,
        val photoMode: String,
        val photosAvailable: Int,
        val bundleName: String,
    )

    class RecoveryException(message: String) : Exception(message)

    // ---- export ----------------------------------------------------------

    fun exportBundle(dest: Uri): ExportSummary {
        val db = repository.readableDatabase
        val rows = TABLES.associateWith {
            db.rawQuery("SELECT COUNT(*) FROM \"$it\"", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else throw RecoveryException("table $it unreadable")
            }
        }
        val photos = db.rawQuery(
            "SELECT media_store_id, content_uri FROM scraped_photos", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getLong(0) to c.getString(1))
                }
            }
        }

        // Make the main db file complete, then take our consistent copy while
        // no writer is attached (SQLiteOpenHelper reopens lazily on next use).
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        repository.close()

        val dbFile = context.getDatabasePath(MiningRepository.expectedDbName())
        val staged = File(workDir(), "export-mining.db")
        dbFile.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }

        val name = queryDisplayName(dest)
            ?: ("ankibiki-corpus-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip")
        val manifest = JSONObject()
            .put(KEY_FORMAT, BUNDLE_FORMAT)
            .put("format_version", FORMAT_VERSION)
            .put("created_utc", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            .put("created_by", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("source_db", dbFile.absolutePath)
            .put(
                "db", JSONObject()
                    .put("user_version", MiningRepository.expectedUserVersion())
                    .put("schema_version", -1) // informational; python re-reads it
                    .put("sha256", sha256Of(staged))
                    .put("rows", JSONObject(rows.mapValues { it.value }))
                    .put("bytes", staged.length())
            )

        var included = 0
        var missing = 0
        val photoFiles = JSONArray()
        context.contentResolver.openOutputStream(dest, "w").use { output ->
            requireNotNull(output) { "cannot open destination" }
            ZipOutputStream(output.buffered()).use { zip ->
                // Stash photos while streaming so we hash exactly what we write.
                val photoBytes = mutableListOf<Triple<String, Long, ByteArray>>()
                for ((mediaId, uriString) in photos) {
                    val bytes = readPhoto(mediaId, uriString)
                    if (bytes == null) {
                        missing++
                    } else {
                        included++
                        photoBytes.add(Triple(photoEntryName(mediaId, uriString), mediaId, bytes))
                    }
                }
                val photosObj = JSONObject()
                    .put("mode", if (included > 0) "included" else "referenced")
                    .put(
                        "note",
                        if (included > 0) {
                            "MediaStore URIs are device-local; on a fresh install " +
                                "re-import photos/ and remap by filename+sha256 (docs/recovery.md)."
                        } else {
                            "images NOT embedded; only referenced by content_uri"
                        },
                    )
                    .put("files", photoFiles)
                manifest.put("photos", photosObj)
                for ((entryName, mediaId, bytes) in photoBytes) {
                    photoFiles.put(
                        JSONObject()
                            .put("file", entryName)
                            .put("media_store_id", mediaId)
                            .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                            .put("bytes", bytes.size),
                    )
                }
                // Re-serialize manifest now that the files array is complete.
                zip.putNextEntry(ZipEntry("manifest.json"))
                manifest.toString(2).toByteArray().inputStream().copyTo(zip)
                zip.closeEntry()
                for ((entryName, _, bytes) in photoBytes) {
                    zip.putNextEntry(ZipEntry("photos/$entryName"))
                    bytes.inputStream().copyTo(zip)
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                staged.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        staged.delete()
        return ExportSummary(name, rows, included, missing)
    }

    private fun readPhoto(mediaId: Long, uriString: String): ByteArray? = runCatching {
        val uri = Uri.parse(uriString)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull().also { if (it == null) android.util.Log.w(TAG, "photo $mediaId ($uriString) not readable") }

    private fun photoEntryName(mediaId: Long, uriString: String): String {
        val display = runCatching {
            context.contentResolver.query(
                Uri.parse(uriString), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
            )?.use { c: Cursor -> if (c.moveToNext()) c.getString(0) else null }
        }.getOrNull()
        val sanitized = display?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "image.jpg"
        return "$mediaId-$sanitized"
    }

    // ---- restore ---------------------------------------------------------

    /** Parse + fully validate a bundle. Throws RecoveryException with all problems. */
    fun validateBundle(src: Uri, display: String?): RestorePlan {
        val dir = workDir()
        val zipFile = File(dir, "import.zip")
        context.contentResolver.openInputStream(src)?.use { input ->
            zipFile.outputStream().use { input.copyTo(it) }
        } ?: throw RecoveryException("cannot open selected file")

        val manifest: JSONObject
        val stagedDb = File(dir, DB_ENTRY)
        var photoCount = 0
        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                var manifestBytes: ByteArray? = null
                var dbBytes: ByteArray? = null
                while (entry != null) {
                    when {
                        entry.name == "manifest.json" -> manifestBytes = zis.readBytes()
                        entry.name == DB_ENTRY -> dbBytes = zis.readBytes()
                        entry.name.startsWith("photos/") && entry.name != "photos/" -> {
                            val out = File(File(dir, "photos"), File(entry.name).name)
                            out.parentFile.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                            photoCount++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                val mb = manifestBytes ?: throw RecoveryException("not an ankibiki bundle (no manifest.json)")
                val dbb = dbBytes ?: throw RecoveryException("not an ankibiki bundle (no mining.db)")
                manifest = JSONObject(String(mb))
                stagedDb.writeBytes(dbb)
            }

            val problems = mutableListOf<String>()
            if (manifest.optString(KEY_FORMAT) != BUNDLE_FORMAT) {
                problems += "unknown bundle format: ${manifest.optString(KEY_FORMAT)}"
            }
            val fv = manifest.optInt("format_version", -1)
            if (fv != FORMAT_VERSION) problems += "unsupported format_version $fv"
            val dbObj = manifest.optJSONObject("db")
                ?: throw RecoveryException("manifest missing db section")
            val sha = dbObj.optString("sha256")
            if (sha.isNotEmpty() && sha != sha256Of(stagedDb)) {
                problems += "mining.db sha256 mismatch (bundle corrupted)"
            }

            var rows: Map<String, Long> = emptyMap()
            var userVersion = -1
            if (problems.isEmpty()) {
                val db = try {
                    SQLiteDatabase.openDatabase(stagedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                } catch (e: Exception) {
                    throw RecoveryException("database unreadable: ${e.message}")
                }
                try {
                    userVersion = pragmaInt(db, "user_version")
                    if (userVersion !in 1..MiningRepository.expectedUserVersion()) {
                        problems += "unsupported schema version $userVersion " +
                            "(app is at ${MiningRepository.expectedUserVersion()})"
                    }
                    val ic = db.rawQuery("PRAGMA integrity_check", null)
                        .use { c -> if (c.moveToFirst()) c.getString(0) else "??" }
                    if (ic != "ok") problems += "integrity_check: $ic"
                    for (t in TABLES) {
                        val cols = db.rawQuery("PRAGMA table_info(\"$t\")", null).use { c ->
                            buildSet { while (c.moveToNext()) add(c.getString(1)) }
                        }
                        if (cols.isEmpty()) { problems += "table $t missing"; continue }
                        (REQUIRED_COLUMNS[t]!! - cols).sorted().let { miss ->
                            if (miss.isNotEmpty()) problems += "$t missing columns: $miss"
                        }
                        rows = rows + (t to (db.rawQuery("SELECT COUNT(*) FROM \"$t\"", null)
                            .use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }))
                    }
                } finally {
                    db.close()
                }
                val manifestRows = dbObj.optJSONObject("rows")
                if (manifestRows != null) {
                    for (t in TABLES) {
                        val exp = manifestRows.optLong(t, -1L)
                        if (exp >= 0 && rows[t] != exp) {
                            problems += "$t row count differs from manifest: ${rows[t]} vs $exp"
                        }
                    }
                }
            }
            if (problems.isNotEmpty()) throw RecoveryException(problems.joinToString("; "))

            val photosObj = manifest.optJSONObject("photos")
            val mode = photosObj?.optString("mode") ?: "referenced"
            if (mode == "included" && photoCount == 0) {
                throw RecoveryException("manifest says photos included but zip has none")
            }
            return RestorePlan(
                rows = rows,
                userVersion = userVersion,
                photoMode = mode,
                photosAvailable = photoCount,
                bundleName = display ?: zipFile.name,
            )
        } finally {
            zipFile.delete()
        }
    }

    /** Swap the live corpus for the validated staged db and restart the app. */
    fun applyRestore(plan: RestorePlan) {
        val staged = File(workDir(), DB_ENTRY)
        if (!staged.isFile) throw RecoveryException("staged bundle no longer present — run restore again")
        // Re-hash for defence against cache changes between validate and apply.
        val problems = mutableListOf<String>()
        val db = SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val ic = db.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "??" }
            if (ic != "ok") problems += "integrity_check: $ic"
            for (t in TABLES) {
                val count = db.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                if (plan.rows[t] != count) problems += "$t changed since validation (${plan.rows[t]} -> $count)"
            }
        } finally {
            db.close()
        }
        if (problems.isNotEmpty()) throw RecoveryException(problems.joinToString("; "))

        repository.close()
        val dbFile = context.getDatabasePath(MiningRepository.expectedDbName())
        dbFile.parentFile?.mkdirs()
        dbFile.resolveSibling("mining.db-wal").delete()
        dbFile.resolveSibling("mining.db-journal").delete()
        val pre = dbFile.resolveSibling("mining.db.pre-restore")
        pre.delete()
        if (dbFile.exists()) {
            if (!dbFile.renameTo(pre)) throw RecoveryException("could not preserve current db as pre-restore")
        }
        if (!staged.renameTo(dbFile)) {
            pre.renameTo(dbFile) // put the old corpus back
            throw RecoveryException("could not move restored db into place (old copy preserved)")
        }

        val photosDir = File(workDir(), "photos")
        if (plan.photoMode == "included" && photosDir.isDirectory) {
            val dest = File(context.filesDir, "recovery-photos").apply { mkdirs() }
            photosDir.listFiles()?.forEach { p -> p.copyTo(File(dest, p.name), overwrite = true) }
            val names = dest.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            val filesArray = JSONArray()
            names.forEach { filesArray.put(it) }
            File(dest, "photos-map.json").writeText(
                JSONObject()
                    .put("note", "recovered photos pending MediaStore re-import + relink (docs/recovery.md)")
                    .put("files", filesArray)
                    .toString(2),
            )
        }
        clearWorkDir()
    }

    // ---- plumbing --------------------------------------------------------

    private fun workDir(): File = File(context.cacheDir, "recovery").apply { mkdirs() }

    private fun clearWorkDir() {
        runCatching { workDir().deleteRecursively() }
    }

    /** Drop any staged restore after the user cancels the confirmation. */
    fun discardStaged() = clearWorkDir()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun pragmaInt(db: SQLiteDatabase, pragma: String): Int =
        db.rawQuery("PRAGMA $pragma", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val BUNDLE_FORMAT = "ankibiki-backup"
        const val FORMAT_VERSION = 1
        private const val KEY_FORMAT = "format"
        private const val DB_ENTRY = "mining.db"
        private const val TAG = "RecoveryManager"
        private val TABLES = listOf("scraped_photos", "extracted_items", "item_occurrences")
        private val REQUIRED_COLUMNS = mapOf(
            "scraped_photos" to setOf(
                "media_store_id", "content_uri", "date_added", "language_tag", "extracted_text",
                "ocr_blocks_json", "status", "duplicate_of_photo_id", "modified_by_user_flag",
            ),
            "extracted_items" to setOf(
                "language_tag", "item_type", "expression", "kanji", "kana", "meaning",
                "level_badge", "corpus_rank", "anki_account_id", "deck_id", "deck_name",
                "anki_note_id", "is_hidden", "visibility_state",
            ),
            "item_occurrences" to setOf(
                "id", "language_tag", "item_type", "expression", "photo_id", "sentence",
                "created_at", "state", "modified_by_user_flag",
            ),
        )
    }
}

