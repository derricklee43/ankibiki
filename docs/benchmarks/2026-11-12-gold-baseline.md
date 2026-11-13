# Gold baseline — 46 user-corrected photos — 2026-11-12 (rev 4, set expanded)

Set grew 25 -> 46: 21 newly corrected photos (1397-1443) from fresh backup
`backups/20261112-225328/mining.db`; no re-edits of the first 25. Harness
changes since rev 3: (a) candidate drafting + standalone ignores tolerate a
trailing hallucinated `。`; (b) misread-fragment guard — a short line that is a
substring of, or within 40% edits of, some gold sentence window is drafted
AMBIGUOUS (review-only, never ignored) instead of a nameplate candidate —
prevents OCR misreads of real content (跡上がると for 技が上ると) from being
auto-ignored. The OLD-25 scores are unchanged by (b) — verified same-run.
Fixtures: `scripts/build_gold_fixtures.py` + `--existing` (legit verdicts and
labels persist); scorer: `GoldBenchmarkTest` (JVM, report-only).

## Headline (nameplate noise excluded)

- Sentence exact: **32/74** = 43.2%
- Mean char accuracy: **0.809**
- Segmentation exact: **10/46** photos
- Correct set, character distance left: 27 photos; wrong set after cleaning: 9 photos (963, 969, 975, 1137, 1158, 1397, 1408, 1416, 1417)
- Leaked nameplate lines ignored: 14 standalone + 11 inline

| Group | Sentence exact | Seg-exact photos | Avg char acc | Missed | Extras | Leaks ignored |
|---|---|---|---|---|---|---|
| OLD-25 (rev-3 harness) | 22/43 (51%) | 6/25 | 0.856 | 2 | 8 | 17 |
| NEW-21 (1397-1443) | 10/31 (32%) | 4/21 | 0.753 | 2 | 4 | 8 |

Loss anatomy (all 42 non-exact gold sentences): 32 sit on photos whose
sentence SET is already correct (recognition distance on frozen mining OCR);
9 photos have a wrong set even after cleaning. The NEW-21 group
scores notably worse than the OLD-25 — it is narration-heavy (one sentence
across 3-4 OCR blocks, and 1397->1398 shows a single narration spanning two
PHOTOS), which the block-stitching rules were not tuned for. Also first
Chinese-tagged photo in gold (1397) — note its extracted_text is Japanese
narration; the language tag looks wrong and is a corpus correction, not a
parser issue.

## Per-photo results

