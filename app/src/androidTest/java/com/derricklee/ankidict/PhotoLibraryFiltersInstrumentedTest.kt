package com.derricklee.ankidict

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library grid filters (roadmap B3, user spec 2026-11-14 — 未読 / 金 / 言語 facets; the stale
 * facet explicitly CUT by the user). 未読 semantics are the inbox's ACTIVE-occurrence condition
 * mirrored VERBATIM: an UNSEEN occurrence row of an active, non-hidden canonical item, word or
 * grammar, either language, on a KEEP photo. Expectations are hand-derived from the fixture table
 * below (oracle), and the unseen facet is additionally cross-checked against an independent
 * scripted EXISTS read (both sides). Sandbox DB only — never mining.db.
 */
@RunWith(AndroidJUnit4::class)
class PhotoLibraryFiltersInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, "mining_test_libfilters.db")
        for (t in listOf("scraped_photos", "item_occurrences", "extracted_items")) {
            repo.writableDatabase.delete(t, null, null)
        }
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase("mining_test_libfilters.db")
    }

    private fun photo(id: Long, tag: LanguageTag, keep: Boolean = true, gold: Boolean = false) {
        repo.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = id, contentUri = "content://test/$id", dateAdded = id, // id doubles as date -> DESC order == id DESC
                languageTag = tag, extractedText = "文$id。", ocrBlocksJson = null,
                status = if (keep) PhotoStatus.KEEP else PhotoStatus.IGNORED,
                duplicateOfPhotoId = null, modifiedByUserFlag = false, isGold = gold,
            ),
        )
    }

    private fun item(tag: LanguageTag, type: ItemType, expr: String) {
        repo.upsertExtractedItem(languageTag = tag, itemType = type, expression = expr, kanji = expr, kana = expr, meaning = "m")
    }

    private fun occ(tag: LanguageTag, type: ItemType, expr: String, photoId: Long): Long =
        repo.insertOccurrence(tag, type, expr, photoId, "文${photoId}。")

    private fun setState(occId: Long, state: String) {
        repo.writableDatabase.execSQL("UPDATE item_occurrences SET state = ? WHERE id = ?", arrayOf(state, occId))
    }

    private fun ids(unseen: Boolean = false, gold: Boolean = false, tags: Set<LanguageTag> = emptySet()): Set<Long> =
        repo.getScrapedPhotosFiltered(unseen, gold, tags).map { it.mediaStoreId }.toSet()

    /**
     * Fixture table (all KEEP unless noted):
     *  940001 JP, UNSEEN word 木            -> unseen
     *  940002 JP gold, no occurrences       -> gold only
     *  940003 JP, SEEN-only occurrence      -> not unseen
     *  940004 JP, UNSEEN row, item hidden AFTER insert -> not unseen (inbox skips hidden)
     *  940005 ZH, UNSEEN word 马             -> unseen, ZH
     *  940006 ZH gold, no occurrences       -> gold, ZH
     *  940007 JP IGNORED with UNSEEN        -> never listed (base KEEP)
     *  940008 JP, DELETED-only occurrence   -> not unseen
     *  940009 ZH, UNSEEN GRAMMAR になる      -> unseen counts grammar too
     *  940010 JP gold + UNSEEN              -> unseen ∧ gold
     */
    private fun seed() {
        photo(940001L, LanguageTag.JAPANESE)
        photo(940002L, LanguageTag.JAPANESE, gold = true)
        photo(940003L, LanguageTag.JAPANESE)
        photo(940004L, LanguageTag.JAPANESE)
        photo(940005L, LanguageTag.CHINESE)
        photo(940006L, LanguageTag.CHINESE, gold = true)
        photo(940007L, LanguageTag.JAPANESE, keep = false)
        photo(940008L, LanguageTag.JAPANESE)
        photo(940009L, LanguageTag.CHINESE)
        photo(940010L, LanguageTag.JAPANESE, gold = true)

        item(LanguageTag.JAPANESE, ItemType.WORD, "木"); occ(LanguageTag.JAPANESE, ItemType.WORD, "木", 940001L)
        item(LanguageTag.JAPANESE, ItemType.WORD, "水")
        setState(occ(LanguageTag.JAPANESE, ItemType.WORD, "水", 940003L), "SEEN")
        item(LanguageTag.JAPANESE, ItemType.WORD, "風"); occ(LanguageTag.JAPANESE, ItemType.WORD, "風", 940004L)
        repo.hideItem(LanguageTag.JAPANESE, ItemType.WORD, "風")
        item(LanguageTag.CHINESE, ItemType.WORD, "马"); occ(LanguageTag.CHINESE, ItemType.WORD, "马", 940005L)
        item(LanguageTag.JAPANESE, ItemType.WORD, "山")
        setState(occ(LanguageTag.JAPANESE, ItemType.WORD, "山", 940008L), "DELETED")
        item(LanguageTag.CHINESE, ItemType.GRAMMAR, "になる"); occ(LanguageTag.CHINESE, ItemType.GRAMMAR, "になる", 940009L)
        item(LanguageTag.JAPANESE, ItemType.WORD, "猫"); occ(LanguageTag.JAPANESE, ItemType.WORD, "猫", 940010L)
        item(LanguageTag.JAPANESE, ItemType.WORD, "雨")
        setState(occ(LanguageTag.JAPANESE, ItemType.WORD, "雨", 940007L), "UNSEEN") // on an IGNORED photo
    }

    private val allKeep = setOf(940001L, 940002L, 940003L, 940004L, 940005L, 940006L, 940008L, 940009L, 940010L)

    @Test
    fun noFacets_keepsLibraryUnchanged() {
        seed()
        assertEquals(allKeep, ids())
        assertEquals(repo.getAllScrapedPhotos().map { it.mediaStoreId }.toSet(), ids())
    }

    @Test
    fun unseenFacet_matchesInboxOracle() {
        seed()
        val expected = setOf(940001L, 940005L, 940009L, 940010L) // hidden-item, SEEN, DELETED, IGNORED excluded
        assertEquals(expected, ids(unseen = true))

        // both-sides cross-check: independent scripted EXISTS (same inbox condition shape)
        val scripted = repo.readableDatabase.rawQuery(
            "SELECT media_store_id FROM scraped_photos p WHERE p.status = 'KEEP' AND EXISTS (" +
                "SELECT 1 FROM item_occurrences o JOIN extracted_items i ON o.language_tag = i.language_tag" +
                " AND o.item_type = i.item_type AND o.expression = i.expression" +
                " WHERE o.photo_id = p.media_store_id AND o.state = 'UNSEEN'" +
                " AND i.visibility_state = 'ACTIVE' AND i.is_hidden = 0)",
            null,
        ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toSet() }
        assertEquals(scripted, ids(unseen = true))
    }

    @Test
    fun goldFacet_andCombinationWithUnseen() {
        seed()
        assertEquals(setOf(940002L, 940006L, 940010L), ids(gold = true))
        assertEquals(setOf(940010L), ids(unseen = true, gold = true)) // facets AND-join
    }

    @Test
    fun languageFacets_single_both_and_none() {
        seed()
        assertEquals(setOf(940001L, 940002L, 940003L, 940004L, 940008L, 940010L), ids(tags = setOf(LanguageTag.JAPANESE)))
        assertEquals(setOf(940005L, 940006L, 940009L), ids(tags = setOf(LanguageTag.CHINESE)))
        assertEquals(allKeep, ids(tags = setOf(LanguageTag.JAPANESE, LanguageTag.CHINESE))) // both == none
        assertEquals(setOf(940005L, 940009L), ids(unseen = true, tags = setOf(LanguageTag.CHINESE)))
        assertEquals(setOf(940002L, 940010L), ids(gold = true, tags = setOf(LanguageTag.JAPANESE)))
    }

    @Test
    fun ordering_newestPhotoFirst_survivesFacets() {
        seed()
        val ordered = repo.getScrapedPhotosFiltered(unseenOnly = true, goldOnly = false, tags = emptySet())
            .map { it.mediaStoreId }
        assertEquals(listOf(940010L, 940009L, 940005L, 940001L), ordered) // date_added DESC (id == date here)
    }
}
