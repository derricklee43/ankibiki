# Roadmap audit notes

Dated analyses behind roadmap items. Newest first. These are investigations, not
specs — re-entry actions they propose need a user go before any code.

## 2026-11-14 · `modified_by_user_flag` — is it still useful given the gold marker? (roadmap Secondary)

Question (user): now that `is_gold` exists as the explicit finalizing stamp and
`is_manual` owns word-editor provenance, does the old flag carry any information?

Method: every writer and reader traced in `app/src/main/java` (path:line below),
tests enumerated, then the live corpus copy on emulator-5554 measured (174 KEEP
photos, 1,002 occurrence rows — the B-era copy of the real corpus).

### Verdict

Keep the photo-level flag. It has exactly one load-bearing consumer that neither
gold nor is_manual covers, and the proposed replacement (deriving authorship from
text != raw OCR) is empirically worthless — see the matrix. The occurrence-level
flag is mostly noise and semantics leaks should be fixed rather than the column
retired.

### Who reads it (photo level)

- Rescan confirmation gate — `MiningPipelineRunner.kt:100`: gold refuses FIRST
  (finalizing stamp), the flag then forces CONFIRMATION_REQUIRED for
  user-authored non-gold photos; `PhotoDetailActivity.kt:217` mirrors in UI.
  Cleared only by `clearPhotoUserModificationFlag` after an authorized rescan
  succeeds (`MiningRepository.kt:254` area). Pinned by `PhotoRescanInstrumentedTest`
  (flag stays set across untouched re-ops; clears only post-authorized-rescan;
  CONFIRMATION gate both directions).
- Reprocess impact preview — `ExtractionStatus.preview` `editedSentencesInRemoved`
  (the §3 "which removed sentences were hand-edited" warning) reads
  `getOccurrenceSentenceRows` MAX per-sentence flag (`MiningQueries.kt:343`).
- Gold-dashboard correction-effort proxy — `MiningQueries.kt:274`
  (`gold ∧ flag` = the 129/151 number). Display metric only.
- Provenance propagation — `insertOccurrence` coalesce (`MiningRepository.kt:849`),
  `reprocessPhotoText` blanket (`MiningPipelineRunner.kt:545`), sticky OR at upsert
  (`MiningRepository.kt:222`). No behavior gates on these reads; they keep the
  flag alive per-sentence.

### Who writes it (photo level)

- `updatePhotoExtractedText` sets it when text actually changed (editor saves).
- `updateSentenceText` (sentence editor) sets it on rows AND photo text.
- Nameplate INLINE ruling → via `reprocessPhotoText` → sets it.
- Nameplate STANDALONE ruling → `MiningNameplateWrites.kt` direct UPDATE of
  extracted_text → does NOT set it. (Leak 1: same user gesture, two outcomes.)
- `markPhotoDeleted` preserves; authorized rescan clears.

### Occurrence level

- Word editor `updateOccurrenceExpression` (rename) sets flag+is_manual — true
  authorship. `setOccurrenceStateForExpression` (delete/restore) defaults
  `modifiedByUserFlag=true` too (Leak 2: a review state flip marks the row
  "user-modified"; review is not authorship; is_manual is the protection that
  matters and the flag is set as a side effect).
- Reprocess inserts blanket-flag EVERY freshly derived row when the photo was
  ever modified (`MiningPipelineRunner.kt:554`: `photoModified || sentence in
  changedSentences`) — Leak 3: after one whole-photo edit, per-sentence
  authorship signal is diluted to "the whole photo".

### Corpus measurement (emulator copy, 2026-11-14)

Photo matrix over 174 KEEP photos (`extracted_text == ocr_blocks_json.fullText`
as text-diff proxy, × flag × gold):

| text vs stored raw | flag | gold | photos |
|---|---|---|---|
| differs | 1 | 1 | 129 |
| differs | 1 | 0 | 9  |
| differs | 0 | 1 | 20 |
| differs | 0 | 0 | 9  |
| same | 0 | 0 | 5 |
| same | 0 | 1 | 2 |

Readings:
- **Derivation replacement is dead**: 167/174 (96%) photos have text != raw OCR
  while flag=0 or for machine reasons (sentence stitching rewrites display text,
  gate corrections, nameplate excisions — sampled diff-without-flag photos
  1249/1253/1256/1407 show stitching/OCR-shape rewrites; 1407 is the known
  standalone-ruling photo). Deriving "user-authored" from text!=raw would prompt
  rescan confirmation on essentially every photo. The stored `fullText` IS the
  post-gate working document, so even the gate's writes land "raw" — but the
  parsed-sentence join in `displayExtractedText` (`MiningPipelineRunner.kt:268`)
  rewrites anyway.
- **The flag's unique live population is the 9 flag-and-not-gold photos** — exactly
  the ones the rescan-confirmation guard exists for. Gold covers 151 differently
  (certified, refuse-aloud); 22 gold photos have flag=0 — marked without editing,
  proving gold ≠ superset of the flag's meaning.
- **Occurrence-level flag is mostly inherited noise**: 628/1002 rows are
  flag=1∧is_manual=0; only 1 row carries is_manual (the editor saw almost no use
  on this copy). 32 flagged photos show per-sentence MIXED flags — the only place
  the row flag beats the photo flag, and it's precisely what Leak 3 erodes over
  time.

### What pins retirement against it

- `RecoveryManager.kt:381,390` and `scripts/backup_bundle.py:61,68` carry the
  column in REQUIRED_COLUMNS/MINING_COLUMNS for BOTH tables — retirement is a
  migration ceremony (v11), and every pre-v10 bundle must keep restoring.
- `scripts/build_gold_fixtures.py:6` — historical note: gold membership WAS derived
  from this flag pre-v7; the user killed that inference explicitly (gold = declared
  marking, era starts from zero). Reintroducing any flag-derived membership would
  reopen the semantic the user closed.
- `UserTextEditsInstrumentedTest`, `SafeReprocessingInstrumentedTest`,
  `PhotoGoldFlagInstrumentedTest`, `OcrCorrectionInstrumentedTest`,
  `NameplateClaimsInstrumentedTest` reference the flag in fixtures/asserts.

### Follow-ups the audit suggests (need a go; none implemented)

1. Decide nameplate-ruling provenance semantics: rulings are user-APPROVED machine
   edits, not authorship. Either make standalone rulings set the flag (consistent
   with inline) or stop inline rulings from setting it (preferred: keep the flag's
   meaning "human typed"; a ruling is already recorded as a verdict row).
2. Stop defaulting `modifiedByUserFlag=true` on state-flip editor ops — is_manual
   is the protection; review is not authorship. (Check the editor test pins first.)
3. Narrow the reprocess blanket: propagate per changed sentence, not per photo.
   Restores the honesty of the impact-preview warning.
4. Dashboard proxy: fine as-is (the number is "gold ∧ ever-user-typed"); could add
   gold-and-never-typed count beside it if the effort metric matters later.
