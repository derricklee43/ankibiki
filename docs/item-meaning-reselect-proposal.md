# Design Proposal: Re-Selecting the Matched System Definition

Date: 2026-11-23
Status: **DRAFT — awaiting user decisions (§7). No code written; nothing here is approved.**

User motivation (2026-11-23): "I want to reselect the matched system definition."
This is the manual, item-level half of the わし problem recorded in
docs/pending-disambiguation.md §2. It is deliberately NOT spike 005: no automatic
disambiguation, no per-occurrence sense pinning, no runtime dictionary join —
the parked homophone line (docs/roadmap.md, Parked ideas) stays parked and this
proposal does not spend that consent.

## 1. Problem Statement

Every mined word gets exactly one system definition, chosen by a fixed heuristic at
extraction time and stored as plain text:

- Japanese: `BundledDictionaryProvider.lookupJapaneseWord` (`BundledDictionaryProvider.kt:40-70`)
  takes the FIRST candidate of `headword==expression (±readingHint) → headword → reading →
  first`, over `DictionaryRepository.lookupJapaneseWords` (`DictionaryRepository.kt:67-95`,
  `ORDER BY is_common DESC`). For わし the candidates are 和紙 / 鷲 / 儂 — the common-first
  order + first-match makes 和紙 win regardless of which the mined sentence meant.
- Chinese: `lookupChineseWord` (`BundledDictionaryProvider.kt:111-130`) takes
  `matches.firstOrNull()` from `lookupChineseWords` (`DictionaryRepository.kt:105-125`,
  ordered to demote surname/proper-noun readings), so multi-pronunciation words (长
  zhǎng/cháng) keep one arbitrary-but-defensible reading and nothing else.

The winner's full gloss string lands in `extracted_items.meaning`, and every consumer reads
that one column: the word view 意味: row (`getItemDetail`, `MiningQueries.kt:686+`), the card
wizard (`CardWizard.kt:258`, `ExportField.MEANING`), exports, preview. Today the user has
exactly two tools, neither of which is selection:

1. `user_definition` free text (schema v11) — displays ABOVE the system meaning and is
   wizard-mappable, but the wrong system meaning still exists underneath and still exports
   if mapped.
2. Nothing else. There is no UI that says "the correct entry was candidate #2 all along."

## 2. Desired Behavior (User Spec)

1. On the word view, the user can open a picker showing ALL dictionary entries that matched
   the expression (the full candidate list the provider saw, not just the winner), and select
   one as the system definition.
2. The selection persists: display, wizard mapping, and export all show the picked entry.
3. Re-mining / word re-derivation must not silently overwrite the picked entry (same
   carry-protection `user_definition` already has through `upsertExtractedItem`'s
   `CONFLICT_REPLACE`, `MiningRepository.kt:1055-1061`).
4. The choice is reversible back to the automatic pick, and every change is one undo step
   (schema v12 undo framework).
5. A wrong pick costs nothing: candidates are read-only views of the bundled dictionary;
   nothing about the occurrence rows or photo text changes.

## 3. Design

### 3.1 Storage (schema v13)

Two additive columns on `extracted_items`:

```
picked_headword TEXT   -- ja: JMdict headword; zh: simplified
picked_reading  TEXT   -- ja: reading;        zh: pinyin (space-form as stored)
```

WRITE-THROUGH semantics: `meaning` (and `kanji`, `kana`, `level_badge`, `corpus_rank`) stay
the single source of truth for all consumers — the picker copies the chosen entry's fields
into them immediately, and the pick columns record WHY they hold that content. No consumer
change is needed anywhere downstream; this is the same "stored fallback" model the app
already uses (the runtime dictionary JOIN question stays parked, unspent).

Provenance rule in `upsertExtractedItem`: when the pick columns are set, a fresh dictionary
lookup does NOT replace `meaning/kanji/kana/level_badge/corpus_rank`; instead the stored key
is RE-RESOLVED against the current bundled dictionary:

- key still present → refresh those fields from it (picks survive dictionary upgrades
  content-wise and follow corrections);
- key gone (word dropped from a future dictionary) → keep the stored fields verbatim; the
  picker marks the row "(stale pick — not in current dictionary)";
- pick columns NULL → today's behavior, byte-for-byte unchanged.

`markSentencesModifiedByUser` / `modified_by_user_flag` are NOT touched: an item definition
choice is neither text authorship nor photo content — the flag keeps its audited
human-typed-only meaning (docs/roadmap-audit-notes.md).

### 3.2 Picker UX (WordGrammarDetailActivity)

- Affordance: a small 変更 button beside the 意味: label, mirroring the v11 pencil pattern
  on 定義/ノート/フォルダ (`folderEditButton` and friends). Pure-kana and kanji headwords
  both work — candidates come from the same `lookupJapaneseWords(expression)` /
  `lookupChineseWords(expression)` the provider already runs.
- Dialog: one row per candidate entry:
  - Japanese: `headword · reading` + glosses (middle-ellipsized if long) + 常用 badge when
    `is_common`;
  - Chinese: `simplified (traditional) · pinyin` + definitions (ellipsized).
  Row order = the provider's current order (is_common DESC / surname-demote), so the user
  sees exactly the ranking that produced the auto-pick. The auto-picked entry is tagged
  自動; the currently stored pick is checked; row 0 is 「自動選択に戻す」.
- Single-select, apply-on-tap (same gesture economy as the folder picker), plus キャンセル.
- Reveal interplay: opening the picker is deliberate, so the glosses being visible there is
  consented. After a pick, the 意味: row shows the new text directly (the user just read it);
  no re-hide ceremony. The 表示 button and the "—" rule from the 2026-11-16 batch are
  unaffected for never-picked items.

