# Plan: Chinese Word ↔ Hanzi bi-directional sync

## Goal

Add a new "Chinese Word" deck/note type. Keep it linked to the existing Hanzi deck
("🇨🇳 RSH1 Hanzi") automatically:

- Creating a word note (e.g. 你好) auto-creates any of its component hanzi that don't already
  exist as cards, and fills in a breakdown field on the word showing each component + meaning.
- Each hanzi note gains a "used in these words" list that stays up to date as word notes are
  added.
- Hanzi notes also gain a recursively-decomposable "Constituents" field (radical/component
  tree), rendered as an expandable tree on the card itself.
- The whole thing (after one-time setup) is re-run from a button in the app.

This is a planning document — nothing here is implemented yet. Section 9 is the intended build
order. Section 10 records the decisions made on the first draft's open questions.

## 1. Current state (recap)

- `AnkiRepository` reads notes from AnkiDroid via `FlashCardsContract.Note.CONTENT_URI_V2`
  (parameterized SQL, read-focused).
- `KnownNoteTypes.kt` hardcodes model IDs and **positional** field indices for the two note
  types the app knows about (`CHARACTER_FIELD_INDEX`, `MEANING_FIELD_INDEX`), and
  `CardAdapters.kt` hardcodes a couple more (`MNEMONIC_FIELD`, `PINYIN_FIELD` for the Chinese
  note type). Every one of these breaks if a field gets inserted or reordered.
- The app already depends on `com.github.ankidroid:Anki-Android:api-v1.1.0`, which ships
  `com.ichi2.anki.api.AddContentApi` — a write-capable SDK class. `AnkiRepository` already uses
  one method from it (`getAnkiDroidPackageName`) but nothing else yet.
- `dictionary.db` (bundled asset) already has CC-CEDICT, so per-character/per-word Chinese
  meanings and pinyin are already available offline, no new network dependency needed for that
  part.
- `audio.db` (bundled asset, gitignored) has per-character pronunciation audio pulled from Anki
  Desktop. No word-level audio pipeline exists.

## 2. What AnkiDroid's API can and can't do (checked against the actual SDK sources)

I pulled `Anki-Android-api-v1.1.0-sources.jar` from the local Gradle cache and read
`AddContentApi.java` directly rather than assume. Relevant methods:

| Method | Does |
|---|---|
| `addNote(modelId, deckId, fields, tags)` | Create one note |
| `addNotes(modelId, deckId, fieldsList, tagsList)` | Batch create |
| `updateNoteFields(noteId, fields)` | Overwrite all fields of an existing note |
| `updateNoteTags(noteId, tags)` | Overwrite tags |
| `getNote(noteId)` | Read one note back (id/fields/tags) |
| `findDuplicateNotes(modelId, key)` | Finds notes whose **first field** equals `key` |
| `addNewCustomModel(name, fields[], cards[], qfmt[], afmt[], css, did, sortf)` | Create a **brand-new** note type, with templates |
| `addNewDeck(deckName)` | Create a deck |
| `getFieldList(modelId)` / `getModelList()` / `getDeckList()` | Introspect what exists |

Important constraint: **there is no "add a field to an existing note type" or "edit an existing
template" method on this API.** `addNewCustomModel` only creates new models. This is a real
limit for anything running *on the phone* against AnkiDroid — but per decision 10.2, all
one-time structural setup (new note type, new deck, new fields on the existing Hanzi note type,
template edits) happens on the desktop side instead, via **AnkiConnect** (the Anki Desktop
add-on that exposes a local HTTP API, default `http://127.0.0.1:8765`), which has no such
restriction — it operates through Anki's own Python internals, so it can add fields to and edit
templates of *existing* note types directly. Relevant AnkiConnect actions (verify exact
names/params against the live docs at implementation time — I don't have local source for this
one the way I do for AddContentApi):

| Action | Does |
|---|---|
| `createModel` | Create a new note type with fields + templates + CSS |
| `modelFieldAdd` | Add a field to an existing note type |
| `updateModelTemplates` / `updateModelStyling` | Edit an existing note type's card HTML/CSS |
| `createDeck` | Create a deck |
| `modelFieldNames` / `modelNames` / `deckNames` | Introspect what exists |

So Section 9 Phase 1 (structural setup) is a Python script using AnkiConnect, not a manual
GUI step and not on-device. The **repeating sync job** (Section 6) is unaffected by this — it
still runs on the phone against AnkiDroid via `AddContentApi`/`FlashCardsContract`, since it
only ever creates/updates *notes*, never note types or fields, which `AddContentApi` already
supports fine.

