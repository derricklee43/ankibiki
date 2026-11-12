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
[docs/recovery.md](recovery.md) (`scripts/backup_bundle.py`). A second-round
demo (2026-11-10) restored the REAL 50-photo Pixel corpus through the in-app
flow into an isolated emulator install and proved content-hash equality both
after restore and after an app-side re-export (demo log in docs/recovery.md).
Remaining: photo bytes staged from the device, and the fresh-install photo
re-link helper.

- [x] Provide an accessible corpus export and restore workflow.
      (RecoveryManager; Settings → Export/Restore, SAF-picked bundle location;
      demonstrated on API 35 emulator 2026-11-10, bundle-compatible with the PC tool)
- [ ] Support an independent, off-device copy rather than relying exclusively on
      the gitignored, machine-local development backups.
      (workflow exists; first real bundles of YOUR corpus now live off the phone
      in backups/manual/*.zip, content-verified against the Pixel source —
      copying one off this machine is still a manual step, and photo bytes
      need the device connected at export time)
- [x] Document recovery and demonstrate a restore into an isolated installation
      or database before calling backup/recovery complete.
      (docs/recovery.md; demonstrated 2026-11-10 on isolated databases and on a
      fresh emulator install of the app — see demonstration logs)

Acceptance: recover photos' metadata, text, user-edit flags, item visibility, and
occurrence review/deletion state; compare restored data with the source. Surface
invalid or incompatible backups without damaging the existing corpus. Make clear
whether source images themselves are included or only referenced by URI.

### Safe reprocessing

- [x] Make editor-driven photo and sentence reprocessing atomic, matching the
      rollback protection already present in the image-rescan path.
- [x] Preserve triage history for unchanged occurrences instead of deleting and
      recreating them as unseen entries.
- [x] Define reconciliation rules for changed or removed sentences and explicitly
      communicate any review-state reset.

Acceptance: injected failures leave the previous corpus intact; unchanged saves,
reprocessing, and rescans do not unexpectedly resurrect reviewed/deleted entries.
Preserve manual-edit protection unless the user explicitly authorizes replacement.

Design consideration: evaluate first-class sentence records with stable IDs and
occurrence references. Sentence text is currently repeated across occurrence
rows, which makes provenance and revision reconciliation harder. Choose the
smallest schema change that satisfies the behavior; a broad rewrite is not a goal.

Domain model decision (2026-11-10): the **sentence is the primary object** and an
image is an **optional field of it** — not the reverse. The view showing a
sentence with its optional photo is the **single sentence view**
(`SentenceDetailActivity`, entered by tapping a sentence in the single word
view; parsed words render as outline chips above the sentence and each opens
its single word view). Today's schema still keys sentences as
`(photo_id, sentence)` occurrence rows with `photo_id NOT NULL`, so a photo-less
sentence exists in the UI layer (`MatchedSentence.photoId = -1`) but not yet as
a stored record; the first-class sentence table above is what makes the model
literal.

## 2. Establish an extraction-quality benchmark

- [ ] Build a small, representative, appropriately licensed or private fixture
      set from actual use: game dialogue, speaker labels, menus, furigana, unusual
      punctuation, poor photos, line wraps, and Japanese/Chinese examples.
- [x] Label expected sentence boundaries and useful word/grammar candidates.
- [ ] Track recognition errors, sentence segmentation, candidate precision/recall,
      and the manual correction effort required to obtain useful study material.
- [x] Keep regression fixtures for discovered failures and repeat a real-image
      rescan when changing recognition or layout handling.

v1 status (2026-11-10): nine labeled fixtures and a two-layer benchmark are in
place — see [the baseline report](benchmarks/2026-11-10-extraction-baseline.md).
Segmentation exact is 2/9 (only locked regressions pass); dominant error classes
are nameplate leakage (5/9) and OCR-hallucinated `。` blocking stitching (3/9).
Gaps: no menu/furigana/Chinese fixtures yet, no wrong-candidate (precision)
metric yet, correction effort measured only via the user-edit-flag proxy.

Acceptance: establish a baseline and explicit quality targets before claiming
accuracy improvements. Distinguish OCR spelling, layout grouping, tokenization,
and grammar matching; a fix at one stage must not be presented as fixing all four.
Passing implementation tests is necessary but is not an accuracy benchmark.

## 3. Make correction and failures understandable

- [x] Show raw OCR alongside parsed/user-edited text, without overwriting either
      merely to display it.
- [x] Record and expose the OCR provider used, fallback events, and meaningful
      stage failures; distinguish unavailable processing from genuinely empty text.
- [x] Preview what reprocessing will replace and what existing work it will retain.
- [x] Clearly indicate when text was saved without refreshing derived word/grammar
      occurrences, and offer a deliberate way to bring them up to date.

Implemented 2026-11-10 and verified on the emulator UI: photo detail now shows
an `OCR: <provider>` line (Cloud Vision / on-device / pre-scan / unknown for
legacy scans) plus fallback and recognition-failure notes persisted in
`ocr_blocks_json`; an expandable raw-OCR section (monospace, selectable, never
overwritten); a `derived words are stale` chip when the display text and
occurrence row keys diverge, with a deliberate refresh action; and a preview
dialog before every word-reprocess reporting kept/new/removed sentences and
explicitly warning which removed sentences were hand-edited or carry
reviewed/deleted history. Follow-ups: fold the editor's intent modal and the
impact preview into one dialog; add the same preview to the sentence-level
editor path (still post-hoc toasts only).

Acceptance: the user can explain what happened to a photo and why its results
changed without inspecting database JSON or developer logs. Any quality indicator
must reflect available evidence, not an invented confidence score. Keep cloud
processing and image-upload behavior transparent.

## 4. Complete a minimal card-creation workflow

Plan (user, 2026-11-10): the flow starts with a **deck-selection wizard** that lists every deck
in the account, then pulls the note type's templated fields **with proper labels** — replacing the
index-only assumption that forced `KnownNoteTypes.kt` constants. Feasibility of labeled fields is
being spiked first: see [spikes/001-anki-field-deck-probe](../spikes/001-anki-field-deck-probe/README.md)
(compile-verified probe; runs when the Pixel is attached). The screen-by-screen design, the AnkiBiki
export-field adapter contract, and template storage are in
[docs/card-creation-wizard-plan.md](card-creation-wizard-plan.md) — design only, still open questions.

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

## Parked ideas

### Speaker-nameplate exclusion (pinned 2026-11-10 — needs evidence and an opt-in
design before any production code)

The baseline report (docs/benchmarks/2026-11-10-extraction-baseline.md) shows
nameplate leakage as the largest segmentation error class (5/9 fixtures). Not
baked in: nameplate exclusion is **not generalizable to all Japanese media** —
short isolated lines are the actual content in menu screens, and may be headings
or legitimate one-word utterances elsewhere.

Agreed guardrails before this may ship:

- The parser only *flags* "short, isolated, punctuation-free block adjacent to
  the reading flow" as a candidate; it never deletes. Dropping is policy.
- Policy is opt-in per photo (the corpus mixes media within one language),
  default KEEP (today's behavior), with DEMOTE (treat as speaker label,
  exclude from mining) and DROP variants. A boolean is the wrong primitive.
- Raw `ocr_blocks_json` stays authoritative, so any policy can be flipped in
  reprocessing without re-OCR; turning it off reproduces current behavior
  exactly.

Prerequisite step (touches no production code): a benchmark-only experiment
running DEMOTE/DROP over the fixtures plus menu-style photos, measuring what
exclusion would win and what legitimate content it would damage. Proceed only
if the wins are real and menu/short-line casualties are zero.