### 3.3 Write path and undo

`MiningRepository.pickItemDefinition(languageTag, itemType, expression,
headword?, reading?)` — null nulls = reset-to-auto:

1. Undo memento `ITEM_MEANING_PICK` (new `UndoAction` value; memento carries the prior
   `meaning, kanji, kana, level_badge, corpus_rank, picked_headword, picked_reading` — the
   cross-view undo framework's row-snapshot pattern, cf. `SET_ITEM_VISIBILITY` at
   `MiningRepository.kt:952`).
2. Inside `inTransaction`: resolve the chosen candidate (the caller passes the exact
   headword+reading the user tapped), UPDATE `extracted_items` fields, run through the same
   `resolveJapaneseLevelAndRank` / HSK helpers so a pick gets the same derived columns an
   auto-match would.
3. Word view re-binds (`onResume` reload already exists from the 2026-11-16 batch).

The pick applies to the ITEM (one expression, one system definition corpus-wide). Occurrence
rows, photos, gold state, and cards already created in AnkiDroid are untouched — the wizard
picks up the new meaning on the next card run (existing drafts keep what they were given;
this is a corpus-side correction, not a retroactive note edit).

### 3.4 Backup contract (v11 ceremony)

`picked_headword` / `picked_reading` join `REQUIRED_COLUMNS` / `MINING_COLUMNS` in
`RecoveryManager` and `scripts/backup_bundle.py` for `extracted_items`. Pre-v13 bundles stay
restorable (migration adds the columns with NULL defaults before restore-target comparison;
same story as v12's `undo_log` era).

### 3.5 Explicit non-goals

- Per-OCCURRENCE sense selection (same word, different sentence, different sense) — that is
  spike 005 territory and stays parked.
- Automatic sense disambiguation, dictionary runtime JOIN, and the "which sense to pin"
  picker inside the mining pipeline — all parked, consent unspent.
- GRAMMAR items: `JlptGrammarAnalyzer.lookupGrammarEntry` (`BundledDictionaryProvider.kt:19`)
  is a single-entry catalog lookup with no candidate list to show. Phase 2 only if real
  duplicate-grammar pain appears.
- Editing gloss TEXT itself (that's what user_definition is for).

## 4. Alternatives considered

- **Separate `meaning_override` read-time column** — rejected: forks `meaning` into two
  sources of truth; every consumer (wizard, export, preview, search adapter) grows a
  coalesce, and the bundle contract must carry both.
- **Reuse `user_definition` for picks** — rejected: different intent (free text vs.
  dictionary entry); the wizard already maps them separately, and the 0-差 display above
  the system meaning would collide.
- **Runtime dictionary join instead of write-through** — rejected for this proposal: it is
  precisely the parked question; this design works entirely on the stored-copy model the
  app already runs.

## 5. Test plan (exit evidence)

`ItemDefinitionPickInstrumentedTest`:
1. pick round-trip: choose candidate #2 → meaning/kanji/kana/level/corpus_rank match its
   entry; pick columns set; getItemDetail + wizard field resolve show the picked gloss.
2. REPLACE-survival: `upsertExtractedItem` with fresh auto metadata on a picked item →
   fields re-resolve from the pick, not the auto winner (mirror of
   `userDefinitionAndNotes_surviveReplaceUpsert`).
3. stale key: pick, then delete the dictionary row from the copy → re-mining keeps stored
   fields untouched.
4. reset-to-auto restores today's heuristic outcome and clears pick columns.
5. undo: `ITEM_MEANING_PICK` memento restores all seven prior columns; one step.
6. Chinese: two pronunciations of the same characters → picking the second updates kana to
   its diacritic pinyin; 自動 returns to the surname-demoted first row.
7. Migration v12→v13: unseeded columns NULL; bundle contract lists updated and old bundles
   still pass `verify`→`restore` (JVM `BundleContractTest` if the seam exists; else extend
   the existing contract test).
8. Live walk: emulator-5554 — わし → pick 鷲 → word view + wizard field preview show 鷲's
   glosses; 自動に戻す; undo. Screenshot + dump evidence into the feature log.

## 6. Change inventory

- `MiningRepository.kt` — schema v13 (2 columns + migration), `pickItemDefinition`,
  `upsertExtractedItem` re-resolve rule, `UndoAction.ITEM_MEANING_PICK` memento recording.
- `UndoCore.kt` — new action + restore branch.
- `WordGrammarDetailActivity.kt` + `activity_word_grammar_detail.xml` — 変更 button +
  candidate dialog.
- `MiningQueries.kt` — candidate list helper (thin wrapper over DictionaryRepository lookups;
  returns entries with their index, not just the winner).
- `RecoveryManager.kt` + `scripts/backup_bundle.py` — contract columns.
- `docs/feature_log.md` + roadmap pointer on ship.

## 7. Decision points (before any code)

1. **Entry-level picks only?** This proposal picks whole dictionary ENTRIES
   (headword+reading). Splitting one entry's gloss list into individual senses ("pin sense
   3 of 4") is a different granularity, needs a sense-index key, and sits closest to the
   parked spike 005. Recommendation: entries first; senses only if they prove necessary.
   Your call.
2. **Re-resolve vs freeze on dictionary upgrades** (§3.1): recommendation re-resolve;
   freeze is the alternative if you want picked text immutable.
3. **Affordance**: 変更 button beside 意味: (recommended, consistent with the pencil rows)
   vs long-press on the 意味 row.
4. **Does a pick belong in the gold story?** Marking gold is the finalizing stamp; a later
   pick edits meaning WITHOUT re-verifying gold status. Options: leave gold alone (pick is
   metadata like user_definition — recommended), or un-gold on pick. Your call.
