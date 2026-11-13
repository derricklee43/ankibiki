# Gold baseline — user-corrected set — 2026-11-12 (rev 3, post label review + harness fix)

Plan: docs/benchmarks/gold-set-iteration-plan.md (Q1–Q3 answered 2026-11-12: gold
scored exactly as typed; user line breaks are the segmentation truth; photo level first).
Fixtures generated from `backups/20261112-155132/mining.db` (25 photos, all frozen
`ocr_blocks_json`) via `scripts/build_gold_fixtures.py`. Scorer: `GoldBenchmarkTest`
(JVM, report-only, deterministic — current `CjkSentenceParser` re-parses frozen blocks;
no device, no corpus writes). Rev 2 = user label-review verdicts (all ambiguous short
lines LEGIT, frozen in `legitShortLines`). Rev 3 = harness fix: candidate drafting and
standalone-ignore now tolerate a trailing hallucinated `。` (ならずもの。 on 1149 was
being charged as a real false extra instead of an ignored nameplate leak).

**Nameplate-agnostic:** nameplate-candidate strings leaking as whole sentences or
merging inline are removed before alignment and counted ONLY in the leak columns.
Candidates remain provisional auto-ignores (`nameplateLabels` empty); strings the
user judged LEGIT can never be auto-ignored (`legitShortLines`).

## Headline (nameplate noise excluded)

- Sentence exact: **22/43** = 51.2% of gold sentences reproduced exactly
- Mean char accuracy (1−NED over all gold sentences): **0.856**
- Segmentation exact: **6/25** photos (1136, 1138, 1140, 1145, 1149, 1159)
- Correct sentence SET, character distance left: 14 photos (983, 1133, 1135, 1139, 1142, 1143, 1147, 1148, 1157, 1164, 1166, 1174, 1278, 1284)
- Wrong sentence set after cleaning: 5 photos (963, 969, 975, 1137, 1158)
- Nameplate leaks ignored from headline: **6 standalone** + **11 inline-merged**
- Real (non-nameplate) errors: 2 gold sentences missed, 8 false extras

Loss anatomy: of the 21 non-exact gold sentences, 16 sit on photos whose sentence
SET is already correct — pure recognition distance (frozen mining-time OCR). The
parser-side headroom is the 5 wrong-set photos; everything else is OCR-bound until
a fresh-OCR (live-layer) measurement says otherwise.

## Per-photo results

| Photo | Category | Exact/gold | Char acc | Seg exact | Missed | Extras | NP leaks |
|---|---|---|---|---|---|---|---|
| 963 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 3 | 1 |
| 969 | user-corrected-nameplate-prone | 1/2 | 0.760 | — | 0 | 1 | 1 |
| 975 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 2 | 0 |
| 983 | menu-or-ui | 2/3 | 0.967 | — | 0 | 0 | 0 |
| 1133 | user-corrected | 0/1 | 0.923 | — | 0 | 0 | 0 |
| 1135 | user-corrected-nameplate-prone | 1/2 | 0.952 | — | 0 | 0 | 1 |
| 1136 | user-corrected-nameplate-prone | 3/3 | 1.000 | ✓ | 0 | 0 | 1 |
| 1137 | user-corrected-nameplate-prone | 1/2 | 0.875 | — | 0 | 1 | 1 |
| 1138 | user-corrected-nameplate-prone | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1139 | user-corrected-nameplate-prone | 0/1 | 0.969 | — | 0 | 0 | 1 |
| 1140 | user-corrected-nameplate-prone | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1142 | user-corrected | 2/4 | 0.917 | — | 0 | 0 | 0 |
| 1143 | user-corrected-nameplate-prone | 1/2 | 0.955 | — | 0 | 0 | 1 |
| 1145 | user-corrected-nameplate-prone | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1147 | user-corrected-nameplate-prone | 0/1 | 0.968 | — | 0 | 0 | 1 |
| 1148 | user-corrected-nameplate-prone | 1/3 | 0.896 | — | 0 | 0 | 1 |
| 1149 | user-corrected-nameplate-prone | 3/3 | 1.000 | ✓ | 0 | 0 | 1 |
| 1157 | user-corrected-nameplate-prone | 0/1 | 0.600 | — | 0 | 0 | 1 |
| 1158 | user-corrected | 2/3 | 0.978 | — | 0 | 1 | 0 |
| 1159 | user-corrected-nameplate-prone | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1164 | user-corrected | 0/1 | 0.962 | — | 0 | 0 | 0 |
| 1166 | user-corrected-nameplate-prone | 0/1 | 0.912 | — | 0 | 0 | 1 |
| 1174 | user-corrected | 0/1 | 0.880 | — | 0 | 0 | 0 |
| 1278 | user-corrected-nameplate-prone | 1/2 | 0.957 | — | 0 | 0 | 1 |
| 1284 | user-corrected | 0/1 | 0.941 | — | 0 | 0 | 0 |

