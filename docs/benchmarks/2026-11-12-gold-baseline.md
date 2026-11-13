# Gold baseline — 46 user-corrected photos — 2026-11-12 (rev 5, gold cleaned)

> **Membership note (2026-11-13):** this rev measures the set selected by the OLD rule
> (touched photos = gold). Membership is now explicit 金 marks (`scraped_photos.is_gold`)
> and the v7 migration does NOT seed them (user decision: gold era starts from zero) — this
> committed fixture set stays the scoring baseline until the first regeneration run against
> photos the user has personally re-marked.

> **Rev 6 addendum (2026-11-13, regeneration DONE):** user annotated heavily; first
> regeneration under explicit 金 membership. Gold set **127 photos / 185 sentences**
> (from 46/73); 82 photos newly in-set. Headline: **73/185 exact (39.5%)**,
> segmentation-exact 31/127, char accuracy 0.796, nameplate noise excluded: 38
> standalone + 27 inline (81 candidate strings drafted set-wide — provisional ignores,
> review round 4 pending). Grouped: previously-benchmarked set 24/71 (34%), new batch
> 49/114 (43%) — the older 46 are the harder, nameplate-stripped, narration-heavy ones.
> Adjudications: 983 + 1159 retired by user retyping; **969 fixed in-app on 2026-11-13**
> (user asked for the correction; device text now reads 陛下, occurrences renamed,
> rule self-retired) — 6 rules still active (1133, 1149, 1284, 1418, 1420, 1440)
> awaiting in-app retypes. Headline unaffected (73/185) — same gold content, cleaner
> provenance. 1158 self-excluded (user typed __ placeholders for
> unreadable chars and left it unmarked — partially-verified photos are OUT; wildcard
> '__'-in-gold scoring is a proposed small extension if partial membership is wanted).
> **OPEN CONFLICT:** locked fixtures 953 & 1160 now have user 金 gold that supersedes
> their content (953: nameplate dropped + 脱出/手当を fixed; 1160: 差し出せ vs pinned
> 差しtせ). Per Q2 gold wins — locks should be downgraded to segmentation-shape checks
> (sentence count + boundary indices) or retired; pending user decision.

Rev 3 = 25-photo set + harness fixes; rev 4 = expanded to 46. **Rev 5: gold
cleanup round 3 applied** — 10 stored-vs-Cloud word disagreements adjudicated by
the user (all 10 confirmed Cloud = screen truth) and encoded as durable rules in
`gold-adjudications.json`, applied by the generator on every regeneration until
each one self-retires via an in-app retype. Gold sentence total 74 -> 73
(1158 two lines merged into 「アリティアの弓兵部隊に所属するゴードンです。」).

The headline DECREASED and that is the point: correcting gold removes the free
credit the old pipeline got for reproducing local-OCR typos that survived the
nameplate-only correction passes (the user confirmed these were misses, not
chosen text — as-typed gold is a correctness floor, not a ceiling). These
numbers measure distance to *correct* text.

## Headline (nameplate noise excluded)

- Sentence exact: **27/73** = 37.0%
- Mean char accuracy: **0.794**
- Segmentation exact: **7/46** photos (1136, 1138, 1140, 1145, 1410, 1411, 1443)
- Correct set, character distance left: 30 photos
- Wrong sentence set after cleaning: 9 photos (963, 969, 975, 1137, 1158, 1397, 1408, 1416, 1417)
- Leaked nameplate lines ignored: 14 standalone + 11 inline
- Real errors: 4 missed, 13 false extras

Loss anatomy: 36 of the 46 non-exact sentences sit on
correct-set photos (recognition-bound on frozen local blocks); the rest are the
wrong-set photos. Spike-002 re-run on cleaned gold: the word-level plausibility
gate now measures **96% precision, zero breaks at margin>=0.10** (23 true fixes,
coverage 44%) and the end-to-end simulation of gated substitutions lifts exact
21 -> 35 under its own rubric with **0 sentences broken**. Auto-apply threshold
met for the gated subset — first time any lever has.

## Per-photo results

