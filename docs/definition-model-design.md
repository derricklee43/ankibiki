# Definition model: merging the user definition with the dictionary entry

**STATUS: RULED 2026-11-26 — E1a/E2a/E3a/E4a/E5a/E6a/E7a ("this is a good
idea"). Awaiting the implementation go.** Written 2026-11-26 in response to the
D4 deferral on [user-dictionary-design.md](user-dictionary-design.md) (user: "I
might want to rework this section altogether. Write me a design on merging user
entered def and default dictionary, plus keep the notes section."). This doc
REPLACES the §3.3 precedence question of that design; the rest of it (D1a/D2a/
D3a/D5a/D6a/D7a/D8a ruled 2026-11-26) stands. Both designs share the ONE v16
migration ceremony (E3a).

## 1. The problem, in live code

Today a word's "definition" is THREE parallel things, none of them authoritative:

1. `extracted_items.meaning` — the system definition. Written at extraction by the
   provider heuristic (CorpusInsertion.ensureCanonicalItemExists / planInsertions via
   BundledDictionaryProvider.lookupJapaneseWord, BundledDictionaryProvider.kt:40-70,
   first-match over is_common-ordered candidates). The single read-side truth for
   the word view 意味 row (WordGrammarDetailActivity.kt:480-492), the card wizard
   (CardWizard.kt:258), and `ExportField.MEANING` (ExportVocabulary.kt:131).
   Survives re-mine via the v13 pick re-resolve (MiningRepository.upsertExtractedItem
   :1427-1446).
2. `extracted_items.user_definition` (v11) — free text the user types into its own
   「定義」 row (WordGrammarDetailActivity.kt:177-181, renderUserRows :497-501). It
   DISPLAYS beside — never replaces — the system meaning, and exports as its own
   field (`ExportField.USER_DEFINITION`, ExportVocabulary.kt:133). The v13 proposal
   named the flaw itself: "the wrong system meaning still exists underneath and
   still exports if mapped" (docs/item-meaning-reselect-proposal.md §1).
3. The v13 pick (`picked_headword`/`picked_reading`) — chooses among DICTIONARY
   candidates only; it cannot express "the dictionary is wrong, here is my
   definition", which is exactly the case for names (user_words entries) and for
   every 儂-class mis-pick.

Consequences the merge must resolve:
- For a user-dictionary name (user_words), `meaning` arrives from the user's own
  global entry while `user_definition` is a SECOND user voice — two texts, both
  user-authored, both exporting, neither winning.
- The reveal ceremony (意味/表示, WordGrammarDetailActivity.kt:481-492, 2026-11-16
  batch) was designed to hide DICTIONARY spoilers; applied to text the user typed
  themselves it hides their own words from them.
- v13 rejected read-time coalescing ("forks meaning into two sources of truth;
  every consumer grows a coalesce", docs/item-meaning-reselect-proposal.md §4) —
  and the current dual-column reality is precisely that fork, just moved to v11.

## 2. Desired behavior (user spec, 2026-11-26)

1. ONE definition per item — the user's entered text and the dictionary's value are
   the SAME field, not two rows that disagree.
2. The dictionary stays the supplier-by-default: mined words get their system
   definition automatically; the candidate picker (変更) keeps working.
3. The user can overwrite the definition with their own text at any time, and that
   text then BECOMES the definition everywhere (word view, wizard, export, preview).
4. Reverting back to the dictionary view must be possible (you never lose the
   system value you replaced).
5. The notes section stays exactly as it is (separate row, untouched semantics).
6. For names registered in the user dictionary (user_words), the entry's meaning IS
   the definition — no third voice, no priority puzzle.

## 3. Design — write-through merge, v13 precedent

### 3.1 One field, four sources

`meaning` remains the single read-side truth (zero consumer changes — the v13
design rule, kept). A new provenance column records WHY it holds what it holds:

```
-- extracted_items additions (schema bump, see §3.5)
definition_source TEXT NOT NULL DEFAULT 'AUTO'   -- AUTO | PICK | USER | USER_DICT
system_meaning    TEXT                           -- last non-USER dictionary/pick value,
                                                  -- the revert target
```

Ladder (write-time, not read-time coalesce):

| source      | meaning holds            | meaning changes when…                          |
|-------------|--------------------------|------------------------------------------------|
| AUTO        | provider first-match     | re-mine re-resolves (today's behavior)         |
| PICK        | picked entry's gloss     | re-mine re-resolves the pick key (v13 today)   |
| USER_DICT   | user_words.meaning       | the user edits their global entry (§3.4, E5)   |
| USER        | hand-typed per-item text | never automatically; explicit edit only        |

`USER` is the top authority: `upsertExtractedItem`'s carry block
(MiningRepository.kt:1456-1465) gains — when `definition_source = 'USER'`, the
caller's fresh dictionary result is dropped for the definition fields
(`meaning`/`level_badge`/`corpus_rank` carry from the stored row; kanji/kana keep
their existing sanitization rules), exactly parallel to how pickKey carry works
today (:1427-1446). `AUTO` and `PICK` behave byte-for-byte as today. The
pick-key re-resolve sets `definition_source='PICK'` when a pick exists, `'AUTO'`
when not.

### 3.2 user_definition becomes a mirror, not a voice

The pencil on the merged definition row writes BOTH `meaning := new text` and
`user_definition := new text`, flips `definition_source := 'USER'`, and preserves
the replaced value into `system_meaning` (only if not already USER — first replace
wins the revert target). Blank save clears USER: restores `meaning :=
system_meaning` when present (source := its implied value again — no stamp kept
for it beyond PICK-vs-AUTO detectable from pick columns; else AUTO) and clears
`user_definition`.

Why keep the column at all (E2): `ExportField.USER_DEFINITION` labels are
PERSISTED in `card_templates` (memory rule: add labels, never rename; the
DraftAdapterContractTest list re-pins deliberately). Old user templates may map
`user_definition`; a dropped write target would silently export blanks. The
mirror keeps every old template working while the UI stops showing two voices.
New templates get `MEANING` = the merged value, so the wizard path
(CardWizard.kt:258 → `ExportField.MEANING to draft.meaning`,
ExportVocabulary.kt:131) needs NO change — it already reads `meaning`.

### 3.3 Word-view UI (WordGrammarDetailActivity)

- The 「意味」 row becomes the merged definition row: text + a small source chip
  (辞書 / 選択 / ユーザー / 名簿) + existing 変更 picker button + pencil editor.
- The separate 「定義」 row is REMOVED from the layout (data stays, mirrored). The
  「ノート」 row is UNTOUCHED (§2.5, D6-style: notes keep their own pencil + row).
- Reveal ceremony follows the SOURCE, not the row: USER/USER_DICT text renders
  directly (it is the user's own — no spoiler to hide); AUTO/PICK keep the 表示
  button and the "—" empty rule (WordGrammarDetailActivity.kt:481-492 becomes
  `if (source is user-authored || blank) render else reveal-gate`).
- 変更 picker: unchanged candidate list, but its result now stamps
  `definition_source='PICK'` — and if the item was USER-edited, picking a
  dictionary candidate REPLACES the user text (with confirm: "ユーザー定義を
  辞書の選択に置き換えますか？") and clears user_definition mirror + restores
  `system_meaning := picked gloss`. Revert affordance: when a USER source row
  has `system_meaning`, the editor dialog gains a 「辞書に戻す」 text button.
- The user-dictionary design's add/edit dialog (user_words) feeds USER_DICT via
  §3.4 — no separate precedence UI anywhere; D4 dies here.

### 3.4 user_words ↔ merged definition (the D4 answer)

`UserDictionaryProvider` (user-dictionary-design §3.3) stays: it supplies
`DictionaryMetadata` for user surfaces at registration time, so a first-time
extract of a sentence containing 竈門炭治郎 registers the item with
`meaning = user_words.meaning`, `definition_source='USER_DICT'`.
Propagation on EDIT of a user_words entry (E5a): re-resolve existing USER_DICT
rows immediately (one UPDATE ... WHERE definition_source='USER_DICT' AND
expression IN changed set; USER and PICK rows never touched).
DELETION of a user_words entry (pin 2026-11-26): never rewrites occurrence-
grounded rows — USER_DICT items keep their text (source unchanged) until the
NEXT extraction of a sentence containing them, which then resolves as if the
word were never registered (AUTO/pick rules); an undone delete (USER_WORD_DELETE
undo) leaves every row already carrying the gloss, so undo is total either way.
Re-mine of a USER_DICT row re-pulls user_words.meaning (live re-resolve, like
pick-key re-resolve). USER (per-item) always beats USER_DICT (global).

### 3.5 Storage / migration / undo / bundle ceremony

- Schema: two nullable-plus-default columns on extracted_items via
  `addColumnIfMissing` guards (schema-versioning rules 5+7+8: defensive ALTER;
  every reader of the new columns' hot paths degrades — missing source column
  reads as 'AUTO' semantics via the DEFAULT, and partial harness DBs materialize
  on first open). ONE bump covering BOTH user_words and these columns (E3:
  v16 = user dictionary + definition model together; the two designs ship as one
  migration ceremony, cheaper and each stays reviewable).
- Reader/writer sync (rule 7): `upsertExtractedItem`'s existing-row SELECT
  (:1412-1413) EXTENDS to carry `definition_source, system_meaning` across
  CONFLICT_REPLACE — omitting them from the REPLACE insert would silently reset
  USER definitions on every re-mine, the exact hazard the v11 carry comment
  documents; `pickItemDefinition`, the editor write path, and `setUserField`
  (MiningRepository.kt:650 region) become the only source-flipping writers.
- Undo: new `UndoAction.ITEM_DEFINITION_EDIT` (do NOT overload the
  ITEM_MEANING_PICK header contract — old log rows keep their 11-field shape and
  decode; new action header carries prior `meaning, kanji, kana, level_badge,
  corpus_rank, picked_headword, picked_reading, definition_source,
  system_meaning, user_definition, user_notes-untouched marker or empty`). The
  v13 ITEM_MEANING_PICK branch (UndoManager.kt:76-87 region) gains a sibling;
  the picker (変更) keeps its existing action.
- Bundle: new columns stay OUT of MINING_COLUMNS/REQUIRED_COLUMNS (rule 3);
  SUPPORTED_USER_VERSIONS gains the new version; docs/recovery.md prose; every
  instrumented `user_version` pin re-pins in the same commit (rule 4 tripwire).
- LEGACY NORMALIZATION (pin 2026-11-26, found in the "vs what I have now" review):
  collapsing the 意味/定義 rows would otherwise SILENTLY HIDE existing
  user_definition text (the merged row reads `meaning`). The v16 migration
  therefore performs the editor's operation once per non-empty row —
  `meaning := user_definition`, `definition_source := 'USER'`,
  `system_meaning := <old meaning>` (the user_definition column keeps its value
  as the export mirror) — so every pre-merge user definition survives visibly.
  Rows where the two columns already agree, and rows with NULL user_definition,
  are untouched; a same-transaction count (rows normalized) lands in the
  migration log line for the demo evidence. Undo does not wrap migrations
  (restore-from-bundle is the escape hatch), which the pin asserts cannot lose
  data: every written column value is preserved in place.

### 3.6 Explicit non-goals

- Per-occurrence senses (spike 005, parked) — untouched, consent unspent.
- Editing DICTIONARY data itself (no custom JMdict rows) — user_words is the
  place for user-owned vocabulary; this doc merges the per-ITEM definition voice.
- Notes: no merge, no move; `user_notes` keeps its own row/editor/field exactly
  as shipped in v11 (§2.5).
- Anki-side note fields: existing cards are never rewritten (v13 §3.3 precedent);
  the wizard picks up the merged definition on future runs.
- Chinese-specific handling: mechanism is tag-agnostic; zh dictionary merge
  behavior follows whatever zh user_words wiring E7/D7 decides (still deferred).

## 4. Alternatives considered

- **Read-time coalesce (`user_definition ?: meaning`)** — rejected: v13 already
  named the disease (every consumer grows a coalesce; two truths).
- **Drop user_definition entirely + new label** — rejected: persisted
  card_templates labels break old decks; the mirror costs one put() per edit.
- **Move meaning INTO user_definition as the field** — rejected: `meaning` is
  read by wizard/export/search/preview today (CardWizard.kt:258,
  ExportVocabulary.kt:131); renaming the truth forks consumers, worse than the
  mirror.
- **Per-item pick among user_words + dictionary candidates in one picker** —
  tempting, rejected for v1: candidates come from the bundled dictionary; the
  user's global entry already wins at registration via the provider, and USER
  text covers the rest. Revisit if real mixed-list pain appears.
- **Three separate rows kept with an explicit priority UI** — rejected: that IS
  today's problem the user asked to merge away.

## 5. Test plan

JVM:
- `DefinitionSourceLadderTest` — pure ladder resolution (given stored row + fresh
  provider result + action → resulting meaning/source/system_meaning); USER never
  auto-overwritten; USER_DICT re-resolves from entry; PICK re-resolve regression
  (existing v13 behavior byte-identical with source columns empty/absent).
- `UserDictionaryAnalyzerTest` interplay case from user-dictionary-design §6 gains:
  registered name → source USER_DICT; per-item edit → USER wins and survives.
- `DraftAdapterContractTest` — label list RE-PINNED deliberately in the same
  commit (USER_DEFINITION still resolves via the mirror); old JSON drafts (no
  source fields) parse via opt* reads.
- UndoCodec round-trip for `ITEM_DEFINITION_EDIT` (extend UndoCodecTest).
Instrumented (emulator backlog batch):
- v16 migration: pre-bump DB → columns present, defaults 'AUTO', user_definition
  rows untouched; LEGACY NORMALIZATION spec — a seeded row with
  user_definition != meaning emerges with meaning := user_definition,
  definition_source='USER', system_meaning := old gloss, and the mirror kept;
  NULL/agreeing rows byte-identical; bundle restore of a v15 backup lands the
  same normalization on first open.
- upsert carry: re-mine over a USER-definition row leaves meaning/user_definition/
  system_meaning intact (the CONFLICT_REPLACE hazard spec, rule 7).
- USER_DICT propagate-on-edit (E5a): edit user_words.meaning → USER_DICT row
  updates, USER row does not. Plus the §3.4 DELETION pin: deleting a user_words
  entry leaves USER_DICT row text intact; next extraction reverts it to AUTO.
- word-view render: USER row shows text without 表示 gate; chip labels; 辞書に戻す.

Device walkthrough: mine an episode, edit one definition, re-mine, verify the
definition survives + revert works; old card template with user_definition
mapping exports the merged text.

## 6. Change inventory

- `MiningRepository.kt` — addColumnIfMissing x2, DB_VERSION bump, upsert SELECT +
  carry + USER/AUTO branch in the pickKey region, `setItemDefinition(user text)`
  writer (mirror write + revert target), pickItemDefinition stamps source.
- `UndoCore.kt`/`UndoManager.kt` — ITEM_DEFINITION_EDIT + replay (row-snapshot
  pattern).
- `WordGrammarDetailActivity.kt` + `activity_word_grammar_detail.xml` — merged
  definition row (chip + pencil + 変更 + 辞書に戻す in dialog), 定義 row removal,
  source-aware reveal.
- `BundledDictionaryProvider.kt`/`UserDictionaryProvider.kt` (from the user
  dictionary design) — provider order (user dict first), USER_DICT stamping at
  registration (CorpusInsertion unchanged — stamps come from provider metadata
  plumbing: DictionaryMetadata gains `source` defaulting AUTO; the two plan/apply
  writers pass it through).
- `ExportVocabulary.kt`/`CardWizard.kt` — no field changes (MEANING already reads
  meaning; USER_DEFINITION keeps reading its mirrored column).
- `scripts/backup_bundle.py` + `docs/recovery.md` — version gates/prose.
- `docs/roadmap.md` — item 10 pointer.
- strings: 辞書 / 選択 / ユーザー / 名簿 chips, 辞書に戻す, confirm copy (Japanese).

## 7. Decision points — RULED 2026-11-26 (user answered 1a/2a/3a/4a/5a/6a/7a,
"this is a good idea"; E4 read as (a))

- **E1 RULED (a):** write-through merge — `meaning` stays the single truth,
  `definition_source` + `system_meaning` record provenance/revert (§3.1).
- **E2 RULED (a):** `user_definition` kept as a write-target MIRROR; old
  card_templates mappings keep exporting (§3.2).
- **E3 RULED (a):** ONE v16 bump shipping user_words + definition columns with
  the user-dictionary design (§3.5).
- **E4 RULED (a):** 変更 allowed over a USER row, behind the confirm dialog
  (§3.3).
- **E5 RULED (a):** user_words EDIT propagates immediately to USER_DICT rows;
  DELETION never rewrites rows (next extraction reverts naturally) — both
  pinned in §3.4.
- **E6 RULED (a):** USER/USER_DICT text renders without the 表示 gate;
  AUTO/PICK keep it (§3.3).
- **E7 RULED (a):** grammar items get the merged editor; the picker degrades to
  the single-entry JLPT catalog (§3.6 non-goals unchanged re: sense pinning).
