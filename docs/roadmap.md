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

**Maintenance rule (2026-11-14):** completed items live in
[the feature log](feature_log.md) — shipped work moves there verbatim with its exit
evidence; this file keeps open items, parked decisions, and dated pointers.

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
- Lifecycle robustness (§5): refreshed 2026-11-14 with track D — long work now runs on
  process-wide AppIo lanes (rotation + survives-destroy + serial pipeline pinned;
  kill -9 mid-rescan proven on the Pixel). Still open: resumable scan progress/cancel/retry
  UI, permission-loss, editor pending-save-target — see §5.

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
   (Checkpoint view SHIPPED 2026-11-14 with B2: Settings → 金ダッシュボード renders
   the accruing N/200 + applied/rejected/excluded breakdown live from `ocr_corrections`;
   the scoring script itself is still parked.)
3. **Claims ledger** — 20 pending 正しい/違う rulings, H9v2 merge proposals,
   then the A6 fuzzy-recurrence revisit. (Spike 004 verdict: auto-exclusion NOT GO;
   manual strip + rulings is the mechanism.)
4. **Fixture gaps** — menu/furigana/Chinese examples + wrong-candidate precision
   metric + correction-effort measure.
5. **Chinese gate port** (CC-CEDICT evidence base; deliberately last).

### 🟢 ACTIVE — feature tracks (proposals; pick what fits the itch)

A. **Card-making loop** (§4 extension): an "add to existing note" path (append
   a newly mined sentence to a card that already exists); wizard
   validation against the real collection in a device session.
   (The batch "create cards" proposal was pulled by user 2026-11-14 — see Parked ideas.)

B. **Review & organization UX** — ✅ COMPLETE 2026-11-13/14 (sentence word editor,
   出現/単語 tab split, claims queue B1, gold dashboard B2, library filters B3; the stale
   grid-wide facet was cut by the user at spec time). Full evidence:
   [docs/feature_log.md](feature_log.md).

C. **Lookup upgrades** (from Secondary): less collection-specific deck/note-type
   configuration (conditional — only if the app is meant to outlive this collection).

   (✅ whole-word lookup + romaji/pinyin/fuzzy search DONE 2026-11-13/14 — evidence in
   [the feature log](feature_log.md); also closed the Secondary whole-word item there.)

D. **Lifecycle & settings surface** (§5): resumable scans + interruption tests;
   a Settings surface hosting the correction-gate kill switch and future toggles
   (today both live only in code).

   (✅ D1 a11y capture self-check + D2 repository split + D3 async lanes SHIPPED 2026-11-14
   — evidence in [the feature log](feature_log.md). REMAINING here: the Settings surface
   hosting the correction-gate kill switch toggle — today it lives only in code.)

Suggested ordering (refreshed 2026-11-14: B and D's specced items shipped; the log
carries the old rationale): **A is the next frontier** — it is the point of the whole
mining loop. C's config item and D's settings-surface toggle are small conditionals.
§5's progress/cancel/retry UI waits for an itch to spend a real scan on.

## 1. Finish the trust foundation — highest priority

> ✅ COMPLETE 2026-11-12→14. Recovery (in-app export/restore + off-device bundle copies,
> re-cut 2026-11-14) and safe reprocessing (atomic edits, triage-preserving reconcile,
> explicit communication) shipped with full evidence in
> [docs/feature_log.md](feature_log.md) — including the 金 = finalizing-stamp semantics
> and the `is_manual` provenance rule.

- [ ] (residue recorded at close, 2026-11-12 prose) fresh-install photo re-link helper:
      today a restore needs the device connected to stage photo bytes; a helper that
      re-links a DB-only restore to MediaStore would decouple the two.

The domain-model decision (2026-11-10) still governs all open work and is quoted in the
log: the **sentence is the primary object**, an image an optional field of it; the single
sentence view is its face; first-class sentence records are the parked page-merge
prerequisite.

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

> ✅ COMPLETE 2026-11-10/13: provider display, raw-OCR section, reprocess preview,
> stale-chip semantics (false positives killed by the would-refresh-change-anything
> predicate) — evidence + recorded follow-ups (single intent dialog; preview on the
> sentence-editor path) in [docs/feature_log.md](feature_log.md).

