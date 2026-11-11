# Extraction quality baseline — 2026-11-10

First measured baseline for docs/roadmap.md §2. Methodology and numbers below; all
figures come from actual test runs on 2026-11-10, not estimates.

## How it is measured

Two layers, both driven by the same fixture file
(`app/src/test/resources/extraction_benchmark_fixtures.json`, mirrored into
androidTest assets):

1. **JVM layer** — `ExtractionBenchmarkTest` (runs in every unit test build).
   Re-parses the OCR blocks *as stored at mining time* with the current
   `CjkSentenceParser`. Deterministic; gates segmentation on locked fixtures.
2. **Live layer** — `ExtractionBenchmarkInstrumentedTest` (opt-in:
   `-e benchmark true`, read-only, never writes to the corpus).
   Runs today's full production stack (image preprocess → Cloud/ML-Kit OCR →
   parser → Kuromoji/JLPT analyzers) on the real photos from MediaStore.

Fixtures are 9 private corpus photos, each labeled by inspecting the actual
source image (2026-11-10): dialogue wraps, speaker nameplates, clean
utterances, a rotated capture. `expected` records the segmentation a correct
pipeline should produce from the image.

## Baseline results

| Photo | Category | Expected | Stored (old pipeline) | JVM re-parse (frozen blocks) | Live today (fresh OCR) |
|-------|----------|---------:|----------------------:|-----------------------------:|-----------------------:|
| 953  | dialogue wrap + nameplate | 2 | 2 | 2 ✓ locked | 2 ✓ |
| 958  | 1 sentence, 3-line wrap | 1 | 3 | 4 | 2 (dialog stitched; nameplate leaks) |
| 960  | 1 sentence, 3-line wrap | 1 | 3 | 4 | 3 (phantom `。` blocks stitch; nameplate leaks) |
| 963  | 1 sentence, 3-line wrap | 1 | 3 | 4 | 3 (phantom `。`; nameplate leaks as `一エリス`) |
| 1153 | nameplate + wrap | 1 | 2 | 2 | 2 (`#ならずもの/` leaks) |
| 1156 | 2 utterances + nameplate | 2 | 3 | 3 | 3 (`マルス` leaks) |
| 1158 | ack + wrapped sentence | 2 | 3 | 4 | 4 (phantom `。`; `ゴードン` leaks) |
| 1160 | 3 clean utterances | 3 | 3 | 3 ✓ locked | 3 ✓ |
| 1170 | rotated narration | 1 | 2 | 2 (garbled) | 2 (garbled) |

- Segmentation exact: **2/9** both layers (only the locked fixtures pass).
- Content-word survival (JVM, must-contain words found in parsed text): **0.61**.
- Live candidate must-contain recall: 958, 1158, 1170 = 0 (recognition errors
  destroy the words before analysis); 963 = 0.5; others 1.0.
- Corpus-level correction-effort proxy: 4 of 50 photos carry
  `modified_by_user_flag` (8%); 20 of 50 stored texts are single-sentence.

## Error classes (ranked by fixture impact)

1. **Nameplate leak — 5/9 fixtures.** Speaker nameplates appear in OCR blocks
   and become their own sentences (`エリス`, `マルス`, `ゴードン`,
   `#ならずもの/`, `一エリス`). The 953 stitching fix made the parser merge
   *reading-flow* neighbors, but nameplates are excluded only accidentally
   (geometry-dependent). Fix direction: geometric/shortness exclusion of
   nameplate regions (they sit above the dialogue box, are short, and
   punctuation-free), or provider-side region filtering. Pinned in
   docs/roadmap.md "Parked ideas" — opt-in design and benchmark evidence are
   prerequisites, since exclusion does not generalize to all Japanese media.
2. **Phantom terminal punctuation — 3/9 (960, 963, 1158).** OCR hallucinates
   `。` mid-sentence (after 担うのは / 1人の / 所属する), which the parser
   correctly honors, blocking stitching. Recognition-bound; mitigation options
   are a better OCR provider (Cloud path) or a stitch heuristic that distrusts
   `。` when the next line is a lowercase continuation — risky, needs care.
3. **Character recognition errors — pervasive.** 命能/変否/鎌承者/引兵部/所霧/
   大文夫/脱中/手当階/差しtせ/猿ぐつわ (correct: 命を/安否/継承者/弓兵部/所属/
   大丈夫/脱出/手当し/差し出せ). Destroys candidate recall and forces manual
   correction; not fixable in the parser.
4. **Rotated capture — 1/9 (1170).** 90° photo yields garbage OCR both stored
   and live; preprocessing needs rotation detection + multi-orientation retry.
5. **Positive signal:** live results beat the stored pipeline on 958 (dialogue
   now stitched) — the 2026-11-10 geometry fix generalizes to new OCR output.

## Proposed targets (to be agreed, not yet gating)

- Segmentation exact ≥ 7/9 after nameplate exclusion (958/1153/1156/1158
  become reachable; 960/963 remain recognition-bound until provider improves).
- Content-word survival ≥ 0.80 on the frozen-block layer.
- Locked fixtures (953, 1160) must never regress — already gating in CI.
- New segmentation failures discovered in normal use become fixtures here
  (procedure recorded in the android-ocr-debugging skill).

## Reproducing

```
./gradlew :app:testDebugUnitTest --tests "*ExtractionBenchmark*"   # deterministic layer
ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.derricklee.ankidict.ExtractionBenchmarkInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.benchmark=true      # live layer (device)
```

Live metrics print as `BENCHMARK_LIVE*` JSON lines (logcat `System.out` or the
connected-test report).
