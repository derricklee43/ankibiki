package com.derricklee.ankidict

import android.content.Context
import android.net.Uri
import com.ichi2.anki.FlashCardsContract
import java.util.Locale
import com.ichi2.anki.api.AddContentApi

data class NoteResult(
    val id: Long,
    val modelId: Long,
    val tags: String,
    val fields: List<String>,
)

/** [exact] is the character/meaning-field tier (already deck-interleaved); [rest] is everything else. */
data class TieredNotes(val exact: List<NoteResult>, val rest: List<NoteResult>)

private const val FIELD_SEPARATOR = "\u001F"

class AnkiRepository(private val context: Context) {

    private val addContentApi by lazy { AddContentApi(context) }

    fun isAnkiDroidAvailable(): Boolean =
        AddContentApi.getAnkiDroidPackageName(context) != null

    /**
     * Searches every note's fields for [query] (a plain substring match, restricted to
     * [modelIds]) and ranks them: a match on the character/kanji field, then a match on the
     * meaning field, then anywhere else -- with each tier interleaved round-robin across decks
     * so a deck with far more raw matches (e.g. NihongoShark's huge, narrative mnemonics
     * matching a common substring) doesn't bury the other deck's results.
     */
    fun searchTiered(query: String, modelIds: Collection<Long>): TieredNotes {
        if (query.isBlank() || modelIds.isEmpty()) return TieredNotes(emptyList(), emptyList())

        val modelPlaceholders = modelIds.joinToString(",") { "?" }
        val selection = "flds LIKE ? AND mid IN ($modelPlaceholders)"
        val selectionArgs = (listOf("%$query%") + modelIds.map { it.toString() }).toTypedArray()
        val results = queryNotes(selection, selectionArgs)

        val byTier = results.groupBy { matchPriority(it, query) }
        val exact = interleaveByModel(byTier[0].orEmpty())
        val rest = (1..2).flatMap { tier -> interleaveByModel(byTier[tier].orEmpty()) }
        return TieredNotes(exact, rest)
    }

    /** Every note of [modelId], unfiltered -- used by HanziWordSyncService to load a whole deck. */
    fun queryAllNotes(modelId: Long): List<NoteResult> =
        queryNotes("mid = ?", arrayOf(modelId.toString()))

    /**
     * Exact character-field lookup for a single [character] restricted to [modelId], used by the
     * ACTION_PROCESS_TEXT popup so selecting text only pulls the exact Kanji card for each character.
     */
    fun findNoteByCharacter(character: String, modelId: Long): NoteResult? {
        val charFieldIndex = CHARACTER_FIELD_INDEX[modelId] ?: return null
        val candidates = queryNotes(
            "flds LIKE ? AND mid = ?",
            arrayOf("%$character%", modelId.toString()),
        )
        return candidates.firstOrNull { it.fields.getOrNull(charFieldIndex) == character }
    }

    fun createNote(modelId: Long, deckId: Long, fields: List<String>, tags: Set<String> = emptySet()): Long? =
        addContentApi.addNote(modelId, deckId, fields.toTypedArray(), tags)

    fun updateNoteFields(noteId: Long, fields: List<String>): Boolean =
        addContentApi.updateNoteFields(noteId, fields.toTypedArray())

    // ---- card-creation wizard gateway (proven on-device by spike 001, 2026-11-12) ----

    data class AnkiDeckInfo(val deckId: Long, val name: String, val counts: List<String>)

    /** All decks incl. empty ones (spike P2); counts are the raw provider [new,learning,mature] triple. */
    fun listDecks(): List<AnkiDeckInfo> {
        val rows = mutableListOf<AnkiDeckInfo>()
        val c = context.contentResolver.query(
            FlashCardsContract.Deck.CONTENT_ALL_URI,
            arrayOf(
                FlashCardsContract.Deck.DECK_ID,
                FlashCardsContract.Deck.DECK_NAME,
                FlashCardsContract.Deck.DECK_COUNTS,
            ),
            null, null, null,
        ) ?: return rows
        c.use {
            while (it.moveToNext()) {
                val counts = it.getString(2).orEmpty().removeSurrounding("[", "]")
                    .split(",").map { n -> n.trim() }
                rows += AnkiDeckInfo(it.getLong(0), it.getString(1).orEmpty(), counts)
            }
        }
        return rows.sortedBy { it.name.lowercase(Locale.US) }
    }

