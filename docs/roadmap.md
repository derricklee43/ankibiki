# AnkiBiki improvement roadmap

## Purpose

Prioritize the work that makes AnkiBiki a dependable personal learning tool, not
just a larger collection of features. These are recommendations from the project
assessment, not scheduled commitments. Items remain open until their acceptance
criteria are verified; owners and dates have not been assigned.

The milestone that opened this roadmap —

> I can collect material, correct it once, trust that my corrections and review
> decisions survive, and turn the useful results into study material with less
> effort than doing it manually.

— is **met as plumbing** (2026-11-13: recovery, safe reprocessing, correction UX,
wizard, gate, gold marks). The next one:

> The pipeline catches the recognition errors it can *prove*, my correction
> effort per photo trends toward zero, and every automatic change is visible,
> measurable, and reversible.

The original feature designs remain in
[the photo-mining plan](saikutsu-photo-mining-plan.md) and
[the Chinese word/hanzi sync plan](chinese-word-hanzi-sync-plan.md).
This roadmap complements those designs rather than replacing them.

## Current assessment (refreshed 2026-11-14 — P0: this section no longer restates benchmark numbers)

- The capture-to-study loop is **closed end-to-end**: photo mining -> transparent
  correction UX -> explicit gold marking (金 button) -> auto-applying quality gate ->
  card-creation wizard writing real AnkiDroid notes. Data trust (§1) and transparency
  (§3) shipped; recovery bundles are proven against the live corpus.
- **Extraction quality is now the bottleneck, not plumbing.** Gold-harness numbers
  live ONLY in [docs/extraction-pipeline-state.md](extraction-pipeline-state.md) —
  P0 rule (2026-11-14): the roadmap points, never restates (the prior restatement
  carried stale rev-5 figures 27/73 here while the same file targeted 31/126); the
  dominant classes — phantom-`。` stitching failures and nameplate leaks — are OUT of
  the correction gate's reach (it fixes recognition-substitution islands only).
- The S003-D' gate ships auto-apply (user decision 2026-11-13) behind a per-island
  audit table: its real-world precision is now an EMPIRICAL question the gold loop
  answers. The spike's n=36 bounds failure at ~8%; ~200 marked-sample evidence gates
  the next rollout decision.
- Nameplate exclusion stays parked; the gate runs behind a deliberately over-excluding
  structural proxy, and the policy's benchmark experiment is still its prerequisite.
- Language support is asymmetric in every direction: Japanese has morphology, a grammar
  catalogue, the gold set, AND the gate; Chinese has dictionary segmentation only — no
  grammar extraction and (deliberately) no gate, because the evidence base is JMdict.
- Lifecycle robustness (§5) is untouched: long scans are not resumable; rotation,
  backgrounding, and process-death behavior are untested.

## Status tracks (2026-11-13: user parked text processing to build features)

### ⏸ PARKED — extraction & text processing

User decision 2026-11-13: the pipeline work freezes here until un-parked.
**docs/extraction-pipeline-state.md** is the single re-entry kit: measured
state, decision log, gated invariants, backlog with entry points, and the
re-entry protocol. Parked items, biggest lever first:

1. **Stitching fixes** for parser-bound photos (`CjkSentenceParser`:
   phantom-`。` guard + wrap stitching; target = segmentation beyond 31/126).
2. **`gate_score.py`** — score `ocr_corrections` against marked gold; 19 applied
   samples already accruing toward the ~200-sample auto-apply checkpoint.
3. **Claims ledger** — 20 pending 正しい/違う rulings, H9v2 merge proposals,
   then the A6 fuzzy-recurrence revisit. (Spike 004 verdict: auto-exclusion NOT GO;
   manual strip + rulings is the mechanism.)
4. **Fixture gaps** — menu/furigana/Chinese examples + wrong-candidate precision
   metric + correction-effort measure.
5. **Chinese gate port** (CC-CEDICT evidence base; deliberately last).

### 🟢 ACTIVE — feature tracks (proposals; pick what fits the itch)

