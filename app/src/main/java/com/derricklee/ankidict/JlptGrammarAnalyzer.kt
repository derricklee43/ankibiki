package com.derricklee.ankidict

data class JlptGrammarEntry(
    val expression: String,
    val kanji: String,
    val kana: String,
    val meaning: String,
    val levelBadge: String,
    val corpusRank: Int,
    val patternRegex: Regex,
)

class JlptGrammarAnalyzer : GrammarAnalyzer {

    override fun extractGrammar(sentence: String): List<NormalizedCandidate> {
        if (sentence.isBlank()) return emptyList()
        val matchedCandidates = mutableListOf<NormalizedCandidate>()
        val seenExpressions = HashSet<String>()
        val consumedCharIndices = BooleanArray(sentence.length)

        for (entry in SORTED_ENTRIES) {
            val match = entry.patternRegex.find(sentence) ?: continue
            val range = match.range
            if (range.isEmpty()) continue
            val overlapsConsumed = (range.first..range.last).any { consumedCharIndices[it] }
            if (overlapsConsumed) continue

            for (idx in range.first..range.last) {
                consumedCharIndices[idx] = true
            }
            if (seenExpressions.add(entry.expression)) {
                matchedCandidates.add(
                    NormalizedCandidate(
                        expression = entry.expression,
                        surfaceForm = match.value,
                        readingKana = entry.kana,
                    ),
                )
            }
        }
        return matchedCandidates
    }

