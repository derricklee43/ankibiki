package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v11 word folders + user definition/notes (user 2026-11-15 laundry list, answers same day):
 * 1:1 folder per word; moving into a skip-inbox folder marks existing UNSEEN occurrences SEEN
 * AND future mined occurrences are born SEEN; folder deletion only unassigns. user_definition/
 * user_notes are user data: targeted UPDATEs, preserved across the CONFLICT_REPLACE upserts.
 */
@RunWith(AndroidJUnit4::class)
class WordFoldersInstrumentedTest {

    private lateinit var context: android.content.Context
    private lateinit var repo: MiningRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = MiningRepository(context, DB_NAME)
        val db = repo.writableDatabase
        db.delete("item_occurrences", null, null)
        db.delete("extracted_items", null, null)
        db.delete("word_folders", null, null)
        db.delete("scraped_photos", null, null)
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun seedPhoto(id: Long) = repo.upsertPhoto(
        ScrapedPhoto(
            mediaStoreId = id, contentUri = "content://test/$id", dateAdded = 1L,
            languageTag = LanguageTag.JAPANESE, extractedText = "文。",
            status = PhotoStatus.KEEP,
        ),
    )

    private fun seedWord(expr: String, meaning: String = "m-$expr") = repo.upsertExtractedItem(
        languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD, expression = expr,
        kanji = "", kana = expr, meaning = meaning,
    )

    private fun occurrenceState(expr: String, sentence: String): String? =
        repo.readableDatabase.rawQuery(
            "SELECT state FROM item_occurrences WHERE expression = ? AND sentence = ?",
            arrayOf(expr, sentence),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Test
    fun createWordFolder_isCreateOrReuse_byUniqueName() {
        val a = repo.createWordFolder("猫", skipInbox = false)
        val b = repo.createWordFolder("猫", skipInbox = true)
        assertEquals(a, b) // reuse keeps the original row (name is the key)
        assertEquals(1, repo.listWordFolders().count { it.id == a })
        assertEquals(-1L, repo.createWordFolder("   ", skipInbox = false))
    }

    @Test
    fun moveToSkipInboxFolder_marksExistingUnseenSeen() {
        seedPhoto(401L)
        seedWord("猫")
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "猫", 401L, "猫がいる。")
        assertEquals("UNSEEN", occurrenceState("猫", "猫がいる。"))

        val folder = repo.createWordFolder("その他", skipInbox = true)
        repo.moveItemToFolder(LanguageTag.JAPANESE, ItemType.WORD, "猫", folder)
        assertEquals("SEEN", occurrenceState("猫", "猫がいる。"))
        assertEquals(folder, repo.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "猫")?.folderId)
        assertEquals("その他", repo.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "猫")?.folderName)
    }

    @Test
    fun futureOccurrences_bornSeenInSkipFolder_unseenInPlainFolder() {
        seedPhoto(402L)
        val skip = repo.createWordFolder("スキップ", skipInbox = true)
        val plain = repo.createWordFolder("通常", skipInbox = false)

        seedWord("魚")
        repo.moveItemToFolder(LanguageTag.JAPANESE, ItemType.WORD, "魚", skip)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "魚", 402L, "魚を釣った。")
        assertEquals("SEEN", occurrenceState("魚", "魚を釣った。"))

        seedWord("鳥")
        repo.moveItemToFolder(LanguageTag.JAPANESE, ItemType.WORD, "鳥", plain)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "鳥", 402L, "鳥が飛ぶ。")
        assertEquals("UNSEEN", occurrenceState("鳥", "鳥が飛ぶ。"))
    }

    @Test
    fun moveOutOfFolder_neverUnsees_andDeleteOnlyUnassigns() {
        seedPhoto(403L)
        seedWord("虫")
        val folder = repo.createWordFolder("虫類", skipInbox = true)
        repo.moveItemToFolder(LanguageTag.JAPANESE, ItemType.WORD, "虫", folder)
        repo.insertOccurrence(LanguageTag.JAPANESE, ItemType.WORD, "虫", 403L, "虫がいる。")
        assertEquals("SEEN", occurrenceState("虫", "虫がいる。"))

        repo.moveItemToFolder(LanguageTag.JAPANESE, ItemType.WORD, "虫", null)
        assertEquals("SEEN", occurrenceState("虫", "虫がいる。")) // removal never re-opens the inbox

        repo.moveItemToFolder(LanguageTag.JAPANESE, ItemType.WORD, "虫", folder)
        repo.deleteWordFolder(folder)
        assertNull(repo.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "虫")?.folderId)
        assertTrue(repo.listWordFolders().none { it.id == folder })
        assertEquals("SEEN", occurrenceState("虫", "虫がいる。")) // deletion touches no occurrences
    }

    @Test
    fun userDefinitionAndNotes_surviveReplaceUpsert() {
        seedWord("花", meaning = "flower")
        repo.setUserDefinition(LanguageTag.JAPANESE, ItemType.WORD, "花", "さいか — my kanji reading note")
        repo.setUserNotes(LanguageTag.JAPANESE, ItemType.WORD, "花", "compare 鼻 (bi)")

        // Re-mining an existing word goes through the CONFLICT_REPLACE upsert — user data must survive.
        repo.upsertExtractedItem(
            languageTag = LanguageTag.JAPANESE, itemType = ItemType.WORD, expression = "花",
            kanji = "", kana = "はな", meaning = "flower (refreshed)",
        )
        val d = repo.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "花")
        assertEquals("さいか — my kanji reading note", d?.userDefinition)
        assertEquals("compare 鼻 (bi)", d?.userNotes)

        repo.setUserDefinition(LanguageTag.JAPANESE, ItemType.WORD, "花", "")
        assertNull(repo.getItemDetail(LanguageTag.JAPANESE, ItemType.WORD, "花")?.userDefinition)
    }

    @Test
    fun folderWordList_scopedToLanguageAndType() {
        val folder = repo.createWordFolder("語彙", skipInbox = false)
        seedWord("雨")
        seedWord("雪")
        repo.upsertExtractedItem(
            languageTag = LanguageTag.CHINESE, itemType = ItemType.WORD, expression = "雷",
            kanji = "", kana = "lèi", meaning = "thunder",
        )
        repo.moveItemToFolder(LanguageTag.JAPANESE, ItemType.WORD, "雨", folder)
        repo.moveItemToFolder(LanguageTag.CHINESE, ItemType.WORD, "雷", folder)

        val jpWords = repo.listWordRowsInFolder(folder, LanguageTag.JAPANESE, ItemType.WORD)
        assertEquals(listOf("雨"), jpWords.map { it.expression })
        val zhWords = repo.listWordRowsInFolder(folder, LanguageTag.CHINESE, ItemType.WORD)
        assertEquals(listOf("雷"), zhWords.map { it.expression })

        // listWordFolders counts are global across languages (folder row badge), user's mental model.
        assertEquals(2, repo.listWordFolders().first { it.id == folder }.wordCount)
    }

    private companion object {
        const val DB_NAME = "test_word_folders.db"
    }
}