A. **Card-making loop** (§4 extension): batch "create cards" from the unseen
   list (the wizard is one-word-at-a-time today); an "add to existing note"
   path (append a newly mined sentence to a card that already exists); wizard
   validation against the real collection in a device session.
B. **Review & organization UX** (new): library filters (unseen / 金 / stale /
   language), a claims-queue screen to drain pending nameplate rulings in batches,
   bulk photo ops (mark, ignore, delete), and a gold dashboard (marks count,
   headline trend line).
   (PARTIAL 2026-11-13: **sentence word editor** shipped — 編集 on the single
   sentence view swaps chips for an occurrence table (word input + 削除 per row,
   追加 row, 保存/取り消し). Semantics pinned by user: free-form adds allowed,
   deletes are soft (re-add restores), renames preserve review state; overlap
   allowed (みお震わせる alongside ふるわせる — occurrences are a SELECTION, not
   a segmentation). New schema v10 `is_manual` provenance marks editor rows;
   ONLY those survive candidate reconcile (`modified_by_user_flag` keeps its
   §1 "user-authored text" meaning and does not protect — regression-pinned).
   **Gold = finalizing stamp** (same day): rescan hidden AND refused in code
   (`rescanPhoto` -> SKIPPED), word re-derivation coerced text-only on both
   reprocess paths, stale chip suppressed. 7 instrumented specs; 73/73 suite.)
   (SHIPPED 2026-11-13: **word list page split into Occurrences/Words tabs** —
   user-specified inbox model: the 出現 tab is the inbox (既読/非表示/アーカイブ
   unchanged; hiding skips the inbox, the Words tab still lists the word); the 単語/
   文法 tab shows one row per canonical item (word + reading + meaning + frequency),
   kana gojūon order for JP / toneless pinyin alphabet for ZH, HIDDEN included,
   ARCHIVED excluded, sort+visibility chips hidden there while search still filters.
   Tap opens the existing word detail via the newest live occurrence (-1 when none —
   guarded). `ItemOrdering` is a hand-rolled weight table (deterministic JVM ==
   device, unlike Collator); `getCanonicalItemDefinitions` joins MAX(id) over
   KEEP-photo occurrences. 11 JVM specs + 4 instrumented specs; full unit suite +
   77/77 emulator suite green; live walkthrough verified on emulator-5554, including
   gojūon order over the real corpus and the zero-occurrence tap path.)
   SPEC 2026-11-14 (user go; from the agent review findings):
   - [x] **B1 — Claims queue screen.** Dedicated screen listing pending
         `nameplate_claims` rows (standalone v8 + inline H9v2 merge proposals)
         with the plate text, a deep-link to the owning photo, and 正しい/違う
         per row; pending-count badge at the entry point. The flywheel currently
         only spins when a photo happens to be open; 20+ rulings are accruing
         unlabeled.
         Exit criteria: (a) instrumented spec — queue drains pending to 0, every
         ruling writes a verdict row, ruled plates never re-surface on rescan
         (extends the existing hide-for-good assertions); (b) live walkthrough —
         real pending count (SELECT at run start) drops to 0 on device/emulator;
         (c) badge count == `SELECT COUNT(*) WHERE verdict IS NULL`, proven by a
         test reading both sides.
         SHIPPED 2026-11-14. The screen: `ClaimsQueueActivity` (名前の板の審査) —
         one card per pending row, standalone plates monospace, inline proposals
         rendered 「sentence」から「plate」をカット → 「remainder」 (remainder preview =
         the shipped H9v2 rule over the REAL gate lexicon, enriched async so rows +
         badge render from fast SQL alone); per-row 正しい (filled) / 違う (outlined) /
         写真を開く icon deep-links the owning photo. Entry: 採掘-tab card 名前の板の候補
         showing 未審査 N件, visible iff pending > 0, refreshed on resume. Rulings run
         through the new `NameplateRuling` shared use case — extracted VERBATIM from
         PhotoDetailActivity's inline handler (both screens now rule identically;
         the photo view was rewired to it, zero behavior change).
         Evidence: (a) `ClaimsQueueInstrumentedTest` 6 specs — query lists both kinds
         newest-photo-first with the deep-link join fields and excludes ruled; badge
         == raw `SELECT COUNT(*) WHERE verdict IS NULL` read BOTH sides (criterion c);
         drain-to-zero writes verdict + verdict_at on every row; queue-ruled standalone
         plates survive rescan hidden with no re-pending; queue-ruled inline cuts excise
         through the real lexicon (tripwire assert: カイン must be out of it) and never
         re-propose; 違う keeps the sentence; (b) emulator-5554 live walkthrough on the
         real corpus copy: entry badge 未審査 20 件 == DB count at run start, 違う tap
         → 19, deep-link opened the photo view and returned, 19 正しい taps drained the
         header to 未審査 0 件 + empty state, entry card gone after BACK (onResume
         re-read), DB readback pending=0 total=108 correct=105 incorrect=3
         missing-verdict_at=0, マルス率いる merged line excised from extracted_text;
         full instrumented suite 83/83 OK (77 prior + 6 new), JVM suite green.
         CAVEAT: the walkthrough's 20 rulings are emulator-copy test data; the Pixel's
         own 20 pending rows are UNTOUCHED — the production label stream for spike 004
         comes from the user's real rulings, which still await him on-device.
   - [ ] **B2 — Gold dashboard.** One screen: 金 count, `ocr_corrections`
         applied/rejected shown against the ~200-sample auto-apply checkpoint,
         headline per benchmark run (static table read from the fixture record —
         NOT live OCR), correction-effort proxy (touched-before-gold per photo).
         Also the guard against the extraction park going permanent: the parked
         track gets a number that visibly accrues.
         Exit criteria: (a) dashboard values match a scripted read of the same
         corpus DB copy exactly (instrumented spec over both sides); (b) "gate
         evidence N/200" renders from `ocr_corrections` counts; (c) roadmap §2
         parked item 2 gets a pointer to the dashboard as its checkpoint view.
