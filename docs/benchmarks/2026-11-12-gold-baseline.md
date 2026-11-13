# Gold baseline — user-corrected set, first run — 2026-11-12

Plan: docs/benchmarks/gold-set-iteration-plan.md (Q1–Q3 answered same day: gold
scored exactly as typed; user \n breaks are the segmentation truth; photo level first).
Fixtures generated from `backups/20261112-155132/mining.db` (25 photos, all with frozen
`ocr_blocks_json`) via `scripts/build_gold_fixtures.py`. Scorer:
`GoldBenchmarkTest` (JVM, report-only, deterministic — current `CjkSentenceParser`
re-parses frozen blocks; no device, no corpus writes).

**Nameplate-agnostic:** confirmed labels + unambiguous auto-draft candidates that leak
as whole sentences or merge inline onto an utterance are removed before alignment and
counted ONLY in the leak columns — they never move headline metrics. Ambiguous short
lines are never auto-ignored; they await the review below.

## Headline (nameplate noise excluded)

- Sentence exact: **22/43** = 51.2% of gold sentences reproduced exactly
- Mean char accuracy (1−NED over all gold sentences): **0.832**
- Segmentation exact (all gold sentences matched, zero non-nameplate extras): **5/25** photos (1136, 1138, 1140, 1145, 1159)
- Nameplate leaks ignored from headline: **6 standalone** + **11 inline-merged** over 16 photos
- Real (non-nameplate) errors: 3 gold sentences missed, 9 false extras

Interpretation: segmentation is no longer the bottleneck on most photos — 18/25
photos already emit a fully correct sentence SET (5 exactly, 13 with only
character-level distance left, char acc ≥ 0.88); the remaining headroom is
mostly recognition-bound (mining-time OCR errors the user corrected by hand),
plus the two 3-line-wrap failures (963, 975) and one misdrafted candidate
(1157, see review).

## Per-photo results

| Photo | Category | Exact/gold | Char acc | Seg exact | Missed | Extras | NP leaks |
|---|---|---|---|---|---|---|---|
| 963 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 3 | 1 |
| 969 | user-corrected-nameplate-prone | 1/2 | 0.760 | — | 0 | 1 | 1 |
| 975 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 2 | 0 |
| 983 | menu-or-ui | 2/3 | 0.967 | — | 0 | 0 | 0 |
| 1133 | user-corrected-nameplate-prone | 0/1 | 0.923 | — | 0 | 0 | 0 |
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
| 1157 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 0 | 2 |
| 1158 | user-corrected-nameplate-prone | 2/3 | 0.978 | — | 0 | 1 | 0 |
| 1159 | user-corrected-nameplate-prone | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1164 | user-corrected | 0/1 | 0.962 | — | 0 | 0 | 0 |
| 1166 | user-corrected-nameplate-prone | 0/1 | 0.912 | — | 0 | 0 | 1 |
| 1174 | user-corrected | 0/1 | 0.880 | — | 0 | 0 | 0 |
| 1278 | user-corrected-nameplate-prone | 1/2 | 0.957 | — | 0 | 0 | 1 |
| 1284 | user-corrected-nameplate-prone | 0/1 | 0.941 | — | 0 | 0 | 0 |

## Failure detail + stage classification

**963** — parser/segmentation-bound (sentence set wrong)
- gold: ["世界でただ1人の神剣ファルシオン継承者となるのです。"]
- parsed (leaks still shown for context): ["世界でただ1人の。", "神剣ファルシオン", "鎌承者となるのです。", "一エリス"]

**969** — parser/segmentation-bound (sentence set wrong)
- gold: ["私は…", "私は、陸下の最期のお言葉を伝えるために戻りました。"]
- parsed (leaks still shown for context): ["カイン私は…", "私は、陸下の最期のお言葉を", "伝えるために戻りました。"]

**975** — parser/segmentation-bound (sentence set wrong)
- gold: ["王子...む...無念です!"]
- parsed (leaks still shown for context): ["カイン画王子…", "無念です!"]

**983** — recognition-bound (character errors only; segmentation correct)
- gold: ["全体危険範囲 閉じるR", "敵全員の攻撃範囲表示", "Xボタンで酸全停の攻等を表示できます。"]
- parsed (leaks still shown for context): ["全体危険範囲閉じるR", "敵全員の攻華範囲表示", "Xボタンで酸全停の攻等を表示できます。"]

