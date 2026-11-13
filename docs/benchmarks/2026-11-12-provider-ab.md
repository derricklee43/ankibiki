# Provider A/B — stored vs fresh ML Kit vs Cloud Vision — 2026-11-12

Question (from the loss anatomy): is the ~40-sentence gap to gold fixable by
re-scanning, and which OCR provider actually reads the corpus better?
`ProviderAbInstrumentedTest` (`-e ab true`, read-only, corpus untouched) ran
all three providers over the 46-photo gold set on the Pixel.

    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.derricklee.ankidict.ProviderAbInstrumentedTest \
      -Pandroid.testInstrumentationRunnerArguments.ab=true

Rubric note: this harness scores with a per-sentence best-match rubric shared
by all providers (its "25/74" for stored is not the headline gold number) —
the comparison is what matters, not the absolute. Full per-photo OcrDocuments:
`data/provider_ab_1794554575148.json` (no key: stored==fresh-local, 40/46
identical, 3 better / 3 worse — net wash) and
`data/provider_ab_1794555946992.json` (with Cloud: 46/46 requests OK).

## Results (46 photos, 74 gold sentences)

| Provider | sentence exact | avg char acc | all-exact photos |
|---|---|---|---|
| stored (mining-time, on-device) | 25/74 | 0.799 | 6 |
| fresh on-device ML Kit | 25/74 | 0.808 | 5 |
| **Cloud Vision** | **27/74** | **0.822** | **7** |

Per-photo winners: Cloud better on 18, worse on 15, tied 13. No dominant
provider — but a decisive split by photo class:

- Cloud cleanly fixes the "recognition-bound" class pinned on the local model:
  963 (phantom `。` + 鎌承者) 0.35→**1.00 char acc, exact sentence**;
  1397 (mis-tagged photo, stored was garbage) 0.00→**1.00, exact**;
  1148 1→**3 exact**; 1417/1441 narration cases fixed outright. The
  hallucinated-`。`-blocking-stitch failures are a local-model artifact, not
  an OCR law.
- Cloud **regresses** on short-line/menu/nameplate-heavy photos: 983
  (0.97→0.79), 1149 (3→1), 1158 (2→1), 1159 (1→0), 1418 (0.94→0.33).
  Mechanism: Cloud groups by paragraphs and merges/drops differently than
  ML Kit — and `CjkSentenceParser`'s geometry constants (0.65 height ratio,
  1.5× gap) were tuned against ML Kit block shapes. A provider switch is also
  a parser re-tuning event.
- Oracle best-of-providers per photo: **37/74 exact** (vs 25 today) — the two
  providers are complementary; ~12 sentences live in "the other model reads
  this one right."

Group split: OLD-25 stored 15 == cloud 15 (wins/losses cancel); NEW-21
narration-heavy cloud 12 > stored 10.

## Conclusion / design options (open — user call)

Re-scanning with local changes nothing. Cloud is a genuinely different reader:
better recognition on dialogue/narration, worse layout on short lines, and its
block shapes invalidate the parser's tuned geometry. Options, rough escalation
order:

1. **Opt-in cloud re-extraction** per photo/batch ("re-extract with cloud"
   action; reprocess already replaces OCR atomically, raw blocks stay
   authoritative). Matches the standing design principle: media-specific
   behavior is opt-in and reversible. Lets you cloud-re-mine narration photos
   and keep local for menu/UI screens.
2. Cloud-first composite with on-device fallback — one-line change, but it
   silently re-tunes every future photo; the 983/1149/1159-class regressions
   argue against making it the implicit default.
3. Dual-provider mining + pick-best heuristic (targets the 37/74 oracle) —
   real complexity; consider only after living with 1 for a while.

Cost: 46 requests/run vs Vision's 1,000/month free tier — a full 150-photo
corpus re-mine fits in one month free.

## Duplicate detection (same day, unchanged decision)

`DuplicateDetectionConfig.enabled` defaults to **false** (PipelineContracts.kt)
per user decision; the detector stays implemented and its E2E test opts in
explicitly. Consequence: retakes/near-identical screenshots are kept as
independent photos (double occurrences until triaged). Discovery never
"reads deep" into the camera roll — MediaStore id/date query only,
newest-first, skipping processed; image bytes open only for ≤25 unprocessed
candidates per scan.
