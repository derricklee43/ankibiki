# Spike 003 — gate upgrades + calibration

> **Superseded 2026-11-13 by user decision:** shipped as AUTO-APPLY into the mining pipeline
> (overriding the "one-tap suggestions first" rollout below); the evidence-log schema became
> the `ocr_corrections` table and the ~200-sample bar is now what the gold loop measures.
> Production port + parity pinning: docs/roadmap.md §2, OcrCorrectionGateParityTest.

Status: DONE. Successor to spikes/002 (whose cleaned-gold verdict was: margin≥0.10
→ 96% precision / 44% coverage, ordering can't separate the tail).
Method: `python3 spikes/003-gate-upgrades/scripts/gate_v2.py` (offline; gold
fixtures + provider A/B artifact + bundled dictionary.db; per-island evidence
dumped to `gate_rows_v2.json`).

## Evidence classes added on top of spike 002's margin

1. **Dictionary anchoring** (`anchB & !anchA`): some JMdict headword occurs in
   the substituted context *covering the variant*, and none does for the original
   (陛下∈辞書 vs 陸下∉). This is the crisp version of "two kanji usually seen
   together" — full words, not fragments.
2. **Confusability**: 1-char kanji island whose two glyphs share a radical in
   the KANJIDIC/RADKFILE table (陸↔陛 share 阝) = plausible OCR glyph slip.
   Whole-phrase rewrites (海幹→迎撃, 郡異→北に貫) are NOT confusable and must
   route to "re-read with cloud", never to the character gate.
3. **Nameplate-island exclusion**: islands whose variant lives inside a
   nameplate-candidate string are the nameplate policy's job (roadmap "Parked
   ideas"), not the correction gate's — the correction gate consumes
   post-exclusion text. (Proxy here = drafted candidate strings; replaced by the
   structural policy when it ships.)

## Results (59 kanji islands, 52 true fixes on cleaned gold)

| gate | accepted | precision | coverage |
|---|---|---|---|
| S002: margin≥0.10 | 24 | 96% (1 harmful) | 44% |
| anchB & !anchA | 36 | 89% | 62% |
| S003-A' anchor + np-excl | 30 | **100%** | 58% |
| **S003-D' anchor ∪ confusable(≥0) + np-excl** | **36** | **100% (0 harmful)** | **69%** |
| S003-D' + margin≥0.10 band | 40 | 98% (1 harmful: 1398 郡異→北に貫) | 75% |

The margin≥0.10 band adds **no fixes** the anchor band missed while adding the
one harmful — S002's margin is fully subsumed by anchoring evidence. The 16
uncovered fixes are margin-0, unanchored, non-confusable cases (kana confusions
like ほ→ま, context-free single kanji) — real ceiling for future evidence
sources (reading consistency, larger LM).

## Verdict

S003-D' is **consistent with the ≥95% auto-apply bar**: 36/36, with the rule of
three putting the 95% upper bound on its true failure rate at ~8% (i.e., point
estimate 100%, small sample). It is NOT yet licensed for silent writes on n=36.
Calibration path: ship S003-D' as **one-tap suggestions** (photo detail:
"Cloud reads 陛下 here — accept?"), log accepted/rejected margins + evidence
flags to a per-island audit row (schema sketched below), and revisit silent
application for the anchor band once real-use samples pass ~200 with ≤1 rejection.

Residual risks: correlated misreads (both providers wrong) are invisible to any
disagreement-based gate; the np-exclusion proxy is provisional until the
nameplate policy ships; window=±4 chars was picked, not swept.

Audit row schema (for the future product table):
`photoId, i1, i2, A, B, margin, anchA, anchB, conf, decision(accepted|rejected|ignored), ts`
— kept per-island so calibration data accumulates exactly in the shape this
spike measured.

## Sequencing note

This gate is only useful on text that has survived nameplate exclusion —
spike-003 evidence (every harmful item under the un-excluded gates was
nameplate-adjacent) says the parked nameplate policy should ship FIRST or
simultaneously, or the correction gate inherits its noise.