## Failure detail + stage classification

**963** — parser/segmentation-bound (sentence set wrong even after nameplate cleaning)
- gold: ["世界でただ1人の神剣ファルシオン継承者となるのです。"]
- parsed (leaks shown for context): ["世界でただ1人の。", "神剣ファルシオン", "鎌承者となるのです。", "一エリス"]

**969** — parser/segmentation-bound (sentence set wrong even after nameplate cleaning)
- gold: ["私は…", "私は、陸下の最期のお言葉を伝えるために戻りました。"]
- parsed (leaks shown for context): ["カイン私は…", "私は、陸下の最期のお言葉を", "伝えるために戻りました。"]

**975** — parser/segmentation-bound (sentence set wrong even after nameplate cleaning)
- gold: ["王子...む...無念です!"]
- parsed (leaks shown for context): ["カイン画王子…", "無念です!"]

**983** — recognition-bound (sentence set correct; character errors only)
- gold: ["全体危険範囲 閉じるR", "敵全員の攻撃範囲表示", "Xボタンで酸全停の攻等を表示できます。"]
- parsed (leaks shown for context): ["全体危険範囲閉じるR", "敵全員の攻華範囲表示", "Xボタンで酸全停の攻等を表示できます。"]

**1133** — recognition-bound (sentence set correct; character errors only)
- gold: ["やがて、敵軍の警戒がゆるやかになり絶好の様会訪れるが"]
- parsed (leaks shown for context): ["やて、敵軍の警戒がゆるやかになり絶好の様会訪れる"]

**1135** — recognition-bound (sentence set correct; character errors only)
- gold: ["グラの大部隊が後ろから迫ってきております。", "先を急がねばなりません。"]
- parsed (leaks shown for context): ["モロドフーグラの大部隊が後るから迫ってきておりほす。", "先を急がねばなりません。"]

**1137** — parser/segmentation-bound (sentence set wrong even after nameplate cleaning)
- gold: ["ここから北へ抜けましょう。", "北東の港に船を用意してあリます。"]
- parsed (leaks shown for context): ["モロドフここから北へ抜けましょう。", "北東の港に船を用意してあ!", "リます。"]

**1139** — recognition-bound (sentence set correct; character errors only)
- gold: ["途上、心ならずも、グラ軍に味方している者たちおるやもしれません。"]
- parsed (leaks shown for context): ["モロドフ途上、心ならずも、グラ軍に味方している苦たちおるやもしれません。"]

**1142** — recognition-bound (sentence set correct; character errors only)
- gold: ["はい。", "いかがしましょうか?", "軍の主力はまだ到着しておリません。", "今のわれわれの戦力では…"]
- parsed (leaks shown for context): ["はい。", "いかしましょうか?", "軍の主力はまだ到着しておリません。", "新争のわれわれの戦力ては…"]

**1143** — recognition-bound (sentence set correct; character errors only)
- gold: ["なあに、手はあるさ。", "敵を罠にはめればいい。"]
- parsed (leaks shown for context): ["グラ軍隊長なあに、手はあるさ。", "敵を買にはめればいい。"]

**1147** — recognition-bound (sentence set correct; character errors only)
- gold: ["ヘっ、英雄アンリの開いた国っていうからどれだけ強いのかと思えば"]
- parsed (leaks shown for context): ["ヘっ、英注アンリの開いた国っていうからどれだけ強いのかと思えば", "ならずもの"]

**1148** — recognition-bound (sentence set correct; character errors only)
- gold: ["たった数日でこのザマだ。", "アリティアも大したことねえな。", "悔しかったらなんとか言ってみな。"]
- parsed (leaks shown for context): ["たった教てこのザマだ。", "アリティアも大したことねえな。", "ならずもの(", "毎しかったらなんとか言ってみな。"]

