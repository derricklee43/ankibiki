package com.derricklee.ankidict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Pronunciation audio, keyed by character, pulled from Anki Desktop's own collection via
 * scripts/build_audio_db.py (AnkiDroid on the phone has no way to expose this -- see that
 * script's header). audio.db is gitignored, so a fresh checkout won't have it; every lookup
 * here degrades to "no audio" rather than crashing when that's the case.
 */
class AudioRepository(private val context: Context) {

    private val db: SQLiteDatabase? by lazy { openBundledDatabase(context, DB_NAME) }

    // Loaded once on first check so RecyclerView binding can test audio availability in O(1)
    // without running a SQLite query on every scroll frame.
    private val availableCharacters: Set<String> by lazy {
        val db = db ?: return@lazy emptySet()
        val chars = HashSet<String>()
        db.rawQuery("SELECT character FROM audio", null).use { cursor ->
            while (cursor.moveToNext()) {
                chars.add(cursor.getString(0))
            }
        }
        chars
    }

    fun hasAudio(character: String): Boolean = character in availableCharacters

    /** Writes (once, then cached) and returns a local file playable via MediaPlayer, or null. */
    fun audioFileFor(character: String): File? {
        val db = db ?: return null
        val dbFile = context.getDatabasePath(DB_NAME)
        val cacheFile = File(context.cacheDir, "audio/$character.mp3")
        if (cacheFile.exists() && cacheFile.lastModified() >= dbFile.lastModified()) return cacheFile

        db.rawQuery("SELECT data FROM audio WHERE character = ?", arrayOf(character)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(cursor.getBlob(0))
        }
        return cacheFile
    }

    companion object {
        private const val DB_NAME = "audio.db"
    }
}
