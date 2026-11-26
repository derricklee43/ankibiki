package com.derricklee.ankidict

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v16 store-level specs: user_words CRUD, the merged-definition writers
 * (setItemDefinition / pick stamping / ladder carry in upsert), the legacy
 * normalization inside the 15->16 upgrade, and both new undo round-trips
 * (docs/definition-model-design.md §5, docs/user-dictionary-design.md §6).
 */
@RunWith(AndroidJUnit4::class)
class UserWordsStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: MiningRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        repo = MiningRepository(context, DB_NAME)
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun seedItem(
        expr: String,
        meaning: String,
        userDefinition: String? = null,
        picked: Pair<String, String>? = null,
        source: String? = null,
        systemMeaning: String? = null,
    ) {
        repo.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO extracted_items " +
                "(language_tag, item_type, expression, kanji, kana, meaning, user_definition, " +
                " picked_headword, picked_reading, definition_source, system_meaning) " +
                "VALUES ('JAPANESE','WORD',?,?,?,?,?,?,?,?,?)",
            arrayOf(
                expr, "", expr, meaning, userDefinition,
                picked?.first, picked?.second,
                source ?: "AUTO", systemMeaning,
            ),
        )
    }

    private fun row(expr: String): android.database.Cursor =
        repo.readableDatabase.rawQuery(
            "SELECT meaning, user_definition, definition_source, system_meaning, kana " +
                "FROM extracted_items WHERE expression = ?", arrayOf(expr),
        ).apply { moveToFirst() }

    @Test
    fun freshSchema_v16_hasTableAndColumns() {
        repo.readableDatabase  // triggers onCreate
        val uv = repo.readableDatabase.rawQuery("PRAGMA user_version", null)
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(MiningRepository.expectedUserVersion(), uv)
        assertEquals(16, uv) // re-pinned at the v16 bump (2026-11-26)
        val hasTable = repo.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='user_words'", null,
        ).use { it.moveToFirst() }
        assertTrue(hasTable)
    }

    @Test
    fun upgradeFrom15_normalizesPicksThenUserDefinitions() {
        val db = repo.writableDatabase
        // simulate pre-v16 rows: drop stamps by forcing values, downgrade version
        seedItem("竈門炭治郎", meaning = "—", userDefinition = "the protagonist")
        seedItem("柱", meaning = "pillar", picked = "柱" to "はしら", userDefinition = "hashira!")
        seedItem("猫", meaning = "cat")
        db.execSQL("UPDATE extracted_items SET definition_source = 'AUTO', system_meaning = NULL")
        db.execSQL("PRAGMA user_version = 15")
        repo.close()

        repo = MiningRepository(context, DB_NAME)
        repo.readableDatabase  // fire onUpgrade 15 -> 16
        // pick row: stamp applied first, then USER normalization wins (user text exists)
        row("柱").use { c ->
            assertEquals("hashira!", c.getString(0))   // meaning := user text
            assertEquals("hashira!", c.getString(1))   // mirror intact
            assertEquals("USER", c.getString(2))
            assertEquals("pillar", c.getString(3))     // system_meaning := old gloss
        }
        row("竈門炭治郎").use { c ->
            assertEquals("the protagonist", c.getString(0))
            assertEquals("USER", c.getString(2))
        }
        // untouched row stays AUTO (no user_definition)
        row("猫").use { c -> assertEquals("AUTO", c.getString(2)) }
    }

    @Test
    fun userWordCrud_roundTrips() {
        repo.upsertUserWord(LanguageTag.JAPANESE, "禰豆子", "ねずこ", "Nezuko", null)
        assertEquals(1, repo.userWordEntries(LanguageTag.JAPANESE).size)
        val w = repo.userWord(LanguageTag.JAPANESE, "禰豆子")!!
        assertEquals("ねずこ", w.readingKana)
        // katakana reading input stores as hiragana
        repo.upsertUserWord(LanguageTag.JAPANESE, "善逸", "ゼンイツ", "Zenitsu", null)
        assertEquals("ぜんいつ", repo.userWord(LanguageTag.JAPANESE, "善逸")!!.readingKana)
    }

    @Test
    fun upsertUserWord_propagatesToAutoAndUserDict_neverUserOrPick() {
        seedItem("花子", meaning = "flower child", source = "AUTO")
        seedItem("太郎", meaning = "old gloss", source = "USER_DICT", systemMeaning = "dict gloss")
        seedItem("二郎", meaning = "mine", source = "USER")
        seedItem("三郎", meaning = "kept", picked = "三郎" to "さぶろう", source = "PICK")
        repo.upsertUserWord(LanguageTag.JAPANESE, "花子", "はなこ", "Hanako", null)
        repo.upsertUserWord(LanguageTag.JAPANESE, "太郎", "たろう", "Taro v2", null)
        repo.upsertUserWord(LanguageTag.JAPANESE, "二郎", "じろう", "should not land", null)
        repo.upsertUserWord(LanguageTag.JAPANESE, "三郎", "さぶろう", "also not", null)
        row("花子").use { c ->
            assertEquals("Hanako", c.getString(0))
            assertEquals("USER_DICT", c.getString(2))
            assertEquals("flower child", c.getString(3)) // AUTO -> revert target captured
            assertEquals("はなこ", c.getString(4))
        }
        row("太郎").use { c ->
            assertEquals("Taro v2", c.getString(0))
            assertEquals("dict gloss", c.getString(3)) // existing system_meaning kept
        }
        row("二郎").use { c -> assertEquals("mine", c.getString(0)) }     // USER untouched
        row("三郎").use { c -> assertEquals("kept", c.getString(0)) }     // PICK untouched
    }

    @Test
    fun upsertLadder_userRowsCarryMeaningThroughReMine() {
        seedItem("煉獄", meaning = "my words", source = "USER", userDefinition = "my words",
            systemMeaning = "purgatory")
        repo.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD, expression = "煉獄",
            kanji = "煉獄", kana = "れんごく", meaning = "purgatory (auto)",
        )
        row("煉獄").use { c ->
            assertEquals("my words", c.getString(0))   // never overwritten
            assertEquals("USER", c.getString(2))
        }
    }

    @Test
    fun upsertLadder_deletedUserDictEntry_fallsThroughToAuto() {
        seedItem("珠世", meaning = "Tamayo", source = "USER_DICT")
        // no user_words entry exists -> caller's fresh dictionary value lands
        repo.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD, expression = "珠世",
            kanji = "珠世", kana = "たまよ", meaning = "new auto gloss",
            definitionSource = DefinitionSource.AUTO,
        )
        row("珠世").use { c ->
            assertEquals("new auto gloss", c.getString(0))
            assertEquals("AUTO", c.getString(2))
        }
    }

    @Test
    fun setItemDefinition_editAndRevert_roundTripThroughUndo() {
        seedItem("胡蝶", meaning = "butterfly (auto)")
        assertTrue(repo.setItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "胡蝶", "Shinobu clan"))
        row("胡蝶").use { c ->
            assertEquals("Shinobu clan", c.getString(0))
            assertEquals("Shinobu clan", c.getString(1))  // export mirror
            assertEquals("USER", c.getString(2))
            assertEquals("butterfly (auto)", c.getString(3)) // revert target
        }
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        row("胡蝶").use { c ->
            assertEquals("butterfly (auto)", c.getString(0))
            assertEquals("AUTO", c.getString(2))
            assertNull(c.getString(3))
        }

        // revert flow: edit -> blank save -> back to system_meaning
        repo.setItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "胡蝶", "edited again")
        repo.setItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "胡蝶", "")
        row("胡蝶").use { c ->
            assertEquals("butterfly (auto)", c.getString(0))
            assertEquals("AUTO", c.getString(2))
            assertNull(c.getString(1))
        }
    }

    @Test
    fun pickItemDefinition_stampsProvenance_andClearsUserMirror() {
        seedItem("禰", meaning = "mine", userDefinition = "mine", source = "USER",
            systemMeaning = "system gloss")
        // no bundled dictionary hit is needed for a reset-to-auto pick(null,null):
        // the writer recomputes via provider (may miss offline -> keeps prior fields)
        repo.pickItemDefinition(LanguageTag.JAPANESE, ItemType.WORD, "禰", null, null)
        row("禰").use { c ->
            // reset path keeps meaning (no resolvable candidate offline), source flips AUTO
            assertEquals("AUTO", c.getString(2))
            assertFalse("USER mirror cleared on pick-over-USER", c.getString(1) == "mine")
        }
    }

    @Test
    fun deleteUserWord_undoRoundTrip_andStaleGuard() {
        repo.upsertUserWord(LanguageTag.JAPANESE, "累", "るい", "Rui", "spider")
        // probe 1: write side persisted notes
        assertEquals("spider", repo.userWord(LanguageTag.JAPANESE, "累")!!.notes)
        assertTrue(repo.deleteUserWord(LanguageTag.JAPANESE, "累"))
        // probe 2: the memento payload carries the notes value
        val payload = repo.readableDatabase.rawQuery(
            "SELECT payload FROM undo_log ORDER BY id DESC LIMIT 1", null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        assertTrue("undo payload must carry the notes value: $payload", payload.contains("spider"))
        assertNull(repo.userWord(LanguageTag.JAPANESE, "累"))
        assertEquals(UndoOutcome.UNDONE, UndoManager.undoLatest(repo))
        val w = repo.userWord(LanguageTag.JAPANESE, "累")!!
        assertEquals("Rui", w.meaning)
        assertEquals("spider", w.notes)
        // re-delete, re-add manually, undo must then GUARD_FAIL (row reappeared)
        repo.deleteUserWord(LanguageTag.JAPANESE, "累")
        repo.upsertUserWord(LanguageTag.JAPANESE, "累", null, "readded", null)
        // newest memento is now the second delete; entry exists again -> guard fails
        assertEquals(UndoOutcome.GUARD_FAILED, UndoManager.undoLatest(repo))
    }

    companion object {
        private const val DB_NAME = "mining_userwords_test.db"
    }
}