**1133** — recognition-bound (character errors only; segmentation correct)
- gold: ["やがて、敵軍の警戒がゆるやかになり絶好の様会訪れるが"]
- parsed (leaks still shown for context): ["やて、敵軍の警戒がゆるやかになり絶好の様会訪れる"]

**1135** — recognition-bound (character errors only; segmentation correct)
- gold: ["グラの大部隊が後ろから迫ってきております。", "先を急がねばなりません。"]
- parsed (leaks still shown for context): ["モロドフーグラの大部隊が後るから迫ってきておりほす。", "先を急がねばなりません。"]

**1137** — parser/segmentation-bound (sentence set wrong)
- gold: ["ここから北へ抜けましょう。", "北東の港に船を用意してあリます。"]
- parsed (leaks still shown for context): ["モロドフここから北へ抜けましょう。", "北東の港に船を用意してあ!", "リます。"]

**1139** — recognition-bound (character errors only; segmentation correct)
- gold: ["途上、心ならずも、グラ軍に味方している者たちおるやもしれません。"]
- parsed (leaks still shown for context): ["モロドフ途上、心ならずも、グラ軍に味方している苦たちおるやもしれません。"]

**1142** — recognition-bound (character errors only; segmentation correct)
- gold: ["はい。", "いかがしましょうか?", "軍の主力はまだ到着しておリません。", "今のわれわれの戦力では…"]
- parsed (leaks still shown for context): ["はい。", "いかしましょうか?", "軍の主力はまだ到着しておリません。", "新争のわれわれの戦力ては…"]

**1143** — recognition-bound (character errors only; segmentation correct)
- gold: ["なあに、手はあるさ。", "敵を罠にはめればいい。"]
- parsed (leaks still shown for context): ["グラ軍隊長なあに、手はあるさ。", "敵を買にはめればいい。"]

**1147** — recognition-bound (character errors only; segmentation correct)
- gold: ["ヘっ、英雄アンリの開いた国っていうからどれだけ強いのかと思えば"]
- parsed (leaks still shown for context): ["ヘっ、英注アンリの開いた国っていうからどれだけ強いのかと思えば", "ならずもの"]

**1148** — recognition-bound (character errors only; segmentation correct)
- gold: ["たった数日でこのザマだ。", "アリティアも大したことねえな。", "悔しかったらなんとか言ってみな。"]
- parsed (leaks still shown for context): ["たった教てこのザマだ。", "アリティアも大したことねえな。", "ならずもの(", "毎しかったらなんとか言ってみな。"]

**1149** — parser/segmentation-bound (sentence set wrong)
- gold: ["ま、言い返したくとも", "猿ぐつわをかまされてリゃ無理だわな。", "ヘヘヘ"]
- parsed (leaks still shown for context): ["ま、言い返したくとも", "ならずもの。", "猿ぐつわをかまされてリゃ無理だわな。", "ヘヘヘ"]

**1157** — parser/segmentation-bound (sentence set wrong)
- gold: ["君はたしか弓兵部隊の"]
- parsed (leaks still shown for context): ["マル入", "しか引兵部隊の"]

**1158** — parser/segmentation-bound (sentence set wrong)
- gold: ["はい。", "アリティアの弓兵部に所霧する。", "ゴードンです。"]
- parsed (leaks still shown for context): ["はい。", "アリティアの引兵部に所霧する。", "ゴードンです。", "ゴードン"]

**1164** — recognition-bound (character errors only; segmentation correct)
- gold: ["本隊に追いつかれた時のため囮の備えをしておきました。"]
- parsed (leaks still shown for context): ["本隊に追いつかれた時のため国の備えをしておきました。"]

**1166** — recognition-bound (character errors only; segmentation correct)
- gold: ["王子の格好をした者がたった一人で逃れようとすれば敵はそれを追うはず。"]
- parsed (leaks still shown for context): ["モロドフ王子の格好をした者がGたった人で進れようとすれば敵はそれを追うはず。"]

