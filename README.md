# <img src="docs/icon.png" width="48" align="center" alt="AnkiBiki icon" /> AnkiBiki

<p align="center">
  <img src="docs/icon.png" width="220" alt="AnkiBiki" />
</p>

A personal Android dictionary app (Pixel 10 Pro XL) that searches your own Anki cards live via
AnkiDroid, with an offline dictionary fallback and Chinese pronunciation audio.

Searches two decks directly out of AnkiDroid's live collection:

- **🇨🇳 RSH1 Hanzi** ("中文") — Chinese hanzi with mnemonics, pinyin, and pronunciation audio
- **01 NihongoShark.com: Kanji** ("日本語") — Japanese kanji with mnemonics and readings

If nothing in your decks matches, results fall back to a bundled offline dictionary
(KANJIDIC2 + JMdict + RADKFILE for Japanese, CC-CEDICT for Chinese — see
[ATTRIBUTION.md](ATTRIBUTION.md)).

## Requirements

- AnkiDroid installed, with the two note types above present in your collection (their model
  IDs are hardcoded in `KnownNoteTypes.kt` — a different collection won't match).
- A device running this build (personal app, not published).

## Architecture, briefly

- `AnkiRepository` — queries AnkiDroid's `FlashCardsContract` ContentProvider directly.
- `Card` / `CardAdapters.kt` — an anti-corruption layer: each Anki note type's raw, positional
  fields get adapted into one canonical `Card` shape (word/meaning/reading/mnemonic). If a note
  type's fields get restructured in Anki later, only its adapter needs to change.
- `DictionaryRepository` — exact-match lookups against the bundled offline dictionary.
- `SearchService` — merges both sources per query: your exact Anki matches, then dictionary
  exact matches, then everything else from your decks, interleaved so no single source (or, for
  multi-character CJK queries, no single character) buries the rest.
- `AudioRepository` — Chinese pronunciation audio, keyed by character.

## Data pipelines

Two SQLite DBs are bundled as Android assets, built by scripts under `scripts/`. Both need
Python 3, no other dependencies.

### Dictionary (`app/src/main/assets/dictionary.db`) — committed to the repo

```
python3 scripts/build_dictionary_db.py
```

Downloads KANJIDIC2, JMdict, and RADKFILE (EDRDG) plus CC-CEDICT fresh from their upstream
sources and rebuilds the DB. These are open, redistributable data (CC BY-SA 4.0), so the built
DB is committed — re-run this occasionally to pick up upstream corrections, but it's not
required for every build.

### Audio (`app/src/main/assets/audio.db`) — gitignored, **not** committed

```
python3 scripts/build_audio_db.py
```

Pulls Chinese pronunciation audio straight from **Anki Desktop's** local collection
(`collection.anki2` + `collection.media`) — not AnkiDroid on the phone. AnkiDroid's storage is
locked down by Android's scoped storage; even the broadest file-access permission can't read
another app's data folder there (confirmed directly, not assumed). Anki Desktop's collection is
just a normal, fully-accessible folder on the same PC, and syncs from the same account as the
phone, so this sidesteps the problem entirely.

This DB is **not committed** — the audio is Forvo native-speaker recordings, not freely
redistributable like the dictionary sources. A fresh checkout of this repo won't have it, and
the app is written to degrade gracefully (no play button shown) rather than crash when it's
missing.

Re-run this any time you've added more audio to cards in Anki (make sure Anki Desktop has synced
first), then rebuild the app to bundle the update.

### Character decomposition (`app/src/main/assets/ids.db`) — gitignored, **not** committed

```
python3 scripts/build_ids_db.py
```

Downloads [cjkvi-ids](https://github.com/cjkvi/cjkvi-ids)' `ids.txt` (derived from the CHISE
project) and builds a fully-recursive decomposition tree (JSON) per character, for the Hanzi
note type's "Constituents" field (see `docs/chinese-word-hanzi-sync-plan.md`).

Not committed, for the same reason as `audio.db`: that specific file's README says it "follows
[CHISE's] terms" rather than the repo's own blanket GPLv2, and CHISE's site doesn't spell those
terms out anywhere either — unclear redistribution terms get the same treatment as Forvo's
audio, even though this is just data and the app itself isn't published. Re-run this if the
upstream data gets corrected/extended; otherwise it basically never changes.
