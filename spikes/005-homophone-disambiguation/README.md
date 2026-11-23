# Spike 005: Homophone & Polysemous Surface Disambiguation

Date: 2026-11-15
Status: Design (ready to run experiment on live corpus snapshot)
**PARKED 2026-11-23 (user): the whole homophone line lives under Parked ideas in
docs/roadmap.md; this design stands as written, un-run, until the user re-asks.**

## Problem Statement

Surface-identical Japanese words/particles with completely different meanings and grammatical roles currently collide in AnkiBiki:

- Example: **でも**
  1. Conjunction (`接続詞`): "but; however; even so" (e.g. 「でも、私は行く。」)
  2. Particle (`助詞・副助詞`): "or something; even" (e.g. 「コーヒーでも飲みましょうか。」)

Today:
- `KuromojiWordAnalyzer` (`KuromojiWordAnalyzer.kt`) filters by `ALLOWED_POS1` (which includes `接続詞` but excludes `助詞`), deduplicates by `cleanExpression`, and discards `token.partOfSpeechLevel1` / `token.partOfSpeechLevel2`.
- `JlptGrammarAnalyzer` matches surface/regex patterns across the whole sentence without consulting Kuromoji POS tags.
- `extracted_items` has primary key `(language_tag, item_type, expression)`, so `でも` can hold only **one** canonical definition.

## Candidate Approaches

### Option A: POS-Qualified Canonical Keys (for high-ambiguity kana/surface items)
- Extend `NormalizedCandidate` to carry `pos1` / `pos2` from Kuromoji.
- Allow specific `助詞` or ambiguous surfaces to be keyed or disambiguated by POS (e.g. `でも (接続詞)` vs `でも (助詞)`), or map `助詞` `でも` directly to `ItemType.GRAMMAR` (`~でも`).

### Option B: Route Grammatical Particles to `GRAMMAR` + POS-Gate `JlptGrammarAnalyzer` (Recommended to test first)
- Keep `でも` (`接続詞`, "however") as `ItemType.WORD`.
- Treat `でも` (`助詞・副助詞`, "or something") as `ItemType.GRAMMAR` (`~でも`), gated by Kuromoji token POS (`pos1 == "助詞"`):
  - When Kuromoji tags `でも` as `接続詞` -> extract `WORD("でも")`, suppress `GRAMMAR("~でも")`.
  - When Kuromoji tags `でも` as `助詞` -> suppress `WORD("でも")`, extract `GRAMMAR("~でも")`.
- **Zero schema migration** (`ItemType.WORD` vs `ItemType.GRAMMAR` already separates the primary key `(language_tag, item_type, expression)`!).

### Option C: Multi-Sense Picker on `extracted_items` (General Homophones)
- For true same-POS homophones (e.g. kana-only `かぎ` = 鍵 "key" vs 鉤 "hook", or `わし` = 和紙 vs 鷲 vs 儂):
  - Store all candidate dictionary senses on the item (or fetch at runtime), and allow the user to pin the intended sense per occurrence/sentence (stored as `sense_index` or `selected_gloss` on `item_occurrences`).
  - This also cleanly solves `わし` / `ザマ` / `カギ` ambiguity!

## Experiment Plan

1. **Corpus sample**: Pull 30+ sentences containing `でも`, `のに`, `から`, `ばかり`, `ほど` from the latest `backups/*/mining.db`.
2. **Measure Kuromoji IPADIC POS accuracy**:
   - Run `Tokenizer().tokenize(sentence)` on each sample and hand-label whether `でも` is `接続詞` ("however") vs `助詞` ("or something").
   - Compute precision/recall of Option B's POS gate.
3. **Measure `JlptGrammarAnalyzer` false-positive reduction**:
   - Compare current regex-only grammar matching vs POS-gated matching on `でも`.
4. **General homophone audit**:
   - Count how many `extracted_items` with kana-only expressions have >1 distinct JMdict entry in `dictionary.db` (to size Option C).

## Exit Criteria
- Report POS-gate precision/recall on the corpus sample.
- Confirm whether Option B alone resolves `でも` without a schema bump, and scope Option C for same-POS homophones if >10% of kana items are ambiguous.