| Photo | Group | Exact/gold | Char acc | Seg exact | Missed | Extras | NP leaks |
|---|---|---|---|---|---|---|---|
| 963 | old | 0/1 | 0.000 | — | 1 | 3 | 1 |
| 969 | old | 1/2 | 0.760 | — | 0 | 1 | 1 |
| 975 | old | 0/1 | 0.000 | — | 1 | 2 | 0 |
| 983 | old | 2/3 | 0.967 | — | 0 | 0 | 0 |
| 1133 | old | 0/1 | 0.923 | — | 0 | 0 | 0 |
| 1135 | old | 1/2 | 0.952 | — | 0 | 0 | 1 |
| 1136 | old | 3/3 | 1.000 | ✓ | 0 | 0 | 1 |
| 1137 | old | 1/2 | 0.875 | — | 0 | 1 | 1 |
| 1138 | old | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1139 | old | 0/1 | 0.969 | — | 0 | 0 | 1 |
| 1140 | old | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1142 | old | 2/4 | 0.917 | — | 0 | 0 | 0 |
| 1143 | old | 1/2 | 0.955 | — | 0 | 0 | 1 |
| 1145 | old | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1147 | old | 0/1 | 0.968 | — | 0 | 0 | 1 |
| 1148 | old | 1/3 | 0.896 | — | 0 | 0 | 1 |
| 1149 | old | 3/3 | 1.000 | ✓ | 0 | 0 | 1 |
| 1157 | old | 0/1 | 0.600 | — | 0 | 0 | 1 |
| 1158 | old | 2/3 | 0.978 | — | 0 | 1 | 0 |
| 1159 | old | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1164 | old | 0/1 | 0.962 | — | 0 | 0 | 0 |
| 1166 | old | 0/1 | 0.912 | — | 0 | 0 | 1 |
| 1174 | old | 0/1 | 0.880 | — | 0 | 0 | 0 |
| 1278 | old | 1/2 | 0.957 | — | 0 | 0 | 1 |
| 1284 | old | 0/1 | 0.941 | — | 0 | 0 | 0 |
| 1397 | new | 0/1 | 0.000 | — | 1 | 0 | 1 |
| 1398 | new | 0/1 | 0.654 | — | 0 | 0 | 0 |
| 1405 | new | 0/1 | 0.500 | — | 0 | 0 | 0 |
| 1406 | new | 0/1 | 0.429 | — | 0 | 0 | 0 |
| 1408 | new | 0/1 | 0.000 | — | 1 | 2 | 0 |
| 1409 | new | 0/1 | 0.630 | — | 0 | 0 | 0 |
| 1410 | new | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1411 | new | 2/2 | 1.000 | ✓ | 0 | 0 | 1 |
| 1412 | new | 1/2 | 0.958 | — | 0 | 0 | 0 |
| 1416 | new | 1/2 | 0.717 | — | 0 | 1 | 1 |
| 1417 | new | 0/2 | 0.753 | — | 0 | 1 | 1 |
| 1418 | new | 1/2 | 0.938 | — | 0 | 0 | 0 |
| 1419 | new | 1/3 | 0.920 | — | 0 | 0 | 0 |
| 1420 | new | 1/2 | 0.962 | — | 0 | 0 | 0 |
| 1421 | new | 0/1 | 0.737 | — | 0 | 0 | 0 |
| 1422 | new | 0/1 | 0.917 | — | 0 | 0 | 0 |
| 1423 | new | 0/2 | 0.912 | — | 0 | 0 | 1 |
| 1430 | new | 0/1 | 0.865 | — | 0 | 0 | 0 |
| 1440 | new | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1441 | new | 0/2 | 0.913 | — | 0 | 0 | 0 |
| 1443 | new | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |

## Failure detail + stage classification

**963** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["世界でただ1人の神剣ファルシオン継承者となるのです。"]
- parsed: ["世界でただ1人の。", "神剣ファルシオン", "鎌承者となるのです。", "一エリス"]

**969** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["私は…", "私は、陸下の最期のお言葉を伝えるために戻りました。"]
- parsed: ["カイン私は…", "私は、陸下の最期のお言葉を", "伝えるために戻りました。"]

**975** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["王子...む...無念です!"]
- parsed: ["カイン画王子…", "無念です!"]

**983** — recognition-bound (set correct; character errors only)
- gold: ["全体危険範囲 閉じるR", "敵全員の攻撃範囲表示", "Xボタンで酸全停の攻等を表示できます。"]
- parsed: ["全体危険範囲閉じるR", "敵全員の攻華範囲表示", "Xボタンで酸全停の攻等を表示できます。"]

**1133** — recognition-bound (set correct; character errors only)
- gold: ["やがて、敵軍の警戒がゆるやかになり絶好の様会訪れるが"]
- parsed: ["やて、敵軍の警戒がゆるやかになり絶好の様会訪れる"]

**1135** — recognition-bound (set correct; character errors only)
- gold: ["グラの大部隊が後ろから迫ってきております。", "先を急がねばなりません。"]
- parsed: ["モロドフーグラの大部隊が後るから迫ってきておりほす。", "先を急がねばなりません。"]

**1137** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["ここから北へ抜けましょう。", "北東の港に船を用意してあリます。"]
- parsed: ["モロドフここから北へ抜けましょう。", "北東の港に船を用意してあ!", "リます。"]

**1139** — recognition-bound (set correct; character errors only)
- gold: ["途上、心ならずも、グラ軍に味方している者たちおるやもしれません。"]
- parsed: ["モロドフ途上、心ならずも、グラ軍に味方している苦たちおるやもしれません。"]

**1142** — recognition-bound (set correct; character errors only)
- gold: ["はい。", "いかがしましょうか?", "軍の主力はまだ到着しておリません。", "今のわれわれの戦力では…"]
- parsed: ["はい。", "いかしましょうか?", "軍の主力はまだ到着しておリません。", "新争のわれわれの戦力ては…"]

