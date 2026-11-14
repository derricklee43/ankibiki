# Spike 004 — speaker-nameplate exclusion (SPIKED 2026-11-13: auto-exclusion FAILS its bars; verdict below)

Status: **executed** 2026-11-13 — `scripts/spike004.py` + `results.json` are the
run of record (offline: 126-photo fixtures, gold_run9 scorer output incl. the
per-line inline-merge truth the design called its reference implementation,
provider A/B geometry, JMdict asset). Headline summary:

- **Standalone flagging — no variant passes the registered bar.** Best
  zero-casualty recall is A2 (recurrence lexicon): recall 0.46, precision
  **1.00, zero LEGIT casualties, zero noise** — but bar is ≥0.90. A3 bootstrap
  reaches 0.56 with 1 casualty. The incumbent **A0 proxy flagged 4 LEGIT
  content lines (ヘヘヘ, 神剣ファルシオン, マルス王子。, 剣十ナバール。) — if it
  ever became an exclusion input it would delete real dialogue; this is the
  spike's most actionable negative finding.**
- **Inline excision — fails.** Gold-free H9 rule: 22/27 recall, precision
  0.69, 1 casualty (`1269 マルス王子。` — exactly the shape the design said to
  fear: a title-vocative indistinguishable from a label merge). Bar was ≥0.95
  with zero casualties → inline excision stays suggestion-only.
- **Auto-exclusion: NOT GO.** Per the pre-registered fallback: manual strip +
  the shipped human-confirmed claims ledger remain the mechanism; the idea
  stays parked for automatic policy.

## Results (run of record)

Phase-0 truth universe: standalone NAMEPLATE 48 (the scorer's 38 exact-match
leaks plus 10 OCR-mangled label variants within ≤2 edits), inline merges 27
(scorer-emitted per-line list), LEGIT 81, NOISE 132.

Solo diagnostics (flagged lines over all 126 photos; recall out of 48):

| signal | flagged | recall | casualties | noise | precision |
|---|---|---|---|---|---|
| H1 geometry (stored blocks) | 21 | 0.35 | 1 | 3 | 0.81 |
| H1 geometry (cloud, 44 photos) | 9 | 0.17 | 0 | 1 | 0.89 |
| H2 shape | 51 | 0.77 | 3 | 11 | 0.72 |
| H3 recurrence K≥2 | 22 | 0.46 | **0** | **0** | **1.00** |
| H4 head-recurrence K≥2 | 20 | 0.08 | 0 | 16 | 0.20 |
| H5 katakana | 23 | 0.46 | 1 | 0 | 0.96 |
| H6 OOV | 6 | 0.10 | 1 | 0 | 0.83 |
| H7 reader-disagreement (44) | 7 | 0.10 | 2 | 0 | 0.71 |

Variants (standalone):

| variant | flagged | recall | casualties | noise | precision |
|---|---|---|---|---|---|
| A0 shipped proxy | 32 | 0.58 | **4** | 0 | 0.88 |
| A1 geometry-only | 21 | 0.35 | 1 | 3 | 0.81 |
| A2 lexicon K=2 | 22 | 0.46 | 0 | 0 | 1.00 |
| A2 lexicon K=3 | 20 | 0.42 | 0 | 0 | 1.00 |
| A3 bootstrap K=2 | 31 | 0.56 | 1 | 3 | 0.87 |
| A3 bootstrap K=3 | 30 | 0.54 | 1 | 3 | 0.87 |
| A5 scoped K=2 | 41 | 0.48 | 0 | 18 | 0.56 |

Inline (separate decision point, over all 181 sentences, truth = 27):

| mode | recall | precision | casualties | noise FP |
|---|---|---|---|---|
| H9 gold-free (a∨b ∨ c∧¬dict) | 22/27 | 0.69 | 1 | 9 |
| H9a/b tiers only | 1/27 | 0.50 | 0 | 1 |
| H9 ∧ A3 lexicon | 21/27 | 0.68 | 1 | 9 |
| **H9v2 guarded (SHIPPED as suggestions)** | **22/27** | **0.82** | **0** | 5 |

