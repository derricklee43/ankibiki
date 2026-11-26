# User dictionary — adding your own words (names) so parsing gets them right

**STATUS: RULED 2026-11-26 (D1a/D2a/D3a/D5a/D6a/D7a/D8a; D4 superseded by
[definition-model-design.md](definition-model-design.md)) — implementation gated on
that design's rulings + a go.** Design doc for the feature request of 2026-11-26
(user): "I want to be able to add words to my dictionary. I will primarily use this
feature to add names, so that they get parsed correctly."

## 1. Problem statement (every claim grounded in live code)

How a mined sentence becomes word candidates today, Japanese path:

- One analyzer per sentence: `CorpusInsertion.analyzeCandidates`
  (CorpusInsertion.kt:15-24) calls `config.wordAnalyzer.extractWords(sentence)`
  — `KuromojiWordAnalyzer` for jpn (MiningPipelineRunner.kt:898), built once per
  process behind `MiningPipelineHolder.of()` (MiningPipelineHolder.kt:14-24) and
  shared by every consumer (photo scan, Stremio cue engine
  StremioExtractionEngine.kt:163-165, amend reprocess, wizard).
- `KuromojiWordAnalyzer` tokenizes with a stock IPADIC tokenizer
  (`sharedTokenizer`, KuromojiWordAnalyzer.kt:9,53) and keeps tokens whose
  pos1 ∈ {名詞,動詞,形容詞,副詞,連体詞,接続詞} and pos2 ∉ {非自立,代名詞,数,接尾,特殊,空白}
  (KuromojiWordAnalyzer.kt:14-18,49-63). Known personal/family names
  (固有名詞-人名) therefore DO pass — the gap is names IPADIC doesn't know, which
  is exactly the anime/fandom vocabulary: unknown kanji runs fall into Kuromoji's
  OOV handling and come back split into arbitrary fragments (or with baseForm
  "＊" normalized to the surface, KuromojiWordAnalyzer.kt:22), and a name written
  across a particle boundary never recombines.
- Candidate registration: every candidate's canonical row is resolved by
  `dictionaryProviders` — the list has ONE bundled provider
  (BundledDictionaryProvider, MiningPipelineRunner.kt:900-905) — first non-null
  wins (CorpusInsertion.kt:93-100, plan twin at :97-105 region). A name absent
  from JMdict registers with `meaning = "—"`
  (CorpusInsertion.ensureCanonicalItemExists:99) — or with a WRONG meaning when
  a fragment happens to hit JMdict (竈門 alone resolves as a real word).
- Today's only remedy is per-sentence: the 編集 word editor adds free-form words
  one sentence at a time, `isManual=true`
  (MiningPipelineRunner.saveSentenceWordsForMedia / saveSentenceWords additions)
  — no global effect, and the next sentence containing the same name is still
  mis-parsed. `extracted_items.user_definition` (schema at
  MiningRepository.kt:66; dialog WordGrammarDetailActivity.kt:179) is an
  ANNOTATION on an existing item — it does not create items and never touches
  tokenization.

Consequence: adding 竈門炭治郎 to a personal lexicon must (a) force it to be
extracted as ONE candidate wherever it appears, (b) attach the user's own
reading + definition, and (c) not regress anything the parsers currently get
right.

## 2. Desired behavior (from the user's request)

1. A screen where the user adds a word (primarily a name): expression, reading
   (kana), meaning, optional note — per language tag.
2. Sentences mined AFTER the entry exists (photo or subtitle-cue) extract that
   name as exactly one word candidate with the user's reading.
3. The registered canonical item gets the user's meaning instead of "—" or a
   dictionary fragment meaning.
4. Entries are the user's own data: they must survive backup/restore and never
   be auto-deleted by reprocessing.
5. Editing/removing an entry is possible and safe.
6. (Open: whether adding an entry retroactively fixes ALREADY-mined sentences —
   decision D2.)

## 3. Design

### 3.1 Storage — `user_words` table, schema v16

```sql
CREATE TABLE IF NOT EXISTS user_words (
    language_tag TEXT NOT NULL,          -- 'JAPANESE' (v1); 'CHINESE' supported by shape
    expression   TEXT NOT NULL,          -- the surface as written, e.g. 竈門炭治郎
    reading_kana TEXT,                   -- hiragana, user-typed; NULL = derive/none
    meaning      TEXT NOT NULL DEFAULT '',
    notes        TEXT,
    created_at   INTEGER NOT NULL,
    PRIMARY KEY (language_tag, expression)
)
```