| Photo | Category | Exact/gold | Char acc | Seg exact | Missed | Extras | NP leaks |
|---|---|---|---|---|---|---|---|
| 963 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 3 | 1 |
| 969 | user-corrected-nameplate-prone | 1/2 | 0.740 | — | 0 | 1 | 1 |
| 975 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 2 | 0 |
| 983 | menu-or-ui | 1/3 | 0.887 | — | 0 | 0 | 0 |
| 1133 | user-corrected | 0/1 | 0.852 | — | 0 | 0 | 0 |
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
| 1149 | user-corrected-nameplate-prone | 2/3 | 0.981 | — | 0 | 0 | 1 |
| 1157 | user-corrected-nameplate-prone | 0/1 | 0.600 | — | 0 | 0 | 1 |
| 1158 | user-corrected-nameplate-prone | 1/2 | 0.795 | — | 0 | 2 | 0 |
| 1159 | user-corrected-nameplate-prone | 0/1 | 0.947 | — | 0 | 0 | 1 |
| 1164 | user-corrected | 0/1 | 0.962 | — | 0 | 0 | 0 |
| 1166 | user-corrected-nameplate-prone | 0/1 | 0.912 | — | 0 | 0 | 1 |
| 1174 | user-corrected | 0/1 | 0.880 | — | 0 | 0 | 0 |
| 1278 | user-corrected-nameplate-prone | 1/2 | 0.957 | — | 0 | 0 | 1 |
| 1284 | user-corrected | 0/1 | 0.857 | — | 0 | 0 | 0 |
| 1397 | user-corrected-nameplate-prone | 0/1 | 0.000 | — | 1 | 0 | 1 |
| 1398 | user-corrected-nameplate-prone | 0/1 | 0.654 | — | 0 | 0 | 0 |
| 1405 | user-corrected-nameplate-prone | 0/1 | 0.500 | — | 0 | 0 | 0 |
| 1406 | user-corrected-nameplate-prone | 0/1 | 0.429 | — | 0 | 0 | 0 |
| 1408 | user-corrected | 0/1 | 0.000 | — | 1 | 2 | 0 |
| 1409 | user-corrected | 0/1 | 0.630 | — | 0 | 0 | 0 |
| 1410 | user-corrected-nameplate-prone | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |
| 1411 | user-corrected-nameplate-prone | 2/2 | 1.000 | ✓ | 0 | 0 | 1 |
| 1412 | user-corrected | 1/2 | 0.958 | — | 0 | 0 | 0 |
| 1416 | user-corrected-nameplate-prone | 1/2 | 0.717 | — | 0 | 1 | 1 |
| 1417 | user-corrected-nameplate-prone | 0/2 | 0.753 | — | 0 | 1 | 1 |
| 1418 | user-corrected | 1/2 | 0.896 | — | 0 | 0 | 0 |
| 1419 | user-corrected | 1/3 | 0.920 | — | 0 | 0 | 0 |
| 1420 | user-corrected | 1/2 | 0.942 | — | 0 | 0 | 0 |
| 1421 | user-corrected-nameplate-prone | 0/1 | 0.737 | — | 0 | 0 | 0 |
| 1422 | user-corrected-nameplate-prone | 0/1 | 0.917 | — | 0 | 0 | 0 |
| 1423 | user-corrected-nameplate-prone | 0/2 | 0.912 | — | 0 | 0 | 1 |
| 1430 | user-corrected | 0/1 | 0.865 | — | 0 | 0 | 0 |
| 1440 | user-corrected-nameplate-prone | 0/1 | 0.857 | — | 0 | 0 | 1 |
| 1441 | user-corrected | 0/2 | 0.913 | — | 0 | 0 | 0 |
| 1443 | user-corrected-nameplate-prone | 1/1 | 1.000 | ✓ | 0 | 0 | 1 |

## Failure detail + stage classification

**963** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["世界でただ1人の神剣ファルシオン継承者となるのです。"]
- parsed: ["世界でただ1人の。", "神剣ファルシオン", "鎌承者となるのです。", "一エリス"]

**969** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["私は…", "私は、陛下の最期のお言葉を伝えるために戻りました。"]
- parsed: ["カイン私は…", "私は、陸下の最期のお言葉を", "伝えるために戻りました。"]

**975** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["王子...む...無念です!"]
- parsed: ["カイン画王子…", "無念です!"]

**983** — recognition-bound (set correct; character errors only)
- gold: ["全体危険範囲 閉じるR", "敵全員の攻撃範囲表示", "Xボタンで敵全体の攻撃範囲を表示できます。"]
- parsed: ["全体危険範囲閉じるR", "敵全員の攻華範囲表示", "Xボタンで酸全停の攻等を表示できます。"]

**1133** — recognition-bound (set correct; character errors only)
- gold: ["やがて、敵軍の警戒がゆるやかになり絶好の機会が訪れるが"]
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

**1149** — recognition-bound (set correct; character errors only)
- gold: ["ま、言い返したくとも", "猿ぐつわをかまされてりゃ無理だわな。", "ヘヘヘ"]
- parsed: ["ま、言い返したくとも", "ならずもの。", "猿ぐつわをかまされてリゃ無理だわな。", "ヘヘヘ"]

