package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v13 entry picks acceptance (docs/item-meaning-reselect-proposal.md §5, user go 2026-11-23).
 * Runs against the REAL bundled dictionary assets (鷲/わし eagle vs 和紙/わし washi; 好 hao3
 * vs 好 hao4) — the pick must write through, survive re-mining via re-resolution, keep stored
 * fields on stale keys, reset cleanly, and undo in one step.
 */
@RunWith(AndroidJUnit4::class)
class ItemDefinitionPickInstrumentedTest {
    private lateinit var repo: MiningRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        repo = MiningRepository(context, TEST_DB_NAME)
    }

    @After
    fun tearDown() {
        repo.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB_NAME)
    }

    private fun seedItem(lang: LanguageTag, expr: String, meaning: String, kana: String = expr) {
        repo.upsertExtractedItem(
            languageTag = lang, itemType = ItemType.WORD, expression = expr,
            kanji = expr, kana = kana, meaning = meaning,
        )
    }

    private fun detail(lang: LanguageTag, expr: String) =
        repo.getItemDetail(lang, ItemType.WORD, expr)!!

    private fun rawPick(expr: String, lang: LanguageTag = LanguageTag.JAPANESE): Pair<String?, String?> =
        repo.readableDatabase.rawQuery(
            "SELECT picked_headword, picked_reading FROM extracted_items WHERE expression = ? AND language_tag = ?",
            arrayOf(expr, lang.name),
        ).use { c -> if (c.moveToFirst()) c.getStringOrNull(0) to c.getStringOrNull(1) else null to null }

    @Test
    fun pickWritesThrough_andDetailExposesPickKey() {
        // The auto situation: わし seeded with the 和紙 gloss (what is_common-first picks).
        seedItem(LanguageTag.JAPANESE, "わし", "washi; Japanese paper")
        assertTrue(repo.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "わし", "鷲", "わし"))

        val d = detail(LanguageTag.JAPANESE, "わし")
        assertTrue("meaning must be the picked entry's glosses: ${d.meaning}", d.meaning.contains("eagle"))
        assertNotEquals("washi; Japanese paper", d.meaning)
        assertEquals("わし", d.kana)
        assertEquals("鷲", d.pickedHeadword)
        assertEquals("わし", d.pickedReading)
        assertEquals("鷲" to "わし", rawPick("わし"))
        // Kana-only surface keeps kanji empty (same sanitize rule as mining):
        assertEquals("", d.kanji)
    }

    @Test
    fun reMiningUpsert_reResolvesThePick_notTheAutoWinner() {
        seedItem(LanguageTag.JAPANESE, "わし", "washi; Japanese paper")
        repo.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "わし", "鷲", "わし")
        // Simulate any later pipeline write passing the AUTO winner's fields back in:
        repo.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD, expression = "わし",
            kanji = "わし", kana = "わし", meaning = "washi; Japanese paper", levelBadge = "常用",
        )
        val d = detail(LanguageTag.JAPANESE, "わし")
        assertTrue("pick must survive re-mining: ${d.meaning}", d.meaning.contains("eagle"))
        assertEquals("鷲", d.pickedHeadword)
    }

    @Test
    fun stalePickKey_carriesStoredFieldsInsteadOfAutoOverride() {
        seedItem(LanguageTag.JAPANESE, "わし", "元からの定義。")
        repo.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "わし", "鷲", "わし")
        val pickedMeaning = detail(LanguageTag.JAPANESE, "わし").meaning
        // Simulate a future dictionary release dropping the entry:
        repo.readableDatabase.execSQL(
            "UPDATE extracted_items SET picked_headword = '存在しない', picked_reading = 'キー' " +
                "WHERE expression = 'わし'",
        )
        repo.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD, expression = "わし",
            kanji = "わし", kana = "わし", meaning = "新しさの自動当たり",
        )
        val d = detail(LanguageTag.JAPANESE, "わし")
        assertEquals("stale key must keep the stored glosses verbatim", pickedMeaning, d.meaning)
        assertNotEquals("新しさの自動当たり", d.meaning)
    }

    @Test
    fun resetRestoresAutoWinner_andClearsPick() {
        seedItem(LanguageTag.JAPANESE, "わし", "washi; Japanese paper")
        repo.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "わし", "鷲", "わし")
        assertTrue(repo.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "わし", null, null))
        assertNull(rawPick("わし").first)
        // Auto re-computation lands on the is_common-first entry (和紙 — washi paper):
        assertTrue("auto winner restored: ${detail(LanguageTag.JAPANESE, "わし").meaning}",
            detail(LanguageTag.JAPANESE, "わし").meaning.contains("washi"))
    }

    @Test
    fun undoRestoresAllSevenPriorColumns() {
        seedItem(LanguageTag.JAPANESE, "わし", "washi; Japanese paper")
        val before = detail(LanguageTag.JAPANESE, "わし")
        repo.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "わし", "儂", "わし")
        assertTrue(detail(LanguageTag.JAPANESE, "わし").meaning.contains("I; me"))
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        val after = detail(LanguageTag.JAPANESE, "わし")
        assertEquals(before.meaning, after.meaning)
        assertEquals(before.kanji, after.kanji)
        assertEquals(before.kana, after.kana)
        assertEquals(before.levelBadge, after.levelBadge)
        assertEquals(before.corpusRank, after.corpusRank)
        assertNull(after.pickedHeadword)
    }

    @Test
    fun chinesePick_keysOnPinyin_andWritesDiacriticKana() {
        seedItem(LanguageTag.CHINESE, "好", "good; appropriate")
        assertTrue(repo.pickItemDefinition(LanguageTag.CHINESE, ItemType.WORD, "好", "好", "hao4"))
        val d = detail(LanguageTag.CHINESE, "好")
        assertTrue("picked 好·hào gloss must land: ${d.meaning}", d.meaning.contains("fond of"))
        assertEquals("hào", d.kana) // toDiacriticPinyin of hao4
        assertEquals("好" to "hao4", rawPick("好", LanguageTag.CHINESE))
    }

    @Test
    fun migration_v12toV13_addsPickColumns_andKeepsRows() {
        seedItem(LanguageTag.JAPANESE, "海賊", "pirate")
        val rowsBefore = repo.readableDatabase.rawQuery("SELECT count(*) FROM extracted_items", null)
            .use { it.moveToFirst(); it.getInt(0) }
        // Downgrade the schema marker and drop the v13 columns, as a pre-v13 bundle restore
        // would present them (the guarded ALTERs must re-add them on next open).
        repo.writableDatabase.execSQL("ALTER TABLE extracted_items DROP COLUMN picked_headword")
        repo.writableDatabase.execSQL("ALTER TABLE extracted_items DROP COLUMN picked_reading")
        repo.writableDatabase.execSQL("PRAGMA user_version = 12")
        repo.close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened = MiningRepository(context, TEST_DB_NAME)
        assertEquals(rowsBefore,
            reopened.readableDatabase.rawQuery("SELECT count(*) FROM extracted_items", null)
                .use { it.moveToFirst(); it.getInt(0) })
        assertEquals(
            MiningRepository.expectedUserVersion(),
            reopened.readableDatabase.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) },
        )
        assertTrue(reopened.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "海賊", "海賊", "かいぞく"))
        reopened.close()
    }

    companion object {
        private const val TEST_DB_NAME = "mining_item_pick_test.db"
    }
}
