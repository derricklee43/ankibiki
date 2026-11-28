package com.derricklee.ankidict

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SavedVideoClipsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository
    private val testDbName = "test_saved_video_clips.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        SavedVideoClipStorage.dir(context).deleteRecursively()
        repo = MiningRepository(context, testDbName)
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(testDbName)
        SavedVideoClipStorage.dir(context).deleteRecursively()
    }

    @Test
    fun upgradeFromV16ToV17_materializesSavedVideoClipsTable() {
        repo.writableDatabase.execSQL("DROP TABLE IF EXISTS saved_video_clips")
        repo.writableDatabase.execSQL("PRAGMA user_version = 16")
        repo.close()

        val reopened = MiningRepository(context, testDbName)
        try {
            val uv = reopened.readableDatabase.rawQuery("PRAGMA user_version", null)
                .use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(MiningRepository.expectedUserVersion(), uv)
            val exists = reopened.readableDatabase.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'saved_video_clips'",
                null,
            ).use { it.moveToFirst() }
            assertTrue("saved_video_clips table missing after v16->v17 upgrade", exists)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun saveLookupFilterAndDelete_roundTripsFilesAndMetadata() {
        val mediaId = repo.getOrCreateMediaRef("video_cues", 10L, "tt999:1:1")
        // Insert one UNSEEN occurrence for sentence A, and one SEEN occurrence for sentence B.
        insertOccurrence(mediaId, "諦めるな！", "諦める", OccurrenceState.UNSEEN, LanguageTag.JAPANESE)
        insertOccurrence(mediaId, "ありがとう", "ありがとう", OccurrenceState.SEEN, LanguageTag.JAPANESE)

        val tmpDir = File(context.cacheDir, "test_slice_src").apply { mkdirs() }
        val imgA = File(tmpDir, "a.webp").apply { writeBytes(byteArrayOf(10, 20, 30)) }
        val audA = File(tmpDir, "a.m4a").apply { writeBytes(byteArrayOf(40, 50, 60)) }
        val imgB = File(tmpDir, "b.webp").apply { writeBytes(byteArrayOf(70, 80, 90)) }
        val audB = File(tmpDir, "b.m4a").apply { writeBytes(byteArrayOf(11, 22, 33)) }

        try {
            val savedA = repo.saveVideoClip(
                context = context,
                mediaId = mediaId,
                sentence = "諦めるな！",
                docId = 10L,
                videoId = "tt999:1:1",
                startMs = 5000L,
                endMs = 7500L,
                offsetMs = -500,
                audioTrack = 1,
                languageTag = LanguageTag.JAPANESE,
                provenanceLabel = "Demon Slayer - S1-E1",
                srcImage = imgA,
                srcAudio = audA,
                nowMs = 1000L,
            )
            val savedB = repo.saveVideoClip(
                context = context,
                mediaId = mediaId,
                sentence = "ありがとう",
                docId = 10L,
                videoId = "tt999:1:1",
                startMs = 9000L,
                endMs = 11000L,
                offsetMs = 0,
                audioTrack = null,
                languageTag = LanguageTag.JAPANESE,
                provenanceLabel = "Demon Slayer - S1-E1",
                srcImage = imgB,
                srcAudio = audB,
                nowMs = 2000L,
            )

            // Deleting cache sources must NOT affect saved permanent files.
            tmpDir.deleteRecursively()
            assertTrue(savedA.hasValidFiles(context))
            assertTrue(savedB.hasValidFiles(context))

            val fetched = repo.getSavedVideoClip(mediaId, "諦めるな！")
            assertNotNull(fetched)
            assertEquals(-500, fetched!!.offsetMs)
            assertEquals(1, fetched.audioTrack)

            // Default filter returns newest-first (B then A).
            val all = repo.getSavedVideoClipsFiltered(unseenOnly = false, tags = emptySet())
            assertEquals(listOf("ありがとう", "諦めるな！"), all.map { it.sentence })

            // Unseen filter returns only sentence A.
            val unseen = repo.getSavedVideoClipsFiltered(unseenOnly = true, tags = emptySet())
            assertEquals(listOf("諦めるな！"), unseen.map { it.sentence })

            // Chinese language filter excludes Japanese clips.
            val zh = repo.getSavedVideoClipsFiltered(unseenOnly = false, tags = setOf(LanguageTag.CHINESE))
            assertTrue(zh.isEmpty())

            // Delete removes DB row + permanent files.
            assertTrue(repo.deleteSavedVideoClip(context, mediaId, "諦めるな！"))
            assertNull(repo.getSavedVideoClip(mediaId, "諦めるな！"))
            assertFalse(savedA.imageFile(context).exists())
            assertFalse(savedA.audioFile(context).exists())
            assertTrue(savedB.hasValidFiles(context))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun insertOccurrence(
        mediaId: Long,
        sentence: String,
        expression: String,
        state: OccurrenceState,
        tag: LanguageTag,
    ) {
        val db = repo.writableDatabase
        db.insertWithOnConflict(
            "extracted_items",
            null,
            ContentValues().apply {
                put("language_tag", tag.name)
                put("item_type", ItemType.WORD.name)
                put("expression", expression)
                put("kanji", expression)
                put("kana", "")
                put("meaning", "gloss")
                put("level_badge", "")
                put("corpus_rank", 1)
                put("is_hidden", 0)
                put("visibility_state", "ACTIVE")
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
        )
        db.insert(
            "item_occurrences",
            null,
            ContentValues().apply {
                put("language_tag", tag.name)
                put("item_type", ItemType.WORD.name)
                put("expression", expression)
                put("photo_id", -1L)
                put("media_id", mediaId)
                put("sentence", sentence)
                put("created_at", 1_700_000_000L)
                put("state", state.name)
                put("modified_by_user_flag", 0)
                put("is_manual", 0)
            },
        )
    }
}