C. **Lookup upgrades** (from Secondary): whole-word lookup for multi-character
   CJK queries while keeping the character-exploration path; less
   collection-specific deck/note-type configuration.
   (DONE 2026-11-13, word-list scope: romaji/kana/kanji/pinyin search in the
   抽出された単語 list via pure `Romaji`/`ItemSearch` — no library dependency: stored
   readings make reading-inference libraries (Kanayanize/ICU4J) unnecessary; the
   only missing bridge was a deterministic kana->romaji table with full+compact
   variants ("toukyou"/"tokyo"). Bounded FUZZY tier added same day: ASCII queries >=4 chars
   tolerate 1 edit (<8 chars) / 2 edits (>=8), windowed over reading variants, results ordered
   exact-first; kanji/kana paths stay exact-containment (fuzzy CJK = noise). Same bridge
   applies to the 引き tab later if wanted.)
D. **Lifecycle & settings surface** (§5): resumable scans + interruption tests;
   a Settings surface hosting the correction-gate kill switch and future toggles
   (today both live only in code).
   SPEC 2026-11-14 (user go; from the agent review findings):
   - [ ] **D1 — A11y capture self-check.** `AnkiAccessibilityService`'s WebView
         warm-up depends on Chromium internals (AnkiDroid's reviewer; Chrome
         updates move the a11y trees) and has NO detector for its own failure —
         a silent regression reads as an app bug. Change: on bubble trigger,
         record whether the a11y selection arrived fresh vs stale/none
         (timestamp compare); Settings surfaces
         `selection capture: OK / degraded / last-success time + count` plus the
         AnkiDroid package version logged alongside.
         Exit criteria: (a) instrumented spec — faked fresh selection event flips
         status to OK, silence past threshold flips to degraded; (b) Pixel manual
         test written into this checkbox — bubble tap on a live AnkiDroid reviewer
         without manually copying fires the lookup AND Settings shows that success
         with a timestamp; (c) forced-degrade case (fake stale timestamps) shows
         the degraded state before any debugging happens. Ship BEFORE the next
         AnkiDroid update — its value requires proving on a working system.
   - [ ] **D2 — MiningRepository read/write split (mechanical).** Extract all
         read queries out of `MiningRepository.kt` (1780 lines) into
         `MiningQueries`; writes/reconcile/migrations stay. Do BEFORE D3 —
         the lifecycle work will touch this file, and the split also cheapens
         diffs from parallel agent sessions.
         Exit criteria: (a) full JVM + instrumented suites green with ZERO test
         bodies edited (proves the split is mechanical); (b) both files < 900
         lines; (c) the commit diff is moves-only.
   - [ ] **D3 — Async consolidation + §5 interruption tests (the expensive one).**
         Replace the 11 hand-rolled per-activity
         `Executors.newSingleThreadExecutor()` + `runOnUiThread` sites with one
         shared pattern (coroutines + `lifecycleScope` if kotlinx is acceptable,
         else a shared executor wrapper class), then write the deferred §5 tests.
         Exit criteria: (a) `grep -r newSingleThreadExecutor app/src/main` returns
         0 after migration; (b) three instrumented tests pass on emulator —
         rotation mid-scan keeps working with no duplicate work; process death
         mid-rescan leaves the previous corpus intact (reuse existing
         rollback-protection assertions); backgrounded-then-returned scan completes
         and the UI reconnects; (c) §5's two checkboxes flip with the three test
         names in the parenthetical.

