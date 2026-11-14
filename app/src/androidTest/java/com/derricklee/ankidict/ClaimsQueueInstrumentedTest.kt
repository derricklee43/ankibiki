package com.derricklee.ankidict

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Claims queue (roadmap B1, user spec 2026-11-14): one screen to drain pending nameplate rulings.
 * Pinned here — the queue's query (both claim kinds, ruled excluded, photo join fields for the
 * deep-link, newest-photo-first order), the badge count against raw SQL (both sides), the shared
 * [NameplateRuling] path (queue ruling == photo-view ruling), drain-to-zero semantics, and
 * hide-for-good across rescan when ruled THROUGH THE QUEUE. Sandbox DB only — never mining.db.
 * (Harness fakes copied verbatim from NameplateClaimsInstrumentedTest — constructor drift guard.)
 */
@RunWith(AndroidJUnit4::class)
class ClaimsQueueInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository

    private class FakeExtractor(private val doc: OcrDocument) : TextExtractor {
        override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) = doc
    }

    private class NoDupDetector : DuplicatePhotoDetector {
        override fun checkDuplicate(extractedText: String, tag: LanguageTag, config: DuplicateDetectionConfig) =
            DuplicateCheckResult(isDuplicate = false)
    }

    private class FakeWordAnalyzer : WordAnalyzer {
        override fun extractWords(sentence: String) = listOf(NormalizedCandidate(sentence, sentence))
    }

    private fun prepared(): PreparedImage {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return PreparedImage(bitmap = bmp, jpegBytes = ByteArray(8))
    }

    private object MediaStoreNoopSource : PhotoSource {
        override fun findUnprocessedPhotos(config: ScanDiscoveryConfig) = emptyList<PhotoCandidate>()
    }

    private object FakePrep : ImagePreprocessor {
        override fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage? = null
    }

    private object NoopTagger : ImageTagger {
        override fun classify(image: PreparedImage) = TagResult(LanguageTag.JAPANESE, 1f, null)
    }

    private fun doc(vararg lines: String) = OcrDocument(
        fullText = lines.joinToString("\n"),
        blocks = lines.map { OcrBlock(text = it, lines = listOf(OcrLine(it, 100))) },
        provider = "on-device",
    )

    private fun config(localDoc: OcrDocument) = PipelineConfig(
        tag = LanguageTag.JAPANESE,
        textExtractor = FakeExtractor(localDoc),
        duplicateDetector = NoDupDetector(),
        sentenceParser = CjkSentenceParser(),
        wordAnalyzer = FakeWordAnalyzer(),
        grammarAnalyzer = NoOpGrammarAnalyzer(),
        dictionaryProviders = emptyList(),
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, "mining_test_claimsqueue.db")
        for (t in listOf("scraped_photos", "item_occurrences", "ocr_corrections", "nameplate_claims")) {
            repo.writableDatabase.delete(t, null, null)
        }
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase("mining_test_claimsqueue.db")
    }

    private fun runner(config: PipelineConfig) =
        MiningPipelineRunner(repo, MediaStoreNoopSource, FakePrep, NoopTagger, mapOf(LanguageTag.JAPANESE to config))

    /** Standalone-plate photo (proxy draft) via the real mining path. */
    private fun mineStandalone(mediaId: Long, dateAdded: Long): MiningPipelineRunner {
        val candidate = PhotoCandidate(mediaId, "content://test/$mediaId", dateAdded)
        val local = doc("マルス。", "ぼくは絶対に忘れない。")
        val cfg = config(local)
        runner(cfg).processOcrDocument(candidate, cfg, local, checkDuplicates = false, prepared = prepared())
        return runner(cfg)
    }

    /** Inline-merge photo (H9v2 draft) via the real mining path with the spike-fixture lexicon. */
    private fun mineInline(mediaId: Long, dateAdded: Long): Pair<MiningPipelineRunner, MiningRepository.NameplateClaim> {
        val candidate = PhotoCandidate(mediaId, "content://test/$mediaId", dateAdded)
        val lex = GateLexiconProvider.lexiconFrom(listOf("村人"), emptyList())
        val local = doc("カイン私は絶対に忘れない。", "村人だから先に進むぞ。")
        val cfg = config(local).copy(nameplateLexicon = { lex })
        val r = runner(cfg)
        r.processOcrDocument(candidate, cfg, local, checkDuplicates = false, prepared = prepared())
        val inline = repo.nameplateClaims(candidate.mediaStoreId).first { it.contextSentence.isNotEmpty() }
        return r to inline
    }

    // ---- query ----

    @Test
    fun pendingQuery_listsBothKinds_newestPhotoFirst_withDeepLinkFields_andExcludesRuled() {
        mineStandalone(889_001L, dateAdded = 1000L)   // older photo -> second
        val (r2, inline) = mineInline(889_002L, dateAdded = 2000L) // newer photo -> first
        assertEquals(2, repo.pendingNameplateClaims().size)

        val pending = repo.pendingNameplateClaims()
        assertEquals("both kinds pending", 2, pending.size)
        assertEquals("newest photo first", 889_002L, pending[0].mediaStoreId)
        assertEquals("inline kind listed", "h9", pending[0].source)
        assertEquals("standalone kind listed", "proxy", pending[1].source)
        assertEquals("マルス", pending[1].text)
        // deep-link + render fields carried from scraped_photos
        assertEquals("content://test/889002", pending[0].contentUri)
        assertEquals(2000L, pending[0].dateAdded)
        assertEquals(LanguageTag.JAPANESE, pending[0].languageTag)
        assertEquals(inline.id, pending[0].claimId)

        // ruling any claim removes it from the queue's set
        repo.applyNameplateVerdict(pending[1].claimId, correct = false)
        val after = repo.pendingNameplateClaims()
        assertEquals("ruled rows excluded", 1, after.size)
        assertEquals(inline.id, after.single().claimId)
    }

    @Test
    fun badgeCount_equalsVerdictIsNullSql_readBothSides() {
        mineStandalone(889_003L, 1000L)
        mineStandalone(889_004L, 1000L)
        mineStandalone(889_005L, 1000L)
        repo.applyNameplateVerdict(repo.nameplateClaims(889_005L).single().id, correct = true)

        val sqlSide = repo.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM nameplate_claims WHERE verdict IS NULL", null,
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        val badgeSide = repo.countPendingNameplateClaims()
        assertEquals("badge must equal the raw verdict-IS-NULL count", sqlSide, badgeSide)
        assertEquals(2, badgeSide)
        assertEquals(badgeSide, repo.pendingNameplateClaims().size) // same set on all three surfaces
    }

    @Test
    fun queueRuling_drainsToZero_writesVerdictAndVerdictAt() {
        mineStandalone(889_006L, 1000L)
        mineInline(889_007L, 1000L)
        assertEquals(2, repo.pendingNameplateClaims().size)

        // Drain exactly the way the queue screen does: take the pending list, rule every row.
        while (true) {
            val row = repo.pendingNameplateClaims().firstOrNull() ?: break
            val correct = row.source == "proxy" // standalone -> 正しい, inline -> 違う
            NameplateRuling.apply(
                context, repo,
                runner(config(doc("マルス。", "ぼくは絶対に忘れない。"))),
                row.mediaStoreId,
                MiningRepository.NameplateClaim(row.claimId, row.text, row.contextSentence, null),
                correct,
            )
        }
        assertEquals("queue drained", 0, repo.pendingNameplateClaims().size)
        assertEquals("badge drained", 0, repo.countPendingNameplateClaims())
        val claims = repo.readableDatabase.rawQuery(
            "SELECT text, verdict, verdict_at FROM nameplate_claims ORDER BY text", null,
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) Triple(c.getString(0), c.getString(1), c.getLong(2)) else null
            }.toList()
        }
        assertEquals(2, claims.size)
        assertTrue("every ruling writes verdict + verdict_at", claims.all { it.second != null && it.third > 0 })
        assertEquals("correct", claims.first { it.first == "マルス" }.second)
        assertEquals("incorrect", claims.first { it.first == "カイン" }.second)
    }

    // ---- ruling equivalence + hide-for-good through the QUEUE path ----

    @Test
    fun queueRuling_correct_standalone_hidesLineAndSurvivesRescan() {
        val r = mineStandalone(889_008L, 1000L)
        val row = repo.pendingNameplateClaims().single { it.mediaStoreId == 889_008L }
        NameplateRuling.apply(
            context, repo, r, row.mediaStoreId,
            MiningRepository.NameplateClaim(row.claimId, row.text, row.contextSentence, null),
            correct = true,
        )
        val photo = repo.getPhoto(889_008L)!!
        assertFalse("plate hidden for good: ${photo.extractedText}", photo.extractedText.contains("マルス"))
        assertTrue("utterance kept: ${photo.extractedText}", photo.extractedText.contains("絶対に忘れない"))

        // Re-mine: nothing re-pends, nothing resurrects (the same guarantees the photo view pins,
        // now proven through the queue's ruling call).
        val candidate = PhotoCandidate(889_008L, "content://test/889008", 1000L)
        val local = doc("マルス。", "ぼくは絶対に忘れない。")
        val cfg = config(local)
        runner(cfg).processOcrDocument(candidate, cfg, local, checkDuplicates = false, prepared = prepared())
        val after = repo.getPhoto(889_008L)!!
        assertFalse("rescan must not resurrect the plate: ${after.extractedText}", after.extractedText.contains("マルス"))
        assertTrue("no re-pending after ruling", repo.pendingNameplateClaims().none { it.mediaStoreId == 889_008L })
    }

    @Test
    fun queueRuling_correct_inline_excisesThroughRealLexicon_andNeverReProposes() {
        val (r, inline) = mineInline(889_009L, 1000L)
        // The queue's inline ruling recomputes the remainder with the SHIPPED gate lexicon (the
        // same production path the photo view uses): カイン is out of JMdict (corpus finding), so
        // the rule must fire — if this assert trips, the lexicon snapshot changed, which is the
        // designed tripwire, not a queue bug.
        val lex = GateLexiconProvider.lexicon(context)
        assertNotNull("gate lexicon must load", lex)
        val remainder = NameplateInlineRule.suggestions(inline.contextSentence, lex!!)
            .firstOrNull { it.plate == inline.text }?.remainder
        assertEquals("私は絶対に忘れない。", remainder)

        val row = repo.pendingNameplateClaims().single { it.mediaStoreId == 889_009L && it.claimId == inline.id }
        NameplateRuling.apply(
            context, repo, r, row.mediaStoreId,
            MiningRepository.NameplateClaim(row.claimId, row.text, row.contextSentence, null),
            correct = true,
        )
        val after = repo.getPhoto(889_009L)!!
        assertFalse("plate excised for good: ${after.extractedText}", after.extractedText.contains("カイン"))
        assertTrue("utterance kept: ${after.extractedText}", after.extractedText.contains("絶対に忘れない"))
        assertEquals("correct", repo.nameplateClaims(889_009L).first { it.id == inline.id }.verdict)
        assertTrue("drained from queue", repo.pendingNameplateClaims().none { it.claimId == inline.id })

        // Re-mine the same document: hiddenInlineClaims re-applies the cut; no fresh proposal.
        val candidate = PhotoCandidate(889_009L, "content://test/889009", 1000L)
        val local = doc("カイン私は絶対に忘れない。", "村人だから先に進むぞ。")
        val lex2 = GateLexiconProvider.lexiconFrom(listOf("村人"), emptyList())
        val cfg = config(local).copy(nameplateLexicon = { lex2 })
        runner(cfg).processOcrDocument(candidate, cfg, local, checkDuplicates = false, prepared = prepared())
        val rescan = repo.getPhoto(889_009L)!!
        assertFalse("rescan must not resurrect the merged plate: ${rescan.extractedText}", rescan.extractedText.contains("カイン"))
        assertTrue("no re-proposal after ruling", repo.pendingNameplateClaims().none { it.mediaStoreId == 889_009L })
    }

    @Test
    fun queueRuling_wrong_inline_keepsSentence_undrains() {
        val (r, inline) = mineInline(889_010L, 1000L)
        NameplateRuling.apply(
            context, repo, r, 889_010L,
            MiningRepository.NameplateClaim(inline.id, inline.text, inline.contextSentence, null),
            correct = false,
        )
        assertEquals("incorrect", repo.nameplateClaims(889_010L).first { it.id == inline.id }.verdict)
        assertTrue(
            "sentence untouched",
            repo.getPhoto(889_010L)!!.extractedText.contains("カイン私は絶対に忘れない。"),
        )
        assertTrue("drained from queue", repo.pendingNameplateClaims().none { it.claimId == inline.id })
    }
}
