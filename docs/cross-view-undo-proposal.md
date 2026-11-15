# Design Proposal: Cross-View Undo System

Date: 2026-11-15
Status: Proposal (awaiting user go before implementation)

## Problem Statement

Mutations in AnkiBiki happen across many activities, several of which immediately `finish()` after mutating (`WordGrammarDetailActivity` on `既読`/`非表示`/`アーカイブ`/`削除`; `PhotoDetailActivity` on `削除`; `SentenceDetailActivity` on word/sentence edits; `ClaimsQueueActivity` on `正しい`/`違う`). A simple per-activity in-memory undo stack is lost as soon as the user navigates or the activity closes.

## Proposed Architecture: DB-Backed Undo Log (`undo_log`)

### 1. Storage (`mining.db`, schema v11)
Add a lightweight `undo_log` table:
```sql
CREATE TABLE IF NOT EXISTS undo_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action_type TEXT NOT NULL,
    label TEXT NOT NULL,          -- Japanese summary for UI (e.g. 「回復」を非表示にしました)
    payload_json TEXT NOT NULL,   -- Serialized before-state memento
    created_at INTEGER NOT NULL
);
```
- Keep the most recent 50 entries (prune `id NOT IN (SELECT id FROM undo_log ORDER BY id DESC LIMIT 50)` on insert).
- All undoable mutations write their `undo_log` row inside the **same SQLite transaction** as the mutation itself (`miningRepository.inTransaction { ... }`), guaranteeing atomicity.

### 2. Undoable Actions & Before-State Mementos

| Action | Screen(s) | Captured Before-State (`payload_json`) | Undo Behavior |
|---|---|---|---|
| `MARK_OCCURRENCE_SEEN` | Word detail | `occurrenceId`, `oldState` (`UNSEEN`) | Restore `item_occurrences.state = oldState` |
| `DELETE_OCCURRENCE` | Word detail | `occurrenceId`, `oldState` | Restore `item_occurrences.state = oldState` |
| `SET_ITEM_VISIBILITY` | Word detail | `key (lang, type, expr)`, `oldVisibility`, `oldIsHidden`, list of `(occurrenceId, oldState)` for rows flipped `UNSEEN -> SEEN` | Restore `extracted_items` visibility + each occurrence's `state` |
| `DELETE_PHOTO` | Photo detail | `mediaStoreId`, `oldStatus` (`KEEP`) | Restore `scraped_photos.status = 'KEEP'` |
| `TOGGLE_GOLD` | Photo detail | `mediaStoreId`, `oldIsGold` | Restore `is_gold` |
| `SENTENCE_TEXT_EDIT` | Sentence / Photo detail | `photoId`, `oldExtractedText` (or `oldSentence`, `newSentence`), full snapshot of affected `item_occurrences` rows | Restore text + exact occurrence rows (`is_manual`, `state`, `modified_by_user_flag`) |
| `SENTENCE_WORDS_EDIT` | Sentence detail | `photoId`, `sentence`, snapshot of `item_occurrences` rows for `(photoId, sentence)` | Replace `(photoId, sentence)` occurrences with snapshot |
| `NAMEPLATE_RULING` | Photo detail / Claims queue | `claimId`, `mediaStoreId`, `oldVerdict`, `oldExtractedText`, occurrence snapshot if inline cut | Restore `verdict = NULL, verdict_at = NULL`, text + occurrences |
| `REORDER_SENTENCES` | Word detail | `key (lang, type, expr)`, `oldSentenceRanksJson` | Restore `sentence_ranks` |

### 3. UI & Cross-View Delivery

1. **Immediate `Snackbar` with `元に戻す`**:
   - When an action stays on the same screen (e.g. `TOGGLE_GOLD`, `SENTENCE_WORDS_EDIT`, `NAMEPLATE_RULING`), show a `Snackbar` on the current screen with `元に戻す`.
   - When an action `finish()`es the detail screen (e.g. `MARK_OCCURRENCE_SEEN`, `SET_ITEM_VISIBILITY`, `DELETE_PHOTO`), the **parent screen** (`ExtractedListActivity`, `PhotoLibraryActivity`, `SentenceDetailActivity`, `MainActivity`) checks `undoRepository.latestUnannounced()` in `onResume()` and displays the `Snackbar` with `元に戻す`!
   - Tapping `元に戻す` executes `UndoManager.undoLatest()` on `AppIo.pipeline`, pops the `undo_log` row, and refreshes the current screen.
2. **Global Undo History (`操作履歴 / 元に戻す`)**:
   - Accessible from Settings / overflow menu in `MainActivity` (or an undo icon), listing the last 20 actions with Japanese labels and timestamps.
   - Allows undoing an action even after navigating through multiple screens.

### 4. Safety & Conflict Rules
- **Single-writer serialization**: All undo operations run on `AppIo.pipeline` inside `miningRepository.inTransaction`.
- **Stale-target guard**: Before applying an undo (e.g. `SENTENCE_TEXT_EDIT`), verify the target row still exists and hasn't been overwritten by a full `rescanPhoto` since `created_at` (if it was rescanned, warn the user instead of silently clobbering).
- **No redo complexity in v1**: Start with a clean undo stack; redo can be added later if needed.

## Exit Criteria
- Schema v11 migration test (`undo_log` creation + backward-compatible `REQUIRED_COLUMNS`).
- JVM unit tests for every `UndoableAction` round-trip (mutate -> undo -> DB byte-identical to pre-mutation state).
- Instrumented UI test: mark word hidden in `WordGrammarDetailActivity` -> returns to `ExtractedListActivity` -> tap `元に戻す` on Snackbar -> word reappears in inbox.