    data class AnkiModelInfo(val modelId: Long, val name: String, val fieldNames: List<String>)

    /** Field names in ordinal order for one model (spike P4: this is the proven truth source). */
    fun fieldNames(modelId: Long): List<String> =
        runCatching { addContentApi.getFieldList(modelId)?.toList() }.getOrNull() ?: emptyList()

    /** Read a written note back (post-create verification). */
    fun readNoteFields(noteId: Long): List<String>? =
        runCatching { addContentApi.getNote(noteId)?.fields?.toList() }.getOrNull()

    /** AnkiDroid's remembered "current" note type (last used in Add) — the soft deck/type link. */
    fun currentModelId(): Long = runCatching { addContentApi.currentModelId }.getOrDefault(-1L)

    /** All note types with their field names in ordinal order (spike P3/P4: FIELD_NAMES is truth). */
    fun listModels(): List<AnkiModelInfo> =
        (addContentApi.getModelList()?.entries ?: emptySet()).map { (mid, name) ->
            AnkiModelInfo(mid, name.orEmpty(), addContentApi.getFieldList(mid)?.toList() ?: emptyList())
        }.sortedBy { it.name.lowercase(Locale.US) }

    /** Rendered Q/A per generated card for a would-be note (creates+removes a scratch note inside
     *  AnkiDroid — the same preview mechanism AnkiDroid's own API sample uses). */
    fun previewNewNote(modelId: Long, fields: List<String>): Map<String, Map<String, String>>? =
        runCatching { addContentApi.previewNewNote(modelId, fields.toTypedArray()) }.getOrNull()

    /** Existing notes whose first field equals [key] for this model (roadmap §4 dedupe check). */
    fun findDuplicateNotes(modelId: Long, key: String): List<String> = runCatching {
        addContentApi.findDuplicateNotes(modelId, key).mapNotNull { it?.getKey() }
    }.getOrDefault(emptyList())

    /** Import a media file into Anki's collection; returns the stored filename for field embedding. */
    fun addMediaFile(uri: String, preferredName: String, mimeType: String): String? = runCatching {
        addContentApi.addMediaFromUri(Uri.parse(uri), preferredName, mimeType)
    }.getOrNull()

    // CONTENT_URI_V2 runs selection/selectionArgs as parameterized SQL against the notes table.
    // The plain CONTENT_URI instead parses `selection` as Anki's browser search syntax and
    // ignores selectionArgs entirely, which isn't what we want here.
    private fun queryNotes(selection: String, selectionArgs: Array<String>): List<NoteResult> {
        val cursor = context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI_V2,
            FlashCardsContract.Note.DEFAULT_PROJECTION,
            selection,
            selectionArgs,
            null,
        ) ?: return emptyList()

        val results = mutableListOf<NoteResult>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
            val midCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.MID)
            val tagsCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS)
            val fldsCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)

            while (it.moveToNext()) {
                results.add(
                    NoteResult(
                        id = it.getLong(idCol),
                        modelId = it.getLong(midCol),
                        tags = it.getString(tagsCol) ?: "",
                        fields = (it.getString(fldsCol) ?: "").split(FIELD_SEPARATOR),
                    )
                )
            }
        }
        return results
    }

    private fun matchPriority(note: NoteResult, query: String): Int {
        if (fieldMatches(note, query, CHARACTER_FIELD_INDEX)) return 0
        if (fieldMatches(note, query, MEANING_FIELD_INDEX)) return 1
        return 2
    }

    private fun fieldMatches(note: NoteResult, query: String, fieldIndexByModel: Map<Long, Int>): Boolean {
        val fieldIndex = fieldIndexByModel[note.modelId] ?: return false
        return note.fields.getOrNull(fieldIndex)?.contains(query, ignoreCase = true) == true
    }

    private fun interleaveByModel(notes: List<NoteResult>): List<NoteResult> =
        interleave(notes.groupBy { it.modelId }.values.toList())
}