H9v2 = v1 + three guards written from the v1 FP census (measured same day, so
this row is an improvement iteration, NOT a post-hoc variant competing for the
auto-go bar): G1 title-phrase tails (王子。/王子よ。/継承者として blocked;
clause-headed titles like 王子の格好 and 王子、ほとんど… pass), G2 copula heads
(だと/なら/です), G3 tier-c tokens ending in a title char (マルス王|よ。). The
first guard draft over-blocked — it ate 1166's true merge — the refinement
round restored it; both iterations are in the git history of this script.
Auto-go verdict unchanged (0.82 < 0.95). What H9v2 IS qualified for: pending
claims in the ledger, where the cost of the 5 noise FPs is one 違う click and
every click is a label.

Effort-lift secondary metric: 44 of 75 leaked lines would be auto-caught →
31 remain for manual strip (target ≤10: **missed**).

## Predictions vs results (all pre-registered above, before the run)

- *"Corpus recurrence is the single strongest unused signal"* — **CONFIRMED,
  emphatically**: H3/A2 is the only signal with perfect precision and zero
  casualties.
- *"Any variant omitting recurrence misses the class labels"* — **CONFIRMED**:
  ならずもの is caught ONLY via recurrence (K=2 lexicon member); 村人 (single
  occurrence) is caught by nothing offline.
- *"H2/H5/H6/H9c predicted to fail solo casualty audit"* — **CONFIRMED** for
  H2/H5/H6 (all hit ヘヘヘ; H2 also hit 神剣ファルシオン and しか引兵部隊の).
- *"H9a/b predicted safe unambiguous"* — **REFUTED in part**: with the corpus
  compound-boundary guards the run added (王は…, 神剣ファルシオン continuation
  shapes), true case-collision tier-a hits collapse to 1/27 — the design's
  5/27 measurement over-counted: several "case collisions" are katakana
  proper nouns continuing the cut token. Tier b (はい-class) survives cleanly.
- *"Cloud reads nameplate geometry cleaner than local"* — **REFUTED**: cloud
  H1 recall 0.17 vs stored 0.35 — cloud paragraphs absorb plates into blocks,
  destroying standalone-ness.
- *"The removal test itself protects content"* — **mostly true (1 casualty in
  181) but not enough to gate on**: マルス王子。 shows title-vocatives collide
  with label-merges at the surface, exactly the design's stated worry.

## Deviations recorded (honest ledger)

1. Length gate ≤4 → ≤6: the design's own examples (モロドフ, グラ軍隊長) are
   5-6 chars; ≤4 was self-contradictory. Run used ≤6.
2. H4 offline proxy: "recurrence as a glued head" needed a production-shaped
   definition; run used remainder-matches-gold (≥0.6 sim) as the offline stand
   in for gold-free remainder plausibility. H4's 0.20 solo precision partly
   reflects that proxy's looseness.
3. A5 cluster proxy is DEGENERATE on committed data: all 126 photos fall in a
   single photoId-gap cluster (contiguous MediaStore ids), so "collection-
   scoped lexicon" could not be tested — cross-media bleed is UNMEASURED, not
   zero. A5's table row is A2 + unscoped H4 noise and should be read as
   "untested", not "tested and failed". Production A5 needs real collection
   metadata (folder/deck), which the corpus does not store yet.
4. Inline truth switched from my reconstruction (25) to the scorer's own
   per-line output (27) — design had named the scorer as reference; the test
   gained an `inlineMergeTexts` field to expose it (benchmark output
   unchanged: 73/181).

## What the data DOES support (not a promotion — proposal for a written A6)

- The shipped **claims ledger** is the mechanism the corpus actually wants:
  offline detectors saturate at ~0.55 recall because half of leaks are
  one-off or OCR-mangled strings; the human ruling catches exactly what no
  string-level detector can, and every ruling trains the next round.
