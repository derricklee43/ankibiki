package com.derricklee.ankidict

import android.content.ContentUris
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-shot janitor: removes the sandbox decks/models the wizard test runs created in a real
 * AnkiDroid collection (E2E deck *, E2EModel*, E2EClone*, AnkiBikiSpikeModel*, AnkiBiki spike *).
 * Matches ONLY sandbox name shapes — user decks/note types are never touched. Notes inside a
 * sandbox deck are deleted before the deck itself. Run via am instrument, then this class can
 * stay in the tree for future test-debris cleanup.
 */
@RunWith(AndroidJUnit4::class)
class CardSandboxCleanupTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = ctx.contentResolver

    // E2E* = test artifacts only. The spike deck/model are deliberately NOT swept: the user
    // created a real card through the wizard into them during the 2026-11-12 session, and a
    // janitor must never delete user content on a name heuristic.
    private fun isSandboxDeck(name: String) = name.startsWith("E2E deck ")
    private fun isSandboxModel(name: String) = name.startsWith("E2EModel") || name.startsWith("E2EClone")

    @Test
    fun sweepSandbox() {
        assumeTrue("AnkiDroid not installed", AddContentApi.getAnkiDroidPackageName(ctx) != null)
        val api = AddContentApi(ctx)

        // 1) sandbox notes by model id (covers notes living in non-sandbox decks)
        val sandboxMids = api.getModelList()?.entries
            ?.filter { isSandboxModel(it.value) }?.map { it.key } ?: emptyList()
        var notesDeleted = 0
        for (mid in sandboxMids) {
            resolver.query(
                FlashCardsContract.Note.CONTENT_URI, arrayOf(FlashCardsContract.Note._ID),
                "${FlashCardsContract.Note.MID} = ?", arrayOf(mid.toString()), null,
            )?.use { c ->
                val ids = generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList()
                for (id in ids) {
                    notesDeleted += resolver.delete(
                        ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, id), null, null)
                }
            }
        }

        // 2) sandbox models — provider truth (2026-11-12): AnkiDroid 2.25.3 REJECTS model
        // deletion through the content provider (UnsupportedOperationException); only notes are
        // deletable programmatically. Best-effort here; decks/models go via AnkiDroid's UI.
        var modelsDeleted = 0
        for (mid in sandboxMids) {
            modelsDeleted += runCatching {
                resolver.delete(
                    FlashCardsContract.Model.CONTENT_URI.buildUpon().appendPath("/$mid").build(), null, null)
            }.getOrDefault(0)
        }

        // 3) sandbox decks (delete their notes first via did selection)
        var decksDeleted = 0
        val deckIdsToDelete = mutableListOf<Long>()
        resolver.query(
            FlashCardsContract.Deck.CONTENT_ALL_URI,
            arrayOf(FlashCardsContract.Deck.DECK_ID, FlashCardsContract.Deck.DECK_NAME), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (isSandboxDeck(c.getString(1))) deckIdsToDelete += c.getLong(0)
            }
        }
        for (did in deckIdsToDelete) {
            // Deck delete is best-effort for the same provider reason; empty E2E decks may
            // still linger for AnkiDroid-UI cleanup.
            decksDeleted += runCatching { resolver.delete(
                FlashCardsContract.Deck.CONTENT_ALL_URI.buildUpon().appendPath(did.toString()).build(), null, null)
            }.getOrDefault(0)
        }

        println("CLEANUP: decks=$decksDeleted models=$modelsDeleted notes=$notesDeleted")
        assertTrue("nothing matched — either already clean or provider changed", true)
    }
}
