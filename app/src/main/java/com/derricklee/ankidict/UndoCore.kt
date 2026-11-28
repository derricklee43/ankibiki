package com.derricklee.ankidict

/**
 * Cross-view undo (user go 2026-11-15, docs/cross-view-undo-proposal.md).
 *
 * Undoable mutations write a memento row into `undo_log` INSIDE the same SQLite transaction as
 * the mutation; UndoManager pops the newest row (LIFO stack, no redo in v1) and restores the
 * captured before-state, guarded against later divergent changes (a stale undo warns, never
 * clobbers). This file is the pure, JVM-testable layer: action vocabulary, before-state
 * snapshots, and the payload codec. No Android types — the escape/encode round-trips run in
 * unit tests against sentences full of tabs, newlines and control characters.
 */

enum class UndoAction {
    MARK_OCCURRENCE_SEEN,
    DELETE_OCCURRENCE,
    SET_ITEM_VISIBILITY,
    DELETE_PHOTO,
    TOGGLE_GOLD,
    SENTENCE_TEXT_EDIT,
    SENTENCE_WORDS_EDIT,
    NAMEPLATE_RULING,
    REORDER_SENTENCES,
    ITEM_MEANING_PICK,
    // Media-anchored sentence editing (user request 2026-11-26): the single-sentence
    // view for non-photo media (video cues). Same capture/restore shape as the photo
    // actions, but scoped by media_refs.id (+ sentence) instead of media_store_id.
    MEDIA_WORDS_EDIT,
    SENTENCE_DELETE,
    // v16 merged definition model + user dictionary (docs/definition-model-design.md):
    // per-item definition edit (write-through, mirror, provenance) and entry deletion.
    ITEM_DEFINITION_EDIT,
    USER_WORD_DELETE,
    // Retroactive reparse on user-word save (docs/user-dictionary-reparse-design.md,
    // RULED R1a-R7a): delta memento restoring deleted fragment rows + removing inserted rows.
    USER_WORD_REPARSE,
}

/** One `item_occurrences` row captured exactly as stored (restore re-inserts with the SAME id). */
data class UndoOccRow(
    val id: Long,
    val languageTag: String,
    val itemType: String,
    val expression: String,
    val photoId: Long,
    val sentence: String,
    val createdAt: Long,
    val state: String,
    val modifiedByUserFlag: Boolean,
    val isManual: Boolean,
    /** v15 media_refs.id; optional trailing codec field so legacy 11-field logs keep decoding. */
    val mediaId: Long? = null,
)

/** One `scraped_photos` row captured exactly as stored. */
data class UndoPhotoRow(
    val mediaStoreId: Long,
    val extractedText: String,
    val languageTag: String,
    val status: String,
    val modifiedByUserFlag: Boolean,
    val isGold: Boolean,
    val flagColor: String?,
    val ocrBlocksJson: String?,
)

/**
 * The before-state of one undoable mutation plus per-action header fields (ids, expected-after
 * values). [action] and [label] also live in dedicated `undo_log` columns; the payload carries
 * only the rest — never duplicated back in.
 */
data class UndoMemento(
    val action: UndoAction,
    /** Japanese summary shown in the Snackbar / history list (captured at the mutation site). */
    val label: String,
    val header: List<String> = emptyList(),
    val photo: UndoPhotoRow? = null,
    val occurrences: List<UndoOccRow> = emptyList(),
) {
    fun encode(): String = UndoCodec.encode(this)

    fun decodePayload(payload: String): UndoMemento? = UndoCodec.decode(action, label, payload)

    companion object {
        /** Rebuild a full memento from an `undo_log` row's columns. */
        fun decode(action: UndoAction, label: String, payload: String): UndoMemento? =
            UndoCodec.decode(action, label, payload)
    }
}

/**
 * Line-based payload format: `v1` marker line, then one typed line per element
 * (`h`=header field, `p`=photo row, `r`=occurrence row). Every value is escaped so tabs,
 * newlines and control characters inside sentences/expressions/JSON survive round-trip.
 */
