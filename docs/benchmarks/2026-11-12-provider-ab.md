# Provider A/B — stored mining OCR vs fresh on-device ML Kit — 2026-11-12

Question (from the loss anatomy): is the 42-sentence gap to gold fixable by simply
re-scanning with today's local stack? `ProviderAbInstrumentedTest` answered it on
the Pixel with the full 46-photo gold set, read-only, corpus untouched.

    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.derricklee.ankidict.ProviderAbInstrumentedTest \
      -Pandroid.testInstrumentationRunnerArguments.ab=true

(Note: this harness scores with a simpler per-sentence best-match rubric — its
"25/74" for stored is NOT the gold headline 22/43+31; both sides use the same
rubric, which is what a provider comparison needs. Full outputs with each
provider's complete OcrDocument are in `data/provider_ab_1794554575148.json`.)

## Result

| Provider | photos scored | sentence exact | all-exact photos |
|---|---|---|---|
| stored (mining-time blocks) | 46 | 25/74 | 6 |
| fresh on-device ML Kit | 46 | 25/74 | 5 |
| Cloud Vision | — | not run: `CLOUD_VISION_API_KEY` unset | — |

Per-photo: 40/46 identical, 3 better (963 char acc 0.35→0.65, 969 0.51→0.75 +
one extra exact sentence, 1174 0.88→0.92), 3 worse (1430, 1441, 1443). Net wash.

## Conclusion

Re-scanning with the local model does NOT move the needle — the mining-time
blocks were already produced by essentially this model, so the character-level
gap to gold is intrinsic to on-device recognition. The remaining untested lever
for the 40 recognition-bound sentences is **Cloud Vision**, which today the
production composite uses only when on-device returns nothing
(CompositeTextExtractor prefers the non-blank preflight). To close that
question: set `CLOUD_VISION_API_KEY` in `local.properties` (or env) and rerun
this same command — the test will add a `cloud` column to every photo.

## Duplicate detection (same day)

Per user decision, `DuplicateDetectionConfig.enabled` now defaults to **false**
(PipelineContracts.kt). Consequences: retakes/near-identical screenshots are
kept as independent photos instead of being linked as DUPLICATE; occurrences
for repeated text will therefore double up and show as gold `falseExtras` on
re-correction. The detector stays implemented and its E2E test opts in
explicitly. On "reading too far into the camera roll": discovery never does —
MediaStorePhotoSource queries id/date only, newest-first, skipping processed
photos; it only opens bytes for unprocessed candidates within the batch.
