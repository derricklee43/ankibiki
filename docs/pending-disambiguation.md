# Pending Disambiguation Items

Date: 2026-11-15
Context: Items from the 2026-11-15 batch that need user input before implementation (per instruction: "If a task requires disambiguation from me, note it in a doc, and move on.").

---

## 1. Photo Flagger (`flag_color` string)
- **What's clear**: Store a string `flag_color` on `scraped_photos` (requires schema v10 -> v11 migration).
- **Needs your input**:
  1. **Color set**: Fixed palette (e.g., `red`, `yellow`, `green`, `blue`, `purple`, `none` — with Japanese labels 赤/黄/緑/青/紫) or free-form string? *(Recommended: fixed 5-color palette + clear/none so chips/filters stay clean.)*
  2. **UI placement**: Where should the flagger live?
     - `PhotoDetailActivity` (top bar or next to `金` button)?
     - Badge on `PhotoThumbnailAdapter` thumbnails?
     - Filter chip in `PhotoLibraryActivity` (e.g. filter by flag color)?

## 2. `わし` Translation + Custom Definition & Notes + Runtime Lookup
- **What we found in the codebase**:
  1. **Why `わし` was translated wrong**: In `dictionary.db` (`words` table), `わし` has 3 entries:
     - `和紙` (`わし`, `is_common = 1`): *"washi; Japanese paper"*
     - `鷲` (`わし`, `is_common = 1`): *"eagle (Accipitridae family)"*
     - `儂` (`わし`, `is_common = 0`): *"I; me"*
     Because `DictionaryRepository.lookupJapaneseWords` orders by `is_common DESC` and `BundledDictionaryProvider` takes the first match when `expression == "わし"`, it picked `和紙`.
  2. **Saved vs Runtime Lookup**: Currently `MiningPipelineRunner.ensureCanonicalItemExists` (`MiningPipelineRunner.kt:459-473`) **saves** `resolvedMeaning` into `extracted_items.meaning` at word creation time — it is NOT a runtime join with `dictionary.db`.
- **Needs your input**:
  1. **Runtime lookup**: Should `WordGrammarDetailActivity` (and `WordDefinitionAdapter`) look up `dictionary.db` at runtime for the regular dictionary definition (using `extracted_items.meaning` only as fallback), OR also let you pick/pin which dictionary entry (e.g. `儂` vs `和紙`) is primary?
  2. **Custom definition & notes**:
     - Should `user_definition` **replace** the dictionary meaning or display **alongside/above** it? *(Recommended: show `user_definition` at top, dictionary meaning below it, and `user_notes` in its own section.)*
     - Should `user_definition` and `user_notes` be exposed as mappable fields in the Anki Card Creation Wizard?

## 3. Word Folders + "Skip Inbox" Toggle
- **What's clear**: You want folders for words (to clear the inbox without creating Anki cards for everything), with a toggle on folder creation: *"future occurrences of the word skip the inbox"*.
- **Needs your input**:
  1. **Cardinality**: Can a word belong to **one** folder (1:1 `folder_id` on `extracted_items`), or **multiple** folders/tags (many-to-many)? *(Recommended: 1:1 folder for simplicity, like real folders.)*
  2. **Existing inbox occurrences**: When you move a word into a "skip inbox" folder, should its **existing** `UNSEEN` occurrences in the `出現` inbox be automatically marked `SEEN` (cleared from inbox immediately), or only *future* occurrences? *(Recommended: clear existing `UNSEEN` occurrences too so moving a word actually clears your inbox!)*
  3. **UI placement**: Where should folders live in `ExtractedListActivity`?
     - A 3rd tab (`出現` / `単語` / `フォルダ`), or a folder filter/chip bar inside `単語`?

## 4. `殺せ` -> `殺せる` / `知れるーー`
- **What we fixed**:
  - `ーー` was extracted as a bogus word because `KuromojiWordAnalyzer.isValidWordCandidate` allowed any kana string of length >= 2 (`"ーー"`). Fixed and pinned in `KuromojiWordAnalyzerTest`.
- **What we observed**:
  - In Kuromoji IPADIC, `"殺せ"` alone tokenizes as `殺す`, whereas `"知れ"` tokenizes as `知れる` (IPADIC prefers the ichidan verb `知れる` over godan `知る` for the `知れ` surface).
- **Needs your input**:
  - Do you have the exact sentence / photo ID where `殺せ` was picked up as `殺せる`? That will let us reproduce the exact Kuromoji token sequence and add a targeted fix + regression test.
