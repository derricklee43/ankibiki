# Resolved Disambiguation Items

Date resolved: 2026-11-15 (user answered all four in-thread the same day).
Formerly docs/pending-disambiguation.md; each question is kept here with the answer that
closed it, so answers map to questions by CONTENT.

## 1. Photo Flagger (`flag_color` string) — RESOLVED → shipped in schema v11
- **User answer**: "Fixed palette for now, eventually, I want to incorporate a color selector,
  so make sure the fixed palette is not too tightly coupled with the rest of the feature.
  Next to 金. Yes to thumbnail badges. Yes, to filtering."
- **Built**: `scraped_photos.flag_color TEXT` stores an OPAQUE color string — nothing but
  `PhotoFlagPalette` interprets it. Palette = red/yellow/green/blue/purple + 解除, Japanese
  labels 赤/黄/緑/青/紫; a `#RRGGBB` value round-trips and renders with zero code change
  when the color selector lands (that decoupling is pinned by `PhotoFlagPaletteTest`).
  Dots sit in the row directly under 金/編集; `flagDot` badge on grid thumbnails;
  旗 filter facet in the library (flagged-only — per-color facets wait for the selector).
- **Evidence**: PhotoFlagInstrumentedTest (set/clear round-trip, REPLACE-preservation, facet,
  v10→v11 migration unseeded); live walk 2026-11-15: tap 赤 → `flag_color='red'` →
  content-desc 旗 赤 → 50/50 red pixels; 旗 chip → grid = 1 flagged tile.

## 2. `わし` / Custom Definition & Notes — RESOLVED → shipped in schema v11
- **User answer**: "Yes and yes" → maps to (a) `user_definition` displayed ABOVE the
  dictionary meaning, and (b) definition + notes exposed as wizard-mappable fields.
- **Root cause on record** (unchanged from the original note): `わし` has 和紙/鷲 (common)
  and 儂 (uncommon) in `dictionary.db`; `is_common DESC` + first-match made 和紙 win.
- **Built**: `extracted_items.user_definition/user_notes TEXT` with pencil dialogs on
  WordGrammarDetailActivity (user row above 意味:); ExportField `user_definition`/
  `user_notes` joined the wizard vocabulary, contract test re-pinned in the same commit.
  The user's own text is now the manual override for the wrong-sense class of cases.
- **Explicitly still open** (not built, no consent spent): runtime dictionary JOIN vs
  saved-meaning fallback, and the pin-which-sense picker — that is spike 005 Option C
  (spikes/005-homophone-disambiguation), untouched pending a go. (2026-11-23: the MANUAL
  item-level re-selection was separately designed at user request —
  docs/item-meaning-reselect-proposal.md, DRAFT; per-occurrence sense pinning remains parked.)
- **Evidence**: WordFoldersInstrumentedTest.userDefinitionAndNotes_surviveReplaceUpsert;
  DraftAdapterContractTest (label list + resolve specs); live walk: 定義/ノート/フォルダ
  rows render on the word view.

## 3. Word Folders + "Skip Inbox" Toggle — RESOLVED → shipped in schema v11
- **User answer**: "One per word is fine. Yes. Third tab." → 1:1 folder; moving into a
  skip folder clears that word's EXISTING UNSEEN occurrences too; folders in a third tab.
- **Built**: `word_folders(id, name UNIQUE, skip_inbox, created_at)` +
  `extracted_items.folder_id`; フォルダ tab in ExtractedListActivity (＋ create row with
  skip toggle; tap folder → word list → tap word opens detail; long-press → delete, which
  ONLY unassigns). フォルダ row on the word detail with a picker (row 0 = フォルダなし;
  removing from a folder never re-opens the inbox). Future occurrences of skip-folder items
  are born SEEN at `insertOccurrence` — same mechanism as the hidden-item rule it sits next to.
- **Evidence**: WordFoldersInstrumentedTest (create-or-reuse; existing-UNSEEN flip; per-folder
  initial state; never-unsees / delete-only-unassigns; language+type scoping of folder lists).
  Live walk 2026-11-15 on the real emulator corpus: created `mining-low` (skip ON) through the
  dialog → moved 海賊 via the picker → `folder_id=1`, UNSEEN 8→0, SEEN 8; state restored after.

## 4. `殺せ` -> `殺せる` — CLOSED BY USER, no further work
- **User answer**: "Maybe it was fixed in an update. Let's not worry about it."
- Pinned facts stay: bare 殺せ tokenizes 殺す in KuromojiWordAnalyzerTest; the ーー
  prolonged-mark rejection shipped with it. Reopen only with a photo ID if it recurs.