Also: `findDuplicateNotes` only matches a note type's **first field**. For the Hanzi note type,
field 0 is `Meaning`, not the character — so it's useless for "does a hanzi note already exist
for 好". The sync will instead do its own full scan of the Hanzi deck into an in-memory
`character -> NoteInfo` map once per run, same pattern `AnkiRepository` already uses for reads.

## 3. Data model changes

### 3.1 New note type: "Chinese Word"

Proposed fields, **in this order** (order matters — see 3.3):

| # | Field | Filled by |
|---|---|---|
| 0 | Word | you, manually (this is the note's identity/sort field) |
| 1 | Translation | you, or auto-filled from CC-CEDICT if blank when synced |
| 2 | Pinyin | you, or auto-filled from CC-CEDICT if blank when synced |
| 3 | Audio | you, manually (optional; `[sound:...]`; word-level audio sourcing is a separate, later project — see 10.5) |
| 4 | HanziBreakdown | **sync-owned** — regenerated every run, don't hand-edit |

Tags (not fields): native Anki tags, `HSK::3`, `Freq::1200`-style — see 10.3.

New deck: **"🇨🇳 Chinese Words"** (sibling to "🇨🇳 RSH1 Hanzi").

Card template: front = Word (+ autoplay Audio if present), back = Translation, Pinyin,
HanziBreakdown.

### 3.2 Hanzi note type ("🇨🇳 RSH1 Hanzi"): two new fields, **appended at the end**

Current known layout: `0=Meaning, 1=?, 2=Character, 3=Mnemonic, 4=Pinyin, ...` (there may be
more fields past 4 that the app doesn't currently read — check `getFieldList(modelId)` before
touching anything, don't assume 4 is the last one).

New fields to append **after the last existing field**, never in the middle:

- `Constituents` — JSON decomposition tree (see Section 4), **sync-owned**.
- `UsedInWords` — newline-separated list of words this character appears in, **sync-owned**.

Appending-only is load-bearing: `CHARACTER_FIELD_INDEX`, `MEANING_FIELD_INDEX`, and
`ChineseCardAdapter`'s `MNEMONIC_FIELD`/`PINYIN_FIELD` constants are positional. Insert a field
before index 4 and pinyin silently starts showing up as the mnemonic in the app.

### 3.3 Non-destructive sync rules

Since this writes into real study data, the sync must never touch anything you hand-author:

- Never overwrite `Mnemonic` on a Hanzi note.
- Only fill `Translation`/`Pinyin` on a Word note if they're currently blank — never clobber
  something you already typed in, even if it disagrees with CC-CEDICT.
- `HanziBreakdown`, `Constituents`, and `UsedInWords` are the only fields the sync freely
  overwrites every run (documented above as "sync-owned").
- Re-running the sync with no new notes should be a no-op (idempotent) — no duplicate notes, no
  spurious field-update writes (skip the `updateNoteFields` call if the computed value already
  matches).

## 4. Character decomposition data (the "Constituents" tree)

Source: **cjkvi-ids** (github.com/cjkvi/cjkvi-ids), the same dataset the CHISE project uses. It
maps each character to an Ideographic Description Sequence, e.g. `好 -> ⿰女子` (left-right
composition of 女 and 子). License needs a quick check at implementation time the same way
KANJIDIC2/JMdict/CC-CEDICT's licenses were checked before bundling — expect CC0/permissive
based on the project's stated terms, but verify rather than assume.

`scripts/build_ids_db.py` (done — see Section 9 step 3 for the implementation notes) downloads
the raw IDS table, recursively resolves each character's components into a nested tree (a
character reference gets re-expanded through the table again; a sub-grouping with no character
of its own just keeps its already-inlined structure; depth-capped and cycle-guarded), and
stores the resulting JSON blob per character in its own `ids.db` (not `dictionary.db` — see 9.3
for why). Example shape:

```json
{
  "char": "好", "op": "⿰",
  "children": [
    { "char": "女", "op": null, "children": [] },
    { "char": "子", "op": null, "children": [] }
  ]
}
```

The sync writes this JSON straight into the Hanzi note's `Constituents` field when it creates
(or refreshes) a note.

## 5. Card template: rendering the tree

Anki card templates are static HTML/CSS per review, but AnkiDroid's reviewer renders them in a
WebView with JS enabled, so a small inline `<script>` in the Hanzi template can parse the
`Constituents` JSON and recursively render nested `<ul>/<li>` with click-to-expand children —
this is a template-only change (no app code involved), set via AnkiConnect's
`updateModelTemplates`/`updateModelStyling` in the same Phase-1 script that adds the field
(Section 9). I'll draft the actual HTML/CSS/JS when we get to that phase rather than freeze it
here.

## 6. Sync algorithm

Runs entirely from the phone against AnkiDroid, using `AddContentApi` for writes and the
existing `FlashCardsContract` query path for reads (same as `AnkiRepository` today).

```
1. Load every Word note (Chinese Word model) and every Hanzi note (existing model) into memory.
   Build hanziByChar: Map<Character, NoteInfo> from the Hanzi notes.

2. For each Word note:
   a. Split its Word field into individual Han characters (skip punctuation/non-Han).
   b. For each character:
        - If missing from hanziByChar:
            meaning  = DictionaryRepository lookup (CC-CEDICT, already bundled)
            tree     = ids table lookup -> JSON
            create the note via addNote(hanziModelId, hanziDeckId, fields, tags=emptySet())
              with Mnemonic left blank
            add it to hanziByChar
        - Append this word to that hanzi note's UsedInWords if not already present
          -> stage an updateNoteFields call (batched/deduped, not one write per character)
   c. Recompute HanziBreakdown from the resolved hanzi notes' Character+Meaning.
   d. If Translation/Pinyin are blank, fill from CC-CEDICT.
   e. If any field actually changed, updateNoteFields the Word note.

3. Report: N hanzi created, M word notes updated, K hanzi notes updated, done.
```

Runs on a background thread (this can touch a lot of notes; must not block the UI thread) with
a simple progress/result callback back to the button's UI state.

## 7. App integration

- New icon button next to the existing hamburger settings button in the header row (not inside
  the settings popup — that popup's whole design is "one row per toggleable `SearchSource`",
  this doesn't fit that shape).
- Tapping it runs the sync on a background thread, button shows a spinner/disabled state while
  running, then a toast or small status line with the result counts from Section 6 step 3.
- No settings/config for this in v1 — it just syncs everything, every time.

## 8. New/changed files (implementation phase, not yet done)

- `scripts/setup_chinese_word_deck.py` — new (Phase 1, one-time, AnkiConnect)
- `scripts/build_ids_db.py` — new, builds `app/src/main/assets/ids.db` (gitignored, not committed)
- `KnownNoteTypes.kt` — add `CHINESE_WORD_MODEL_ID` once it exists, plus field-index constants
  for the new note type and the two appended Hanzi fields
- `Word.kt` / `WordAdapter.kt` (naming TBD) — new schema for Word notes, parallel to but
  separate from `Card`/`CardAdapters.kt` (see 10.4) — a word doesn't have a "mnemonic" the way
  a hanzi character does, so it doesn't fit the existing `Card` shape
- `HanziWordSyncService.kt` — new, implements Section 6
- `MainActivity.kt` / `activity_main.xml` — new button + wiring

## 9. Build order

1. ✅ **DONE** — `scripts/setup_chinese_word_deck.py`, run against the live collection via
   AnkiConnect. Verified afterward (not just trusted): `RSH`'s field list now ends
   `..., "JP Constituent", "Constituents", "UsedInWords"` (appended, existing 0-11 untouched),
   the `Chinese Word` model has exactly `[Word, Translation, Pinyin, Audio, HanziBreakdown]`,
   and deck `🇨🇳 Chinese Words` exists. Re-ran the script a second time and every step reported
   `[skip]` — confirmed idempotent. The tree-render JS itself is only reviewed by eye so far
   (no JS runtime available here to execute it, and didn't want to plant a throwaway note with
   fake `Constituents` JSON in the real Hanzi deck just to preview it) — first real visual
   check happens naturally in step 6 once `Constituents` has real data.
   **Still need to do:** sync Anki Desktop ↔ AnkiDroid so the phone's collection picks up the
   new fields/model/deck before step 2 below.
2. ✅ **DONE** — confirmed on-device via a temporary debug hook in `MainActivity` (added,
   triggered once via `adb shell am start --ez dumpSchema true`, logcat captured, then fully
   reverted -- same throwaway-hook pattern used earlier this project). Chinese Word model id
   `1786984418063` with fields exactly `[Word, Translation, Pinyin, Audio, HanziBreakdown]`;
   Hanzi model's full field list now ends `..., JP Constituent, Constituents, UsedInWords`
   (indices 12/13, matching Phase 1); deck ids `🇨🇳 Chinese Words` = `1786984418128`,
   `🇨🇳 RSH1 Hanzi` = `1786665356369`. All hardcoded into `KnownNoteTypes.kt`.
3. ✅ **DONE** — `scripts/build_ids_db.py`. One correction from the original Section 4 sketch:
   CHISE's `ids.txt` already inlines further structure for sub-groupings that aren't
   themselves an encoded character (e.g. 亭 = ⿱⿳亠口冖丁 -- the top part has no character of
   its own, so it's spelled out in place), but *does* just reference a real character where
   one exists (e.g. 谢 = ⿰讠射, not ⿰讠⿰身寸) -- so building the tree needs a proper
   recursive-descent parse of each IDC's arity (2- or 3-way) *plus* re-expanding every leaf
   character through the table again, not just one flat parse. Verified against 好, 你, 谢,
   亭 by hand (谢 correctly expands 射 into 身+寸 via the second lookup). Also discovered the
   license for this one file is murkier than KANJIDIC2/JMdict/CC-CEDICT (its own README defers
   to CHISE's terms, which aren't published anywhere I could find) -- so `ids.db` gets the same
   gitignored, not-committed treatment as `audio.db`, unlike `dictionary.db`. Filtered the
   ~89k-character source down to CJK Unified Ideographs + Extension A + the radical/stroke
   blocks (27,577 characters, 11.1MB) -- the rest is Unicode planes no Chinese word/hanzi study
   deck will ever reference.
4. ✅ **DONE** — `HanziWordSyncService`. Also needed: exposing `AnkiRepository.queryAllNotes`/
   `createNote`/`updateNoteFields` (the repository only had search-scoped reads before),
   `DictionaryRepository.lookupChineseWords` made non-private, `isCjkCharacter` (previously
   private to `SearchService`) shared, and `HANZI_FIELD_COUNT`/`HANZI_PINYIN_FIELD_INDEX` added
   to `KnownNoteTypes.kt` (the latter also de-duplicates `ChineseCardAdapter`'s private copy of
   the same constant). `Word`/`NoteResult.toWord()` per decision 4 -- deliberately no
   `CardAdapter`-style registry since there's only one note type to map, that'd be premature.
5. ✅ **DONE** — sync icon button next to the hamburger, runs on a plain background `Thread`
   (no coroutines dependency in this codebase) with `runOnUiThread` back for the
   enable/disable + result Toast, matching the existing `Handler`-based patterns already used
   for search debouncing.
6. ✅ **DONE** — real end-to-end test against the live collection, not a simulation. Created a
   genuine Word note (悲伤/"sad", via AnkiConnect) with one existing component (伤) and one
   missing (悲), synced Anki Desktop -> AnkiDroid, tapped the button on-device, and read the
   actual resulting note fields back (via the same temporary-debug-hook-then-revert pattern as
   Phase 2). Result matched exactly: 悲 auto-created with correct Meaning/Pinyin from
   CC-CEDICT, Mnemonic left blank, `Constituents` = `{"char":"悲","op":"⿱","children":[{"char":
   "非",...},{"char":"心",...}]}` (linguistically correct -- 悲 really is 非 over 心), and
   `UsedInWords` = "悲伤" on *both* the new 悲 note and the pre-existing 伤 note. The word note
   got `Translation`/`Pinyin` auto-filled and `HanziBreakdown` = "悲 (sad; sadness; sorrow;
   grief), 伤 (wound)", `Audio` left untouched. Re-ran the sync a second time: toast reported
   `0 hanzi created, 0 words updated, 0 hanzi updated` -- confirmed idempotent. Kept the test
   note/card in the real collection (both are legitimate, correctly-filled-in cards) rather
   than deleting them.

   Not yet checked: what the `Constituents` tree actually looks like *rendered* (expand/collapse
   JS) on an actual review screen -- 悲's card hasn't been reviewed yet. Worth a look next time
   it comes up in a study session.

## 10. Decisions

1. **Deck name**: "🇨🇳 Chinese Words" — confirmed.
2. **Who creates the new note type/deck/fields**: scripted via AnkiConnect against Anki
   Desktop, not manual GUI work and not on-device (Sections 2, 9).
3. **HSK/Frequency**: native Anki tags (`HSK::3`, `Freq::1200`), not a field. HSK wordlist
   source still needs finding — CC-CEDICT doesn't carry HSK level, so this needs a separate
   open dataset, license to be checked at implementation time like the others.
4. **Word schema**: a new, separate shape from `Card` — see `Word.kt` in Section 8.
5. **Word-level audio**: out of scope here entirely. A later, separate project will source real
   word-level audio recordings (not a same-project fallback like concatenating hanzi clips).
   The `Audio` field exists in the schema now so there's somewhere for it to land later, but the
   sync never touches it.
6. **`UsedInWords` on Hanzi notes**: confirmed, this is what "my hanzi character cards will get
   added to this new word" meant.
