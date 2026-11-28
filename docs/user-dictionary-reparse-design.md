# User dictionary retroactive reparse — match & reparse existing sentences on word creation

**STATUS: RULED 2026-11-27 (R1a/R2a/R3a/R4a/R5a/R6a/R7a). IMPLEMENTED 2026-11-27
(`UserDictionaryReparse.kt`, `MiningQueries.kt` exact-match + zero-candidate
finders, `UndoAction.USER_WORD_REPARSE` + `UndoOccRow.mediaId`, modal checkbox
on `UserDictionaryActivity`, JVM `UserDictionaryReparseTest` + `UndoCodecTest`,
instrumented `UserDictionaryReparseInstrumentedTest`).** Phase-2
follow-up to [user-dictionary-design.md](user-dictionary-design.md) (§3.5 / D2a
deferred retroactive corpus application to phase-2) and
[definition-model-design.md](definition-model-design.md). Design doc for the
feature request of 2026-11-27 (user): "In my user dictionary, I want a checkbox
on the word creation modal that will search all sentence strings for an exact
match then reparse all matches."

## 1. Problem statement (every claim grounded in live code)

How adding a user-dictionary entry behaves today:

- `UserDictionaryActivity.showEntryDialog(existing: UserWord?)`
  (`UserDictionaryActivity.kt:156-213`) renders four `EditText` inputs
  (`expressionInput`, `readingInput`, `meaningInput`, `notesInput`) and on save
  calls `miningRepository.upsertUserWord(...)` on `AppIo.io`
  (`UserDictionaryActivity.kt:28, 196-203`).
- `MiningRepository.upsertUserWord` (`MiningRepository.kt:1853-1893`) writes
  `user_words` and immediately propagates `meaning`/`kana`/`kanji` to
  ALREADY-existing `extracted_items` rows where
  `expression = expr AND definition_source IN ('AUTO', 'USER_DICT')` (E5a). It
  never scans or modifies `item_occurrences`.
- Future extractions pick up the entry automatically because
  `UserDictionaryWordAnalyzer` (`UserDictionary.kt:33-113`) and
  `UserDictionaryProvider` (`UserDictionary.kt:115-135`) read `user_words` via
  live suppliers wired once in `MiningPipelineRunner.createDefault`
  (`MiningPipelineRunner.kt:903-915`).
