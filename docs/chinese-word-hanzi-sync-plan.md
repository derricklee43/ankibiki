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

Status: **IMPLEMENTED** (Aug 2026: `6979de2` sync + button, `e1f9916`/`c90d1d6` UsedInWords,
`e1f9916` Constituents directory-tree render, `b984dba` per-node meanings, `fae4cc4` audio
reuse, `26e43cf` diacritic pinyin, `511ad0b` HSK + kanji cross-reference backfill). This doc
is now the design record; live status lives in docs/roadmap.md. Section 9 is the build order
that was followed. Section 10 records the decisions made on the first draft's open questions.

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

- `scripts/setup_chinese_word_deck.py` — new (Phase 1, one-time, AnkiConnect). **Moved to the
  sibling `scripts-anki/` repo in Section 19** -- this and all other references to its old path
  in this doc are historical.
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

   **Follow-up fix**: CC-CEDICT pinyin is numbered-tone ("bei1 shang1"), but the collection's
   own convention (e.g. 一's existing Pinyin field) is diacritic form ("yī") -- so the sync's
   writes were inconsistent with data already in the deck. Added `toDiacriticPinyin()`
   (`PinyinFormat.kt`) and applied it everywhere the sync writes a Pinyin field. Converts at
   write time rather than at render/template time, since (a) it's a deterministic one-line
   transform, not a real structure like `Constituents` that needs JS, and (b) it keeps the raw
   field plain, correct text in the Anki browser outside the card view too. Verified against
   13 cases including both `u:`-notation (nu:3 -> nǚ) and the "iu"/"ui" mark-placement rule
   (jiu3 -> jiǔ, hui4 -> huì) -- all correct. Manually fixed the two already-written fields from
   the Phase 6 test (悲's and 悲伤's Pinyin) via AnkiConnect directly, since the sync's
   non-destructive design means it won't retroactively touch a field it already filled in.

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

## 11. HSK 1/2 bulk import

Resolves decision 3's open "HSK wordlist source" question, for levels 1/2 at least.
`scripts/import_hsk_words.py`, run against Anki Desktop via AnkiConnect (same as
`setup_chinese_word_deck.py`): pulls the old (classic 6-level, not 2021's HSK 3.0) standard's
level-1 and level-2 word lists from `drkameleon/complete-hsk-vocabulary` (MIT licensed) and
creates a Word note per word tagged `HSK::1`/`HSK::2`.

First version left Translation blank for the sync to fill from CC-CEDICT, same as any other
word. **Revised** after reviewing real output side by side: CC-CEDICT's raw dictionary gloss is
often more technical than useful for a beginner word (e.g. 爸爸 got "(coll.) father; dad;
CL:位[wei4]" -- that trailing classifier note is dictionary jargon, not something a learner
wants on the card) where the HSK wordlist's own curated gloss is already exactly what's wanted
("(coll.) father; dad"). So now: **Translation comes from the HSK wordlist itself**, written
directly by this script (own field per script, not the sync) -- Pinyin/Audio/HanziBreakdown
stay the sync's job entirely, untouched by this script. Re-running only touches Translation, and
only when the upstream HSK gloss actually differs from what's already there (checked before
writing, so an unrelated Pinyin/HanziBreakdown update from the sync never gets clobbered and a
no-op rerun doesn't even bump the note's mod-time).

Run against the real collection twice: first the original 297-new-word run (150 HSK1 + 147
HSK2; 悲伤 already existed from Phase 6's test and was left alone), then -- after the sync had
already filled in CC-CEDICT-sourced translations for those 297 -- the revised script overwrote
all 297 with the HSK wordlist's own gloss instead, verified via AnkiConnect on both 爸爸
(classifier note gone) and 爱 (happened to already match CC-CEDICT's, unaffected either way),
confirmed Pinyin/HanziBreakdown untouched on both. 悲伤 (not an HSK word) confirmed still on its
original CC-CEDICT translation, out of this script's scope entirely. Re-ran once more: 0 updates.

## 12. Single-character audio reuse

A word that's exactly one hanzi (e.g. 菜) can reuse that character's own pronunciation clip
directly -- same `[sound:...]` filename, already sitting in the shared `collection.media`
folder both note types draw from, so this is a plain field-to-field text copy, not new audio
data or a new pipeline. Added to `HanziWordSyncService`: after resolving a word's component
hanzi, if the word is a single character and that hanzi's own Audio field is non-blank, copy it
into the Word's Audio field -- but only if the Word's Audio is still blank (never overwrites
anything you or a future real word-audio pipeline puts there; see decision 5, still unaffected --
this is reuse of an *exact match's* existing clip, not synthesis for multi-character words).

Ran the sync against the real collection: 107 of 298 words picked up audio this way (every
single-character HSK 1/2 word whose hanzi already had a Forvo clip). Verified 菜 specifically --
its Word note's Audio now reads `[sound:forvo_zh_菜_7b4bb888fb65.mp3]`, identical to its Hanzi
note's, confirmed via the temporary-debug-hook-then-revert pattern.

## 13. Richer UsedInWords + rendering it on the Hanzi card

Two asks: (1) `UsedInWords` should carry the word's pinyin and meaning, not just the bare word
text, and (2) it should actually be visible on the Hanzi card -- Phase 1 added the field but
never added it to the template, so it was invisible the whole time.

`HanziWordSyncService`: a word's Translation/Pinyin now get resolved *before* the per-character
loop (previously resolved after, inside `updateWordFields`) since the "used in words" line
needs them too, even when the word's own fields are already filled in and nothing there needs
changing. Each hanzi's `UsedInWords` line is now `"<word> (<pinyin>) — <meaning>"`, one word per
`\n`-separated line, parsed back into a `word -> line` map keyed on the word (splitting on
`" ("`) so a re-run updates a line in place (e.g. once a blank Translation gets filled in later)
rather than leaving a stale duplicate parked next to the new one -- this also means it correctly
migrates old bare-word-only lines from before this change, since the key extraction works the
same way on those.

`setup_chinese_word_deck.py`: new `ensure_hanzi_used_in_words_rendered()`, appended to the Back
template/CSS the same way the Constituents tree was -- `white-space: pre-line` is enough here
(no JS needed) since the field is already plain, readable text with real `\n`s.

Ran the sync against the real collection: 菜's `UsedInWords` went from `"菜"` to `"菜 (cài) —
dish (type of food); vegetable; cuisine; (coll.) (one's) type; (of one's skills etc) weak;
poor"`; 悲's went to `"悲伤 (bēi shāng) — sad; sorrowful"`. Verified via the temporary-debug-hook
pattern. The toast's `hanziUpdated` count was cut off by the toast's width in the screenshot,
which briefly looked like nothing had changed -- it hadn't actually failed, the field data
directly confirmed the enrichment did apply.

## 14. Constituents tree: directory-style redesign

Wasn't happy with the original tree's look (v1: plain nested `<ul>`, bold text for expandable
nodes, no connecting lines, everything collapsed by default including the root's own direct
children). Redesigned to look like an actual file-explorer/directory tree --
[the classic CSS technique](https://freefrontend.com/css-tree-view/) for this: each `<li>` gets
a vertical "trunk" line (`::before`) and a horizontal "elbow" connecting it to its label
(`::after`), with the last sibling's trunk shortened so it doesn't run past its own elbow. Added
a real ▾/▸ disclosure triangle per expandable node (a fixed-width empty spacer on leaf nodes, so
labels stay aligned whether or not they have one). Monospace font, matching the "directory
listing" feel.

Also changed the default expand state: v1 collapsed the root's own children too, so flipping the
card showed nothing but the character itself until you clicked -- now depth-0's children (the
character's immediate decomposition) start expanded, anything deeper starts collapsed, so the
immediate breakdown is visible without any interaction while a long decomposition chain still
doesn't dump everything onto the card at once.

`ensure_hanzi_template()` in `setup_chinese_word_deck.py` now migrates in place: a new
`ct-toggle`-based marker distinguishes "v2 already applied" from "v1 still live" (the old
`constituent-tree-render` marker alone can't tell the two apart, since it's present in both
versions), and finds-and-replaces the exact v1 HTML/CSS strings kept around specifically for
this. Ran against the real collection and confirmed idempotent (`[skip]` on a second run).
Verified the resulting HTML/CSS/JS via AnkiConnect and traced the JS logic against 悲's actual
`⿱非心` tree by hand -- correct -- but, same limitation as the original tree, there's no way to
screenshot Anki Desktop's or AnkiDroid's actual WebView rendering from here, so this hasn't
been *seen* rendering yet. Worth a look next time a decomposable character comes up for review.

## 15. Constituents nodes show a meaning, not the IDC operator glyph

Didn't want the raw `⿰`/`⿱`/etc. icon next to each node -- wanted each component's *meaning*
instead, pulled from that character's own card in this deck specifically (not a fresh
dictionary lookup -- the whole point is showing what you already know this piece as). Since Anki
templates can't query other notes at render time, this has to be baked into the stored
`Constituents` JSON at sync time, not resolved client-side.

`HanziWordSyncService.annotateWithMeanings()` (new, uses `org.json` -- built into the Android
SDK, no new dependency) walks the raw tree from `IdsRepository` and adds a `"meaning"` string to
every node whose character has its own Hanzi note, pulled from that note's own Meaning field.
Moved Constituents out of `buildNewHanziFields` (which only ever ran once, at creation) and into
the per-character loop in `sync()`, recomputed on *every* run rather than write-once -- so a
component that gets its own card later still picks up a meaning retroactively, not just at the
moment its parent's tree was first built. `op` stays in the JSON (still structurally correct,
just unused by the template now) rather than being stripped, in case something else wants it
later.

`setup_chinese_word_deck.py`: v3 of the tree template, `label.textContent = node.char +
(node.meaning ? ' — ' + node.meaning : '')` instead of `node.op`. `ensure_hanzi_template()`
chains v1 -> v2 -> v3 migration (checks for `CONSTITUENT_TREE_V3_MARKER` first; falls back
through the v2 and v1 exact-string replacements in turn) since CSS didn't need to change this
time -- only the label logic did.

Ran against the real collection: 悲's `Constituents` is now `{"char":"悲","op":"⿱","children":
[{"char":"非","op":null,"children":[],"meaning":"non-"},{"char":"心","op":null,"children":[],
"meaning":"heart"}],"meaning":"sad; sadness; sorrow; grief"}` -- both 非 and 心 already had their
own cards in the deck, and their meanings got pulled in correctly. Verified via the
temporary-debug-hook pattern; migration confirmed idempotent on a second script run.

## 16. Word audio moved to the answer side

Audio was on the Front template (and echoed again at the top of Back), so it played before
you'd tried to recall the pronunciation yourself -- moved out of Front entirely and into the
`.word-back` answer section (right after Pinyin), so hearing it is part of checking your answer,
not a freebie beforehand.

`ensure_word_audio_on_answer()` in `setup_chinese_word_deck.py`, same migrate-in-place pattern
as the other template changes: strips `{{#Audio}}...{{/Audio}}` from the front block wherever it
appears (both Front and the echoed block at the top of Back), and inserts it into the answer
block after Pinyin. No CSS changes needed -- `.audio` styling already existed.

Ran against the real collection and verified the actual rendered question/answer HTML via
AnkiConnect for 菜: question is now just the bare word, answer has
`<div class="audio">[anki:play:a:0]</div>` between Pinyin and HanziBreakdown. Confirmed
idempotent on a second run.

## 17. Constituents tile + pinyin sizing

Two asks: (1) the Constituents tree sat bare on the card background with no label -- give it the
same bordered/shadowed "tile" treatment already used for the Japanese-kanji section (`.jp-tile`),
with a "Decomposition" header; (2) Pinyin (18px) read visibly smaller than Meaning (23px) despite
both being answer-side headline info -- bumped Pinyin to match.

`ensure_hanzi_template()` in `setup_chinese_word_deck.py`: v4 of the tree template. Wraps the
existing tree-data/tree-render/script in a new `.constituents-tile` div with a
`.constituents-tile-header` ("Decomposition") above it, styled to match `.jp-tile`/
`.jp-tile-header` (white/dark rounded box, shadow, padding, centered bold header with a bottom
border). `.constituent-tree-render`'s own `margin`/`max-width` moved out into the tile wrapper,
since the tile now supplies that inset. Migration chain extended: v1/v2/v3 (checked via
`CONSTITUENT_TREE_V4_MARKER` = `"constituents-tile"`, since v3's own marker --
`node.meaning` -- is still present in v4 and can't tell them apart) all fold forward into v4
directly.

New `ensure_pinyin_size_matches_meaning()`: finds-and-replaces the exact `.back-card .pinyin`
rule, `font-size: 18px` -> `23px`, same guarded exact-string-match pattern as the other CSS
migrations here.

Ran against the real collection via AnkiConnect: both changes applied (`[migrated]` on the tile
HTML/CSS, `[add]` on the pinyin size). Verified afterward -- not just trusted -- by re-fetching
`modelStyling`/`modelTemplates` and confirming `.back-card .pinyin` now reads `font-size: 23px`
and the Back template's Constituents block is wrapped in `.constituents-tile`. Re-ran the script
a second time: every step reported `[skip]`, confirming idempotent. Not yet visually confirmed in
an actual AnkiDroid/Anki Desktop review screen (no way to render a WebView from here, same
limitation as the original tree work in Section 14) -- worth a look next time a Hanzi card comes
up for review, and remember to sync AnkiDroid with this collection first.

## 18. Confirmed: field additions auto-backfill blank, Constituents/UsedInWords are already sync-owned

Question raised: since the Hanzi template was already updated to reference `Constituents`/
`UsedInWords`, doesn't adding a new field to a note type automatically give every note (existing
*and* new) an empty value for it, without any extra step?

**Yes, confirmed on both sides:**
- `modelFieldAdd` (AnkiConnect) retroactively adds the new field to every *existing* note of that
  model with an empty string -- this is exactly what let Section 9 step 1 verify `RSH`'s field
  list ends `..., Constituents, UsedInWords` with "existing 0-11 untouched" and nothing further to
  do on the schema side.
- `AddContentApi.addNote` (on-device) requires a fields array sized to the model's current field
  count, so a *newly created* note gets `""` for any field the creating code doesn't explicitly
  set -- see `buildNewHanziFields()` (`HanziWordSyncService.kt:154-165`), which deliberately
  leaves `Constituents`/`UsedInWords` blank at creation time and lets a later pass fill them in.

And on the follow-up ("if so, move Constituents/UsedInWords to the sync script"): **already the
case, no change needed.** `setup_chinese_word_deck.py` only ever touches note-type schema/
templates/CSS -- it has never written an actual field *value* for any note. All Constituents/
UsedInWords value computation already lives in `HanziWordSyncService.sync()` (the per-hanzi
backfill loop from the Section-17-adjacent fix earlier this session). No steps to build here --
this section exists so the pattern is written down once, since Sections 20-22 below reuse it
verbatim for two new fields.

## 19. Move one-time backfill scripts to a new `scripts-anki/` directory

Split `ankidroid-dict/scripts/` by what the script actually operates on, not just "is it one-time":

- **Stay in `ankidroid-dict/scripts/`**: `build_ids_db.py`, `build_dictionary_db.py`,
  `build_audio_db.py`. These build *bundled app assets* (`app/src/main/assets/*.db`) and use
  paths relative to the app's own layout (`Path(__file__).parent.parent / "app" / "src" / ...`).
  They're a build-time dependency of the Android app itself, not the Anki collection.
- **Move to new top-level `scripts-anki/`** (sibling to `ankidroid-dict/`): `setup_chinese_word_deck.py`
  and `import_hsk_words.py`. Neither touches the app codebase at all -- both are pure AnkiConnect
  HTTP clients that mutate the *live Anki collection* (schema/templates/notes), and have no
  path dependency on living inside `ankidroid-dict/`.

**Decision**: the new repo starts fresh, no shared history with `ankidroid-dict` -- a plain file
copy + `git rm` in the old repo, not a history-preserving move.

1. ✅ **DONE** -- created `scripts-anki/` at the workspace root (sibling to `ankidroid-dict/`),
   copied `setup_chinese_word_deck.py` and `import_hsk_words.py` there (carrying over this
   session's in-progress, not-yet-committed edits to `setup_chinese_word_deck.py`), `git rm`'d
   both from `ankidroid-dict`, and ran `git init` fresh in `scripts-anki/` with both files staged.
   Confirmed via read-through that neither file has any path assumption tied to its old location
   (both are pure AnkiConnect HTTP clients; `import_hsk_words.py`'s only external reference is a
   GitHub raw URL) -- the move needed no code changes, just relocation.
2. Updated the stale `scripts/...` references elsewhere in this doc (Sections 8, 20) to point at
   the new location or note them as historical.
3. **Still to do**: re-run both scripts from `scripts-anki/` against the live collection to
   confirm the move didn't break anything (expect all-`[skip]` on `setup_chinese_word_deck.py`
   since nothing changed but its address; `import_hsk_words.py` should report 0 new words, 0
   updates, same as its last real run).

## 20. New Hanzi field: HSK level (HSK 3.0)

New field on the Hanzi note type, sync-owned (same treatment as Constituents -- see Section 18).
"New version" means the current **HSK 3.0** (2021) standard specifically, *not* the old classic
6-level standard `import_hsk_words.py` already uses for word-level HSK tagging (Section 11) --
those are different, incompatible level systems, so this needs its own data source, not a reuse
of the existing word-level one.

Steps:
1. Decide the exact field name and value shape (e.g. `HSK` holding `"1"`-`"6"`/`"7-9"`/blank).
2. **Open question -- needs research before implementation**: find a *character-level* HSK 3.0
   dataset (band per hanzi, not per word) with a clear, redistributable license -- same diligence
   already applied to `ids.txt`/CC-CEDICT/KANJIDIC2. `import_hsk_words.py`'s existing source
   (`drkameleon/complete-hsk-vocabulary`) is word-level and old-standard; check whether it (or
   another source) also ships a character-level HSK 3.0 list before assuming a new dependency is
   needed.
3. New `build_hsk_db.py` in `ankidroid-dict/scripts/` (asset-builder, per Section 19's split --
   it produces a bundled `app/src/main/assets/hsk.db`, same pattern as `build_ids_db.py`).
   Committed-or-gitignored depends on the source license, same case-by-case call already made for
   `ids.db` vs. `dictionary.db`.
4. New `HskRepository.kt` (parallel to `IdsRepository`): `hskLevel(character): String`, degrading
   to `""` when the asset is missing or the character has no data, same defensive pattern as
   `IdsRepository`/`AudioRepository`.
5. `ensure_hanzi_fields()` (now in `scripts-anki/setup_chinese_word_deck.py` per Section 19): add
   `"HSK"` to the appended-field list, landing at index 14 (after `UsedInWords`), append-only per
   the load-bearing positional-index rule (Section 3.2).
6. `KnownNoteTypes.kt`: add `HANZI_HSK_FIELD_INDEX = 14`, bump `HANZI_FIELD_COUNT` to 15.
7. **Open question**: whether/how to render this on the card template at all (e.g. a small badge
   near `Number`) -- not asked for explicitly, so left undecided here rather than assumed; likely
   its main value is for browsing/filtering in the Anki browser (`HSK:5` search) even with no
   template change.

**✅ DONE.** Decision on step 2: reuse `drkameleon/complete-hsk-vocabulary` (already this
project's vetted, MIT-licensed source for `import_hsk_words.py`) rather than a new dependency --
it turns out to also carry the official HSK 3.0 word list, just word- not character-level
(`wordlists/exclusive/new/{1..7}.json`, "exclusive" meaning each level's file holds only the
words *first* introduced there, no repeats across levels -- confirmed live: level 1 has 506
words, level 2 750, ..., level 7 (the combined "7-9" band) 5606). `scripts/build_hsk_db.py`
derives the character-level table exactly as specified: split every word into its Han
characters, a character's level = the lowest level any word containing it belongs to. Ran it
against the live upstream data: 2970 characters resolved, written to
`app/src/main/assets/hsk.db` (88 KB). Spot-checked: 一/好/爱/谢/菜 -> `"1"`, 伤 -> `"3"`, 悲 ->
`"5"` -- all plausible against their actual commonality. Committed to the repo (MIT is
permissive enough, same call as `dictionary.db`; confirmed `git check-ignore` finds no rule
excluding it, unlike `ids.db`/`audio.db`).

Steps 5-6 done: `HSK` added via the relocated `scripts-anki/setup_chinese_word_deck.py` (ran
against the live collection, confirmed via `modelFieldNames` that `RSH`'s field list now ends
`..., Constituents, UsedInWords, HSK` at index 14 exactly as planned; re-run is `[skip]`, still
idempotent). `KnownNoteTypes.kt`: `HANZI_HSK_FIELD_INDEX = 14`, `HANZI_FIELD_COUNT` bumped to 15.
`HskRepository.kt` added, same defensive/degrade-to-`""` shape as `IdsRepository`.

Step 7 (template rendering) intentionally left undone -- still an open question, not part of
what was asked for here.

## 21. HSK lookup in the sync script

Steps:
1. `HanziWordSyncService` gains an `hskRepository: HskRepository` constructor param, wired up in
   `MainActivity` next to the existing `IdsRepository(this)`.
2. New backfill pass (see Section 23's refactor) `backfillHskLevel(hanziByChar, hskRepository)`:
   for every entry in `hanziByChar`, look up `hskRepository.hskLevel(charStr)`, compare against
   `entry.fields[HANZI_HSK_FIELD_INDEX]`, write + mark dirty only if different -- identical
   diff-checked shape to `backfillConstituents`.
3. **"Mark all existing cards as dirty" -- already handled, no extra mechanism needed.** Every
   existing Hanzi note has `""` in the brand-new HSK field the moment the schema step runs; the
   very next sync run, any character that resolves to a real (non-blank) HSK level differs from
   that `""` and gets written automatically -- the same self-healing first-run backfill already
   verified for Constituents earlier this session. No explicit "force resync" flag or dirty-marking
   step needs to be built; running the schema script then tapping the sync button once is enough.
4. Order of operations: run the (relocated) `setup_chinese_word_deck.py` first (adds the field +
   builds/bundles `hsk.db`), sync AnkiDroid with Desktop, *then* tap the on-device sync button --
   expect a large `hanziUpdated` count on that first run, same shape as the Constituents backfill.

**✅ DONE (code).** `backfillHskLevel()` added to `HanziWordSyncService`, wired into `sync()`
per Section 23's new shape. `HskRepository` threaded through the constructor and
`MainActivity.onCreate()`. Schema (step 4 prerequisite) already applied live -- see Section 20.

**Not yet run.** Unlike the Python/AnkiConnect changes, this part only executes inside the actual
Android app talking to AnkiDroid's `AddContentApi` -- nothing reachable from this dev environment
can trigger it. **You'll need to**: rebuild the app, sync AnkiDroid with Desktop (to pick up the
new `HSK` field), then tap the in-app sync button once. Expect a large `hanziUpdated` count on
that first run, same shape as the original Constituents backfill.

## 22. Kanji reference tile sync

"Some cards are already missing that" -- the `.jp-tile` block only shows for Hanzi notes with a
non-blank `JP Kanji ID`, and today those four `JP *` fields are filled in by hand (or by whatever
one-off process populated the deck originally), not by anything repeatable -- so any card whose
match was never manually entered stays permanently missing it.

Checked the live collection directly (via AnkiConnect) to confirm the exact field mapping, using
一 (id `1577354407397`) as a known-good example against its linked `NihongoShark.com: Kanji` note
(id `1354715253526`):

| RSH field | Value on 一 | Source |
|---|---|---|
| `JP Kanji` | `"一 (one)"` | `"{kanji} ({keyword})"` |
| `JP Kanji ID` | `"1354715253526"` | the NihongoShark note's own **Anki note ID** (not its internal `id` field, which is `"1"`) |
| `JP Story` | `"One down, 2041 to go. ;)."` | NihongoShark's `myStory` field |
| `JP Constituent` | `"One"` | NihongoShark's `constituent` field |

`NihongoShark.com: Kanji` field order: `id(0), frameNoV4(1), frameNoV6(2), keyword(3), kanji(4),
strokeDiagram(5), hint(6), constituent(7), strokeCount(8), lessonNo(9), myStory(10),
heisigStory(11), ...` -- `kanji`(4)/`keyword`(3) already match this app's existing
`CHARACTER_FIELD_INDEX`/`MEANING_FIELD_INDEX` entries for `NIHONGO_SHARK_KANJI_MODEL_ID`.

Steps:
1. New `KnownNoteTypes.kt` constants: `NIHONGO_SHARK_STORY_FIELD_INDEX = 10`,
   `NIHONGO_SHARK_CONSTITUENT_FIELD_INDEX = 7` (character/meaning indices already exist).
2. `HanziWordSyncService` loads all `NihongoShark.com: Kanji` notes once per run
   (`ankiRepository.queryAllNotes(NIHONGO_SHARK_KANJI_MODEL_ID)`), builds a
   `character -> NoteResult` map, same construction as `hanziByChar` itself.
3. New backfill pass (Section 23) `backfillJpTile(hanziByChar, jpKanjiByChar)`: for each Hanzi
   entry, look up its character in `jpKanjiByChar`; on a match, compute all four `JP *` field
   values and diff-check/write exactly like the other passes; on no match, leave whatever's
   already there alone (don't blank out a previously hand-entered value just because this
   specific deck doesn't cover that character).
4. **Open question**: a second model, `NihongoShark.com: Kanji+`, exists in the live collection
   and isn't queried by this app at all today. Some of the "missing" cards may have a match only
   in `Kanji+`, not the base `Kanji` model -- worth checking at implementation time whether to
   query both and, if a character appears in both, how to pick.
5. Handle a character appearing more than once within the base `Kanji` model itself (not observed,
   but not verified absent either) -- first match wins, same convention as everywhere else in this
   file.

**✅ DONE (code).** `backfillJpTile()` added, matching the mapping table above exactly.
`NIHONGO_SHARK_STORY_FIELD_INDEX`/`NIHONGO_SHARK_CONSTITUENT_FIELD_INDEX`/`HANZI_JP_*_FIELD_INDEX`
constants added to `KnownNoteTypes.kt`. Step 5 resolved by construction: `associateBy` on the JP
notes list keeps the *last* match on a duplicate key, not "first" as originally guessed -- noted
here since it's the one place this file's usual "first match wins" convention doesn't actually
hold, in case it matters later.

**Step 4 resolved -- `Kanji+` was a dead end, no fix needed.** Queried the live collection
directly: `NihongoShark.com: Kanji+` and `NihongoShark.com: JLPT N4 Cramming Deck` both have
**zero notes** -- they're model definitions with no data behind them, not a hidden source of
missing matches. The real shape of "some cards are missing that": of RSH's 1,514 Hanzi notes,
895 have a character match in the base `Kanji` model (2,200 notes; the other 619 are Chinese-only
characters with no Japanese-kanji counterpart, so a blank tile there is correct, not missing
data), and of those 895, 888 already have `JP Kanji ID` filled in from whatever manual process
populated the deck originally -- leaving exactly **7** cards with a real match but no tile yet.
`backfillJpTile()` will pick those up (and keep the other 888 current) the first time the
on-device sync actually runs. No further code needed for this.

**Not yet run**, same reason and same next step as Section 21 -- needs the on-device sync button,
not reachable from here.

## 23. Refactor: one function per sync concern

Today `sync()` already factors out small helpers (`buildNewHanziFields`, `updateWordFields`,
`annotateWithMeanings`, `parseUsedInWords`, `formatUsedInWordLine`), but its two top-level passes
-- the per-word loop (note creation + `UsedInWords`) and the per-hanzi `Constituents` backfill
loop -- are written inline inside `sync()` itself. Sections 21 and 22 above each add another full
per-hanzi backfill pass, so that inline-loop shape needs to generalize now rather than turning
`sync()` into one long function with four loops bodies stacked in it.

Target shape:

```kotlin
fun sync(): SyncResult {
    val wordNotes = ankiRepository.queryAllNotes(CHINESE_WORD_MODEL_ID)
    val hanziNotes = ankiRepository.queryAllNotes(CHINESE_MNEMONICS_MODEL_ID)
    val hanziByChar = buildHanziIndex(hanziNotes)

    val (hanziCreated, wordsUpdated) = syncWordNotes(wordNotes, hanziByChar)
    backfillConstituents(hanziByChar)
    backfillHskLevel(hanziByChar)
    backfillJpTile(hanziByChar)
    val hanziUpdated = flushDirtyHanzi(hanziByChar)

    return SyncResult(hanziCreated, wordsUpdated, hanziUpdated)
}
```

Each `backfillX(hanziByChar, ...deps)` owns exactly one field's compute/compare/mark-dirty logic
and takes whatever it needs as an explicit parameter (an `HskRepository`, a `jpKanjiByChar` map,
...) instead of every pass being an anonymous block inline in `sync()` -- keeps each pass
independently readable (and testable, if tests get added later) and keeps `sync()` itself a short
orchestrator that's easy to scan for "what does a sync actually do."

Steps:
1. Extract `buildHanziIndex(hanziNotes: List<NoteResult>): MutableMap<String, HanziEntry>` from
   today's inline `associateTo` call.
2. Extract `syncWordNotes(wordNotes, hanziByChar): Pair<Int, Int>` (hanziCreated, wordsUpdated) --
   everything currently in the `for (wordNote in wordNotes)` loop, unchanged in behavior.
3. Extract `backfillConstituents(hanziByChar)` -- today's per-hanzi Constituents loop, verbatim.
4. Add `backfillHskLevel(hanziByChar, hskRepository)` (Section 21) and
   `backfillJpTile(hanziByChar, jpKanjiByChar)` (Section 22).
5. Extract `flushDirtyHanzi(hanziByChar): Int` -- today's final write loop.
6. `sync()` itself shrinks to the six-line orchestration shown above.

**✅ DONE.** `HanziWordSyncService` now matches the target shape exactly (one difference from
the sketch above: `jpKanjiByChar` is built once in `sync()`, alongside `hanziByChar`, and passed
into `backfillJpTile` as a parameter, rather than queried inside it -- keeps all
`AnkiRepository.queryAllNotes` calls together at the top of `sync()`). `hskRepository` stays a
constructor-injected field rather than a passed parameter, matching how `idsRepository`/
`dictionaryRepository` were already handled before this refactor -- only per-call *data*
(`hanziByChar`, `jpKanjiByChar`, etc.) is threaded through as explicit function parameters. Also
extracted a small `setFieldIfChanged(entry, fieldIndex, value)` helper, since the compute-
compare-mark-dirty shape was about to be repeated three times (Constituents/HSK/JP-tile) instead
of two.

## 24. JP-tile matching was still missing a whole class: Shinjitai

Section 22's Traditional-form fallback (绍/紹) fixed the case where Japanese kept the
Traditional-Chinese form unchanged. But there are genuinely **three** independent CJK
character-form lineages, not two: Traditional Chinese, Simplified Chinese (Mainland China's own
1950s reform), and Shinjitai (Japan's own, separate 1946 postwar reform). They frequently
coincide (国/国, 学/学) or Shinjitai matches Traditional exactly (绍/紹, Section 22's case) -- but
sometimes all three genuinely differ: 灵 (Simplified) / 靈 (Traditional) / 霊 (Shinjitai) are
three different characters, and a lookup that only knows Simplified<->Traditional can't find 霊
for 灵. Confirmed this is real in the actual collection, not hypothetical: 灵 has a Hanzi note,
its true Japanese counterpart 霊 exists in the NihongoShark deck, and the Section 22 fix alone
left its JP-tile fields empty.

Checked whether Unicode's own Unihan database (`kSimplifiedVariant`/`kTraditionalVariant`/
`kSemanticVariant`/`kZVariant` in `Unihan_Variants.txt`, downloaded directly from
unicode.org/Public/UCD/latest/ucd/Unihan.zip) could cover this before looking further --
confirmed it can't, empirically, not from assumption: tested five known three-way splits (灵/靈/
霊, 転/轉/转, 対/對/对, 圧/壓/压, 沢/澤/泽) and every single Shinjitai form had **zero** entries in
`Unihan_Variants.txt`. Unihan's variant fields are scoped to the Chinese simplification
relationship only; Japan's separate one isn't represented there at all.

Found and verified `BYVoid/OpenCC`'s `JPShinjitaiCharacters.txt` instead (Apache-2.0, 9900+
GitHub stars, actively maintained) -- keyed Shinjitai -> Kyuujitai/Traditional variant(s),
curated specifically from Japan's official 常用漢字表 (jouyou kanji), 人名用漢字表, and
表外漢字字体表 tables. Confirmed against the same five test characters: all five correctly
present (霊->靈, 転->轉, 対->對, 圧->壓, 沢->澤).

`scripts/build_shinjitai_db.py` (new) downloads that file and inverts it (kyuujitai/traditional
-> shinjitai, since that's the direction the sync needs) into the bundled `shinjitai.db`.
Apache-2.0 is clearly redistributable -- same tier as KANJIDIC2/JMdict/CC-CEDICT/HSK, so unlike
`ids.db`/`audio.db` this **is** committed. `ShinjitaiRepository` (new) wraps it, same
degrade-to-null-on-missing-asset pattern as `IdsRepository`/`HskRepository`.

`backfillJpTile`'s lookup is now three tiers via a new `findJpKanjiNote()` helper: exact
character match, then Traditional form (Section 22, unchanged), then that Traditional form's
Shinjitai via `ShinjitaiRepository` (new). Verified against the real collection via the
temporary-debug-hook pattern: 灵's JP Kanji field is now `"霊 (spirits)"`, with the correct note
ID and story pulled through -- the full three-tier chain confirmed working end to end, not just
each piece in isolation.
