# AnkiBiki improvement roadmap

## Purpose

Prioritize the work that makes AnkiBiki a dependable personal learning tool, not
just a larger collection of features. These are recommendations from the project
assessment, not scheduled commitments. Items remain open until their acceptance
criteria are verified; owners and dates have not been assigned.

The next product milestone is:

> I can collect material, correct it once, trust that my corrections and review
> decisions survive, and turn the useful results into study material with less
> effort than doing it manually.

The original feature designs remain in
[the photo-mining plan](saikutsu-photo-mining-plan.md) and
[the Chinese word/hanzi sync plan](chinese-word-hanzi-sync-plan.md).
This roadmap complements those designs rather than replacing them.

## Current assessment

- Personal Anki lookup, offline dictionaries, and source-photo context provide
  useful functionality today.
- Photo mining is an **assisted workflow**, not yet a reliable unattended source
  of study-ready text. Successful execution does not establish linguistic accuracy.
- Manual editing, provenance flags, rescan confirmation, and development-time DB
  backups improve trust, but do not complete recovery or revision management.
- New-card creation from mined material is intentionally deferred in the original
  plan; its absence still leaves the practical capture-to-study loop incomplete.
- Language support is asymmetric: Japanese has morphological analysis and a
  limited regex grammar catalogue; Chinese uses dictionary-based segmentation
  and currently has no grammar extraction.

## 1. Finish the trust foundation — highest priority

### Recovery

In progress: the verifiable bundle format, restore/diff tooling, documented
workflow, and an isolated-database restore demonstration are in
[docs/recovery.md](recovery.md) (`scripts/backup_bundle.py`). Remaining: the
in-app export/restore UI, photo bytes staged from the device, and the
fresh-install photo re-link helper.

- [x] Provide an accessible corpus export and restore workflow.
      (RecoveryManager; Settings → Export/Restore, SAF-picked bundle location;
      demonstrated on API 35 emulator 2026-11-10, bundle-compatible with the PC tool)
- [ ] Support an independent, off-device copy rather than relying exclusively on
      the gitignored, machine-local development backups.
      (workflow exists — export to any storage incl. cloud folders; first real
      off-device copies of YOUR corpus remain, and photo bytes need the device)
- [x] Document recovery and demonstrate a restore into an isolated installation
      or database before calling backup/recovery complete.
      (docs/recovery.md; demonstrated 2026-11-10 on isolated databases and on a
      fresh emulator install of the app — see demonstration logs)

Acceptance: recover photos' metadata, text, user-edit flags, item visibility, and
occurrence review/deletion state; compare restored data with the source. Surface
invalid or incompatible backups without damaging the existing corpus. Make clear
whether source images themselves are included or only referenced by URI.

### Safe reprocessing

- [ ] Make editor-driven photo and sentence reprocessing atomic, matching the
      rollback protection already present in the image-rescan path.
- [ ] Preserve triage history for unchanged occurrences instead of deleting and
      recreating them as unseen entries.
- [ ] Define reconciliation rules for changed or removed sentences and explicitly
      communicate any review-state reset.

Acceptance: injected failures leave the previous corpus intact; unchanged saves,
reprocessing, and rescans do not unexpectedly resurrect reviewed/deleted entries.
Preserve manual-edit protection unless the user explicitly authorizes replacement.

Design consideration: evaluate first-class sentence records with stable IDs and
occurrence references. Sentence text is currently repeated across occurrence
rows, which makes provenance and revision reconciliation harder. Choose the
smallest schema change that satisfies the behavior; a broad rewrite is not a goal.

## 2. Establish an extraction-quality benchmark

- [ ] Build a small, representative, appropriately licensed or private fixture
      set from actual use: game dialogue, speaker labels, menus, furigana, unusual
      punctuation, poor photos, line wraps, and Japanese/Chinese examples.
- [ ] Label expected sentence boundaries and useful word/grammar candidates.
- [ ] Track recognition errors, sentence segmentation, candidate precision/recall,
      and the manual correction effort required to obtain useful study material.
- [ ] Keep regression fixtures for discovered failures and repeat a real-image
      rescan when changing recognition or layout handling.

Acceptance: establish a baseline and explicit quality targets before claiming
accuracy improvements. Distinguish OCR spelling, layout grouping, tokenization,
and grammar matching; a fix at one stage must not be presented as fixing all four.
Passing implementation tests is necessary but is not an accuracy benchmark.

## 3. Make correction and failures understandable

- [ ] Show raw OCR alongside parsed/user-edited text, without overwriting either
      merely to display it.
- [ ] Record and expose the OCR provider used, fallback events, and meaningful
      stage failures; distinguish unavailable processing from genuinely empty text.
- [ ] Preview what reprocessing will replace and what existing work it will retain.
- [ ] Clearly indicate when text was saved without refreshing derived word/grammar
      occurrences, and offer a deliberate way to bring them up to date.

Acceptance: the user can explain what happened to a photo and why its results
changed without inspecting database JSON or developer logs. Any quality indicator
must reflect available evidence, not an invented confidence score. Keep cloud
processing and image-upload behavior transparent.

## 4. Complete a minimal card-creation workflow

- [ ] Define a reviewed draft containing expression, reading, meaning, selected
      sentence, and source photo/context.
- [ ] Allow destination deck/note-type mapping within the existing Anki integration
      boundaries, validate required fields, and check for existing cards.
- [ ] Show a final preview and explicitly confirm the write to AnkiDroid.
- [ ] Read back the created note and keep its association with the mined item.

Acceptance: turn a reviewed unfamiliar item into a usable Anki card without
re-entering the same information elsewhere. Cancellation creates nothing; retries
must not silently create duplicate cards. Keep this a focused flow, not a general
Anki editor.

## 5. Improve lifecycle reliability and maintainability

- [ ] Move long scans toward lifecycle-independent, recoverable execution with
      useful progress, cancellation, and retry behavior.
- [ ] Test rotation, backgrounding, process death, permission loss, and interruption
      during processing; preserve editor drafts and pending save targets.
- [ ] Consolidate mutation/reprocessing rules into explicit use cases rather than
      allowing Activity callbacks to become the source of business rules.
- [ ] Update the README to describe mining, data ownership, backups, cloud/offline
      processing, build prerequisites, and the limitations of supported languages.

Acceptance: interrupted work can be resumed or safely retried without corrupting
or duplicating data, and a fresh checkout has a documented path to a working build.
Prefer incremental changes over a wholesale architecture migration.

## Secondary improvements

- [ ] Offer whole-word lookup for multi-character CJK queries while retaining an
      explicit character-exploration path. Currently such queries are split into
      characters before lookup in `SearchService`.
- [ ] Evaluate Japanese grammar catalogue coverage and Chinese segmentation quality
      against the benchmark; do not imply that a language-routing interface means
      equivalent language support.
- [ ] Make deck/note-type configuration less collection-specific if the app is
      intended for anyone beyond its current personal setup.

These should follow demonstrated learning-workflow needs, not expand the scope
at the expense of data trust and extraction quality.