Migration ceremony per references/schema-versioning.md, all in the bump commit:
`DB_VERSION = 15 -> 16` (MiningRepository.kt:1738); `CREATE TABLE IF NOT EXISTS`
is already stub-safe (rule 5/8 — harness partial DBs get the table materialized,
and every hot-path read of it is wrapped so a missing table degrades to "no user
words", never crashes); extend `SUPPORTED_USER_VERSIONS` in
scripts/backup_bundle.py:67; do NOT add `user_words` to `MINING_COLUMNS` /
`RecoveryManager.REQUIRED_COLUMNS` (rule 3 — pre-v16 bundles must keep
validating; the v12 `undo_log` precedent, backup_bundle.py:61-63); update
docs/recovery.md prose; re-pin instrumented version asserts (rule 4).

Bundle: the tool ships the whole sqlite file (backup_bundle.py:49-52 comment —
"auxiliary tables/columns ship and restore regardless of validation scope"), so
`user_words` rides in every bundle with zero contract change. That satisfies §2.4.

### 3.2 Parsing integration — span-forcing decorator, no Kuromoji surgery

`UserDictionaryWordAnalyzer(base: WordAnalyzer, index: () -> UserWordIndex)`
wraps the jpn analyzer at the ONE construction site (MiningPipelineRunner.kt:898).
`UserWordIndex` = per-language immutable snapshot: sorted surfaces + readings.
Algorithm per sentence:

1. `base.extractWords(sentence)` exactly as today (all existing pins hold).
2. For each user surface, `indexOf`-scan the sentence (surfaces are few — names
   in the hundreds; a `String.indexOf` loop per sentence is noise next to one
   MeCab tokenize; if ever hot, a first-char bucket map). Collect match spans.
3. Conflict rules, in order:
   - a match span fully INSIDE one existing token's span (user word is a strict
     substring of a legitimate token, e.g. 花子 inside 花生まれ) → IGNORE the
     user match; existing tokens are never fragmented by user entries;
   - a match span equal to one token span → replace that candidate's
     expression/reading with the user entry (definition effect via §3.3);
   - a match span crossing ≥2 token spans → add the user candidate and DROP
     every candidate whose surface span falls entirely inside the match
     (fragment suppression — the fragments 竈門/炭/治郎 never land when the
     user declared 竈門炭治郎).
4. User matches bypass the POS/stopword filters by construction (the user's
   intent outranks e.g. the 代名詞 exclusion — if someone registers 彼女 as a
   name, it extracts). Partially-overlapping candidate spans are kept
   (conservative: only fully-contained fragments die).

Token spans come from Kuromoji's token positions + surfaces
(com.atilika.kuromoji.ipadic.Token carries `start` and `surface`) — the one
assumption to verify at implementation; if the shipped version lacks positions,
rebuild spans by cursor-walking surfaces (deterministic; spec-pinned either way).

Cache & invalidation: `index` is a supplier reading `user_words` fresh per
sentence-parse — no staleness by design (hundreds of rows, one cheap SELECT;
memoized per batch via a generation counter bumped by the add/delete paths, and
`MiningPipelineHolder` survives it because the supplier closes over the repo,
not a snapshot). The supplier pattern is already precedent: `nameplateLexicon =
{ ... }` in PipelineConfig (MiningPipelineRunner.kt:910-912).

Zh parity hook: the decorator is language-tag agnostic; wiring it onto
`ChineseWordAnalyzer` (MiningPipelineRunner.kt:919) is a one-liner DEFERRED by
D7, not removed.

### 3.3 Definition & registration precedence — prepend a provider, change no engine

`UserDictionaryProvider(repo, tag)` joins `dictionaryProviders` FIRST
(MiningPipelineRunner.kt:900-905, :920-925): lookup returns
`DictionaryMetadata(kanji = expression-if-ideographic, kana = reading_kana,
meaning = meaning, levelBadge = "固" marker or null, corpusRank = null)` when the
expression is a user entry, else null → bundled JMdict lookup proceeds
unchanged. Because the plan/apply split (CorpusInsertion.planInsertions
dictCached loop) and `ensureCanonicalItemExists` already walk the provider list
first-non-null, NOTHING inside CorpusInsertion or the engine changes.

Canonical row semantics that follow for free:
- `upsertExtractedItem` v13 entry-pick preservation (MiningRepository.kt:1335+)
  keeps user picks above machine picks — a user word's meaning is the BASE row
  content, so pick logic is untouched (D4 pins this choice);
