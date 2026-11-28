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
 *  - cues land on media_refs kind='video_cues' rows (v15; the stage-1 negative
 *    photo_id namespace is retired by the migration spec below);
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
    fun extraction_landsCuesThroughSharedEngine_withMediaRefs() {
        val outcome = engine.runJob("tt999:1:1", "series", "tt999")
        assertEquals(StremioLedgerStatus.MINED, outcome.status)
        assertEquals(2, outcome.cuesStored)
        assertNotNull("doc id assigned", outcome.docId)
        assertTrue("occurrences exist", outcome.occurrencesCreated > 0)
        // v15: rows hang off a kind='video_cues' media_ref (ref_key = doc id)
        // and carry photo_id NULL -- the stage-1 negative namespace is retired.
        val docId = outcome.docId!!
        assertEquals(outcome.occurrencesCreated,
            occurrenceCount(
                "media_id = (SELECT id FROM media_refs WHERE kind = 'video_cues'" +
                    " AND ref_key = ?)",
                arrayOf(docId.toString()),
            ))
        assertEquals(0, occurrenceCount("photo_id IS NOT NULL"))
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
        val countA = occurrenceCount("photo_id IS NULL")
        val second = engine.runJob("tt999:1:1", "series", "tt999")
        assertEquals("same doc reused", first.docId, second.docId)
        assertEquals("no duplicate rows", countA, occurrenceCount("photo_id IS NULL"))
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
        // I1 dedupe (v15): the superseded generation's media_ref goes inactive
        // (rows kept for audit) and the ACTIVE ref holds exactly one copy.
        repo.readableDatabase.rawQuery(
            "SELECT active FROM media_refs WHERE kind = 'video_cues' ORDER BY id", null,
        ).use { c ->
            val flags = ArrayList<Int>()
            while (c.moveToNext()) flags.add(c.getInt(0))
            assertEquals(listOf(0, 1), flags)
        }
        val active = occurrenceCount(
            "media_id IN (SELECT id FROM media_refs WHERE kind = 'video_cues' AND active = 1)",
        )
        assertEquals(second.occurrencesCreated, active)
        assertEquals(second.occurrencesCreated * 2, occurrenceCount("photo_id IS NULL"))
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

    /** spikes/007 D4a: builtin Jimaku rung fires only after synced addons miss. */
    @Test
    fun builtinRung_rescuesWhenSyncedAddonsComeUpEmpty() {
        val jmTransport = object : StremioTransport {
            override fun get(url: String, headers: Map<String, String>) = when {
                url.contains("/subtitles/") -> """{"subtitles":[]}""" // synced addon: no jpn
                url.contains("/entries/search?query=Fake+Show") ->
                    """[{"id":9898,"name":"Fake Show"}]"""
                url.contains("/entries/9898/files?episode=1") ->
                    """[{"url":"https://jm.example/s1.ass","name":"images.zip","size":2,"last_modified":"2025-01-01T00:00:00Z"},
{"url":"https://jm.example/one.srt","name":"[GRP] Fake Show - 01 (1080p).srt","size":1,"last_modified":"2025-01-01T00:00:00Z"}]"""
                url.endsWith("one.srt") -> srt
                url.contains("/stream/") -> """{"streams":[]}"""
                else -> "{}"
            }
            override fun post(url: String, body: String, contentType: String,
                              headers: Map<String, String>) = "{}"
        }
        val e = StremioExtractionEngine(
            transport = jmTransport,
            repo = repo,
            configFor = { japaneseConfig() },
            addonsProvider = { repo.loadStremioAddons() },
            jimaku = JimakuClient(jmTransport, { "K" }),
        )
        val outcome = e.runJob("tt777:1:1", "series", "tt777", "Fake Show")
        assertEquals(StremioLedgerStatus.MINED, outcome.status)
        assertEquals(2, outcome.cuesStored)
    }

    @Test
    fun builtinRung_missAppendsToLedgerTriedList() {
        val jmTransport = object : StremioTransport {
            override fun get(url: String, headers: Map<String, String>) = when {
                url.contains("/subtitles/") -> """{"subtitles":[]}"""
                url.contains("/entries/search") -> "[]"
                else -> "{}"
            }
            override fun post(url: String, body: String, contentType: String,
                              headers: Map<String, String>) = "{}"
        }
        val e = StremioExtractionEngine(
            transport = jmTransport,
            repo = repo,
            configFor = { japaneseConfig() },
            addonsProvider = { repo.loadStremioAddons() },
            jimaku = JimakuClient(jmTransport, { "K" }),
        )
        val outcome = e.runJob("tt666:1:1", "series", "tt666", "Nowhere Show")
        assertEquals(StremioLedgerStatus.FAILED, outcome.status)
        assertTrue("ledger names both rungs", outcome.error!!.contains("Jimaku (builtin)") &&
            outcome.error!!.contains("Fake Subs"))
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

    @Test
    fun migration_v14Shape_rebuildsToMediaRefs_retiringNegativeNamespace() {
        // Simulate a stage-1 (v14) landing: v14-shaped item_occurrences holding
        // BOTH namespaces -- one photo row (positive) and one video row with
        // the retired negative sentinel -- then reopen so migrateToV15 runs.
        val outcome = engine.runJob("tt999:1:1", "series", "tt999")
        val docId = outcome.docId!!
        val db = repo.writableDatabase
        db.execSQL("DROP TABLE IF EXISTS item_occurrences")
        db.execSQL("DROP TABLE IF EXISTS item_occurrences_v14shape")
        db.execSQL(
            """
            CREATE TABLE item_occurrences_v14shape (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language_tag TEXT NOT NULL, item_type TEXT NOT NULL,
                expression TEXT NOT NULL, photo_id INTEGER NOT NULL,
                sentence TEXT NOT NULL, created_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                modified_by_user_flag INTEGER NOT NULL DEFAULT 0,
                is_manual INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO item_occurrences_v14shape(language_tag, item_type, expression," +
                " photo_id, sentence, created_at, state) VALUES (?,?,?,?,?,?,?), (?,?,?,?,?,?,?)",
            arrayOf(
                "JAPANESE", "WORD", "海賊", (-docId).toString(), "今日は海賊の話をする。", 1, "UNSEEN",
                "JAPANESE", "WORD", "海賊", "12345", "今日は海賊の話をする。", 2, "UNSEEN",
            ),
        )
        db.execSQL("ALTER TABLE item_occurrences_v14shape RENAME TO item_occurrences")
        db.execSQL("PRAGMA user_version = 14")
        repo.close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened = MiningRepository(context, TEST_DB_NAME)
        reopened.readableDatabase.rawQuery("PRAGMA user_version", null)
            .use { assertTrue(it.moveToFirst() && it.getInt(0) == MiningRepository.expectedUserVersion()) }
        // negatives retired; every row resolved to a media ref; mirrors kept
        reopened.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM item_occurrences WHERE photo_id < 0 OR media_id IS NULL", null,
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        reopened.readableDatabase.rawQuery(
            "SELECT m.kind, o.photo_id FROM item_occurrences o" +
                " JOIN media_refs m ON m.id = o.media_id WHERE o.created_at = 1", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("video_cues", c.getString(0))
            assertTrue("negative sentinel became photo_id NULL", c.isNull(1))
        }
        reopened.readableDatabase.rawQuery(
            "SELECT m.kind, o.photo_id FROM item_occurrences o" +
                " JOIN media_refs m ON m.id = o.media_id WHERE o.created_at = 2", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("photo", c.getString(0))
            assertEquals(12345L, c.getLong(1))
        }
        reopened.close()
        repo = reopened
    }

    @Test
    fun deleteMinedEpisode_removesOccurrencesCuesLedgerAndClips_preservesOtherEpisodes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val choice = SubtitleChoice("1", "jpn", "Show.S01E01.srt", "https://ex/1.srt", "a")
        val docA = repo.createStremioDoc("tt999:1:1", "gen1", choice, "hashA", null, null, 1000L)
        val docB = repo.createStremioDoc("tt999:1:2", "gen1", choice, "hashB", null, null, 1000L)
        repo.replaceStremioCues(docA, listOf(SubtitleCue(1000L, 2000L, "消える文")))
        repo.replaceStremioCues(docB, listOf(SubtitleCue(1000L, 2000L, "残る文")))
        val refA = repo.getOrCreateMediaRef("video_cues", docA, "tt999:1:1")
        val refB = repo.getOrCreateMediaRef("video_cues", docB, "tt999:1:2")
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "消える", null, "消える文", mediaId = refA)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "残る", null, "残る文", mediaId = refB)
        repo.upsertStremioLedger("tt999:1:1", "gen1", StremioLedgerStatus.MINED, docId = docA, cueCount = 1, now = 1000L)
        repo.upsertStremioLedger("tt999:1:2", "gen1", StremioLedgerStatus.MINED, docId = docB, cueCount = 1, now = 1000L)

        val tmp = java.io.File(context.cacheDir, "ep_del_test").apply { mkdirs() }
        val img = java.io.File(tmp, "a.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val aud = java.io.File(tmp, "a.m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val clipA = repo.saveVideoClip(
            context, refA, "消える文", docA, "tt999:1:1", 1000L, 2000L, 0, null,
            LanguageTag.JAPANESE, "Show - S1-E1", img, aud, 1000L,
        )
        tmp.deleteRecursively()

        val summary = repo.deleteMinedEpisode(context, "tt999:1:1")
        assertEquals(1, summary.docsDeleted)
        assertEquals(1, summary.occurrencesDeleted)
        assertEquals(1, summary.clipsDeleted)
        assertTrue(!clipA.imageFile(context).exists())
        assertTrue(!clipA.audioFile(context).exists())

        // Episode 1 completely gone from cues, docs, ledger, media_refs, occurrences.
        assertTrue(repo.loadStremioCues(docA).isEmpty())
        assertTrue(repo.loadStremioLedgerByEpisode()["tt999:1:1"] == null)
        repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM item_occurrences WHERE media_id = ?",
            arrayOf(refA.toString()),
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }

        // Episode 2 untouched.
        assertEquals(1, repo.loadStremioCues(docB).size)
        assertTrue(repo.loadStremioLedgerByEpisode()["tt999:1:2"] != null)
        repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM item_occurrences WHERE media_id = ?",
            arrayOf(refB.toString()),
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
    }

    companion object {
        private const val TEST_DB_NAME = "mining_stremio_test.db"
    }
}