**1143** — recognition-bound (set correct; character errors only)
- gold: ["なあに、手はあるさ。", "敵を罠にはめればいい。"]
- parsed: ["グラ軍隊長なあに、手はあるさ。", "敵を買にはめればいい。"]

**1147** — recognition-bound (set correct; character errors only)
- gold: ["ヘっ、英雄アンリの開いた国っていうからどれだけ強いのかと思えば"]
- parsed: ["ヘっ、英注アンリの開いた国っていうからどれだけ強いのかと思えば", "ならずもの"]

**1148** — recognition-bound (set correct; character errors only)
- gold: ["たった数日でこのザマだ。", "アリティアも大したことねえな。", "悔しかったらなんとか言ってみな。"]
- parsed: ["たった教てこのザマだ。", "アリティアも大したことねえな。", "ならずもの(", "毎しかったらなんとか言ってみな。"]

**1157** — recognition-bound (set correct; character errors only)
- gold: ["君はたしか弓兵部隊の"]
- parsed: ["マル入", "しか引兵部隊の"]

**1158** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["はい。", "アリティアの弓兵部に所霧する。", "ゴードンです。"]
- parsed: ["はい。", "アリティアの引兵部に所霧する。", "ゴードンです。", "ゴードン"]

**1164** — recognition-bound (set correct; character errors only)
- gold: ["本隊に追いつかれた時のため囮の備えをしておきました。"]
- parsed: ["本隊に追いつかれた時のため国の備えをしておきました。"]

**1166** — recognition-bound (set correct; character errors only)
- gold: ["王子の格好をした者がたった一人で逃れようとすれば敵はそれを追うはず。"]
- parsed: ["モロドフ王子の格好をした者がGたった人で進れようとすれば敵はそれを追うはず。"]

**1174** — recognition-bound (set correct; character errors only)
- gold: ["もし、盗賊に襲われたら取り返しがつかんことになる。"]
- parsed: ["もし、盗映に襲われたら取返しがかんことになる。"]

**1278** — recognition-bound (set correct; character errors only)
- gold: ["受けた恩は、これからの戦いで返していけばいい。", "行こうカシム。"]
- parsed: ["マルスミ愛けた恩は、これからの戦いて返していけばいい。", "行こうカシム。"]

**1284** — recognition-bound (set correct; character errors only)
- gold: ["彼らは、付近の村を荒うしたり旅人を帳ったして大変恐れられております。"]
- parsed: ["彼うば、付近の村を荒うしたり旅人を帳ったして大変恐れられております。"]

**1397** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["国境地帯からアカネイアにぬける最大の難関は"]
- parsed: ["手始縣O¥售空y"]

**1398** — recognition-bound (set correct; character errors only)
- gold: ["半島を南東に貫くサ厶スーフのけわしい山なみであった。"]
- parsed: ["半島を南郡異くサムスーフのすしいなであった"]

**1405** — recognition-bound (set correct; character errors only)
- gold: ["いつの頃からこに山賊たちが巣くい"]
- parsed: ["つの頃らしこに1競大ちが単く"]

**1406** — recognition-bound (set correct; character errors only)
- gold: ["近くのこ村々や旅人達をおそうようになった。"]
- parsed: ["おそううになった。"]

**1408** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["人々は恐れをこめて、この山をデビルマウント(悪魔の山)"]
- parsed: ["ま相をごて、このを。", "デビルマウンテン(悪磨の山1"]

**1409** — recognition-bound (set correct; character errors only)
- gold: ["彼らをサムスーフの悪魔(サムシアン)と呼んでいた..."]
- parsed: ["彼うをサ払スーフの悪籍サムンンと呼んでいた"]

**1412** — recognition-bound (set correct; character errors only)
- gold: ["でも、大事なリライブの杖を置いてきてしまったの。", "あれをとりもどさないと"]
- parsed: ["でも、大事なリライプの林を置いてきてしまったの。", "あれをとりもどさないと"]

**1416** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["そうはいかねえよ。", "それじゃ、なんのためにオレは奴らを裹切ったのかわかりゃねえ。"]
- parsed: ["そうはいかねえよ。", "それじゃ、なんのた奇にオレは", "ジュリアン", "架らを裏切ったのか分かリゃしねえ。"]

