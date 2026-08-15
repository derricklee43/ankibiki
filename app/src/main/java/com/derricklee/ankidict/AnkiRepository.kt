package com.derricklee.ankidict

import android.content.Context
import com.ichi2.anki.FlashCardsContract
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

        // CONTENT_URI_V2 runs selection/selectionArgs as parameterized SQL against the notes
        // table. The plain CONTENT_URI instead parses `selection` as Anki's browser search
        // syntax and ignores selectionArgs entirely, which isn't what we want here.
        val cursor = context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI_V2,
            FlashCardsContract.Note.DEFAULT_PROJECTION,
            selection,
            selectionArgs,
            null,
        ) ?: return TieredNotes(emptyList(), emptyList())

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

        val byTier = results.groupBy { matchPriority(it, query) }
        val exact = interleaveByModel(byTier[0].orEmpty())
        val rest = (1..2).flatMap { tier -> interleaveByModel(byTier[tier].orEmpty()) }
        return TieredNotes(exact, rest)
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
