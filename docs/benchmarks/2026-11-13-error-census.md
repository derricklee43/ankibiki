# Error census — where the remaining sentence failures live

Generated offline by `scripts/error_census.py` from `gold_run9` (126 gold photos,
181 gold sentences). Nameplate noise excluded
up front exactly as the headline scorer does: 38 standalone leaks +
27 inline merges. Every scored pool line and unmatched gold lands in ONE bucket.

Method note: this census aligns greedily (best-similarity first, threshold 0.4), so its
exact count (72) can differ by ±1 from the official headline (73, scorer alignment) —
the DISTRIBUTION is the deliverable, not the exact-match count.

| class | count | share |
|---|---|---|
| exact match | 72 | 31.9% |
| extra garbage (no gold match) | 45 | 19.9% |
| boundary/stitch (length mismatch) | 30 | 13.3% |
| misread (1-2 chars) | 28 | 12.4% |
| misread (3-5 chars) | 26 | 11.5% |
| heavy rewrite / garbage tail | 14 | 6.2% |
| punctuation/latin-only | 8 | 3.5% |
| missing gold (never emitted) | 3 | 1.3% |

## Examples (up to 3 per class, `photoId | gold -> parsed`)

### extra garbage (no gold match) (45)
- #954  ``  ->  `王子`
- #956  ``  ->  `モロドフエリス様`
- #958  ``  ->  `国を守るため`

### boundary/stitch (length mismatch) (30)
- #958  `国を守るため城を守るために多くの民が命を落としました。`  ->  `城を守るために`
- #959  `この結果を誰かが背負わねばなりません。`  ->  `この結果を`
- #960  `父上、母上の安否が分からぬ今私がその責を担うのは当然のことです。`  ->  `父上、母上の変否が分からぬ今`

### misread (1-2 chars) (28)
- #956  `脱出のご準備を。`  ->  `脱出のご準備能。`
- #966  `こうして、マルス王子はジェイガンたちと共に`  ->  `こうして、マルス王刊はジェイガンたちと共に`
- #967  `だが、さらに過酷な運命がマルスたちを待っているのだった。`  ->  `だが、さらに過詰な運命がマルスたちを待っているのだった。`

### misread (3-5 chars) (26)
- #953  `早く手当を...`  ->  `早く手当階·.`
- #953  `...その傷でグラから脱出してきたのか?`  ->  `その傷でグラから脱中してきたのか?`
- #954  `王子今はここの脱出が先です。`  ->  `今はここの税出が先です。`

### heavy rewrite / garbage tail (14)
- #977  `主命とはいえ一人おめおめと…`  ->  `カインー主命とはいえ`
- #1001  `囮だと......くそっ!`  ->  `ジオルだとくそっ!`
- #1002  `この老骨がお役に立ったのならばそれで本望…`  ->  `お役に立ったのならばそれて本望…`

### punctuation/latin-only (8)
- #1001  `このわしをコケにしおって...殺せ!`  ->  `このわしをコケにしおって殺せ!`
- #1006  `この国に...わが祖国アリティアに!!`  ->  `この国にわが祖国アリティアに!!`
- #1015  `この地にとどまり、力をたくわえそして時を待つのだ...」と。`  ->  `この地にとどまり、力をたくわえそして時を待つのだ」と。`

### missing gold (never emitted) (3)
- #984  `おびきよせて、たたく。`  ->  ``
- #1280  `あなたの身に何があれば......`  ->  ``
- #1397  `国境地帯からアカネイアにぬける最大の難関は`  ->  ``