**1157** — recognition-bound (sentence set correct; character errors only)
- gold: ["君はたしか弓兵部隊の"]
- parsed (leaks shown for context): ["マル入", "しか引兵部隊の"]

**1158** — parser/segmentation-bound (sentence set wrong even after nameplate cleaning)
- gold: ["はい。", "アリティアの弓兵部に所霧する。", "ゴードンです。"]
- parsed (leaks shown for context): ["はい。", "アリティアの引兵部に所霧する。", "ゴードンです。", "ゴードン"]

**1164** — recognition-bound (sentence set correct; character errors only)
- gold: ["本隊に追いつかれた時のため囮の備えをしておきました。"]
- parsed (leaks shown for context): ["本隊に追いつかれた時のため国の備えをしておきました。"]

**1166** — recognition-bound (sentence set correct; character errors only)
- gold: ["王子の格好をした者がたった一人で逃れようとすれば敵はそれを追うはず。"]
- parsed (leaks shown for context): ["モロドフ王子の格好をした者がGたった人で進れようとすれば敵はそれを追うはず。"]

**1174** — recognition-bound (sentence set correct; character errors only)
- gold: ["もし、盗賊に襲われたら取り返しがつかんことになる。"]
- parsed (leaks shown for context): ["もし、盗映に襲われたら取返しがかんことになる。"]

**1278** — recognition-bound (sentence set correct; character errors only)
- gold: ["受けた恩は、これからの戦いで返していけばいい。", "行こうカシム。"]
- parsed (leaks shown for context): ["マルスミ愛けた恩は、これからの戦いて返していけばいい。", "行こうカシム。"]

**1284** — recognition-bound (sentence set correct; character errors only)
- gold: ["彼らは、付近の村を荒うしたり旅人を帳ったして大変恐れられております。"]
- parsed (leaks shown for context): ["彼うば、付近の村を荒うしたり旅人を帳ったして大変恐れられております。"]

## Label review — verdicts recorded 2026-11-12

User call: every ambiguous short line (unflagged) is LEGIT content; pre-flagged
misreads joined them. Frozen into `legitShortLines` (survives regeneration):

| Photo | Legit (never-ignorable) line |
|---|---|
| 963 | `神剣ファルシオン` |
| 983 | `敵全員の` |
| 983 | `Xボタンで` |
| 983 | `攻華範囲表示` |
| 1133 | `絶好の様会訪れる` |
| 1135 | `グラの大部隊が` |
| 1145 | `おまえたちは` |
| 1157 | `しか引兵部隊の` |
| 1158 | `ゴードン` |
| 1159 | `このザマに` |
| 1284 | `旅人を帳ったして` |

Provisional ignores (auto-drafted nameplate candidates; confirm or reclassify later).
Note 1158: `ゴードン` was called LEGIT (it lives inside the utterance ゴードンです。)
so its standalone leak stays charged as a false extra — a photo-level label could
ignore the leak without eating the utterance if that call is revisited:

- 963: `一エリス`
- 969: `カイン`
- 975: `カイン画`
- 1135: `モロドフー`
- 1136: `モ口ドフ`
- 1137: `モロドフ`
- 1138: `モロドア`
- 1139: `モロドフ`
- 1140: `村人`
- 1143: `グラ軍隊長`
- 1145: `グラ軍隊長`
- 1147: `.`
- 1147: `ならずもの`
- 1148: `ならずもの(`
- 1149: `ならずもの。`
- 1157: `マル入`
- 1159: `ゴードン`
- 1166: `モロドフ`
- 1278: `マルスミ`

## Loss anatomy / iteration targets (rev 3)

Non-exact sentences by lever: recognition-distance-on-correct-set 16 (OCR-bound on
frozen blocks — live layer decides the true share); wrong sentence set 5 photos
(963, 969, 975, 1137, 1158 — parser stitching, 963/975 phantom-`。` riskier).
Nameplate opt-in policy would clear 17 leaked/merged lines (headline-neutral by
design, real correction-effort win).

## Reproducing

```
python3 scripts/build_gold_fixtures.py --db backups/20261112-155132/mining.db \
  --out app/src/test/resources/gold_fixtures.json \
  --existing app/src/test/resources/gold_fixtures.json   # keeps labels + legit verdicts
./gradlew :app:testDebugUnitTest --tests "*GoldBenchmark*"
# JSON lines: BENCHMARK_GOLD_FIXTURE / BENCHMARK_GOLD_SUMMARY in the test XML stdout
```