Suggested ordering: **B → A → D**, with C as occasional quick wins — B first
because the ledger queue and triage are the daily loop right now while the
pipeline side is frozen, and A turns the 126-photo corpus into study material,
which is the point of all of it. Two dependencies from the 2026-11-14 spec pass:
B2 is the parked gate track's accruing checkpoint (pair it with a review date at
the ~200-sample mark), and inside D the order is fixed D2 → D3 (split before
lifecycle) with D1 independent and time-critical (before the next AnkiDroid
update).

## 1. Finish the trust foundation — highest priority

### Recovery

Done: the verifiable bundle format, restore/diff tooling, in-app export/restore UI, documented
workflow, isolated-install restore demos (including a real-corpus round-trip), and photo bytes
staged from the device with a verified off-machine copy (2026-11-12 — 100-photo/199 MB bundle in
Documents + OneDrive, see docs/recovery.md). Remaining: the fresh-install photo re-link helper.
See [docs/recovery.md](recovery.md) (`scripts/backup_bundle.py`).

- [x] Provide an accessible corpus export and restore workflow.
      (RecoveryManager; Settings → Export/Restore, SAF-picked bundle location;
      demonstrated on API 35 emulator 2026-11-10, bundle-compatible with the PC tool)
- [x] Support an independent, off-device copy rather than relying exclusively on
      the gitignored, machine-local development backups.
      (2026-11-12: full bundle WITH all 100 source photos — 199 MB, hash-verified — placed at
      C:\Users\derri\Documents\AnkiBikiBackups\ AND C:\Users\derri\OneDrive\AnkiBikiBackups\
      (cloud sync); phone snapshot backups/20261112-133025, integrity ok)
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

> **PARKED 2026-11-13** (user) — everything in this section and its child reports
> freezes under docs/extraction-pipeline-state.md; the gate keeps auto-applying
> and accruing audit rows passively while it sleeps.

- [ ] Build a small, representative, appropriately licensed or private fixture
      set from actual use: game dialogue, speaker labels, menus, furigana, unusual
      punctuation, poor photos, line wraps, and Japanese/Chinese examples.
- [x] Label expected sentence boundaries and useful word/grammar candidates.
- [ ] Track recognition errors, sentence segmentation, candidate precision/recall,
      and the manual correction effort required to obtain useful study material.
      (recognition/segmentation/char-distance tracked in the gold harness; still missing:
      wrong-candidate precision metric; correction-effort proxy upgraded from "touched"
      to explicit 金 marks + ocr_corrections rows, 2026-11-13; marks NOT seeded at v7 —
      gold era starts from zero by user decision, committed fixtures remain the baseline
      until re-marking drives the next regeneration)
- [x] Keep regression fixtures for discovered failures and repeat a real-image
      rescan when changing recognition or layout handling.