**1417** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["杖は、オレが後できっと取り返す。", "だから、今は逃げることだけを考えろよ!"]
- parsed: ["秋は、オレが後できっと取返す。", "だから、今は", "ュリアンィ", "選げることだけを考えろよ!"]

**1418** — recognition-bound (set correct; character errors only)
- gold: ["傭兵には気をつけな。", "奴らは、腕がいいから下手うてば一発て倒されるぜ。"]
- parsed: ["傭兵には気をつけな。", "契うは、腕がいいから下手うてば一発て剛されるぜ。"]

**1419** — recognition-bound (set correct; character errors only)
- gold: ["こんな山奥まてで来てくれたんじゃ。", "上産に、この斧やろう。", "どうじゃ、立派なもんじゃろ。"]
- parsed: ["こんな山典まてで来てくれたんじゃ。", "土商に、この斧やろう。", "どうじゃ、立派なもんじゃろ。"]

**1420** — recognition-bound (set correct; character errors only)
- gold: ["しかしな、こいつは、血を吸い肉を喰らう悪磨の斧じゃ 。", "決して使ってはならぬぞ、よいな!"]
- parsed: ["し加しな、こいつは、血を吸い内を喰らう悪磨の斧じゃ。", "決して使ってはならぬぞ、よいな!"]

**1421** — recognition-bound (set correct; character errors only)
- gold: ["技が上ると必殺の一撃が出やすくなるの。"]
- parsed: ["跡上がると必殺の一幹が出やずくなるの。"]

**1422** — recognition-bound (set correct; character errors only)
- gold: ["素早さが上がると敵の攻撃をかわしやすくなるよ。"]
- parsed: ["素早さが上がると敵の攻幹を、かわしやすくなるよ。"]

**1423** — recognition-bound (set correct; character errors only)
- gold: ["剣士ナバール。", "あなたぼどの方が、どうしてこんな盗賊たちの用心棒をしているの?"]
- parsed: ["剣十ナバール。", "あなたぼどの方が、どうしてこんな盗時たちの用心棒をしているの?", "ーダ"]

**1430** — recognition-bound (set correct; character errors only)
- gold: ["お前が命をかけてまでおれが必要だというのなら仕方がない、力を貸してやろう。"]
- parsed: ["お前が命を加けてきでおれが必要だというのなら仕方がない、してやろう。"]

**1441** — recognition-bound (set correct; character errors only)
- gold: ["グルニア、ドルーア、グラ...いずれにせよ正規の軍隊が相手だ。", "気をひきしめていかないと。"]
- parsed: ["グルニア、ドルーア、グラいずれにせよ正規の軍隊が相手だ。", "気をひきしていかないと。"]

## Review round 2 — new photos' drafted strings (verdicts pending)

Provisional ignores (counted as nameplate leaks until you confirm or reclassify):

- 1397: `手始縣O¥售空y`
- 1405: `thapte5`
- 1406: `thapter7`
- 1410: `ジュリアン`
- 1411: `ジュリアンA`
- 1416: `ジュリアン`
- 1417: `ュリアンィ`
- 1423: `ーダ`
- 1440: `モ口ドフ`
- 1443: `モ日ドフ/`

Ambiguous short lines (NOT ignored anywhere — nameplate vs misread-fragment call):

- 1398: `すしいなであった`
- 1405: `つの頃ら`
- 1406: `おそううになった。`
- 1410: `来てるっていうし`
- 1417: `だから、今は`
- 1421: `跡上がると`
- 1422: `素早さが上がると`
- 1422: `敵の攻幹を`
- 1423: `剣十ナバール。`
- 1440: `おそらく敵も`
- 1443: `敵と戦う前に`

Frozen so far (round 1, LEGIT, never re-draftable): 963/神剣ファルシオン, 983/敵全員の, 983/Xボタンで, 983/攻華範囲表示, 1133/絶好の様会訪れる, 1135/グラの大部隊が, 1145/おまえたちは, 1157/しか引兵部隊の, 1158/ゴードン, 1159/このザマに, 1284/旅人を帳ったして

## Reproducing

```
python3 scripts/build_gold_fixtures.py --db backups/20261112-225328/mining.db \
  --out app/src/test/resources/gold_fixtures.json \
  --existing app/src/test/resources/gold_fixtures.json
./gradlew :app:testDebugUnitTest --tests "*GoldBenchmark*"
```