## 4. Complete a minimal card-creation workflow

> ✅ COMPLETE 2026-11-12: four-screen wizard (picker → per-(deck,model) template →
> confirm → read-back), schema v5 `card_templates`, gateway E2E 5/5 on emulator with
> real AnkiDroid 2.25.3 — full record (with its intro repaired from git history) in
> [docs/feature_log.md](feature_log.md).

Open follow-ons live as track A above: the add-to-existing-note path and wizard
validation against the user's own collection on device.

## 5. Improve lifecycle reliability and maintainability

(2026-11-14: these boxes were the §5 half of track D — D1/D2/D3 shipped, evidence in
[docs/feature_log.md](feature_log.md). They STAY open per the evidence rule: what remains
(progress/cancel/retry UI, permission loss, editor pending-save-target) can only be
proven in real use, not in-process.)

- [ ] Move long scans toward lifecycle-independent, recoverable execution with
      useful progress, cancellation, and retry behavior.
      (STATUS 2026-11-14, D3: scans now run on the serial AppIo.pipeline lane, survive
      screen teardown, and a re-issued scan naturally skips processed photos — but the
      progress/cancel/retry UI is untouched; stays open.)
- [ ] Test rotation, backgrounding, process death, permission loss, and interruption
      during processing; preserve editor drafts and pending save targets.
      (STATUS 2026-11-14, D3: rotation + work-survives-destroy + one-at-a-time pipeline +
      reader concurrency pinned by AppIoLifecycleTest 3 specs (99/99 suite) and a live
      user_rotation walk on emulator-5554; mid-write corpus integrity pinned by the
      existing rollback suite. REMAINING for a Pixel session: real kill -9 during rescan,
      permission-loss behavior, editor pending-save-target evidence — in-process
      simulation cannot honestly fake these, so the box stays unchecked per the evidence rule.)
      (PIXEL SESSION 2026-11-14: kill -9 mid-rescan DONE on #952 (least-impact target
      chosen from the snapshot: KEEP, non-gold, never-edited, 10 chars; reached via the
      claims-queue deep-link, the claim itself left pending): force-stopped ~1s after
      再スキャン -> pulled DB: integrity ok, photo text byte-identical to the pre-probe
      snapshot, occurrences/claims/totals unchanged (the kill landed before the write
      transaction — the atomicity contract held), app reopened the photo clean. Permission
      loss + editor pending-save-target remain open; box stays unchecked.)
- [ ] Consolidate mutation/reprocessing rules into explicit use cases rather than
      allowing Activity callbacks to become the source of business rules.
- [x] Update the README — ✅ refreshed 2026-11-14 after the feature sprint; the dated
      coverage record lives in [docs/feature_log.md](feature_log.md).

Acceptance: interrupted work can be resumed or safely retried without corrupting
or duplicating data, and a fresh checkout has a documented path to a working build.
Prefer incremental changes over a wholesale architecture migration.

## Secondary improvements

- [x] Single word view sentence ordering (2026-11-12) — shipped with the wizard's
      `sentence_ranks`; record in [docs/feature_log.md](feature_log.md).

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

### Batch "create cards" from the unseen list (pulled from track A 2026-11-14 by user)

Was the first item of the Card-making loop (batching the one-word-at-a-time wizard over the
unseen list). User decision, same standing as bulk photo ops below: not to be implemented
until he personally feels the want for it — no spec, no investigation record, nothing written
ahead of that. Re-entry trigger: the user asks. The rest of track A (add-to-existing-note,
wizard validation against the real collection) stays active.

### Bulk photo ops — mark / ignore / delete (pulled from track B 2026-11-14 by user)

Was listed among the Review & organization UX proposals (batch operations over the library
grid). User decision: not to be implemented until he personally feels the want for it —
no spec, no investigation record, deliberately nothing written ahead of that. Re-entry
trigger: the user asks. (Design notes if/when: 削除 is the one op with corpus consequences —
today no photo-deletion path exists in the app at all, so it would need the same
reversibility ceremony as every other destructive surface here.)

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