v1 status (2026-11-10): nine labeled fixtures and a two-layer benchmark are in
place — see [the baseline report](benchmarks/2026-11-10-extraction-baseline.md).
Segmentation exact is 2/9 (only locked regressions pass); dominant error classes
are nameplate leakage (5/9) and OCR-hallucinated `。` blocking stitching (3/9).
Gaps: no menu/furigana/Chinese fixtures yet, no wrong-candidate (precision)
metric yet, correction effort measured only via the user-edit-flag proxy.
Iteration plan against the 46-photo user-corrected gold set (gold
membership since 2026-11-13 = explicitly marked photos, `scraped_photos.is_gold`
via the Mark-as-gold button in the photo view — not "any touched photo";
nameplate-agnostic scoring; v1 JVM layer built 2026-11-12, rev-5 numbers in
[docs/benchmarks/2026-11-12-gold-baseline.md](benchmarks/2026-11-12-gold-baseline.md)
— sentence exact 27/73 (cleaned gold), segmentation exact 7/46, char acc 0.794, 25 provisional
nameplate leaks excluded from headline): [docs/benchmarks/gold-set-iteration-plan.md](benchmarks/gold-set-iteration-plan.md).
- Error-class census of the remaining non-exact sentences (post nameplate exclusion) →
  [docs/benchmarks/2026-11-13-error-census.md](benchmarks/2026-11-13-error-census.md):
  segmentation/boundary + orphan-garbage ≈ 75 lines is the biggest cluster, near-miss
  misreads 54; regenerate with `scripts/error_census.py` after the next benchmark run.