- `user_definition` remains the separate freeform annotation it is today —
  never overwritten by provider output;
- Anki card matching, folder `skip_inbox` birth-state rules
  (MiningRepository.insertOccurrence folderSkipsInbox) and is_manual protections
  apply to user-word occurrences exactly as to any other word — same rows, same
  writers;
- the reading feeds romaji search + detail rendering because
  `NormalizedCandidate.readingKana` flows into the registered `kana` column
  (CorpusInsertion.ensureCanonicalItemExists:98).

### 3.4 Cue-text specifics (subtitle corpora, learned this session)

Subtitle lines carry parenthetical furigana (柱(はしら)), speaker annotations
（アオイ）and credits lines. The span rules above already handle them: the kanji
surface matches independently of the parenthetical; a user entry equal to the
parenthesized reading itself matches like any other surface. No special-casing —
but a spec pins both shapes.

### 3.5 Retroactive application (the corpus-wide question)

v1 scope = future extraction only (§3.2 runs wherever sentences are analyzed
henceforth; photo path, cue path, amend-reprocess path — all funnel through
`analyzeCandidates`, so all are covered by the single decorator). Applying a
newly added name to already-stored sentences means a corpus re-derivation pass
LIKE-scanning `item_occurrences.sentence` (+ cue documents), inserting the
missing candidate per matched sentence, never deleting, with an undo memento —
mechanically identical to one arm of `reprocessAmendedSentence`'s
added-sentences branch. It is real value but a second blast radius; D2 decides
v1 vs phase-2 (recommendation: phase-2 button "この単語を出現済みに反映", same
spec discipline).

### 3.6 List/search visibility