    companion object {
        fun lookupGrammarEntry(expression: String): JlptGrammarEntry? =
            ENTRIES_BY_EXPRESSION[expression]

        private val CATALOG: List<JlptGrammarEntry> = listOf(
            // N1 / N2 high-signal constructions
            JlptGrammarEntry(
                expression = "ざるを得ない",
                kanji = "ざるを得ない",
                kana = "ざるをえない",
                meaning = "cannot help (doing); have no choice but to",
                levelBadge = "N2",
                corpusRank = 1890,
                patternRegex = Regex("ざるを(得|え)(ない|なかった|ません|ず)"),
            ),
            JlptGrammarEntry(
                expression = "ずにはいられない",
                kanji = "ずにはいられない",
                kana = "ずにはいられない",
                meaning = "cannot help but; cannot resist doing",
                levelBadge = "N2",
                corpusRank = 2140,
                patternRegex = Regex("(ず|ない)にはいられ(ない|なかった|ません)"),
            ),
            JlptGrammarEntry(
                expression = "にほかならない",
                kanji = "に他ならない",
                kana = "にほかならない",
                meaning = "nothing but; none other than",
                levelBadge = "N2",
                corpusRank = 1950,
                patternRegex = Regex("に(他|ほか)なら(ない|ぬ|ず)"),
            ),
            JlptGrammarEntry(
                expression = "に相違ない",
                kanji = "に相違ない",
                kana = "にそういない",
                meaning = "without a doubt; certain to be",
                levelBadge = "N2",
                corpusRank = 2310,
                patternRegex = Regex("に相違(ない|ありません)"),
            ),
            JlptGrammarEntry(
                expression = "に越したことはない",
                kanji = "に越したことはない",
                kana = "にこしたことはない",
                meaning = "it is best to; nothing is better than",
                levelBadge = "N2",
                corpusRank = 2480,
                patternRegex = Regex("に(越|こ)したことは(ない|ありません)"),
            ),
            JlptGrammarEntry(
                expression = "にもかかわらず",
                kanji = "にも関わらず",
                kana = "にもかかわらず",
                meaning = "despite; in spite of; nevertheless",
                levelBadge = "N2",
                corpusRank = 1120,
                patternRegex = Regex("にも(関|かか)わらず"),
            ),
            JlptGrammarEntry(
                expression = "とは限らない",
                kanji = "とは限らない",
                kana = "とはかぎらない",
                meaning = "not necessarily so; is not always true",
                levelBadge = "N2",
                corpusRank = 1340,
                patternRegex = Regex("とは(限|かぎ)ら(ない|ず|ません)"),
            ),
            JlptGrammarEntry(
                expression = "にすぎない",
                kanji = "に過ぎない",
                kana = "にすぎない",
                meaning = "no more than; just; merely",
                levelBadge = "N2",
                corpusRank = 1180,
                patternRegex = Regex("に(過|す)ぎ(ない|なかった|ません|ず)"),
            ),
            JlptGrammarEntry(
                expression = "わけにはいかない",
                kanji = "訳にはいかない",
                kana = "わけにはいかない",
                meaning = "cannot afford to; must not",
                levelBadge = "N3",
                corpusRank = 1050,
                patternRegex = Regex("(わけ|訳)にはいか(ない|なかった|ません)"),
            ),
            JlptGrammarEntry(
                expression = "というわけではない",
                kanji = "という訳ではない",
                kana = "というわけではない",
                meaning = "it does not mean that; not necessarily",
                levelBadge = "N3",
                corpusRank = 880,
                patternRegex = Regex("という(わけ|訳)では(ない|ありません|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "というわけだ",
                kanji = "という訳だ",
                kana = "というわけだ",
                meaning = "it means that; that is why; in other words",
                levelBadge = "N3",
                corpusRank = 620,
                patternRegex = Regex("という(わけ|訳)(だ|です|か|ね|よ|な)"),
            ),
            JlptGrammarEntry(
                expression = "わけではない",
                kanji = "訳ではない",
                kana = "わけではない",
                meaning = "it does not mean that; it is not the case that",
                levelBadge = "N3",
                corpusRank = 710,
                patternRegex = Regex("(わけ|訳)(では|じゃ)(ない|ありません|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "わけがない",
                kanji = "訳がない",
                kana = "わけがない",
                meaning = "there is no way that; impossible that",
                levelBadge = "N3",
                corpusRank = 790,
                patternRegex = Regex("(わけ|訳)(が|は)(ない|ありません|ねえ)"),
            ),
            JlptGrammarEntry(
                expression = "に違いない",
                kanji = "に違いない",
                kana = "にちがいない",
                meaning = "must be; no doubt that",
                levelBadge = "N3",
                corpusRank = 940,
                patternRegex = Regex("に(違|ちが)い(ない|ありません|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "に決まっている",
                kanji = "に決まっている",
                kana = "にきまっている",
                meaning = "certainly; bound to be; must be",
                levelBadge = "N3",
                corpusRank = 1090,
                patternRegex = Regex("に(決|き)まって(いる|る|いた)"),
            ),
            JlptGrammarEntry(
                expression = "しかない",
                kanji = "しかない",
                kana = "しかない",
                meaning = "have no choice but to; only",
                levelBadge = "N3",
                corpusRank = 540,
                patternRegex = Regex("しか(ない|ありません|なかった|ねえ)"),
            ),
            JlptGrammarEntry(
                expression = "なければならない",
                kanji = "なければならない",
                kana = "なければならない",
                meaning = "must do; have to do",
                levelBadge = "N4",
                corpusRank = 320,
                patternRegex = Regex("なければ(なら(ない|なかった)|いけ(ない|ません))"),
            ),
            JlptGrammarEntry(
                expression = "なくてはいけない",
                kanji = "なくてはいけない",
                kana = "なくてはいけない",
                meaning = "must do; have to do",
                levelBadge = "N4",
                corpusRank = 410,
                patternRegex = Regex("なくては(いけ(ない|ません)|なら(ない|なかった))"),
            ),
            JlptGrammarEntry(
                expression = "かもしれない",
                kanji = "かも知れない",
                kana = "かもしれない",
                meaning = "might; perhaps; may",
                levelBadge = "N4",
                corpusRank = 260,
                patternRegex = Regex("かも(しれ|知れ)(ない|ません|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "に比べると",
                kanji = "に比べると",
                kana = "にくらべると",
                meaning = "compared to; in comparison with",
                levelBadge = "N3",
                corpusRank = 1280,
                patternRegex = Regex("に(比|くら)べ(ると|れば|て)"),
            ),
            JlptGrammarEntry(
                expression = "に関して",
                kanji = "に関して",
                kana = "にかんして",
                meaning = "regarding; concerning; related to",
                levelBadge = "N3",
                corpusRank = 810,
                patternRegex = Regex("に(関|かん)(して|する|しては)"),
            ),
            JlptGrammarEntry(
                expression = "に対して",
                kanji = "に対して",
                kana = "にたいして",
                meaning = "towards; against; in contrast to",
                levelBadge = "N3",
                corpusRank = 640,
                patternRegex = Regex("に(対|たい)(して|する|しては)"),
            ),
            JlptGrammarEntry(
                expression = "について",
                kanji = "について",
                kana = "について",
                meaning = "about; concerning; regarding",
                levelBadge = "N3",
                corpusRank = 390,
                patternRegex = Regex("について(は|も|の)?"),
            ),
            JlptGrammarEntry(
                expression = "にとって",
                kanji = "にとって",
                kana = "にとって",
                meaning = "to; for; from the standpoint of",
                levelBadge = "N3",
                corpusRank = 510,
                patternRegex = Regex("にとって(は|も|の)?"),
            ),
            JlptGrammarEntry(
                expression = "によって",
                kanji = "によって",
                kana = "によって",
                meaning = "by means of; due to; depending on",
                levelBadge = "N3",
                corpusRank = 450,
                patternRegex = Regex("に(よっ|依っ)て(は)?|に(より|よる)"),
            ),
            JlptGrammarEntry(
                expression = "おかげで",
                kanji = "お陰で",
                kana = "おかげで",
                meaning = "thanks to; owing to",
                levelBadge = "N3",
                corpusRank = 760,
                patternRegex = Regex("(おかげ|お陰)(で|だ|です)"),
            ),
            JlptGrammarEntry(
                expression = "せいで",
                kanji = "せいで",
                kana = "せいで",
                meaning = "because of; due to (negative result)",
                levelBadge = "N3",
                corpusRank = 780,
                patternRegex = Regex("せい(で|だ|か|にする)"),
            ),
            JlptGrammarEntry(
                expression = "どころか",
                kanji = "どころか",
                kana = "どころか",
                meaning = "far from; anything but; let alone",
                levelBadge = "N2",
                corpusRank = 1490,
                patternRegex = Regex("どころか"),
            ),
            JlptGrammarEntry(
                expression = "どころではない",
                kanji = "どころではない",
                kana = "どころではない",
                meaning = "not the time for; far from being able to",
                levelBadge = "N2",
                corpusRank = 1620,
                patternRegex = Regex("どころ(では|じゃ)(ない|ありません|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "ばかりか",
                kanji = "ばかりか",
                kana = "ばかりか",
                meaning = "not only... but also",
                levelBadge = "N2",
                corpusRank = 1580,
                patternRegex = Regex("ばかりか"),
            ),
            JlptGrammarEntry(
                expression = "のみならず",
                kanji = "のみならず",
                kana = "のみならず",
                meaning = "not only... besides; as well as",
                levelBadge = "N2",
                corpusRank = 1740,
                patternRegex = Regex("のみならず"),
            ),
            JlptGrammarEntry(
                expression = "かねない",
                kanji = "兼ねない",
                kana = "かねない",
                meaning = "might happen; risk of doing",
                levelBadge = "N2",
                corpusRank = 1830,
                patternRegex = Regex("(かね|兼ね)(ない|ません|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "かねる",
                kanji = "兼ねる",
                kana = "かねる",
                meaning = "unable to do; hesitate to do",
                levelBadge = "N2",
                corpusRank = 1910,
                patternRegex = Regex("(かね|兼ね)(る|ます)"),
            ),
            JlptGrammarEntry(
                expression = "てたまらない",
                kanji = "て堪らない",
                kana = "てたまらない",
                meaning = "unbearably; terribly; cannot help feeling",
                levelBadge = "N2",
                corpusRank = 1410,
                patternRegex = Regex("(て|で)(たまら|堪ら)(ない|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "てしょうがない",
                kanji = "て仕様がない",
                kana = "てしょうがない",
                meaning = "very; extremely; can't help feeling",
                levelBadge = "N3",
                corpusRank = 1220,
                patternRegex = Regex("(て|で)(しょうが|仕様が|しかたが|仕方が)(ない|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "はずがない",
                kanji = "筈がない",
                kana = "はずがない",
                meaning = "cannot possibly be; impossible that",
                levelBadge = "N3",
                corpusRank = 680,
                patternRegex = Regex("(はず|筈)(が|は)(ない|ありません|なかった)"),
            ),
            JlptGrammarEntry(
                expression = "べきだ",
                kanji = "べきだ",
                kana = "べきだ",
                meaning = "should do; ought to do",
                levelBadge = "N3",
                corpusRank = 590,
                patternRegex = Regex("べき(だ|です|ではない|じゃない|だった)"),
            ),
            JlptGrammarEntry(
                expression = "おそれがある",
                kanji = "恐れがある",
                kana = "おそれがある",
                meaning = "there is a risk/fear that; danger of",
                levelBadge = "N2",
                corpusRank = 1360,
                patternRegex = Regex("(恐れ|おそれ|虞)が(ある|あります|あった)"),
            ),
            JlptGrammarEntry(
                expression = "ようになる",
                kanji = "様になる",
                kana = "ようになる",
                meaning = "come to; reach the point where",
                levelBadge = "N4",
                corpusRank = 480,
                patternRegex = Regex("ように(なる|なった|なります|なりました|なって)"),
            ),
            JlptGrammarEntry(
                expression = "ことにする",
                kanji = "事にする",
                kana = "ことにする",
                meaning = "decide to",
                levelBadge = "N4",
                corpusRank = 520,
                patternRegex = Regex("ことに(する|した|します|しました|しよう)"),
            ),
            JlptGrammarEntry(
                expression = "ことになる",
                kanji = "事になる",
                kana = "ことになる",
                meaning = "it has been decided that; turn out that",
                levelBadge = "N4",
                corpusRank = 530,
                patternRegex = Regex("ことに(なる|なった|なります|なりました|なっている)"),
            ),
        )

        private val SORTED_ENTRIES: List<JlptGrammarEntry> =
            CATALOG.sortedByDescending { it.expression.length }

        private val ENTRIES_BY_EXPRESSION: Map<String, JlptGrammarEntry> =
            CATALOG.associateBy { it.expression }
    }
}

class NoOpGrammarAnalyzer : GrammarAnalyzer {
    override fun extractGrammar(sentence: String): List<NormalizedCandidate> = emptyList()
}