Provider A/B on the 46-photo set (stored == fresh-local; Cloud different but
complementary, oracle 37/74): [docs/benchmarks/2026-11-12-provider-ab.md](benchmarks/2026-11-12-provider-ab.md).
Spike 002 — word-level correction gate evaluated on gold (post-cleanup: 88% precision,
96% on the margin>=0.1 subset, zero breaks in end-to-end sim — gated subset meets the
auto-apply bar; ship suggestion-first, then calibrate in spike 003):
[spikes/002-ocr-plausibility-gate/README.md](../spikes/002-ocr-plausibility-gate/README.md).
Spike 003 — gate upgrades: dictionary-anchored band ∪ glyph-confusable band with
nameplate islands excluded = 36/36 (100% precision, 69% coverage) on cleaned gold;
S002's margin band is subsumed. Rollout verdict: ship as one-tap suggestions with
per-island evidence logging; revisit silent-apply at ~200 samples with ≤1 rejection;
nameplate exclusion should ship first or jointly:
[spikes/003-gate-upgrades/README.md](../spikes/003-gate-upgrades/README.md).
**GATE SHIPPING AS AUTO-APPLY (user decision 2026-11-13, overriding the spike's
suggestion-first rollout:** "implement the gate to auto correct — I'll continue to
annotate and we get a better idea of how well it works"). Production port in
`OcrCorrectionGate.kt`/`OcrCorrectionPass.kt`, JVM-pinned to the spike's exact output
by `OcrCorrectionGateParityTest` (72/72 islands, accepted set == 36/36/0, gold labels
stable); at mining time the pipeline fetches a second Cloud Vision read per photo and
auto-applies only S003-D'-accepted single substitutions into the LOCAL document
(segmentation stays ML-Kit-authoritative); offline/no-key = untouched text; the
provisional nameplate exclusion (short katakana/no-hiragana lines, deliberately
over-excluding — font-size proved no use, photo 1136: nameplate 214px == dialogue
median) keeps all 3 harmful islands out and ≥34/36 fixes in. Every island (applied or
not) lands in schema-v6 `ocr_corrections` (photo, offsets, A/B, margin, anchoring,
confusability, decision, line_before/after) = the annotation scoreboard for the
≥200-sample auto-apply evidence + per-write reversibility. Japanese-only for now
(JMdict evidence base); `PipelineConfig.ocrCorrectionEnabled` is the kill switch.

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
Chip semantics tightened 2026-11-13 (user report: 導出した単語が古い showing on
some cards): the old raw-lines-vs-row-keys set comparison flagged PERMANENTLY
whenever a display line yields no candidates (「はい。」) or merges sentences whose
parsed pieces already have rows — refresh couldn't clear it. The chip now asks
the pipeline "would a refresh change anything?": re-parses the text and flags
only when a candidate-bearing parsed sentence lacks rows or rows key sentences
the text no longer parses to. Pure predicate pinned in
ExtractionTransparencyTest; end-to-end in StaleChipSemanticsInstrumentedTest;
verified against the live corpus copy (2026-11-13, backup 174002): the
「はい。」-class and merged-line false positives (1136/1273/970/1156) are quiet,
genuine text-only saves still flag and clear on refresh.

Acceptance: the user can explain what happened to a photo and why its results
changed without inspecting database JSON or developer logs. Any quality indicator
must reflect available evidence, not an invented confidence score. Keep cloud
processing and image-upload behavior transparent.

## 4. Complete a minimal card-creation workflow

|x|
in the account, then pulls the note type's templated fields **with proper labels** — replacing the
index-only assumption that forced `KnownNoteTypes.kt` constants. Feasibility of labeled fields is
being spiked first: see [spikes/001-anki-field-deck-probe](../spikes/001-anki-field-deck-probe/README.md)
(compile-verified probe; runs when the Pixel is attached). The screen-by-screen design, the AnkiBiki
export-field adapter contract, and template storage are in
[docs/card-creation-wizard-plan.md](card-creation-wizard-plan.md) — implemented 2026-11-12
(plan doc carries the shipped-status header; Q1–Q8 all answered).

- [x] Define a reviewed draft containing expression, reading, meaning, selected
      sentence, and source photo/context. (`CardDraft` + `DraftAdapter` export vocabulary,
      pinned by `DraftAdapterContractTest` — 5 JVM tests.)
- [x] Allow destination deck/note-type mapping within the existing Anki integration
      boundaries, validate required fields, and check for existing cards.
      (Screen 0 deck+type picker, Screen 1 per-(deck,model) `card_templates` in mining.db
      schema v5, `findDuplicateNotes` warning; gateway E2E `CardWizardE2ETest` 5/5 on
      emulator with real AnkiDroid 2.25.3.)
- [x] Show a final preview and explicitly confirm the write to AnkiDroid.
      (Screen 2 renders via provider `previewNewNote`; nothing is written until
      確認して作成; キャンセル writes nothing — verified in UI walkthrough.)
- [x] Read back the created note and keep its association with the mined item.
      (`readNoteFields` gate + `updateExtractedItemAnkiMatch`; walkthrough created two real
      notes on the emulator and both words now show カードを表示 instead of カードを作成.
      Remaining: photo-bound fields embed via `addMediaFromUri` at confirm — coded but not
      yet walked in UI; validation against the user's own collection pending device attach.)

Acceptance: turn a reviewed unfamiliar item into a usable Anki card without
re-entering the same information elsewhere. Cancellation creates nothing; retries
must not silently create duplicate cards. Keep this a focused flow, not a general
Anki editor.

## 5. Improve lifecycle reliability and maintainability

(SPEC 2026-11-14: the two unchecked items below are pinned as track D D3 with
named exit criteria under ACTIVE track D; D1 a11y self-check + D2 repo split are
its prerequisites/companions there.)

- [ ] Move long scans toward lifecycle-independent, recoverable execution with
      useful progress, cancellation, and retry behavior.
- [ ] Test rotation, backgrounding, process death, permission loss, and interruption
      during processing; preserve editor drafts and pending save targets.
- [ ] Consolidate mutation/reprocessing rules into explicit use cases rather than
      allowing Activity callbacks to become the source of business rules.
- [x] Update the README to describe mining, data ownership, backups, cloud/offline
      processing, build prerequisites, and the limitations of supported languages.
      (2026-11-13 housekeeping pass: README now covers the mining pipeline, the cloud
      correction gate + upload transparency, gold marking, kill switch, and the
      Japanese/Chinese asymmetry.)

Acceptance: interrupted work can be resumed or safely retried without corrupting
or duplicating data, and a fresh checkout has a documented path to a working build.
Prefer incremental changes over a wholesale architecture migration.

## Secondary improvements

- [x] Single word view sentence ordering (user 2026-11-12, separate from the card wizard): sentences
      sort ascending by date added by default; ordering is adjustable and the rank lives on the word
      entity (`extracted_items`), falling back to date order when unset. The wizard's Sentence 1/2/3
      dropdown (docs/card-creation-wizard-plan.md Q8) reads the same order, so this feature should
      land before or with the wizard's first release.
      (implemented 2026-11-12, schema v4 `sentence_ranks`; verified live on API 35 emulator —
      default date-asc on a 7-sentence word, swap + boundary-disabled + cold-reopen persistence,
      rank map materialized on item; SentenceOrderingInstrumentedTest added, pending the same
      device run as the other pending suites)
- [ ] Offer whole-word lookup for multi-character CJK queries while retaining an
      explicit character-exploration path. Currently such queries are split into
      characters before lookup in `SearchService`.
- [ ] Evaluate Japanese grammar catalogue coverage and Chinese segmentation quality
      against the benchmark; do not imply that a language-routing interface means
      equivalent language support.
- [ ] Make deck/note-type configuration less collection-specific if the app is
      intended for anyone beyond its current personal setup.
- [ ] **Revisit `modified_by_user_flag`** (user doubt 2026-11-13: "not sure it is
      useful anymore"). After schema v10 its job split: `is_manual` owns word-editor
      row provenance (the only reconcile protection) and `is_gold` owns the
      finalizing stamp. What remains on the old flag is a mushy "text is
      user-authored" signal — rescan confirmation prompts, `sentenceModified`
      inference in occurrence inserts, and the photo-level edit protection.
      Audit consumers; likely collapse toward per-sentence authorship only, or
      retire in favor of deriving authorship (text != raw OCR hash). Caveats:
      existing §1 contracts pin some behaviors, and bundles carry the column —
      any retirement needs a migration ceremony, not just deletion.

These should follow demonstrated learning-workflow needs, not expand the scope
at the expense of data trust and extraction quality.

## Parked ideas

### Merge photo page + sentence page into one (pinned 2026-11-13 by user after investigation)

User model: one page, the photo optional. Investigation verdict: worth doing, but the
work is not the transplant — data loaders already overlap (the photo page is a superset)
and the word editor is sentence-scoped by construction. The long pole is **sentence
granularity**: the photo page renders all sentences as one static blob with a blob
editor, so merging means per-sentence rows (tap-to-expand chips + word editor + amend)
that stay coherent with blob-level reprocess/stale semantics and the pinned sentence-
ordering tests. Stage plan when un-parked: (1) sentence page grows the full photo panel
(gold/rescan/raw-OCR/claims/stale — moved, not rewritten; low risk), (2) text becomes
sentence rows, blob editor demoted to overflow, gallery opens photo-anchored, (3) delete
PhotoDetailActivity and rewire ~6 instrumented classes that deep-link these activities.
Open decisions to settle first: row interaction shape; keep-or-kill the blob text editor;
swipe axes (horizontal=photo vs sentence). Cost estimate: 2–3 sessions, stage 2 the big one.

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

Spike DESIGN written 2026-11-13 (execution pending candidate review round 4):
heuristics H1–H8 enumerated with corpus evidence and counterexamples, variants
A1–A5 (geometry-only / corpus lexicon / hybrid bootstrap / feature-scored /
collection opt-in), zero-casualty gate on LEGIT lines (menu control is a
recorded coverage gap — photo 983 excluded from the set by user decision),
LLM-detection explicitly rejected — see
[spikes/004-nameplate-exclusion/README.md](../spikes/004-nameplate-exclusion/README.md).

**First slice SHIPPED 2026-11-13 (user-specified UX): the human-confirmed
claims ledger, schema v8** — guardrails honored: the parser only flags
(the mining proxy's drafts that leak into the sentence list become PENDING
`nameplate_claims` rows; drafting deletes nothing), the user's 正しい/違う
verdict in the photo detail view is the opt-in (正しい hides the plate line
for good: stripped from display text, occurrence rows dropped, and filtered
from every future mine/rescan via `hiddenNameplateCores`; 違う keeps the line
and never re-claims), and raw blocks stay authoritative (table is fully
re-derivable; deleting it loses no truth). A one-shot startup backfill
re-derives pending rows for photos mined before v8 from their stored blocks.
The 81 round-4 verdicts were seeded as confirmed 'correct' on the phone
(ledger: 82 correct + 8 pending); first live ruling captured on-device
(#1407 `アプ`). This is also the production label stream spike 004 asked for:
`SELECT text, verdict FROM nameplate_claims WHERE verdict IS NOT NULL` is
per-string detector y/n data accruing with normal use. Auto-exclusion
(no per-photo ruling) remains PARKED pending the spike's go thresholds.

**Spike 004 EXECUTED 2026-11-13 — auto-exclusion NOT GO.** All variants
measured against the pre-registered bars on the 126-photo human-truth set:
best zero-casualty offline recall is the recurrence lexicon (A2) at 0.46
(precision 1.00) vs the ≥0.90 bar; gold-free inline excision hit 0.69
precision with 1 casualty (マルス王子。 — title-vocative/label-merge shape
collision) vs the ≥0.95 bar. The incumbent A0 proxy flagged 4 genuine
content lines — it must NOT become the exclusion input (roadmap shipping
note amended: replace it with A2-recurrence ∪ ledger-confirmed strings when
the gate's upstream input lands). Recall saturates because ~half of leaks
are one-off or OCR-mangled strings — exactly what the shipped claims ledger
catches and no string detector can. Verdict, tables, predictions-vs-results
and recorded deviations: [spikes/004-nameplate-exclusion/README.md](../spikes/004-nameplate-exclusion/README.md).
Manual strip + ledger rulings remain the mechanism; A6 (fuzzy recurrence)
is written up as a proposal but unmeasured. Production-go blocked twice:
bars missed AND the menu-control coverage gap stands.

**Second slice SHIPPED same day (user: "wasn't H9 supposed to count?"):
merge PROPOSALS in the ledger, schema v9.** The spike's suggestion-grade
finding — H9v2 (22/27 recall, 0.815 precision, ZERO casualties) — now runs
at mining: inline plate merges become PENDING claims with the sentence, the
proposed cut, and the resulting remainder; 正しい excises through the normal
reconciled text-edit path (word occurrences rebuilt, and every future
rescan re-applies the cut via hiddenInlineClaims — hidden for good), 違う
keeps the sentence and never re-proposes. Non-destructive until ruled (G1),
user click is the policy (G2), claims re-derive from stored blocks (G3).
Port: `NameplateInlineRule.kt` — verbatim from the spike run of record
parity spec `NameplateInlineRuleTest` (10 corpus cases incl. the guards);
inline lifecycle + v8→v9 migration covered by 20/20 instrumented tests;
backfill (v9 prefs pass) proposes for already-mined photos — verified live
(18 inline proposals; first live 違う click on #1280 オグマー persisted with
text intact). Gate input swapped per spike verdict: plate-adjacency now =
recurrence lexicon ∪ ledger-confirmed (`nameplateGateStrings`), NOT the A0
proxy. Remaining known-noise cost of suggestion grade: prefix-duplicate
proposals on tier-c pairs (グラ軍/グラ軍隊) and clause-ambiguous titles —
each is one 違う click, each click is a label.

Shipping note (2026-11-13, amended same day): AUTOMATIC exclusion stays parked —
nothing is dropped by nameplate reasoning alone; the only hides that ship are
behind the user's own 正しい verdict (v8 claims ledger above).
The auto-applying correction gate (roadmap §2) does ship a *provisional
exclusion proxy* (`OcrCorrectionPass.draftNameplateStrings`: short,
katakana-bearing, hiragana-free, punctuation-free lines): it only routes
ambiguous islands AWAY from auto-apply, never writes or deletes text, and
deliberately over-excludes. When the real policy lands, it replaces the proxy
as the gate's upstream input.
