# Gold baseline — user-corrected set — 2026-11-12 (rev 2, post label review)

Plan: docs/benchmarks/gold-set-iteration-plan.md (Q1–Q3 answered 2026-11-12: gold
scored exactly as typed; user line breaks are the segmentation truth; photo level first).
Fixtures generated from `backups/20261112-155132/mining.db` (25 photos, all with frozen
`ocr_blocks_json`) via `scripts/build_gold_fixtures.py`. Scorer: `GoldBenchmarkTest`
(JVM, report-only, deterministic — current `CjkSentenceParser` re-parses frozen blocks;
no device, no corpus writes). Rev 2 applies the user's label review verdicts.

**Nameplate-agnostic:** nameplate-candidate strings that leak as whole sentences or
merge inline onto an utterance are removed before alignment and counted ONLY in the
leak columns — they never move headline metrics. Candidates are still auto-drafted
provisional ignores (nameplateLabels not yet frozen); every string the user judged
LEGIT is now recorded in `legitShortLines` and can never be auto-ignored again.

## Headline (nameplate noise excluded)

- Sentence exact: **22/43** = 51.2% of gold sentences reproduced exactly
- Mean char accuracy (1−NED over all gold sentences): **0.856**
- Segmentation exact (all gold sentences matched exactly, zero non-nameplate extras): **5/25** photos (1136, 1138, 1140, 1145, 1159)
- Correct sentence SET, character distance left: 14 photos (983, 1133, 1135, 1139, 1142, 1143, 1147, 1148, 1157, 1164, 1166, 1174, 1278, 1284)
- Wrong sentence set (missed/extra after cleaning): 6 photos (963, 969, 975, 1137, 1149, 1158)
- Nameplate leaks ignored from headline: **5 standalone** + **11 inline-merged**
- Real (non-nameplate) errors: 2 gold sentences missed, 9 false extras

Interpretation: the parser's stitching already produces the correct sentence SET on
19/25 photos; most exact-match loss is recognition-bound
(mining-time OCR character errors the user fixed by hand). The parser-side headroom is
the 6 wrong-set photos, two of them phantom-`。` stitch blocks (963, 975-shape).

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
| 1149 | user-corrected | 3/3 | 1.000 | — | 0 | 1 | 0 |
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

**1149** — parser/segmentation-bound (sentence set wrong even after nameplate cleaning)
- gold: ["ま、言い返したくとも", "猿ぐつわをかまされてリゃ無理だわな。", "ヘヘヘ"]
- parsed (leaks shown for context): ["ま、言い返したくとも", "ならずもの。", "猿ぐつわをかまされてリゃ無理だわな。", "ヘヘヘ"]

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

User call: every ambiguous short line (unflagged) is LEGIT content. Combined with the
pre-flagged misreads, these strings are frozen into `legitShortLines` in the fixtures
(the generator now never re-drafts them as nameplate candidates):

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

Still provisional — auto-ignored as nameplate candidates until confirmed NAMEPLATE or
moved to legitShortLines if they turn out to be content:

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
- 1147: `ならずもの`
- 1148: `ならずもの(`
- 1157: `マル入`
- 1159: `ゴードン`
- 1166: `モロドフ`
- 1278: `マルスミ`

## Targets (proposed, not gating)

- Promote a photo to `assert_segmentation=true` only when a parser change makes it
  pass AND the user confirms its gold final. Never gate recognition-bound photos
  on exact text.
- Headline to beat next iteration: sentence exact > 22/43; wrong-set photos < 6.
- 963-class failures (phantom `。` blocking stitching) stay red until the OCR path
  changes — parser work must not claim them.

## Reproducing

```
python3 scripts/build_gold_fixtures.py --db backups/20261112-155132/mining.db \
  --out app/src/test/resources/gold_fixtures.json \
  --existing app/src/test/resources/gold_fixtures.json   # keeps reviewed labels + legit verdicts
./gradlew :app:testDebugUnitTest --tests "*GoldBenchmark*"
# JSON lines: BENCHMARK_GOLD_FIXTURE / BENCHMARK_GOLD_SUMMARY in the test XML stdout
```
