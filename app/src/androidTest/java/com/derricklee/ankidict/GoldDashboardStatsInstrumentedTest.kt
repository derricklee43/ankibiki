package com.derricklee.ankidict

import android.content.ContentValues
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gold dashboard aggregates (roadmap B2): the screen must render EXACTLY a scripted SQL read of
 * the same corpus DB — criterion (a), both sides. Seeds mixed gold/edit/decision states directly,
 * computes the expectation with independent raw queries in the test, asserts repo.getDashboardStats()
 * field-for-field, then mutates one row on each table and proves the two sides move together.
 * Sandbox DB only — never mining.db.
 */
@RunWith(AndroidJUnit4::class)
class GoldDashboardStatsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, "mining_test_dashboard.db")
        for (t in listOf("scraped_photos", "ocr_corrections")) {
            repo.writableDatabase.delete(t, null, null)
        }
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase("mining_test_dashboard.db")
    }

    private fun photo(id: Long, keep: Boolean, edited: Boolean, gold: Boolean) {
        repo.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = id, contentUri = "content://test/$id", dateAdded = 1000L,
                languageTag = LanguageTag.JAPANESE, extractedText = "文$id。", ocrBlocksJson = null,
                status = if (keep) PhotoStatus.KEEP else PhotoStatus.IGNORED,
                duplicateOfPhotoId = null, modifiedByUserFlag = edited,
            ),
        )
        if (gold) {
            repo.writableDatabase.execSQL("UPDATE scraped_photos SET is_gold = 1 WHERE media_store_id = ?", arrayOf(id))
        }
    }

    private fun correction(photoId: Long, decision: String) {
        val values = ContentValues().apply {
            put("photo_id", photoId); put("line_index", 0)
            put("a_start", 0); put("a_end", 1); put("a_text", "ア"); put("b_text", "イ")
            put("kind", "substitution"); put("margin", 0.5)
            put("anchored_a", 1); put("anchored_b", 1); put("confusable", 1); put("nameplate_excluded", 0)
            put("decision", decision); put("line_before", "ア。"); put("line_after", "イ。")
            put("created_at", System.currentTimeMillis())
        }
        repo.writableDatabase.insert("ocr_corrections", null, values)
    }

    /** Independent scripted read of the same DB — the other side of criterion (a). */
    private fun scriptedExpectation(): MiningRepository.DashboardStats {
        val db = repo.readableDatabase
        fun c(sql: String): Int = db.rawQuery(sql, null).use { x -> if (x.moveToFirst()) x.getInt(0) else 0 }
        return MiningRepository.DashboardStats(
            totalPhotos = c("SELECT COUNT(*) FROM scraped_photos"),
            keepPhotos = c("SELECT COUNT(*) FROM scraped_photos WHERE status = 'KEEP'"),
            goldPhotos = c("SELECT COUNT(*) FROM scraped_photos WHERE is_gold = 1"),
            goldUserEditedPhotos = c("SELECT COUNT(*) FROM scraped_photos WHERE is_gold = 1 AND modified_by_user_flag = 1"),
            correctionsApplied = c("SELECT COUNT(*) FROM ocr_corrections WHERE decision = 'applied'"),
            correctionsRejected = c("SELECT COUNT(*) FROM ocr_corrections WHERE decision = 'rejected'"),
            correctionsExcluded = c("SELECT COUNT(*) FROM ocr_corrections WHERE decision = 'excluded'"),
        )
    }

    @Test
    fun stats_matchScriptedSqlRead_bothSides() {
        // 6 photos: 4 KEEP, 3 gold, 2 gold∧edited, 1 edited-not-gold
        photo(900_001L, keep = true, edited = true, gold = true)
        photo(900_002L, keep = true, edited = true, gold = true)
        photo(900_003L, keep = true, edited = false, gold = true)
        photo(900_004L, keep = true, edited = true, gold = false)
        photo(900_005L, keep = false, edited = false, gold = false)
        photo(900_006L, keep = false, edited = false, gold = false)
        // 8 corrections: 4 applied, 3 rejected, 1 excluded
        repeat(4) { correction(900_001L, "applied") }
        repeat(3) { correction(900_002L, "rejected") }
        correction(900_003L, "excluded")

        val stats = repo.getDashboardStats()
        assertEquals(scriptedExpectation(), stats) // data-class equality: every field, both sides
        assertEquals(6, stats.totalPhotos)
        assertEquals(4, stats.keepPhotos)
        assertEquals(3, stats.goldPhotos)
        assertEquals(2, stats.goldUserEditedPhotos)
        assertEquals(4, stats.correctionsApplied) // the N the dashboard renders as N/200
        assertEquals(3, stats.correctionsRejected)
        assertEquals(1, stats.correctionsExcluded)
    }

    @Test
    fun stats_moveTogether_underMutation() {
        photo(900_010L, keep = true, edited = false, gold = false)
        correction(900_010L, "rejected")
        assertEquals(scriptedExpectation(), repo.getDashboardStats())

        // gold-mark a photo the dashboard sees as effort-clean; apply one more correction
        repo.writableDatabase.execSQL("UPDATE scraped_photos SET is_gold = 1 WHERE media_store_id = ?", arrayOf(900_010L))
        repo.writableDatabase.execSQL(
            "UPDATE ocr_corrections SET decision = 'applied' WHERE photo_id = ? AND decision = 'rejected'",
            arrayOf(900_010L),
        )
        val after = repo.getDashboardStats()
        assertEquals(scriptedExpectation(), after)
        assertEquals(1, after.goldPhotos)
        assertEquals(0, after.goldUserEditedPhotos) // gold but never edited => clean side of the proxy
        assertEquals(1, after.correctionsApplied)
        assertEquals(0, after.correctionsRejected)
    }

    @Test
    fun stats_emptyCorpus_isZerosNotFailure() {
        assertEquals(MiningRepository.DashboardStats(0, 0, 0, 0, 0, 0, 0), repo.getDashboardStats())
        assertEquals(scriptedExpectation(), repo.getDashboardStats())
    }
}