User words with no occurrences anywhere are dictionary entries, NOT corpus
items — nothing is written to `extracted_items` at add time (D3 recommendation;
ghost rows would pollute 単語/頻度/inbox counts, which are occurrence-grounded).
Once extracted, the word appears in the 単語 tab like any other, and its detail
screen behaves normally (definition = the user's meaning via §3.3).
Search surface: optionally a `SearchSource.USER_WORDS` source
(SearchSource.kt:4-11 pattern) so the home search finds entries before first
extraction — D8.

### 3.7 UX

- Entry point: hamburger popup row 「ユーザー事典」 exactly like the
  Stremio-console row (MainActivity.kt:406-411 `ItemSettingsActionBinding`
  pattern).
- Screen: list of the user's words (expression + kana + meaning), 追加 button →
  dialog with expression field (kanji/kana), reading field (kana;
  pre-suggest from `DictionaryRepository.lookupJapaneseWords` when JMdict knows
  the surface, blank for invented names — user types), meaning (required for
  names, JMdict miss otherwise → "—"), notes; Japanese labels per repo rule
  (追加/保存/取り消し/削除).
- Delete: confirmation dialog (quotes the entry) + a lightweight undo
  (`UndoAction.USER_WORD_DELETE`, memento = header carrying the whole row →
  re-insert on restore; the row can't "drift" like photos so per-row capture is
  byte-exact). D5 offers confirm-only.
- Edit: same dialog prefilled; editing a reading/meaning re-derives NOTHING on
  its own (D4: the canonical row keeps what it has unless re-mined; the next
  extraction of a sentence uses the NEW entry — same asymmetry as
  picked_headword's re-resolve rule, docs/item-meaning-reselect-proposal.md).

## 4. Explicit non-goals (consents this design does NOT spend)

- Grammar entries: no user GRAMMAR items — `JlptGrammarAnalyzer` stays the
  closed JLPT catalogue (grammar extraction is pattern-list based, Jlpt-lookup in
  BundledDictionaryProvider.kt:19-28; a user grammar item is a different design).
- Chinese user words: table and decorator are tag-ready, wiring DEFERRED (D7).
- OCR / correction-gate lexicon: user words are NOT injected into GateLexicon
  (S003-D' parity-pinned, sha-pinned JVM snapshot
  scripts/export_gate_lexicon.py) — mixing user data into the
  parity-locked correction lexicon would break `OcrCorrectionGateParityTest`
  discipline; stays parked as its own future thread.
- Speaker-nameplate exclusion (roadmap §Parked ideas 2026-11-10 ruling): the
  nameplate_claims ledger is untouched; DEMOTE/DROP ruling stays parked.
- Stremio watch-time / auto-scrub: docs/stremio-auto-scrub-parked.md stays
  parked; nothing here spends it.
- No name-suggestion automation ("detect repeated unknown spans and propose
  entries") — nice later, not consented.

## 5. Alternatives considered

- Compile a real MeCab user dictionary and rebuild KuromoSimpler's model at
  runtime: Kuromoji ships no user-lexicon API; generating binary dictionaries on
  device is heavy, fragile, and needs the IPADIC build toolchain. Rejected.
- Write added names straight into `extracted_items`: breaks the
  occurrence-grounded invariant of the whole corpus UI (§3.6), confuses
  frequencies/cards/undo. Rejected.
- Rely on the per-sentence 編集 free-add: does not scale (§1 last bullet) —
  this is the status quo, rejected by the feature request itself.
- Abuse `nameplate_claims` or `user_definition` as the storage: different
  semantics (OCR plate ledger; per-item annotation), wrong lifecycle. Rejected.
- FTS index over user words for span matching: overkill at hundreds of names;
  indexOf scan is provably cheap and simpler to spec. Rejected (revisit only if
  entries reach tens of thousands).

## 6. Test plan

JVM (all offline, fakes):
- `UserDictionaryAnalyzerTest` — force-span add; fragment suppression (multi-token
  span); strict-substring IGNORE rule; token-equal replace; POS/stopword bypass;
  kana-only name; paren-furigana cue line （アオイ）/柱(はしら) shapes; empty
  index = byte-identical to base output (regression lock).
- `UserDictionaryProviderTest` — provider-first precedence, fallthrough to bundled
  when miss, reading → kana metadata, meaning pass-through.
- `UserWordsStoreTest` (instrumented) — CRUD + v16 migration landing + fresh-db
  user_version re-pin; hot-path degrade when table absent (rule 8 stub shape).
- `USER_WORD_DELETE` undo round-trip (instrumented, extends the UndoLog suite).
Device walkthrough — add a real name, re-mine one episode, verify the chip
count/definition on the sentence view, delete + undo the entry.

## 7. Change inventory

- `MiningRepository.kt` — DB_VERSION 16, `createUserWordsSchema` guarded,
  CRUD helpers (`userWordEntries(tag)`, `upsertUserWord`, `deleteUserWord`),
  v16 migration call; hot-path reads runCatching.
- `UserWordIndex.kt` (new) — snapshot + generation; `UserDictionaryWordAnalyzer.kt`
  (new) — decorator + span rules; JVM specs above.
- `UserDictionaryProvider.kt` (new) — DictionaryProvider impl.
- `MiningPipelineRunner.kt` — wire decorator + provider into the two configs
  (:898-912, :919-925).
- `UndoCore.kt` / `UndoManager.kt` / `UndoStore.kt` — USER_WORD_DELETE action +
  capture/restore.
- `UserDictionaryActivity.kt` (new) + `activity_user_dictionary.xml` + row/d dialog
  layouts + `strings.xml` (Japanese labels); `MainActivity.kt` — hamburger row;
  `AndroidManifest.xml` — activity registration.
- `scripts/backup_bundle.py` — SUPPORTED_USER_VERSIONS {..,16}; `docs/recovery.md`
  prose.
- `docs/roadmap.md` — item 9 pointer (already added with this doc).

## 8. Decision points — RULED 2026-11-26

- **D1 RULED (a):** span-forcing decorator with the three conflict rules (§3.2).
- **D2 RULED (a):** v1 = future extractions only; the "apply to occurrences"
  retroactive button is phase-2 (§3.5).
- **D3 RULED (a):** entries without occurrences live only in ユーザー事典; the
  corpus stays occurrence-grounded (§3.6).
- **D4 RULED — SUPERSEDED by a rework (user: "I am not sure, I am thinking that I
  might want to rework this section altogether. Write me a design on merging user
  entered def and default dictionary, plus keep the notes section.").** The
  precedence question dissolves into the merged single-definition model:
  [definition-model-design.md](definition-model-design.md) — `meaning` becomes the
  one definition with provenance sources (AUTO / PICK / USER_DICT / USER); notes
  keep their existing separate row untouched. This doc's §3.3 provider-first
  registration stands unchanged; its "D4 pins this choice" caveat resolves
  through that design. Implementation of the user dictionary is gated on that
  design's rulings + a go.
- **D5 RULED (a):** confirm dialog + USER_WORD_DELETE undo (§3.7).
- **D6 RULED (a):** reading optional; blank keeps parser behavior (§3.7).
- **D7 RULED (a):** Chinese deferred; schema/decorator stay tag-ready (§3.2).
- **D8 RULED (a):** no home-search source in v1; the list screen is the lookup
  surface (§3.6).