- Candidate **A6 = fuzzy recurrence**: cluster the standalone shape pool by
  edit-distance ≤2 before counting K (would recover モ口ドフ/モ日ドフ/マル入/
  ュリアンィ-class manglings, ~10 of the 26 missed lines in this corpus).
  Must be written here with its zero-casualty argument BEFORE being measured;
  not run in this spike.
- Immediate safe follow-up for the gate (no policy change): replace the
  A0-proxy string source with the recurrence lexicon ∪ ledger-confirmed
  strings — A0's 4 content casualties would otherwise propagate into the
  auto-apply gate's exclusion list (it currently only drops *fixes* there,
  but the roadmap says this policy replaces it as gate input — this spike
  says: replace it with A2+ledger, not with A0).
- Production-go remains BLOCKED twice over: no variant met its bar, AND the
  menu-control coverage gap (983 excluded) still stands.

## Problem and binding guardrails (from roadmap "Parked ideas")

Nameplate leakage is the largest visible-garbage class: 38 standalone +
27 inline-merged leaks excluded from the rev-6 headline across 127 photos,
and it is what the user's correction passes mostly consist of. But exclusion
does not generalize: short isolated lines ARE the content in menus (photo 983,
control case) and legitimate one-word utterances exist (ヘヘヘ, 不覚にも…).
Binding rules this spike cannot break:

- **G1** The parser only *flags* candidate nameplate regions; it never deletes.
  Dropping is policy, applied elsewhere.
- **G2** Policy is per-photo, opt-in, default KEEP (= today's byte-identical
  behavior), with DEMOTE (mine as speaker label, exclude from sentence mining)
  and DROP variants. A boolean is the wrong primitive.
- **G3** Raw `ocr_blocks_json` stays authoritative; any policy decision is
  re-derivable by reprocess; turning policy off reproduces current behavior
  exactly.
- **G4** Zero-casualty requirement for production go: menu/short-legit-line
  content must never be lost under any shipped policy.

**Sample-set exclusion (user decision 2026-11-13):** photo 983 (system-menu
screenshot) is an outlier and is NOT part of any sample set — un-marked on the
device, out of gold fixtures (126 photos). Its geometry remains cited as
counter-evidence (an observation, not a scored sample). CONSEQUENCE: the spike
executes with NO menu-style control photo — recorded as a coverage gap, not
silently ignored. Casualty audit runs on the in-set LEGIT lines that do exist
(1149 ヘヘヘ, 1136 はい。, all `legitShortLines` ledger entries, every ≤8-char
gold sentence); any menu-style photo the user marks in a future round joins
the audit set automatically, and production-go should refuse until at least
one menu-style control exists in the corpus.

## Ground truth construction (Phase 0, no device needed)

Per photo, the universe of *leaked lines* = current-parser sentences not
matching user gold (nameplate-clean comparison already excludes them; we
re-score WITHOUT exclusion). Truth classes, all derivable from committed data:

- NAMEPLATE: line equals (or contains-with-≤2-edits) a confirmed label string.
  **Round-4 review DONE (2026-11-13)**: user confirmed all 81 occurrences;
  `nameplateLabels` populated on 79 photos — Phase 0 inputs complete.
- LEGIT: line appears in user gold, or is in `legitShortLines`, or is a short
  gold sentence (≤8 chars, punctuation-free) — e.g. ヘヘヘ, はい。. The former
  menu-line examples (photo 983) apply shape-wise, but that photo is excluded
  from the set per the note above. Never-flaggable.
- NOISE: neither (recognition fragment / segmentation artifact) — flagging it
  is a false positive against the NAMEPLATE detector but NOT a casualty
  (policy would DROP it too; only LEGIT matters for G4).

Inline-merged truth: pipeline sentences whose head equals a confirmed label +
gold-shaped remainder (the scorer's existing one-to-one + strip logic is the
reference implementation — 27 known instances).