- **The gap**: When a user notices a mis-parsed name (e.g. `竈門炭治郎` split into
  `竈門` / `炭` / `治郎`, or a name dropped by Kuromoji's POS/stopword filters)
  in already-mined photos or Stremio subtitle cues and adds it to ユーザー事典:
  - No canonical `extracted_items` row exists yet for `竈門炭治郎`, so E5a
    propagation matches 0 rows.
  - Every already-mined sentence containing `竈門炭治郎` keeps its broken
    fragment rows in `item_occurrences` and never gains the new word.
  - Today's only remedies are per-sentence manual edits in
    `SentenceDetailActivity` / `TextEditorActivity` (does not scale) or
    re-mining whole Stremio episodes via `StremioExtractionEngine.runJob` (which
    calls `deleteStremioOccurrencesForDoc`, `StremioExtractionEngine.kt:150`,
    wiping all review triage for that episode).
- `MiningPipelineRunner.reprocessAmendedSentence`
  (`MiningPipelineRunner.kt:681-793`) already implements candidate-granularity
  reconciliation (preserving `is_manual = 1` rows and triage on surviving
  candidates, dropping obsolete non-manual rows), but it is photo-only
  (`photo_id`), single-sentence, and executes `insertCandidates` (which fires
  AnkiDroid ContentProvider IPC + dictionary lookups) INSIDE the SQLite write
  transaction — the exact lock-hold hazard that caused the 2026-11-26 60s
  device freeze (`StremioExtractionEngine.kt:158-174`).

Consequence: retroactive reparsing needs a **media-agnostic, plan/apply,
reversible** batch reparse use case wired to a checkbox on the word creation
modal.

## 2. Desired behavior (from the user's request)

1. The word creation modal in ユーザー事典 (`UserDictionaryActivity`) has a
   checkbox to search all existing sentence strings for an exact match of the
   new word and reparse every matched sentence.
2. Saving with the checkbox **unchecked** behaves byte-identically to today
   (fast `user_words` upsert + E5a `extracted_items` propagation).
3. Saving with the checkbox **checked**:
   - Saves the `user_words` entry first (so user input is never lost on failure
     and live analyzer/provider suppliers immediately see it).
   - Finds all active sentence strings in the corpus containing an exact
     substring match of `expression`.
   - Reparses each matched sentence through the single `CorpusInsertion`
     analyzer seam (`UserDictionaryWordAnalyzer` + `JlptGrammarAnalyzer`) and
     reconciles its occurrences at candidate granularity:
     - Newly parsed candidates (the user word) are registered (`USER_DICT`
       definition provenance) and inserted.
     - Obsolete non-manual fragment occurrences (e.g. `竈門`, `炭`, `治郎`) are
       removed.
     - Manual word-editor rows (`is_manual = 1`) are never deleted.
     - Surviving candidates keep their existing review state
       (`UNSEEN`/`SEEN`/`DELETED`) and metadata untouched.
     - False-positive substring hits rejected by `UserDictionaryWordAnalyzer`'s
       strict-substring IGNORE rule (e.g. `累` inside `累計`,
       `UserDictionary.kt:76-77`) produce zero changes.
4. Works uniformly across all active media kinds (`photo` with `status = 'KEEP'`
   and `video_cues` with `media_refs.active = 1`), preserving v15 dual-write
   (`photo_id` mirrored for photos, `NULL` for non-photo media) and gold photo
   invariants (never runs OCR or modifies `extracted_text`, `ocr_blocks_json`,
   or `is_gold`).
5. Runs safely off the UI thread on `AppIo.pipeline`, survives Activity
   rotation/teardown, never holds a SQLite transaction across IPC/analysis,
   reports concrete match/change counts, and is reversible via Cross-View Undo.

## 3. Design

### 3.1 Modal UI & Lifecycle (`UserDictionaryActivity.kt`)

In `UserDictionaryActivity.showEntryDialog(existing: UserWord?)`
(`UserDictionaryActivity.kt:156-213`):

- Add a `CheckBox` below `notesInput` inside `container`:
  - Label (Japanese per repo rule): `一致する既存の文を再解析する`
    (`R.string.user_word_reparse_checkbox`).
  - Visibility on edit (`existing != null`): decision **R1** (recommendation:
    show on both create and edit — unchecked on edit — so a user who saved a
    word earlier without reparsing can re-open it and trigger a reparse without
    adding a separate row button).
  - Default state on creation (`existing == null`): decision **R2**.
- **Threading & teardown safety (live-code hazard)**:
  - `UserDictionaryActivity.onDestroy()` (`UserDictionaryActivity.kt:215-218`)
    closes the Activity's own `miningRepository`. Running a multi-second batch
    reparse on that instance would crash with `IllegalStateException` on
    rotation or back navigation.
  - Unchecked save keeps the existing fast `AppIo.io` write.
  - Checked save dispatches onto `AppIo.pipeline` (the single serial corpus
    lane, `AppIo.kt:35` — prevents racing with photo scans, Stremio episode
    mining, or sentence edits) using `MiningPipelineHolder.of(applicationContext)`
    (`MiningPipelineHolder.kt:15`), whose process-wide `MiningRepository`
    outlives Activity teardown.
- **Feedback**:
  - While the reparse is in flight, disable the header `追加` button (and show a
    lightweight inline status text e.g. `再解析中…` above the list) so the user
    sees active work without a blocking modal dialog.
  - On completion (guarded by `!isFinishing && !isDestroyed`): reload list,
    show a concise summary Toast:
    - 0 matched sentences: `保存しました（一致する文はありませんでした）`
    - Matched sentences, 0 changed (all no-ops / already parsed):
      `保存しました（%d文一致・変更なし）`
    - Changed sentences: `保存しました：%d文を再解析（+%d / -%d）`
  - If any occurrence row changed, invoke
    `UndoUi.maybeShow(listContainer, miningRepository) { loadEntries() }`.
    Because `undo_log.announced = 0` is DB-backed (`UndoUi.kt:18-22`), if the
    user navigated back to `MainActivity` or `ExtractedListActivity` while the
    reparse finished, that parent screen's `onResume()` delivers the Undo
    Snackbar automatically.

### 3.2 Exact Match Search Scope

#### Exact match predicate
- Use SQLite `instr(sentence, ?) > 0` — character-exact, case-sensitive
  substring match with no wildcard escaping and no ASCII case folding (unlike
  SQLite `LIKE`, which folds ASCII case and treats `%`/`_` as wildcards).
- Confirm in Kotlin with `sentence.contains(expr)` (mirrors
  `UserDictionaryWordAnalyzer.kt:71`'s `sentence.indexOf(word.expression, from)`).
- **Legacy bidi-control note**: Fan-sub cues ingested before the 2026-11-26
  `SubtitleParser.cleanLines` fix (`SubtitleParser.kt:42-52`,
  `docs/roadmap.md:184-189`) may carry invisible bidi isolates (`U+202A..E`)
  inside names. Neither `instr()` nor `UserDictionaryWordAnalyzer.indexOf()`
  matches across embedded bidi controls; consistent with the roadmap pin,
  stored sentence strings are never mutated here (re-mining that episode cleans
  them).

#### Active corpus filter
Match the exact visibility rules of `MiningQueries.getActiveOccurrences`
(`MiningQueries.kt:615-637`):

```sql
SELECT o.media_id,
       m.kind,
       m.ref_int,
       o.sentence,
       MAX(o.created_at) AS latest_created_at
FROM item_occurrences o
JOIN media_refs m
  ON o.media_id = m.id AND m.active = 1
LEFT JOIN scraped_photos p
  ON m.kind = 'photo' AND p.media_store_id = m.ref_int
WHERE o.language_tag = ?
  AND (m.kind <> 'photo' OR p.status = 'KEEP')
  AND instr(o.sentence, ?) > 0
GROUP BY o.media_id, m.kind, m.ref_int, o.sentence
HAVING SUM(CASE WHEN o.state != 'DELETED' THEN 1 ELSE 0 END) > 0
```

Key guarantees built into this query:
- **Cross-media**: covers `photo`, `video_cues`, and any future `media_refs.kind`.
- **No retired generations**: `m.active = 1` skips superseded Stremio subtitle
  generations (v15 I1 dedupe, `StremioWrites.kt:347-355`).
- **No deleted/duplicate photos**: `p.status = 'KEEP'` excludes
  `DELETED`/`DUPLICATE`/`SKIPPED` photos.
- **No resurrection of deleted sentences**: the `HAVING` clause skips
  `(media_id, sentence)` scopes where EVERY occurrence is `DELETED` (e.g.
  sentences explicitly deleted via `SENTENCE_DELETE`,
  `MiningPipelineRunner.kt:518-530`).
- **Language-scoped**: filtered by `language_tag = LanguageTag.JAPANESE.name`
  (parameterized by `LanguageTag`; Chinese stays inert until D7 is un-deferred).

#### Zero-candidate sentences (decision R3)
A sentence that previously yielded ZERO extracted candidates (e.g. a one-word
subtitle cue `"アオイ！"` or dialogue bubble whose only token Kuromoji filtered
out) has no rows in `item_occurrences` at all, so the query above would not see
it. Under **R3(a)** (recommended), two small supplementary queries add ONLY
genuinely zero-candidate scopes (`NOT EXISTS` in `item_occurrences` for that
`(media_id, sentence)`, which cleanly distinguishes them from `SENTENCE_DELETE`
sentences whose `DELETED` rows DO exist in `item_occurrences`):

1. **Photos**: `SELECT media_store_id, extracted_text, date_added FROM scraped_photos WHERE status = 'KEEP' AND language_tag IN (?, 'NA') AND instr(extracted_text, ?) > 0` → parse sentences via `parsePhotoText(config, extracted_text)`, keep sentences containing `expr` that have no rows at all in `item_occurrences` for that photo's `media_refs.id`. (Note: if the photo has a stale text-only edit where `extracted_text` diverged from `item_occurrences`, a sentence in `extracted_text` with no occurrence rows is treated as a zero-candidate match; `scraped_photos` itself is never modified.)
2. **Stremio cues**: `SELECT m.id, c.text, d.created_at FROM stremio_cues c JOIN stremio_cue_documents d ON d.id = c.doc_id JOIN media_refs m ON m.kind = 'video_cues' AND m.active = 1 AND m.ref_int = d.id WHERE instr(c.text, ?) > 0` (filtered to docs whose resolved `stremioSettingsFor` / existing occurrence language matches `tag`), keeping `(m.id, c.text)` pairs with no rows in `item_occurrences`.

Under **R3(b)**, scanning stops at `item_occurrences` (simpler; only reparses
sentences that already produced at least one candidate).

### 3.3 Candidate-Granularity Reparse & Reconciliation (`UserDictionaryReparse.kt`)

Extract the core logic into a dedicated use case (`UserDictionaryReparse.kt`,
aligning with `docs/roadmap.md` item 4: keep mutation/reprocessing rules out of
Activity callbacks) with a **pure, JVM-testable reconciliation function**:

```kotlin
internal data class SentenceScope(
    val mediaId: Long,
    val photoId: Long?,          // non-null iff media_refs.kind == "photo" (v15 dual-write)
    val sentence: String,
    val existingCreatedAt: Long?, // MAX(created_at) from existing rows, or source timestamp
)

internal data class ReconcileDiff(
    val keptCount: Int,
    val deleteIds: List<Long>,
    val missingCandidates: List<Pair<ItemType, NormalizedCandidate>>,
    val mergedDuplicateIds: List<Long>,
) {
    val hasChanges: Boolean
        get() = deleteIds.isNotEmpty() || missingCandidates.isNotEmpty() || mergedDuplicateIds.isNotEmpty()
}
```

`reconcileOccurrenceRows(existingRows: List<MiningRepository.OccurrenceRow>, candidates: List<Pair<ItemType, NormalizedCandidate>>): ReconcileDiff` mirrors `MiningPipelineRunner.reprocessAmendedSentence` (`MiningPipelineRunner.kt:744-773`) verbatim:

1. Compute `keepKeys = candidates.map { candidateKey(it) }.toSet()`.
2. Sort `existingRows` by `stateRank(DELETED=2, SEEN=1, UNSEEN=0) DESC, createdAt DESC, id DESC`.
3. For each row (`key = row.itemType + "\u0000" + row.expression`):
   - `key !in keepKeys && !row.isManual` → add `row.id` to `deleteIds` (drops obsolete Kuromoji fragments like `竈門`, `炭`, `治郎`; see **R4**).
   - `key !in keepKeys && row.isManual` → keep untouched (schema v10 manual-row protection).
   - `key in keepKeys && !seenKeys.add(key)` → add `row.id` to `mergedDuplicateIds` (keeps most-triaged row).
   - `key in keepKeys` → `keptCount++` (preserves existing `state`, `createdAt`, `modifiedByUserFlag`, `isManual`).
4. `missingCandidates = candidates.filter { candidateKey(it) !in seenKeys }`.

Why full `CorpusInsertion.analyzeCandidates(config, sentence)` works cleanly:
- `UserDictionaryWordAnalyzer` (`UserDictionary.kt:38-112`) runs the base
  Kuromoji analyzer, enforces the three span rules (strict-substring IGNORE,
  token-equal replace, multi-token fragment suppression), and bypasses
  POS/stopword filters for registered user words.
- False-positive substring hits (e.g. `累` inside `累計`) are ignored by rule 1,
  so `candidates` matches `existingRows`, `diff.hasChanges == false`, and the
  sentence is skipped with zero writes and zero undo entries.
- Gold photos (`scraped_photos.is_gold == 1`) are safe: OCR is never invoked,
  and `scraped_photos` is never written (`docs/gold-sentence-edit-reconcile-proposal.md`).

Inserted occurrence metadata:
- `mediaId = scope.mediaId`, `photoId = scope.photoId` (v15 dual-write:
  positive `media_store_id` for `kind == 'photo'`, `NULL` for `video_cues`).
- `modifiedByUserFlag = false` passed into `insertOccurrence` — note that
  `insertOccurrence` (`MiningRepository.kt:2040-2043`) automatically inherits
  `modified_by_user_flag = 1` if any surviving row on `(media_id, sentence)`
  already carries it, and leaves it `0` otherwise (machine reparse never
  fabricates human text authorship — Audit FU1/FU3).
- `isManual = false`.
- `createdAt`: decision **R5** (`scope.existingCreatedAt ?: nowSeconds` to
  preserve chronological position vs `nowSeconds` to surface at the top of the
  inbox).
- `state`: computed automatically by `insertOccurrence`
  (`MiningRepository.kt:2015-2044` — `SEEN` if the canonical item is hidden/archived
  or in a `skip_inbox = 1` folder, else `UNSEEN`).

### 3.4 Plan / Apply Architecture & Lock-Hold Discipline

Grounded in the 2026-11-26 device freeze (`StremioExtractionEngine.kt:158-174`,
`CorpusInsertion.kt:15-25`): cross-app AnkiDroid ContentProvider round-trips,
dictionary lookups, and Kuromoji tokenization must NEVER run inside a SQLite
write transaction.

Three-phase execution on `AppIo.pipeline`:

1. **Phase 1 — Persist user word (immediate commit)**:
   - Call `repo.upsertUserWord(tag, expr, reading, meaning, notes)`.
   - If the entry already has a canonical `extracted_items` row (`AUTO` or
     `USER_DICT`), E5a propagation updates its meaning/kana/kanji immediately.
   - If Phase 2 throws for any reason, the user's dictionary entry is still
     safely saved and `item_occurrences` is completely untouched.
2. **Phase 2 — Read, Analyze & Plan (OUTSIDE any transaction)**:
   - Query matching `SentenceScope`s (§3.2). If empty, return immediately with
     `ReparseSummary(matchedSentences = 0, ...)`.
   - For each scope:
     - Read existing rows via new media-scoped helper
       `repo.occurrenceRowsForMediaSentence(scope.mediaId, scope.sentence)`
       (twin of `occurrenceRowsForSentence`, `MiningQueries.kt:397-417`, using
       `idx_occurrences_media`).
     - Run `CorpusInsertion.analyzeCandidates(config, scope.sentence)` and
       `reconcileOccurrenceRows(existingRows, candidates)`.
     - Retain only scopes where `diff.hasChanges` is true.
   - Build ONE `CorpusInsertion.planInsertions` call across all changed scopes'
     `missingCandidates` with a single shared `CorpusInsertion.InsertionMemo`
     (`CorpusInsertion.kt:22-25, 69-146`).
     - Result: even if `炭治郎` matches 500 sentences across 20 episodes,
       `UserDictionaryProvider` lookup + AnkiDroid IPC runs **once** for
       `炭治郎`, not 500 times!
   - Capture the full `UndoOccRow` snapshots (including `mediaId`) of every row
     in `deleteIds + mergedDuplicateIds` before opening the write transaction.
3. **Phase 3 — Apply & Record Undo (ONE short write transaction)**:
   - Inside `repo.inTransaction { ... }`:
     1. Upsert `plan.items` (`repo.upsertExtractedItem(...)`) and apply
        `plan.links` (`repo.updateExtractedItemAnkiMatch(...)`) — pure SQLite
        writes, zero IPC.
     2. Delete every id in `deleteIds + mergedDuplicateIds` via
        `repo.deleteOccurrenceById(id)`.
     3. Insert every missing candidate per changed scope via
        `repo.insertOccurrence(...)`, collecting all returned `insertedIds`.
     4. Record ONE `UndoAction.USER_WORD_REPARSE` memento atomically inside the
        same transaction (when `deletedRows.isNotEmpty() || insertedIds.isNotEmpty()`).
   - Pure SQLite writes for 500 matched sentences (~1,500 statements) take well
     under 100 ms (reference `dumpsys dbinfo` in `CorpusInsertion.kt:19`: 5,451
     statements summed 366 ms), keeping lock hold negligible while guaranteeing
     all-or-nothing atomicity.

**Hot-path note (`userWordEntries` caching)**:
`UserDictionaryWordAnalyzer.extractWords` (`UserDictionary.kt:39`) invokes its
`userWords()` supplier once per sentence, and `MiningRepository.userWordEntries`
(`MiningRepository.kt:1831-1844`) queries `sqlite_master` + `user_words` every
call. Memoizing `userWordEntries(tag)` in `MiningRepository` behind a simple
volatile cache invalidated in `upsertUserWord`, `deleteUserWord`, and
`restoreUserWordRow` (already anticipated in `user-dictionary-design.md:128`)
eliminates 2×N redundant queries per reparse batch AND speeds up Stremio
episode mining, with zero staleness.

### 3.5 Undo Design (`UndoAction.USER_WORD_REPARSE`)

Grounded in `UndoCore.kt`, `UndoStore.kt`, and `UndoManager.kt`:

- **Live-code gap found & fixed**: `UndoOccRow` (`UndoCore.kt:37-48`) and
  `OCC_COLS` (`UndoStore.kt:86-87`) do not currently carry `media_id`.
  Single-scope `MEDIA_WORDS_EDIT` works around this by passing the single
  `mediaRefId` in `header[0]` (`UndoManager.kt:141-144`), but a corpus-wide
  reparse spans many distinct `media_id`s (both `photo` and `video_cues` where
  `photo_id` is `NULL`).
  - **Backwards-compatible fix**: add `val mediaId: Long? = null` to
    `UndoOccRow`, include `media_id` in `OCC_COLS`, and encode it as an
    optional 12th field on `r` lines in `UndoCodec.encode`/`decode`
    (`UndoCore.kt:160-165, 188-197`). Existing 11-field `r` lines in `undo_log`
    keep decoding with `mediaId = null`. In `undoOccValues(r)`
    (`UndoStore.kt:250-261`), write `media_id` when `r.mediaId != null`.
- **Delta memento shape** (captures ONLY changed rows, not untouched sibling
  occurrences in every matched sentence — ~5–10× smaller on common names while
  restoring byte-identically):
  - `action = UndoAction.USER_WORD_REPARSE` (new enum value; never overload
    existing action headers, per `definition-model-design.md:164-166`).
  - `label`: `再解析を取り消す（%s・%d文）` / `「%s」の文を再解析しました（%d文）`
    (matching the past-tense style of existing `undo_label_*` strings).
  - `header`:
    - `[0]` = `languageTag.name`
    - `[1]` = `expression`
    - `[2]` = comma-separated `insertedIds` (empty string if none)
    - `[3]` = comma-separated distinct touched `mediaIds` (for active-ref guard)
    - `[4]` = `"1"` if the `user_words` row was newly created in this action,
      else `"0"` (used only if **R6(b)** is ruled)
  - `occurrences`: exact `UndoOccRow` snapshots (with original `id` and
    `mediaId`) of ONLY the deleted / merged-duplicate rows.
- **Restore & guard (`UndoManager.kt`)**:
  1. Guard: every `mediaId` in `header[3]` must still be active
     (`repo.mediaRefActive(id)`); none of the deleted `r.id`s in `m.occurrences`
     may already exist. If any guard fails, return `false`
     (`UndoOutcome.GUARD_FAILED`) without partial writes.
  2. Delete every row in `item_occurrences` whose `id` is in `insertedIds`.
  3. Re-insert every `r` in `m.occurrences` via `CONFLICT_REPLACE` preserving
     its exact `id`, `media_id`, `photo_id` (`NULL` when `<= 0`), `state`,
     `created_at`, `modified_by_user_flag`, and `is_manual`.
  4. `extracted_items` is never deleted on undo (existing invariant shared by
     `SENTENCE_WORDS_EDIT`, `MEDIA_WORDS_EDIT`, and `SENTENCE_TEXT_EDIT` —
     0-occurrence items do not appear in the occurrence-grounded UI, and
     preserving the row avoids clobbering `user_notes`/`folder_id`/Anki links).
  5. Whether the `user_words` entry itself stays or is also removed on undo is
     decided by **R6**.

### 3.6 Schema & Backup Contract

- **No schema bump**: `DB_VERSION` remains `16`. No new tables, no new columns.
- `undo_log` stores `action_type TEXT` and `payload TEXT`; adding
  `UndoAction.USER_WORD_REPARSE` and an optional trailing `mediaId` field on
  `r` lines is 100% backwards-compatible with existing bundles and databases.
- `scripts/backup_bundle.py`, `RecoveryManager.kt`, and `docs/recovery.md`
  require zero changes.

## 4. Explicit non-goals

- **No schema migration** (`DB_VERSION` stays 16).
- **No OCR / image rescanning** (`scraped_photos.extracted_text`,
  `ocr_blocks_json`, and `is_gold` are never modified).
- **No fuzzy, kana-normalized, inflected, or bidi-stripping match**: matching is
  exact substring on stored sentence text (legacy pre-fix bidi-polluted cues
  require episode re-mine per `docs/roadmap.md:188-189`).
- **No retroactive reparse on user-word deletion**: preserves the
  `definition-model-design.md` §3.4 DELETION pin (deleting a dictionary entry
  never rewrites existing corpus occurrences).
- **No Chinese user-word wiring**: stays deferred per D7a (code stays
  `LanguageTag`-parameterized).
- **No Anki note rewriting**: existing AnkiDroid cards are never modified.
- **No live-as-you-type match count in the modal**: avoids firing DB full scans
  on every keystroke during Japanese IME composition.

## 5. Alternatives considered

- **Loop `MiningPipelineRunner.reprocessAmendedSentence` over matched sentences**:
  rejected — photo-only (`photo_id`), runs AnkiDroid IPC + dictionary lookups
  inside per-sentence write transactions (re-introduces the 2026-11-26 60s
  lock-hold freeze), and floods `undo_log` (capped at 50 rows, `UndoStore.kt:21`)
  with N separate `SENTENCE_TEXT_EDIT` entries instead of one atomic undo.
- **SQLite `LIKE '%' || ? || '%' ESCAPE '\'` instead of `instr(sentence, ?) > 0`**:
  rejected — SQLite `LIKE` is ASCII case-insensitive and requires wildcard
  escaping; `instr()` is exact, case-sensitive, and wildcard-free.
- **Insert-only (never drop obsolete fragments)**: rejected as default —
  leaves `竈門`/`炭`/`治郎` junk occurrences beside `竈門炭治郎`, which defeats
  most of the value of reparsing; offered as **R4(c)** if the user wants
  ultra-conservative behavior.
- **Full scope-snapshot undo (capturing every untouched sibling row of every
  matched sentence)**: viable for small matches, but for a main character name
  appearing in 200+ sentences (~2,000 rows), delta capture (deleted rows +
  inserted IDs) is ~5–10× smaller in `undo_log` while still restoring
  byte-identically.
- **Separate "反映" button on each list row instead of a modal checkbox**: user
  specifically requested a checkbox on the word creation modal; **R1(a)** also
  makes it available when tapping an existing entry to edit, covering later
  re-runs cleanly without cluttering list rows.
- **Live match preview counter inside the creation dialog**: rejected for v1 —
  triggers repeated table scans while Japanese IME composition edits text; a
  post-save summary Toast gives exact matched + changed counts cleanly.

## 6. Test plan

JVM (all offline, fakes):
- `UserDictionaryReparseTest`:
  - Pure `reconcileOccurrenceRows`: fragment suppression (`竈門`/`炭`/`治郎`
    deleted, `竈門炭治郎` added); strict-substring IGNORE (`累` inside `累計`)
    produces `hasChanges == false`; `is_manual = 1` rows survive even when not
    in `keepKeys`; surviving candidates preserve `SEEN`/`DELETED` states and
    merge duplicate rows keeping highest `stateRank`.
  - Plan/apply integration with `CorpusInsertion.planInsertions` +
    `InsertionMemo`: new user word registers with `DefinitionSource.USER_DICT`,
    and `ankiMatchLookup.findMatch` is called at most ONCE across N matched
    sentences.
- `UndoCodecTest` extensions:
  - `UndoOccRow` with non-null `mediaId` round-trips cleanly.
  - Legacy 11-field `r` lines (pre-change `undo_log` payloads) still decode
    with `mediaId = null` (backwards-compatibility lock).
  - `UndoAction.USER_WORD_REPARSE` encode/decode round-trip.

Instrumented (`UserDictionaryReparseInstrumentedTest`, real SQLite):
- Cross-media scope: reparses matching sentences in BOTH `photo`
  (`status = 'KEEP'`, `photo_id` dual-written) AND `video_cues`
  (`media_refs.active = 1`, `photo_id IS NULL`); ignores `DELETED`/`DUPLICATE`
  photos, retired `media_refs.active = 0` video generations, and all-`DELETED`
  sentences.
- Gold photo safety: `is_gold = 1` photo sentence reparses words without
  modifying `scraped_photos.extracted_text`, `ocr_blocks_json`, or `is_gold`.
- Zero-candidate sentence coverage (if **R3(a)** ruled): 1-word cue/photo line
  with 0 prior occurrences gains the new word; an explicitly `SENTENCE_DELETE`d
  sentence with all-`DELETED` rows does NOT resurrect.
- `USER_WORD_REPARSE` undo round-trip: restores deleted fragment rows with
  their exact original `id`, `media_id`, `photo_id`, and `state`; removes
  inserted occurrence rows; active-ref guard returns `GUARD_FAILED` if a
  touched video ref was retired by a later episode re-mine.
- Idempotency: running reparse a second time for the same entry reports
  0 changed sentences and writes no undo row.

Device walkthrough:
- Add a name that already appears fragmented in a mined photo or Stremio
  episode, check the reparse box, save → verify summary Toast, confirm chips on
  the sentence view and 名簿 gloss + occurrence count on the word detail view,
  tap `元に戻す` on the Snackbar → confirm original fragments return cleanly.

## 7. Change inventory

- `UserDictionaryReparse.kt` (new) — `SentenceScope`, `ReconcileDiff`, pure
  `reconcileOccurrenceRows`, `ReparseSummary`, and `reparseMatchingSentences`
  plan/apply use case.
- `MiningQueries.kt` — `findActiveSentenceScopesContaining(languageTag, expression)`,
  `occurrenceRowsForMediaSentence(mediaId, sentence)`, and (per **R3**)
  zero-candidate `scraped_photos` / `stremio_cues` scope finder.
- `MiningRepository.kt` — generation-invalidated memo cache on
  `userWordEntries(languageTag)` (invalidated in `upsertUserWord`,
  `deleteUserWord`, `restoreUserWordRow`).
- `UndoCore.kt` / `UndoStore.kt` / `UndoManager.kt` —
  `UndoAction.USER_WORD_REPARSE`, optional `mediaId: Long? = null` on
  `UndoOccRow` + `OCC_COLS` + `undoOccValues`, restore branch with active-ref
  guard.
- `UserDictionaryActivity.kt` — `CheckBox` in `showEntryDialog`,
  `AppIo.pipeline` + `MiningPipelineHolder.of(applicationContext)` dispatch for
  checked saves, non-blocking busy indicator, summary Toast + `UndoUi.maybeShow`.
- `res/values/strings.xml` — Japanese checkbox label, busy status text, summary
  toast formats, and undo label.
- `docs/roadmap.md` — add pointer under item 9 once ruled.

## 8. Decision points — RULED 2026-11-27 (R1a/R2a/R3a/R4a/R5a/R6a/R7a)

- **R1 RULED (a):** Checkbox shown on BOTH create and edit modals (unchecked on edit).
- **R2 RULED (a):** Default checked on creation modal.
- **R3 RULED (a):** Scan `item_occurrences` PLUS zero-candidate sentences in
  `scraped_photos` (`KEEP`) and active `stremio_cues` (still skipping
  `SENTENCE_DELETE`d sentences).
- **R4 RULED (a):** Full candidate reconciliation matching `reprocessAmendedSentence`
  (drop obsolete non-manual fragments; keep `is_manual = 1` and triage on surviving
  candidates).
- **R5 RULED (a):** Preserve matched sentence's existing `MAX(created_at)`
  (fallback `now` for zero-candidate sentences).
- **R6 RULED (a):** `USER_WORD_REPARSE` undo reverts corpus occurrence changes
  only; `user_words` entry stays saved in ユーザー事典.
- **R7 RULED (a):** No extra confirmation dialog; runs asynchronously on
  `AppIo.pipeline`, one-tap reversible via `元に戻す`.