**1157** — recognition-bound (set correct; character errors only)
- gold: ["君はたしか弓兵部隊の"]
- parsed: ["マル入", "しか引兵部隊の"]

**1158** — parser/segmentation-bound (set wrong even after cleaning)
- gold: ["はい。", "アリティアの弓兵部隊に所属するゴードンです。"]
- parsed: ["はい。", "アリティアの引兵部に所霧する。", "ゴードンです。", "ゴードン"]

**1159** — recognition-bound (set correct; character errors only)
- gold: ["不覚にも敵に捕らわれてしまいこのザマに"]
- parsed: ["不覚にも敵に飾らわれてしまいこのザマに", "ゴードン"]

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
- gold: ["彼らは、付近の村を荒らしたり旅人を襲ったりして大変恐れられております。"]
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
- gold: ["傭兵には気をつけな。", "奴らは、腕がいいから下手ってば一発で倒されるぜ。"]
- parsed: ["傭兵には気をつけな。", "契うは、腕がいいから下手うてば一発て剛されるぜ。"]

**1419** — recognition-bound (set correct; character errors only)
- gold: ["こんな山奥まてで来てくれたんじゃ。", "上産に、この斧やろう。", "どうじゃ、立派なもんじゃろ。"]
- parsed: ["こんな山典まてで来てくれたんじゃ。", "土商に、この斧やろう。", "どうじゃ、立派なもんじゃろ。"]

**1420** — recognition-bound (set correct; character errors only)
- gold: ["しかしな、こいつは、血を吸い肉を喰らう悪魔の斧じゃ 。", "決して使ってはならぬぞ、よいな!"]
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

**1440** — recognition-bound (set correct; character errors only)
- gold: ["おそらく敵も迎撃の軍を出してくるでしょう。"]
- parsed: ["おそらく敵も海幹の軍を出してくるてしょう。", "モ口ドフ"]

**1441** — recognition-bound (set correct; character errors only)
- gold: ["グルニア、ドルーア、グラ...いずれにせよ正規の軍隊が相手だ。", "気をひきしめていかないと。"]
- parsed: ["グルニア、ドルーア、グラいずれにせよ正規の軍隊が相手だ。", "気をひきしていかないと。"]

## Label review state

legit verdicts (round 1+2) frozen in `legitShortLines`; round-2 candidate list
re-drafted against cleaned gold below; `nameplateLabels` still empty (all
current ignores are provisional). Round-3 text adjudications: see
`gold-adjudications.json` (10 rules, all applied).

- 963 candidates: `一エリス`
- 969 candidates: `カイン`
- 975 candidates: `カイン画`
- 1135 candidates: `モロドフー`
- 1136 candidates: `モ口ドフ`
- 1137 candidates: `モロドフ`
- 1138 candidates: `モロドア`
- 1139 candidates: `モロドフ`
- 1140 candidates: `村人`
- 1143 candidates: `グラ軍隊長`
- 1145 candidates: `グラ軍隊長`
- 1147 candidates: `.`, `ならずもの`
- 1148 candidates: `ならずもの(`
- 1149 candidates: `ならずもの。`
- 1157 candidates: `マル入`
- 1159 candidates: `ゴードン`
- 1166 candidates: `モロドフ`
- 1278 candidates: `マルスミ`
- 1397 candidates: `手始縣O¥售空y`
- 1405 candidates: `thapte5`
- 1406 candidates: `thapter7`
- 1410 candidates: `ジュリアン`
- 1411 candidates: `ジュリアンA`
- 1416 candidates: `ジュリアン`
- 1417 candidates: `ュリアンィ`
- 1423 candidates: `ーダ`
- 1440 candidates: `モ口ドフ`
- 1443 candidates: `モ日ドフ/`

## Targets

- Lock photos to `assert_segmentation=true` when a fix passes AND gold confirmed final.
- Next lever (spike 003): gate upgrades; gated-subset auto-apply is now evidence-backed
  but should ship as suggestions first while margin calibration accumulates real-use data.

## Reproducing

```
python3 scripts/build_gold_fixtures.py --db backups/20261112-225328/mining.db \
  --out app/src/test/resources/gold_fixtures.json \
  --existing app/src/test/resources/gold_fixtures.json \
  --mirror app/src/androidTest/assets/gold_fixtures.json
./gradlew :app:testDebugUnitTest --tests "*GoldBenchmark*"
python3 spikes/002-ocr-plausibility-gate/scripts/gate_report.py
```
