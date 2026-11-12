package com.derricklee.ankidict

import android.content.ContentUris
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wizard gateway E2E (docs/card-creation-wizard-plan.md): template persistence, provider
 * enumerations, note writing + read-back, duplicate detection, and the mined-item link
 * update — against a REAL AnkiDroid (skips loudly if none installed).
 * Run via adb install + am instrument (WSL can't reach connectedAndroidTest over the firewall).
 */
@RunWith(AndroidJUnit4::class)
class CardWizardE2ETest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val anki = AnkiRepository(ctx)
    private val mining = MiningRepository(ctx)
    private val account = AnkiDroidAccountGateway.DEFAULT_ACCOUNT_ID

    private class Seeded(val deckId: Long, val modelId: Long, val fieldNames: List<String>)

    private fun assumeAnki() {
        assumeTrue("AnkiDroid not installed on this device — wizard gateway E2E skipped",
            AddContentApi.getAnkiDroidPackageName(ctx) != null)
    }

    /** Scratch sandbox like spike P7: new deck + new basic model (default Forward/Backward fields). */
    private fun seed(): Seeded? {
        val api = AddContentApi(ctx)
        val ts = System.currentTimeMillis()
        val deckId = api.addNewDeck("E2E deck $ts") ?: return null
        val modelId = api.addNewBasicModel("E2EModel$ts") ?: return null
        val fields = api.getFieldList(modelId)?.toList() ?: return null
        return Seeded(deckId, modelId, fields)
    }

    @Test
    fun deckListing_includesNewDeckWithCounts() {
        assumeAnki()
        val s = seed()
        assertNotNull("seed failed", s)
        val mine = anki.listDecks().firstOrNull { it.deckId == s!!.deckId }
        assertNotNull("created deck missing from listDecks", mine)
        assertEquals(3, mine!!.counts.size) // [new, learning, mature] triple (spike P2)
    }

    @Test
    fun modelListing_exposesOrderedFieldNames() {
        assumeAnki()
        val s = seed()
        assertNotNull(s)
        val mine = anki.listModels().firstOrNull { it.modelId == s!!.modelId }
        assertNotNull("created model missing from listModels", mine)
        assertEquals(mine!!.fieldNames, anki.fieldNames(s!!.modelId))
        assertTrue("basic model should expose 2 ordered fields", mine.fieldNames.size == 2)
    }

    /**
     * OBSERVED PROVIDER TRUTH (emulator + AnkiDroid 2.25.3, 2026-11-12): addNewBasicModel with an
     * identical field set does NOT dedup — it mints a new model id. This is pinned so a future
     * provider change that starts deduping (potentially re-pointing saved templates) trips here.
     * Wizard impact: templates keyed by modelId stay valid; duplicates with the same fields can
     * coexist, so Screen 0 must show model NAMES and let the user pick — never assume uniqueness.
     */
    @Test
    fun modelCreation_sameFieldSetMintsNewId_pinnedBehavior() {
        assumeAnki()
        val s = seed()
        assertNotNull(s)
        val again = AddContentApi(ctx).addNewBasicModel("E2EClone${'$'}{System.currentTimeMillis()}")
        if (again != null) {
            org.junit.Assert.assertNotEquals("provider started deduping identical field sets", s!!.modelId, again)
        }
    }

    @Test
    fun cardTemplates_roundTripThroughMiningDb() {
        val rows = listOf(
            CardTemplateMapping(0, ExportField.TERM.label),
            CardTemplateMapping(1, null),
            CardTemplateMapping(2, ExportField.SENTENCE.label),
        )
        val did = -9001L
        val mid = -9002L
        mining.saveCardTemplate(did, mid, rows)
        assertEquals(rows, mining.loadCardTemplate(did, mid))
        val shrunk = listOf(CardTemplateMapping(0, ExportField.READING.label))
        mining.saveCardTemplate(did, mid, shrunk)
        assertEquals(shrunk, mining.loadCardTemplate(did, mid))
        mining.saveCardTemplate(did, mid, emptyList())
        assertEquals(emptyList<CardTemplateMapping>(), mining.loadCardTemplate(did, mid))
    }

    @Test
    fun wizardWritePath_endToEnd() {
        assumeAnki()
        val s = seed()
        assertNotNull("seed failed", s)
        val draft = CardDraft(
            languageTag = LanguageTag.JAPANESE.name,
            itemType = ItemType.WORD.name,
            expression = "溺れる",
            reading = "おぼれる",
            meaning = "to drown",
            sentence = "彼は湖で溺れた。",
            sentenceWords = listOf("彼", "湖"),
            photoUri = null,
            languageLabel = "Japanese",
            levelBadge = "N2",
            corpusRank = 7,
        )
        val values = DraftAdapter.resolve(draft)
        val mappings = listOf(
            CardTemplateMapping(0, ExportField.TERM.label),
            CardTemplateMapping(1, ExportField.SENTENCE.label),
        )
        mining.saveCardTemplate(s!!.deckId, s.modelId, mappings)
        assertEquals(mappings, mining.loadCardTemplate(s.deckId, s.modelId))

        // compose exactly like Screen 2 does: bound ordinals -> value, unbound -> ""
        val composed = (0 until s.fieldNames.size).map { ord ->
            val bound = mappings.firstOrNull { it.ordinal == ord }?.fieldLabel?.let { ExportField.fromLabel(it) }
            if (bound == null) "" else DraftAdapter.compose(values[bound].orEmpty(), "", "")
        }
        assertEquals(listOf("溺れる", "彼は湖で溺れた。"), composed)

        assertTrue("fresh sandbox should have no duplicates", anki.findDuplicateNotes(s.modelId, "溺れる").isEmpty())

        val noteId = anki.createNote(s.modelId, s.deckId, composed, setOf("ankibiki"))
        assertNotNull("provider write failed", noteId)
        val readback = anki.readNoteFields(noteId!!)
        assertNotNull(readback)
        assertEquals("溺れる", readback!![0])
        assertEquals("彼は湖で溺れた。", readback[1])

        assertEquals(1, anki.findDuplicateNotes(s.modelId, "溺れる").size)

        // association: seed item, link like the wizard does, read the link back
        mining.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
            expression = draft.expression, kanji = draft.expression, kana = draft.reading,
            meaning = draft.meaning,
        )
        mining.insertOccurrence(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD,
            expression = draft.expression, photoId = -1L, sentence = draft.sentence,
            createdAt = 1700000000L,
        )
        mining.updateExtractedItemAnkiMatch(
            LanguageTag.JAPANESE, ItemType.WORD, draft.expression,
            AnkiCardMatch(account, s.deckId, "E2E deck", noteId),
        )
        val detail = mining.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, draft.expression)!!
        assertEquals(noteId, detail.ankiNoteId)

        // cleanup: note removed via provider contract (no api.deleteNote in 2.25.3)
        val deleted = runCatching {
            ctx.contentResolver.delete(
                ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, noteId), null, null)
        }.getOrDefault(0)
        assertEquals("note delete", 1, deleted)
        runCatching {
            mining.writableDatabase.delete("item_occurrences", "sentence = ?", arrayOf(draft.sentence))
            mining.writableDatabase.delete(
                "extracted_items", "language_tag = ? AND item_type = ? AND expression = ?",
                arrayOf(LanguageTag.JAPANESE.name, ItemType.WORD.name, draft.expression),
            )
        }
        mining.saveCardTemplate(s.deckId, s.modelId, emptyList())
    }

    @After
    fun closeDb() { runCatching { mining.close() } }
}
