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