## Heuristics (the detector flag candidates; each is measured independently)

| id | signal | evidence in our corpus | known counterexamples |
|---|---|---|---|
| H1 | **Geometry isolation**: standalone block, no terminal punctuation, sits above/adjacent to reading flow with height < dialogue height (963: 77px vs 100–128px dialogue; cloud reads these cleaner than local) | cloud artifact gives height + bounds for 46 photos | 983 menu blocks are isolated AND short |
| H2 | **Shortness + punctuation-free**: ≤8 CJK chars, no 。！？… | current drafting rule; ~all candidates match | 敵全員の/攻撃範囲表示 fragments (983), 攻華範囲表示 misreads |
| H3 | **Corpus recurrence (standalone)**: same short string appears as standalone non-gold line on ≥K photos (モロドフ-class ×5, ジュリアン ×4, カイン ×2, ゴードン ×2) | recurrence is the single strongest unused signal; per-string counts computable now | a repeated system message ("攻撃範囲表示" in menus) also recurs — H3 must be intersected with non-gold-ness or scoped by collection |
| H4 | **Corpus recurrence (inline-prefix)**: string repeatedly occurs glued at the head of an utterance (カイン私は…, モ口ドフはい…) | 27 instances incl. 1136/1138/1145 | none observed; a verb stem can't fake prefix-recurrence across unrelated sentences |
| H5 | **Katakana-only short line** | most names are katakana | ヘヘヘ, ならずもの(plain), 村人 (class label, katakana-free!) — weak, auxiliary feature only |
| H6 | **Dictionary-absence (OOV)**: not a JMdict headword and headword-anchoring fails (spike-003 lexicon available) | モロドフ ∉辞書 | ならずもの/村人 ∈辞書 (class labels — THEY LEAK TOO and must be excludable); 弓兵部 ∉辞書 (game term content) — cannot stand alone, H5-like auxiliary |
| H7 | **Reader disagreement**: local leaks a standalone line where cloud merges/omits it or vice versa | artifact covers only 46 of the set's photos | coverage gap; auxiliary/cross-check only |
| H8 | **First-block-of-photo position**: nameplates cluster top-of-dialogue-region; measured as vertical rank within the flow region | cloud 963 B2 一エリス below utterances — CONTRADICTS top-position prior (nameplate position varies with text direction) | weak; include as a logged feature, don't gate on it |
| H9 | **Grammatical detachment of a leading/trailing noun** (user-proposed 2026-11-13): a name glued to an utterance has no connecting particle and no punctuation, and the sentence remains grammatical without it. Three tiers, measured on this corpus's 27 known inline merges: **H9a case collision** (5/27) — remainder begins noun+は/が/も, so the sentence already has its own subject (モドフーあなたは脱出しなさい; マルスぼくは絶対に忘れない): unambiguous. **H9b self-contained remainder** (2/27) — remainder begins はい/なあに-class that cannot attach to a preceding noun (モ口ドフはい。; グラ軍隊長なあに…): unambiguous. **H9c bare dangling noun** (20/27) — remainder grammatical without it (カイン無論です; マルスだから、今は) but NOT safe alone: genuine vocatives (マリー、行くな without punctuation) and noun-subject openers (村人だから…) match identically. H9c is usable only ANDed with H6-OOV of the exact token — which fires precisely on names in this corpus (カイン/ジェイガン/マルス ∉ JMdict) and correctly refuses class labels (村人/王子 ∈ dict — those need H3/H4 recurrence, consistent with the class-label finding). Universal guard: the removal test itself protects content (ゴードンです。 minus ゴードン is ungrammatical → no variant fires). | all 27 inline-merge instances accounted for by tiers (5+2+20); trailing mirrors exist in principle (label after 。/」) — same removal test, none observed merged in-set yet | H9c without OOV (vocatives, subject-nouns); Kuromoji OOV is unreliable for names — use exact-token dictionary presence, not tokenizer unknown-tags |

