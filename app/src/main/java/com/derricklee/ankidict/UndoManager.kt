package com.derricklee.ankidict

/**
 * Undo dispatch (cross-view undo, user go 2026-11-15). Pops the NEWEST log row (strict LIFO —
 * no redo, no arbitrary-row undo in v1; the history screen explains why older rows are display
 * only), verifies the world still looks like the post-mutation state the memento recorded, and
 * restores the captured before-state inside ONE transaction. A failed guard never half-applies:
 * the mutation stack above the stale entry must be undone first or the target was changed by
 * other means — the user gets a warning instead of a silent clobber.
 */

internal enum class UndoOutcome { UNDONE, GUARD_FAILED, NOTHING_LEFT, CORRUPT }

internal object UndoManager {

    fun undoLatest(repo: MiningRepository): UndoOutcome {
        val entry = repo.latestUndo() ?: return UndoOutcome.NOTHING_LEFT
        val memento = UndoMemento.decode(entry.action, entry.label, entry.payload)
            ?: return UndoOutcome.CORRUPT
        val ok = repo.inTransaction { restore(repo, memento, entry) }
        return if (ok) {
            repo.deleteUndo(entry.id)
            UndoOutcome.UNDONE
        } else {
            UndoOutcome.GUARD_FAILED
        }
    }

    /** True = restored. Runs inside the caller's transaction. */
    private fun restore(repo: MiningRepository, m: UndoMemento, entry: UndoEntry): Boolean {
        val h = m.header
        when (m.action) {
            UndoAction.MARK_OCCURRENCE_SEEN -> {
                val id = h.getOrNull(0)?.toLongOrNull() ?: return true // nothing to restore
                if (repo.undoOccState(id) != OccurrenceState.SEEN.name) return false
                repo.restoreOccurrenceState(id, h.getOrElse(1) { OccurrenceState.UNSEEN.name })
            }
            UndoAction.DELETE_OCCURRENCE -> {
                val id = h.getOrNull(0)?.toLongOrNull() ?: return true
                if (repo.undoOccState(id) != OccurrenceState.DELETED.name) return false
                repo.restoreOccurrenceState(id, h.getOrElse(1) { OccurrenceState.UNSEEN.name })
            }
            UndoAction.SET_ITEM_VISIBILITY -> {
                // header: lang, type, expr, expectedNewVisibility, oldVisibility, oldIsHidden
                if (h.size < 6) return true
                val (lang, type, expr, expectedNew, oldVis) = h
                val current = repo.undoItemVisibility(lang, type, expr)
                if (current != null && current.first != expectedNew) return false
                repo.restoreItemVisibility(lang, type, expr, oldVis, h[5] == "1")
                // Occurrences this action flipped (captured rows): restore their states where the
                // rows still exist; rows the user later deleted outright stay gone (LIFO already
                // covers interleaved edits — their own entries undo first).
                for (r in m.occurrences) {
                    if (repo.undoOccState(r.id) != null) repo.restoreOccurrenceState(r.id, r.state)
                }
            }
            UndoAction.DELETE_PHOTO -> {
                val mediaId = h.getOrNull(0)?.toLongOrNull() ?: return true
                if (repo.undoPhotoStatus(mediaId) != PhotoStatus.DELETED.name) return false
                m.photo?.let { repo.restorePhotoRow(it) }
                repo.restoreOccurrenceScopeAll(mediaId, m.occurrences)
            }
            UndoAction.TOGGLE_GOLD -> {
                val mediaId = h.getOrNull(0)?.toLongOrNull() ?: return true
                val oldGold = h.getOrNull(1) == "1"
                // Gold has no hidden side effects from text edits; a later rescan also writes
                // gold=false — restoring the recorded value is the truthful previous state.
                if (repo.undoGold(mediaId) == oldGold) return true // already the state
                repo.restorePhotoGold(mediaId, oldGold)
            }
            UndoAction.REORDER_SENTENCES -> {
                if (h.size < 4) return true
                val (lang, type, expr, ranks) = h
                repo.restoreSentenceRanks(lang, type, expr, ranks.ifEmpty { null })
            }
            UndoAction.ITEM_MEANING_PICK -> {
                // header: lang, type, expr, expectedAfterMeaning, then seven prior values
                // ("" sentinel = NULL). Guard = the meaning is still the one this pick wrote.
                if (h.size < 11) return true
                val current = repo.undoItemMeaningCurrent(h[0], h[1], h[2]) ?: return false
                if (current != h[3]) return false
                repo.restoreItemMeaningFields(
                    h[0], h[1], h[2], h[4], h[5], h[6],
                    h[7].ifEmpty { null }, h[8].toIntOrNull(),
                    h[9].ifEmpty { null }, h[10].ifEmpty { null },
                )
            }
            UndoAction.SENTENCE_TEXT_EDIT -> {
                // header: mediaStoreId, expectedAfterText. Capture is the whole photo + all its
                // occurrence rows; restore replaces them (byte-identity without key-set math).
                val mediaId = h.getOrNull(0)?.toLongOrNull() ?: return true
                val expected = h.getOrNull(1) ?: return true
                if (repo.undoPhotoText(mediaId) != expected) return false
                m.photo?.let { repo.restorePhotoRow(it) }
                repo.restoreOccurrenceScopeAll(mediaId, m.occurrences)
            }
            UndoAction.SENTENCE_WORDS_EDIT -> {
                val mediaId = h.getOrNull(0)?.toLongOrNull() ?: return true
                val expected = h.getOrNull(1) ?: return true
                // Word edits don't move the photo text — that equality is the stale-target guard.
                if (repo.undoPhotoText(mediaId) != expected) return false
                m.photo?.let { repo.restorePhotoRow(it) }
                repo.restoreOccurrenceScopeAll(mediaId, m.occurrences)
            }
            UndoAction.NAMEPLATE_RULING -> {
                // header: claimId, mediaStoreId, expectedAfterText
                val claimId = h.getOrNull(0)?.toLongOrNull() ?: return true
                val mediaId = h.getOrNull(1)?.toLongOrNull() ?: return true
                val expected = h.getOrNull(2)
                if (expected != null && repo.undoPhotoText(mediaId) != expected) return false
                repo.restoreClaimPending(claimId)
                m.photo?.let { repo.restorePhotoRow(it) }
                repo.restoreOccurrenceScopeAll(mediaId, m.occurrences)
            }
        }
        return true
    }
}
