# Design Proposal: Reparsing Words on Gold Photo Sentence Edits

Date: 2026-11-15
Status: **SHIPPED 2026-11-15** (user approved the same day). Implemented exactly as specced,
with two honest deltas recorded in the feature log: the exit-criteria tests live in the
instrumented suite (the runner is bound to real SQLite; the repo has no JVM seam for it), and
the stale-words chip / refresh affordance on the photo view was un-hidden for gold as a
sibling fix (the chip offers word reprocess — which is now the very thing allowed).

## Problem Statement

Currently, `MiningPipelineRunner.reprocessPhotoText` (`MiningPipelineRunner.kt:527-528`) and `MiningPipelineRunner.reprocessAmendedSentence` (`MiningPipelineRunner.kt:589`) treat `photo.isGold == true` as a blanket block on word re-derivation:

- Editing a sentence on a gold photo saves text-only (`wordsReprocessed = false`).
- As a result, fixing an OCR typo on a gold card (e.g. correcting a misread kanji or kana in a sentence) leaves the **erroneous word occurrence** attached to that sentence, and any newly revealed word is not extracted.

## Desired Behavior (User Spec)

1. **No image reprocessing**: Saving a sentence text change on a gold photo must NOT run OCR or touch `ocr_blocks_json`.
2. **Reparse words**: Saving the sentence change SHOULD reparse the sentence text (`analyzeCandidates(config, trimmedNew)`) and reconcile occurrences at candidate granularity.
3. **Preserve touched / manual occurrences**:
   - Rows with `is_manual = 1` (added/edited in the word editor, schema v10) MUST survive even if the analyzer does not re-produce them.
   - Rows already triaged by the user (`state = 'SEEN'` or `state = 'DELETED'`) MUST preserve their state if the candidate still exists in the new parse.
   - Un-touched (`is_manual = 0`) occurrences that are no longer produced by the new parse are fair game to remove.
   - Newly parsed candidates that do not already have a row on `(photo_id, sentence)` are inserted as `UNSEEN`.
4. **Keep gold status**: `scraped_photos.is_gold` remains `1` (`true`).
5. **Keep image-rescan block**: `MiningPipelineRunner.rescanPhoto` (`MiningPipelineRunner.kt:95-99`) MUST continue to refuse `is_gold == true` (gold is still the finalizing stamp against destructive image re-OCR).

## Proposed Code Changes

### 1. `MiningPipelineRunner.reprocessAmendedSentence` (`MiningPipelineRunner.kt:589`)
- Remove `|| photo?.isGold == true` from the early-return guard:
  ```kotlin
  // Only skip word reconciliation when the caller explicitly requested text-only save.
  if (!reprocessWords) {
      miningRepository.updateSentenceText(photoId, oldSentence, trimmedNew)
      return SentenceReprocessSummary(textSaved = true, wordsReprocessed = false,
          keptCandidates = 0, addedCandidates = 0, removedCandidates = 0)
  }
  ```
- Note: The candidate-level reconciliation loop right below (`MiningPipelineRunner.kt:607-655`) ALREADY implements the exact preservation rules:
  - `key !in keepKeys && !row.isManual` -> `deleteOccurrenceById(row.id)` (removes erroneous un-touched word).
  - `key !in keepKeys && row.isManual` -> kept (manual word survives).
  - `key in keepKeys` -> kept with its existing `state` (`UNSEEN`/`SEEN`/`DELETED`).
  - `missing` -> inserted fresh (`UNSEEN`).

### 2. `MiningPipelineRunner.reprocessPhotoText` (`MiningPipelineRunner.kt:527-528`)
- Today, `reprocessPhotoText` operates at sentence granularity (`oldSet - newSet` removes whole sentences and `newSet - oldSet` extracts new sentences).
- When a user edits a typo in `PhotoDetailActivity` on a gold photo and chooses "reprocess words", removing `!goldPhoto` allows the changed sentence to be re-extracted while keeping unchanged sentences intact.
- **Improvement**: If a sentence was edited in `PhotoDetailActivity`, sentence-level `removedSentences` would drop `is_manual = 1` rows on the old sentence string. To be 100% consistent with rule #3, `reprocessPhotoText` should preserve `is_manual = 1` rows when a single sentence is edited (or route single-sentence diffs through `reprocessAmendedSentence`).

### 3. UI (`TextEditorActivity` / `SentenceDetailActivity` / `PhotoDetailActivity`)
- Ensure the "単語を再解析する" (reprocess words) option in `TextEditorActivity` is enabled (and defaulted ON) for gold sentence edits, rather than coerced to text-only.

## What Does NOT Change
- `rescanPhoto` still refuses `is_gold == true`.
- `is_gold` is never cleared by text edits.
- `is_manual` remains the sole protection flag against reconcile deletion (`modified_by_user_flag` is not overloaded).

## Exit Criteria / Verification
Add JVM unit tests in `MiningPipelineUnitTest.kt` asserting:
1. Amending a sentence on a gold photo with `reprocessWords = true` does NOT invoke `imageTagger` or `textExtractor`.
2. An erroneous un-touched (`is_manual = 0`) occurrence is removed when no longer parsed from the corrected sentence.
3. A newly parsed word from the corrected sentence is added (`UNSEEN`).
4. An `is_manual = 1` occurrence survives even when not parsed from the corrected sentence.
5. `scraped_photos.is_gold` remains `true`.
6. `rescanPhoto` still returns `PhotoRescanResult.SKIPPED` on `is_gold == true`.