**1174** — recognition-bound (character errors only; segmentation correct)
- gold: ["もし、盗賊に襲われたら取り返しがつかんことになる。"]
- parsed (leaks still shown for context): ["もし、盗映に襲われたら取返しがかんことになる。"]

**1278** — recognition-bound (character errors only; segmentation correct)
- gold: ["受けた恩は、これからの戦いで返していけばいい。", "行こうカシム。"]
- parsed (leaks still shown for context): ["マルスミ愛けた恩は、これからの戦いて返していけばいい。", "行こうカシム。"]

**1284** — recognition-bound (character errors only; segmentation correct)
- gold: ["彼らは、付近の村を荒うしたり旅人を帳ったして大変恐れられております。"]
- parsed (leaks still shown for context): ["彼うば、付近の村を荒うしたり旅人を帳ったして大変恐れられております。"]

## Nameplate label review (confirm / reject; confirmations freeze into nameplateLabels)

Unambiguous candidates (ignored from headline in this run — REJECT any that are real content):

| Photo | Candidate | Seen as | Confirm? |
|---|---|---|---|
| 963 | `一エリス` | standalone leak | ☐ |
| 969 | `カイン` | inline merge | ☐ |
| 975 | `カイン画` | inline merge | ☐ |
| 983 | `攻華範囲表示` | not leaked by current parser | ☐ |
| 1135 | `モロドフー` | inline merge | ☐ |
| 1136 | `モ口ドフ` | inline merge | ☐ |
| 1137 | `モロドフ` | inline merge | ☐ |
| 1138 | `モロドア` | inline merge | ☐ |
| 1139 | `モロドフ` | inline merge | ☐ |
| 1140 | `村人` | inline merge | ☐ |
| 1143 | `グラ軍隊長` | inline merge | ☐ |
| 1145 | `グラ軍隊長` | inline merge | ☐ |
| 1147 | `ならずもの` | standalone leak | ☐ |
| 1148 | `ならずもの(` | standalone leak | ☐ |
| 1157 | `マル入` | standalone leak | ☐ |
| 1157 | `しか引兵部隊の` | standalone leak | ☐ |
| 1159 | `ゴードン` | standalone leak | ☐ |
| 1166 | `モロドフ` | inline merge | ☐ |
| 1278 | `マルスミ` | inline merge | ☐ |

Ambiguous short lines (NOT ignored anywhere — need a NAMEPLATE/LEGIT call):

| Photo | Line | Why ambiguous | Confirm? |
|---|---|---|---|
| 963 | `神剣ファルシオン` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 983 | `敵全員の` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 983 | `Xボタンで` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 1133 | `絶好の様会訪れる` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 1135 | `グラの大部隊が` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 1145 | `おまえたちは` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 1158 | `ゴードン` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 1159 | `このザマに` | also inside a gold utterance | ☐ nameplate / ☐ legit |
| 1284 | `旅人を帳ったして` | also inside a gold utterance | ☐ nameplate / ☐ legit |

Flags from this run: `1157/しか引兵部隊の` was auto-drafted as a candidate and wrongly
ignored (it is an OCR misread of the gold line 君はたしか弓兵部隊の — REJECT); `983`
menu fragments 攻華範囲表示/敵全員の/Xボタンで are misread UI text, not nameplates
(REJECT all three; 983 is the menu control photo).

## Targets (proposed, not gating)

- After label review + REJECT fixes: promote to `assert_segmentation=true` per photo
  only when a parser change makes it pass AND the user confirms gold final. Never gate
  recognition-bound photos on exact text.
- Headline to beat next iteration: sentence exact > 22/43; segmentation exact > 5/25.
- 963-class failures (phantom 。 blocking stitching) stay red until the OCR path
  changes — parser work must not claim them.

## Reproducing

```
python3 scripts/build_gold_fixtures.py --db backups/20261112-155132/mining.db \
  --out app/src/test/resources/gold_fixtures.json \
  --existing app/src/test/resources/gold_fixtures.json   # keeps reviewed labels on regen
./gradlew :app:testDebugUnitTest --tests "*GoldBenchmark*"
# JSON lines: BENCHMARK_GOLD_FIXTURE / BENCHMARK_GOLD_SUMMARY in the test XML stdout
```
