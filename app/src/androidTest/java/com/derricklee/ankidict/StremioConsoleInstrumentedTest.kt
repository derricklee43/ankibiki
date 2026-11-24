package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stremio Mining Console stage-1 acceptance (docs/stremio-mining-console-design.md S0-E,
 * build-pass 2026-11-23). Runs the REAL engine + repository against a throwaway db with a
 * FAKE transport (network calls belong to S0-A on-device, not here). Pins:
 *  - cues land with NEGATIVE photo_id only (stage-1 §6.1 shape);
 *  - vocabulary/occurrences go through the SAME CorpusInsertion path as photos;
 *  - re-running identical settings+file is replace-in-place, never duplicating;
 *  - photo rows (positive ids) can NEVER be touched by cue-doc operations;
 *  - v14 tables materialize when a pre-v14 database opens (migration landing).
 */
@RunWith(AndroidJUnit4::class)
class StremioConsoleInstrumentedTest {

    private lateinit var repo: MiningRepository
    private lateinit var engine: StremioExtractionEngine

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        今日は海賊の話をする。

        2
        00:00:04,000 --> 00:00:06,000
        いいえ、わかりません。
    """.trimIndent()

    private val fakeTransport = object : StremioTransport {
        override fun get(url: String, headers: Map<String, String>): String = when {
            url.contains("/subtitles/") ->
                """{"subtitles":[{"id":"os-1","lang":"jpn","filename":"[GRP] Show - 01",
                   "url":"https://sub.example/one.srt"}]}"""
            url.endsWith("one.srt") -> srt
            url.contains("/stream/") ->
                """{"streams":[{"name":"1080p","url":"https://rd.example/f.mkv",
                   "infoHash":"AB12CD34","fileIdx":2}]}"""
            else -> "{}"
        }
        override fun post(url: String, body: String, contentType: String,
                          headers: Map<String, String>): String = "{}"
    }

    private val addons = listOf(
        StremioAddon("subs-addon", "Fake Subs", "1.0", "https://subs.example/a/manifest.json",
            setOf("subtitles")),
        StremioAddon("stream-addon", "Fake Streams", "1.0",
            "https://stream.example/a/manifest.json", setOf("stream")),
    )

    /** Real pipeline configs from the bundled assets; only vocab LOOKUPS are read-only. */
    private fun japaneseConfig(): PipelineConfig {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return requireNotNull(MiningPipelineHolder.of(context).configFor(LanguageTag.JAPANESE)) {
            "bundled pipeline config must exist for JAPANESE"
        }
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        repo = MiningRepository(context, TEST_DB_NAME)
        repo.replaceStremioAddons(addons, System.currentTimeMillis())
        engine = StremioExtractionEngine(
            transport = fakeTransport,
            repo = repo,
            configFor = { if (it == LanguageTag.JAPANESE) japaneseConfig() else null },
            addonsProvider = { repo.loadStremioAddons() },
        )
    }

    @After
    fun tearDown() {
        repo.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB_NAME)
    }

    private fun occurrenceCount(where: String, args: Array<String> = emptyArray()): Int =
        repo.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM item_occurrences WHERE $where", args)
            .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun extraction_landsCuesThroughSharedEngine_withNegativePhotoNamespace() {
        val outcome = engine.runJob("tt999:1:1", "series", "tt999")
        assertEquals(StremioLedgerStatus.MINED, outcome.status)
        assertEquals(2, outcome.cuesStored)
        assertNotNull("doc id assigned", outcome.docId)
        assertTrue("occurrences exist", outcome.occurrencesCreated > 0)
        // all rows for this doc carry the negative sentinel photo_id
        val docId = outcome.docId!!
        assertEquals(outcome.occurrencesCreated,
            occurrenceCount("photo_id = ?", arrayOf((-docId).toString())))
        assertEquals(0, occurrenceCount("photo_id > 0"))
        // cues retained for the media phase + §11.1 deletion
        repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stremio_cues WHERE doc_id = ?", arrayOf(docId.toString()),
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
        // vocabulary went through the canonical upsert (empty dictionary -> em-dash gloss)
        repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM extracted_items WHERE language_tag = 'JAPANESE'", null,
        ).use { c -> assertTrue(c.moveToFirst()); assertTrue(c.getInt(0) > 0) }
        // infoHash observed best-effort (M6-lite) — fake stream addon returns one
        repo.readableDatabase.rawQuery(
            "SELECT info_hash, file_idx FROM stremio_cue_documents WHERE id = ?",
            arrayOf(docId.toString()),
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("ab12cd34", c.getString(0)) // lowercased by AddonResources
            assertEquals(2, c.getInt(1))
        }
    }

    @Test
    fun reExtraction_sameSettingsSameFile_replacesInPlace_neverDuplicates() {
        val first = engine.runJob("tt999:1:1", "series", "tt999")
        val countA = occurrenceCount("photo_id < 0")
        val second = engine.runJob("tt999:1:1", "series", "tt999")
        assertEquals("same doc reused", first.docId, second.docId)
        assertEquals("no duplicate rows", countA, occurrenceCount("photo_id < 0"))
        repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stremio_cue_documents", null,
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
    }

    @Test
    fun photoRowsAreUntouchableByCueDocOperations() {
        // seed a positive-id occurrence the way the photo path does
        repo.insertOccurrence(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
            expression = "海賊", photoId = 12345L, sentence = "今日は海賊の話をする。",
        )
        val before = occurrenceCount("photo_id = ?", arrayOf("12345"))
        engine.runJob("tt999:1:1", "series", "tt999")
        assertEquals(before, occurrenceCount("photo_id = ?", arrayOf("12345")))
        val docId = repo.loadStremioLedgerByEpisode()["tt999:1:1"]!!.docId!!
        val deleted = repo.deleteStremioOccurrencesForDoc(docId)
        assertTrue(deleted > 0)
        assertEquals(before, occurrenceCount("photo_id = ?", arrayOf("12345")))
    }

    @Test
    fun settingsChange_createsNewGeneration_withoutTouchingOldRows() {
        val first = engine.runJob("tt999:1:1", "series", "tt999")
        repo.saveStremioSettings(
            StremioSettingsRow("tt999", "jpn", "OTHER-GROUP", null, null, 0),
            System.currentTimeMillis(),
        )
        // fake list has no OTHER-GROUP row -> ladder falls back to FIRST (same file):
        // generation changes (pattern set), file identical -> distinct doc expected only
        // when the file hash differs; here hash matches but generation differs -> new doc.
        val second = engine.runJob("tt999:1:1", "series", "tt999")
        assertNotNull(second.docId)
        assertTrue("new generation lands as a NEW doc row (coexisting, D6)",
            second.docId != first.docId)
        repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stremio_cue_documents", null,
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
    }

    @Test
    fun failurePaths_landInLedger_andRetrySucceeds() {
        val brokenEngine = StremioExtractionEngine(
            transport = object : StremioTransport {
                override fun get(url: String, headers: Map<String, String>) =
                    throw StremioHttpException(403, "GET $url")
                override fun post(url: String, body: String, contentType: String,
                                  headers: Map<String, String>) = "{}"
            },
            repo = repo,
            configFor = { japaneseConfig() },
            addonsProvider = { repo.loadStremioAddons() },
        )
        val broken = brokenEngine.runJob("tt888:1:1", "series", "tt888")
        assertEquals(StremioLedgerStatus.FAILED, broken.status)
        assertTrue(broken.error!!.contains("403"))
        val recovered = engine.runJob("tt888:1:1", "series", "tt888")
        assertEquals(StremioLedgerStatus.MINED, recovered.status)
    }

    @Test
    fun migration_preV14Database_upgradesTo14_materializingStremioTables() {
        repo.writableDatabase.execSQL("DROP TABLE IF EXISTS stremio_cues")
        repo.writableDatabase.execSQL("DROP TABLE IF EXISTS stremio_ledger")
        repo.writableDatabase.execSQL("PRAGMA user_version = 13")
        repo.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened = MiningRepository(context, TEST_DB_NAME)
        assertEquals(
            MiningRepository.expectedUserVersion(),
            reopened.readableDatabase.rawQuery("PRAGMA user_version", null)
                .use { it.moveToFirst(); it.getInt(0) },
        )
        // the dropped tables materialize on first open; an existing one stays intact
        reopened.readableDatabase.rawQuery("SELECT COUNT(*) FROM stremio_cues", null)
            .use { assertTrue(it.moveToFirst()) }
        reopened.readableDatabase.rawQuery("SELECT COUNT(*) FROM stremio_ledger", null)
            .use { assertTrue(it.moveToFirst()) }
        reopened.close()
        repo = reopened
    }

    companion object {
        private const val TEST_DB_NAME = "mining_stremio_test.db"
    }
}