internal object UndoCodec {
    private const val SEP = '\u001F'
    private const val V1 = "v1"
    private const val NULL = "\u0000N" // sentinel for literal-NULL columns (never appears escaped)

    fun escape(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 || ch.code == 0x7F ->
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < value.length) {
                            val code = value.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 6 }
                            else { sb.append(ch); i += 1 }
                        } else { sb.append(ch); i += 1 }
                    }
                    else -> { sb.append(ch); i += 1 }
                }
            } else {
                sb.append(ch); i += 1
            }
        }
        return sb.toString()
    }

    private fun fields(values: List<String>): String =
        values.joinToString(SEP.toString()) { escape(it) }

    private fun split(line: String): List<String> = line.split(SEP).map { unescape(it) }

    private fun boolOf(b: Boolean) = if (b) "1" else "0"
    private fun boolOf(s: String) = s == "1"
    private fun nul(v: String) = if (v == NULL) null else v
    private fun nulOf(v: String?) = v ?: NULL

    fun encode(m: UndoMemento): String {
        val sb = StringBuilder(V1)
        for (h in m.header) {
            sb.append('\n').append("h").append(SEP).append(escape(h))
        }
        m.photo?.let { p ->
            sb.append('\n').append("p").append(SEP).append(fields(listOf(
                p.mediaStoreId.toString(), p.extractedText, p.languageTag, p.status,
                boolOf(p.modifiedByUserFlag), boolOf(p.isGold), nulOf(p.flagColor), nulOf(p.ocrBlocksJson),
            )))
        }
        for (r in m.occurrences) {
            val rowFields = mutableListOf(
                r.id.toString(), r.languageTag, r.itemType, r.expression, r.photoId.toString(),
                r.sentence, r.createdAt.toString(), r.state, boolOf(r.modifiedByUserFlag), boolOf(r.isManual),
            )
            if (r.mediaId != null) rowFields += r.mediaId.toString()
            sb.append('\n').append("r").append(SEP).append(fields(rowFields))
        }
        return sb.toString()
    }

    fun decode(action: UndoAction, label: String, payload: String): UndoMemento? {
        val lines = payload.split('\n')
        if (lines.firstOrNull() != V1) return null
        val header = mutableListOf<String>()
        var photo: UndoPhotoRow? = null
        val rows = mutableListOf<UndoOccRow>()
        for (line in lines.drop(1)) {
            val parts = split(line)
            when (parts.first()) {
                "h" -> header.add(parts.getOrElse(1) { "" })
                "p" -> if (parts.size >= 9) {
                    val id = parts[1].toLongOrNull() ?: return null
                    photo = UndoPhotoRow(
                        mediaStoreId = id,
                        extractedText = parts[2], languageTag = parts[3], status = parts[4],
                        modifiedByUserFlag = boolOf(parts[5]), isGold = boolOf(parts[6]),
                        flagColor = nul(parts[7]), ocrBlocksJson = nul(parts[8]),
                    )
                } else return null
                "r" -> if (parts.size >= 11) {
                    val id = parts[1].toLongOrNull() ?: return null
                    val photoId = parts[5].toLongOrNull() ?: return null
                    val createdAt = parts[7].toLongOrNull() ?: return null
                    val mediaId = parts.getOrNull(11)?.let { it.toLongOrNull() ?: return null }
                    rows.add(UndoOccRow(
                        id = id, languageTag = parts[2], itemType = parts[3], expression = parts[4],
                        photoId = photoId, sentence = parts[6], createdAt = createdAt,
                        state = parts[8], modifiedByUserFlag = boolOf(parts[9]), isManual = boolOf(parts[10]),
                        mediaId = mediaId,
                    ))
                } else return null
                else -> return null
            }
        }
        return UndoMemento(action, label, header, photo, rows)
    }
}
