# Gold-set iteration plan — parsing quality vs user-corrected text

Status: v1 BUILT 2026-11-12. Steps 1–5 implemented for the JVM layer
(`scripts/build_gold_fixtures.py`, `gold_fixtures.json`, `GoldBenchmarkTest`,
first report `2026-11-12-gold-baseline.md` rev 2). Label review round 1 DONE:
all ambiguous short lines judged LEGIT by the user and frozen into
`legitShortLines` (generator will never re-draft them); nameplateCandidates
remain provisional auto-ignores; nameplateLabels still empty — candidate
confirmation optional. Pending: device live-layer pass over gold fixtures,
v1.1 occurrence layer (deferred per Q3). Q1–Q3 answered 2026-11-12 — see
§Answered questions.

## Why this exists

The 2026-11-10 baseline (`2026-11-10-extraction-baseline.md`) scores against
`expected` lists an agent read off 9 source images. That measures
segmentation, but not acceptability: it can't say "the pipeline now outputs
what the user would have typed instead." Since then the user has manually
corrected 25 photo texts in-app (backup `backups/20261112-155132`,
`modified_by_user_flag=1` on `scraped_photos.extracted_text`). That corrected
text is now our gold standard: content AND sentence boundaries (the `\n`
breaks are the user's segmentation decisions). All 25 photos carry frozen
`ocr_blocks_json` in the backup, so the deterministic JVM layer can replay
every one without a device.

**Membership revised 2026-11-13:** gold is no longer assumed from
`modified_by_user_flag=1` — it is whatever the user marked with the
**Mark as gold** button (`scraped_photos.is_gold`, schema v7). Partial edits
across multiple passes no longer imply verification, never-edited perfect
photos can join, and a full image rescan retires a photo's mark (its text was
replaced). The v7 migration does NOT seed marks (user decision, "flip them all
off"): live membership starts at zero and the 46-photo era is re-marked by
choice; the committed fixtures JSON keeps scoring the pipeline until the first
regeneration run against marked photos.

**The caveat:** the user stripped speaker nameplates while correcting
(`一エリス`, `ゴードン`, …). Naive exact-match against gold would charge every
nameplate leak against "parsing quality" — a confound the user explicitly
does not want in the headline results. Nameplate leakage therefore gets its
own ignored diagnostic column, measured separately (it also becomes the
acceptance data for the parked opt-in exclusion design; see §Integration).

## Inputs

- Gold source: `backups/20261112-155132/mining.db`, 25 photos (963, 969,
  975, 983, 1133, 1135–1143, 1145, 1147–1149, 1157–1159, 1164, 1166, 1174,
  1278, 1284). Golden source of truth for edits: always a fresh
  `adb`-pulled backup copy, never the live DB.
- Overlap with the 9 old fixtures: 963 and 1158. The two locked regressions
  (953, 1160) are untouched by the gold set and stay as-is.
- Photo 983 is a menu/system-text capture whose gold KEEPS short isolated
  lines — it is the control case proving "short line" ≠ "nameplate".

## Step 1 — gold fixture generator (script, no app code)

`scripts/build_gold_fixtures.py`, mirroring the existing fixtures format:
per photo emit `photoId`, `contentUri`, frozen `ocrDocument`, `gold`
(user text split on `\n`), `source_backup` (timestamp, so reruns diff),
and `nameplate_labels` (filled in step 2, then frozen). Regenerate whenever
the user corrects more photos; gold is derived, never hand-maintained.
Rerunning on a newer backup only ADDS/UPDATES photos with `is_gold=1`
(explicit marks; unmarked photos are never picked up by edits alone); photos
with human-reviewed nameplate labels are not silently re-labeled.

## Step 2 — nameplate labels (one human review, benchmark-side only)

A Python pass joins frozen OCR blocks with the current parser's output over
the gold set and proposes, per photo, every leaked line that matches no gold
sentence — these are nameplate candidates (e.g. `一エリス` on 963). The user
reviews the list once (~5–10 strings) and confirms each as NAMEPLATE, LEGIT
utterance, or MENU-LINE (never-ignorable). Labels live in the fixture JSON.
These same labels are the ground truth for the parked DEMOTE/DROP
experiment — see §Integration.

## Step 3 — nameplate-agnostic scorer (JVM layer)

For each fixture, re-parse frozen blocks with the current
`CjkSentenceParser`, then:

1. Align gold sentences ↔ pipeline sentences order-preserving (DP
   alignment), one-to-one, maximizing exact matches then minimizing
   normalized edit distance (NED).
2. Pipeline sentences that align to nothing: if the text is one of the
   photo's confirmed `nameplate_labels` → count in the **nameplate-leak
   diagnostic**, excluded from every headline metric. Everything else is a
   real false-positive (penalized).
3. Inline merges (nameplate stuck to the front of an utterance, the 953
   shape): only attempted for a gold sentence still unmatched after pass 1 —
   retry alignment against the candidate with the label removed as an exact
   leading/standalone token. Never substring-strip blindly: `ゴードン` is a
   label on 1158 yet appears legitimately inside the utterance `ゴードンです。`
   — one-to-one matching first means that utterance matches exactly and is
   never stripped.
4. Gold sentences are scored as typed. No fuzzy normalization: gold still
   contains recognition the user left in (`所霧`, `様会`), and per Q1
   corrections may keep going, so tolerance would hide real movement.

Headline metrics per run (report only, see §Gating):

- `sentences_exact`: # gold sentences matched exactly / # gold sentences.
- `char_accuracy`: mean (1 − NED) over aligned pairs.
- `segmentation_errors`: splits + merges + missing + non-nameplate extras.
- `nameplate_leaks` (diagnostic): count and which labels; tracks toward the
  parked exclusion feature but never moves the headline.

## Step 4 — the iteration loop

- Inner loop: generator → JVM scorer on every parser change. Deterministic,
  `./gradlew :app:testDebugUnitTest`, no device. Compare this run's JSON
  report against the previous one; every still-failing fixture must be
  classified by responsible stage (recognition-bound vs parser vs
  preprocessing) exactly as the baseline report does — a parser fix must not
  be claimed against recognition-bound failures (960/963-shape phantom `。`
  cases stay red until the OCR path changes).
- Outer loop: opt-in live layer (`-e benchmark true`, device session) on the
  same gold fixtures, to confirm frozen-block wins survive fresh OCR. Live
  numbers are recorded separately; a JVM-only improvement is not "fixed"
  until live agrees.
- New failures discovered in normal use continue to become fixtures via the
  android-ocr-debugging procedure.

## Step 5 — first gold baseline report

`docs/benchmarks/2026-11-12-gold-baseline.md` (dated): 25-row table (photo,
category, exact/total sentences, char accuracy, error class, nameplate leaks
ignored), headline metrics, and a short "distance to acceptable" list.
Targets in roadmap §2 are replaced with numbers agreed after this report
exists — not before.

## Gating policy

The gold set starts REPORT-ONLY: `assert_segmentation` stays false for all
25 because the current pipeline is expected to fail many of them; promoting
a photo to locked requires (a) the fix that makes it pass and (b) the user
confirming the gold is final for that photo. The existing locked pair
(953, 1160) keeps gating against regressions immediately. Nothing writes to
the corpus; both layers remain read-only.

## Integration with the parked nameplate-exclusion design

Per docs/roadmap.md "Parked ideas", shipping exclusion requires a
benchmark-only DEMOTE/DROP experiment showing real wins and zero menu/
short-line casualties. Steps 2–3 build exactly that evidence surface:
confirmed labels (wins/losses measurable), a menu-control photo (983), and a
leak counter. No production parser code changes flow from this plan; the
flag-only guardrail, opt-in per-photo policy, and raw-blocks-authoritative
rules stay in force.

## Answered questions (user, 2026-11-12)

- **Q1 (gold authority) — scored exactly as typed.** Mechanically unchanged, but
  the user set the record straight (2026-11-12): during correction passes he
  mostly stripped nameplates and did NOT catch every character typo — surviving
  misreads like `所霧`, `様会`, `陛下→陸下` are **misses, not endorsements**.
  Consequences, recorded so no future round misreads them:
  - As-typed gold is the *floor* of correctness, not the ceiling; a "failure"
    measured against it may be the pipeline reproducing a typo-free truth that
    the stale gold punishes (round 3 proved this happens systematically).
  - The adjudication loop (`gold-adjudications.json`) is the sanctioned way to
    raise the ceiling between in-app re-edit passes; rules self-retire when the
    user retypes.
  - When reporting metrics against this gold, state the floor caveat whenever
    exact-match numbers are quoted; do not interpret low exactness as
    "user chose this text."
  To raise the ceiling in-app: correct more photos (retype from the screen) and
  rerun the generator — gold stays derived, never hand-edited.
- **Q2 (segmentation truth) — the user's line breaks ARE the sentence
  boundaries.** Gold supersedes the old agent-made `expected` on overlapping
  photos 963 and 1158 (locks 953/1160 unaffected). Implemented: the scorer's
  segmentation-exact criterion is set equality with the user's splits after
  nameplate-agnostic cleaning.
  FOLLOW-UP (2026-11-13): the overlap prediction proved incomplete — round-3
  adjudication + 金 marking brought 953 and 1160 into the gold set too, and the
  user confirmed his own text over the agent-pinned content (脱出/手当を/差し出せ).
  Both legacy locks now guard SEGMENTATION SHAPE only (sentence count + per-pair
  similarity ≥ 0.6 against the user's gold; catches merges, splits, boundary
  migration) while GoldBenchmarkTest owns content — a lock pinned to frozen local
  blocks can never reproduce corrected content, shape is what it can and should
  still guard.
- **Q3 (scope) — photo level first.** Occurrence-layer checks (the 103 edited
  `item_occurrences.sentence` rows: survival after reprocess, candidate recall
  vs corrected text) deferred to v1.1 after the first gold report exists —
  which it now does (`2026-11-12-gold-baseline.md`).
