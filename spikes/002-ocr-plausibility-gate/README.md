# Spike 002 — OCR plausibility gate: can we auto-correct?

Status: DONE (verdict: SUGGESTION-GRADE, not yet auto-apply-grade).
Method: `python3 spikes/002-ocr-plausibility-gate/scripts/gate_report.py`
(read-only; inputs: gold fixtures + provider A/B artifact + bundled dictionary.db).

## Question

Two providers (local ML Kit, Cloud Vision) read the same photos and disagree at
specific words. The user's gold set contains corrections proving one reader was
often wrong. Can a *dictionary-evidence score* (headword coverage + legal kanji
bigrams over context windows, all from the already-bundled JMdict DB) decide
which variant is real Japanese — well enough to substitute automatically?

## Evidence model

- HEADWORDS: CJK projections of 218k JMdict headwords (dictionary.db `words`).
- BIGRAMS: adjacent kanji pairs occurring inside any headword (~"kanji usually
  seen together", the user's phrasing).
- fragment score = greedy longest-match coverage over kanji runs, minus 0.15 per
  illegal bigram; gate margin = score(B) - score(A) on a ±4-char window.
- Islands extracted only where stored/cloud line similarity is 0.55..1.0 and
  the replace-island is ≤4 chars — word-level, not sentence-level, because
  whole-sentence provider swaps inherit segmentation differences (spike 001's
  17% precision was almost entirely that artifact).

## Results

60 gateable kanji-substitution islands found (42 raw disagreement islands total):

(Final run: 72 raw islands, 13 excluded as kana-styling, **59 gateable**.)

| metric | value |
|---|---|
| gate picks cloud, cloud matches gold (true fix) | 39 |
| gate picks cloud, gold says stored was right | 8 (≈7 are **gold-as-typed errors**) |
| both / neither in gold (noise) | 12 |
| raw precision (vs gold-as-typed) | 66% |
| precision after manual audit of the 8 | ~80-85% |

End-to-end simulation — apply all margin≥0 gated substitutions to the parser's
current sentences, rescore: **26 → 33/74 exact, 3 sentences "broken."** Audit:
all 3 breakers are 陛下 mis-typed as 陸下 in gold, 機会→様会, 所属→所霧
(+捕らわれて→飾らわれて) — the gate was RIGHT and the gold was wrong (typos the
user never noticed when correcting). Real damage: ~1 uncertain (1440 海幹/迎撃).

Margin ordering does NOT improve precision (62-65% across all thresholds) — the
coverage signal ranks true fixes and false ones similarly. The evidence that
would separate them is full-word dictionary membership of the *substituted
phrase* (陛下 ∈ JMdict, 陸下 ∉), which coverage-per-run only partially captures.

## Non-findings that matter

- **Kana styling must be excluded from any gate**: ヘ/へ, リ/り, ド/ト, え/ぇ
  disagreements are rendering choices (the user's gold uses katakana emphatically:
  ヘヘヘ, リゃ). Auto-correcting these would rewrite style into prose. Excluded
  12 islands; any product gate must hard-exclude this class.
- **Whole-document provider selection is weak** (28/74 vs oracle 36/74): doc-level
  average plausibility barely beats "always cloud" (27/74). The value is at the
  island level, not the reader level — argues against building provider routing
  and for building disagreement surfacing.

## Verdict and next state

The gate is **not** at the ≥95% bar for silent auto-apply (78-85% best estimate;
at 78%, roughly 1 in 5 accepted substitutions is wrong — unacceptable for writes
the user never sees). It **is** strong enough for its first product form:

- **Suggestion, not write**: at disagreement islands, show both variants with the
  plausibility delta, one tap to accept. 39+8 true fixes become visible instead
  of silent.
- Prerequisite cleanup round: re-correct the ~7 known gold typos in-app
  (陛下, 機会, 所属, 捕らわれて, 襲った, 悪魔, and audit 海幹) and regenerate —
  the benchmark itself is currently *punishing* correct readings, and every
  future precision estimate inherits that distortion.
- Gate upgrade candidates (spike 003 material): full-phrase dictionary match of
  the window after substitution; reading-consistency (does the fragment's kana
  context support the kanji's on/kun from kanji table); confusion-neighbor
  constraint (Yencken-style glyph similarity) so 陸→陛 (shared radical) passes a
  different bar than 海幹→迎撃 (whole-word rewrite).
- `gate_rows.json` committed for reuse.

## Honest framing for the roadmap §2 target

Auto-correction was the user's proposal; data says: *right idea, wrong trust
level, today*. The gap between 65% (measured) and 78-85% (audited) is itself
gold-set debt — measuring on cleaned gold first is the cheapest precision
improvement available and is required before any auto-apply decision is
trustworthy.