**Production role of H9 (no-gold excision rule)**: in Phase 3/4 the inline
merge decision must be made WITHOUT gold (gold is benchmark-side truth; production
has only the text). The gold-free excision rule: cut leading token T when
(H9a ∨ H9b) ∨ (H9c ∧ T is not a JMdict word ∧ len(T) ≤ 4 ∧ T katakana-or-kanji).
The spike's inline-excision precision test (the 27 instances) is exactly a
measurement of this rule; H9a/b predicted safe, H9c∧OOV predicted to carry the
20-instance bulk — result decides whether inline excision ships at all or stays
suggestion-only.

**Class-label finding to respect**: ならずもの / 村人 are dictionary words,
not katakana, and appear inside utterances' same register — H9c∧OOV cannot
excise them (OOV fails) and H1 may miss them; only H3/H4 recurrence recognizes
them as labels (H9a/H9b remain usable on them IF the remainder is case-marked,
since those tiers don't require OOV). Any variant that omits recurrence
(A1 below) will miss them; this is a predicted result the spike must verify,
not discover.

## Policy application (after flags exist)

- Standalone flag hit: KEEP/DEMOTE/DROP per photo (per-line override UI is a
  production-design question, out of spike scope; spike measures at policy-on
  the *flagged set only*, per-photo granularity).
- Inline-prefix flag: excision candidate — remainder must still pass the
  spike-003-style anchoring (the scorer's one-to-one protection generalizes:
  ゴードンです。 must NEVER be cut just because ゴードン is a label). Spike
  measures excision precision on the 27 known inline instances.

## Variants to test (each a row in the results table)

- **A0 the shipped proxy (incumbent baseline)**: `OcrCorrectionPass.draftNameplate
  Strings` as-is (short ∧ katakana-bearing ∧ hiragana-free ∧ punctuation-free) —
  it exists in production today (routes ambiguous islands away from auto-apply;
  deliberately over-excludes). Every variant A1–A5 must be scored against A0 so
  "better than what the gate already does" is measurable, and the real policy
  replaces A0 as the gate's upstream input when it lands (roadmap shipping note).
- **A1 geometry-only**: H1 ∧ H2. Per-photo, no cross-photo state. The
  minimal, most conservative shipped candidate.
- **A2 lexicon-only**: H3 ∨ H4 (recurrence across photos, K=2 first, sweep
  K∈{2,3}). No geometry dependence — should catch cloud's cleaner reads AND
  ならずもの-class labels; risk = cross-media bleed (measure: does any label
  recur across game boundaries? tag photos by date-cluster to test).
- **A3 hybrid bootstrap**: seed from A1 standalone hits → build lexicon →
  expand via A2 → re-apply everywhere including inline-prefix. Expected best;
  measure whether it beats A2 alone (geometry adding precision or just
  recall loss).
- **A4 A3 + auxiliaries**: add H5/H6/H7/H8 as scoring features with
  hand-tuned weights (logistic-style, fixed during spike). Only evaluate if
  A3 falls short of targets.
- **A5 collection opt-in** (product alternative, not detector): user declares
  once per media/collection "speaker labels present" (or seeds one label),
  system applies A2-lexicon thereafter. Test = A2's numbers under
  collection-scoped vs global lexicon; this may be the shipping shape because
  it puts a single opt-in where the ambiguity actually lives.
- **Rejected, documented**: per-photo LLM "find the names" (cost,
  non-determinism, G3 tension — raw blocks must re-derive flags offline);
  geometric-top-row-only rules (H8 evidence contradicts).

## Evaluation method (what "testing" means here — user question 2026-11-13)

Three layers, deliberately NOT a 2^9 combination sweep (with ~27+81 labeled
instances, a 512-way sweep is guaranteed to surface a 100%-by-chance combo —
a fake result; pre-registration is the defense):

1. **Solo diagnostic**: every heuristic H1–H9 scored alone against the Phase-0
   truth table: nameplate recall, LEGIT casualties (any >0 disqualifies it as a
   standalone production rule — H9c, H2, H5, H6 predicted to fail this alone),
   NOISE pickup. Purpose: locate where each signal's failures live. No shipping
   decision taken here.
2. **Pre-registered combinations** = A0–A5 only, declared above before any run,
   judged against the fixed go/no-go thresholds. If all five fail: verdict is
   "stays parked" — no post-hoc combination #6 gets promoted without being
   written here first as A6 with its rationale.
3. **Two decision points scored separately**: standalone-line flagging (policy
   input) vs inline-prefix excision (H9 rule) — different actions, different
   blast radius, separate result tables; they never average each other out.

## Metrics + go/no-go

Primary (against Phase-0 truth, all 126 gold photos + 9 legacy fixtures):
- nameplate-line recall, per variant (standalone and inline separate)
- flag precision (NAMEPLATE / NOISE among flagged) — NOISE flags are allowed
  for the *experiment* but counted
- **casualties on LEGIT = must be 0 for any production candidate (G4 hard gate):
  in-set audit = every `legitShortLines` entry, ヘヘヘ/はい。-class short gold
  sentences; NO menu-style control photo exists in the set (983 excluded) —
  production-go blocked until one is marked (coverage-gap note above)**
Secondary: rev-6 scorer re-run under simulated DEMOTE/DROP → headline
unchanged by construction (nameplate noise was already excluded); the honest
lift metric is correction-effort proxy: leaked lines a user no longer strips
(rev-6: 38+27 → target ≤10 across all variants).

Go thresholds to production design: ≥90% nameplate recall (A2/A3/A5), zero
LEGIT casualties, inline-excision precision ≥95% on the 27 instances. If only
A1 passes casualty audit → ship geometry-only opt-in and accept the label
gap. If none → keep manual strip, re-pin this idea, and say so plainly.

## Phase 4 preview (what production would cost, NOT in spike scope)

- Parser: `parseSentences` stays untouched; NEW side-channel API returning
  flags (byte-identical default — G2/G3), e.g.
  `data class SentenceParse(val sentences: List<String>, val flags: List<LineFlag>)`.
- Schema: `scraped_photos.nameplate_policy TEXT NOT NULL DEFAULT 'KEEP'` →
  v8 migration; remember the version-gate sync rule (backup_bundle
  SUPPORTED_USER_VERSIONS + recovery.md prose) and RecoveryManager REQUIRED
  columns.
- UI: photo-detail policy control (KEEP/DEMOTE/DROP), flag chips in reprocess
  preview; DEMOTE needs a destination (speaker field vs display-only) —
  production design question, listed open.
- Lexicon storage (A2/A3): derived from corpus rows, must be recomputable —
  no new authoritative table; cache allowed (G3).

## Order of work

1. ~~Candidate review round 4 (user) → freeze `nameplateLabels`~~ DONE 2026-11-13.
   **Production label stream now exists**: the app's v8 `nameplate_claims` ledger
   (photo view 正しい/違う buttons) accrues per-string human verdicts on real
   mining output — same semantics as this spike's truth classes. Cross-check
   spike ground truth against it before Phase 4 (`SELECT text, verdict FROM
   nameplate_claims WHERE verdict IS NOT NULL` off the device DB).
2. Phase 0 truth tables (script, committed alongside).
3. A1–A3 + casualty audit (offline; geometry needs the provider-artifact
   cloud docs for 46 photos and stored blocks for the rest — flag both
   coverage modes separately).
4. Inline-excision test on 27 instances.
5. Verdict here + roadmap parked-idea updated to "spiked" with numbers.

Open questions to settle during execution: lexicon scoping unit (global vs
date-cluster vs collection — A5 depends on this); DEMOTE destination; whether
legacy 9-fixture photos without 金 gold get truth labels from their `expected`
(they partially do — 963/953/1160/1156/1158 carry verdicts already).
