package com.derricklee.ankidict

import kotlin.math.max
import kotlin.math.roundToInt

class BundledDictionaryProvider(
    private val languageTag: LanguageTag,
    private val dictionaryRepository: DictionaryRepository? = null,
    private val hskRepository: HskRepository? = null,
) : DictionaryProvider {

    override fun lookup(
        expression: String,
        itemType: ItemType,
        readingHint: String?,
    ): DictionaryMetadata? {
        val expressionHasKanji = expression.any { MlKitImageTagger.isCjkIdeograph(it) }
        if (itemType == ItemType.GRAMMAR) {
            val grammar = JlptGrammarAnalyzer.lookupGrammarEntry(expression) ?: return null
            val kanjiValue = if (expressionHasKanji && grammar.kanji.any { MlKitImageTagger.isCjkIdeograph(it) }) {
                grammar.kanji
            } else {
                ""
            }
            return DictionaryMetadata(
                kanji = kanjiValue,
                kana = grammar.kana,
                meaning = grammar.meaning,
                levelBadge = grammar.levelBadge,
                corpusRank = grammar.corpusRank,
            )
        }

        return when (languageTag) {
            LanguageTag.JAPANESE -> lookupJapaneseWord(expression, readingHint)
            LanguageTag.CHINESE -> lookupChineseWord(expression, readingHint)
            LanguageTag.NA -> null
        }
    }

    private fun lookupJapaneseWord(
        expression: String,
        readingHint: String?,
    ): DictionaryMetadata {
        val expressionHasKanji = expression.any { MlKitImageTagger.isCjkIdeograph(it) }
        val dictRepo = dictionaryRepository
        val words = dictRepo?.lookupJapaneseWords(expression).orEmpty()

        val bestWord = words.firstOrNull { it.headword == expression && (readingHint == null || it.reading == readingHint) }
            ?: words.firstOrNull { it.headword == expression }
            ?: words.firstOrNull { it.reading == expression }
            ?: words.firstOrNull()

        if (bestWord != null) {
            val (levelBadge, corpusRank) = resolveJapaneseLevelAndRank(
                headword = if (expressionHasKanji) bestWord.headword else expression,
                isCommon = bestWord.isCommon,
                dictRepo = dictRepo,
            )
            // If the extracted word is pure kana, never backfill Kanji!
            val resolvedKanji = if (expressionHasKanji && bestWord.headword.any { MlKitImageTagger.isCjkIdeograph(it) }) {
                bestWord.headword
            } else {
                ""
            }
            return DictionaryMetadata(
                kanji = resolvedKanji,
                kana = bestWord.reading.ifBlank { readingHint ?: expression },
                meaning = bestWord.glosses,
                levelBadge = levelBadge,
                corpusRank = corpusRank,
            )
        }

        if (expression.length == 1 && expressionHasKanji && dictRepo != null) {
            val kanjiEntry = dictRepo
                .lookupExact(expression, setOf(SearchSource.DICT_KANJI))
                .filterIsInstance<DictionaryEntry.Kanji>()
                .firstOrNull()
            if (kanjiEntry != null) {
                val reading = (kanjiEntry.kunYomi + kanjiEntry.onYomi).firstOrNull().orEmpty()
                val (levelBadge, corpusRank) = resolveJapaneseLevelAndRank(
                    headword = expression,
                    isCommon = true,
                    dictRepo = dictRepo,
                )
                return DictionaryMetadata(
                    kanji = expression,
                    kana = reading.ifBlank { readingHint ?: expression },
                    meaning = kanjiEntry.meanings,
                    levelBadge = levelBadge,
                    corpusRank = corpusRank,
                )
            }
        }

        val (fallbackLevel, fallbackRank) = resolveJapaneseLevelAndRank(
            headword = expression,
            isCommon = false,
            dictRepo = dictRepo,
        )
        return DictionaryMetadata(
            kanji = if (expressionHasKanji) expression else "",
            kana = readingHint ?: expression,
            meaning = "—",
            levelBadge = fallbackLevel,
            corpusRank = fallbackRank,
        )
    }

    private fun lookupChineseWord(
        expression: String,
        readingHint: String?,
    ): DictionaryMetadata {
        val matches = dictionaryRepository?.lookupChineseWords(expression).orEmpty()
        val best = matches.firstOrNull()
        val hskBadge = resolveHskBadge(expression)

        if (best != null) {
            return DictionaryMetadata(
                kanji = best.simplified,
                kana = toDiacriticPinyin(best.pinyin),
                meaning = best.definitions,
                levelBadge = hskBadge,
                corpusRank = null,
            )
        }

        return DictionaryMetadata(
            kanji = expression,
            kana = readingHint ?: expression,
            meaning = "—",
            levelBadge = hskBadge,
            corpusRank = null,
        )
    }

    private fun resolveHskBadge(expression: String): String? {
        val repo = hskRepository ?: return null
        var hardestNumeric = 0
        var has7to9 = false
        for (ch in expression) {
            if (!MlKitImageTagger.isCjkIdeograph(ch)) continue
            val lvl = repo.hskLevel(ch.toString())
            if (lvl == "7-9") {
                has7to9 = true
            } else {
                val n = lvl.toIntOrNull()
                if (n != null && n > hardestNumeric) {
                    hardestNumeric = n
                }
            }
        }
        return when {
            has7to9 -> "HSK 7-9"
            hardestNumeric > 0 -> "HSK $hardestNumeric"
            else -> null
        }
    }

    private fun resolveJapaneseLevelAndRank(
        headword: String,
        isCommon: Boolean,
        dictRepo: DictionaryRepository?,
    ): Pair<String?, Int?> {
        CURATED_JP_WORD_STATS[headword]?.let { return it }
        if (dictRepo == null) return null to null

        var hardestNLevel: Int? = null // 1 (hardest) .. 5 (easiest)
        var maxKanjiFreq = 0
        var kanjiCount = 0

        for (ch in headword) {
            if (!MlKitImageTagger.isCjkIdeograph(ch) || ch == '々') continue
            kanjiCount++
            val (oldJlpt, freq) = dictRepo.lookupKanjiFreqAndJlpt(ch.toString()) ?: continue
            if (freq != null && freq > maxKanjiFreq) {
                maxKanjiFreq = freq
            }
            val mappedN = when (oldJlpt) {
                4 -> 5
                3 -> 4
                2 -> if (freq != null && freq <= 850) 3 else 2
                1 -> if (freq != null && freq <= 750 && isCommon) 2 else 1
                else -> null
            }
            if (mappedN != null) {
                hardestNLevel = if (hardestNLevel == null) mappedN else minOf(hardestNLevel, mappedN)
            }
        }

        val levelBadge = hardestNLevel?.let { "N$it" }
        val corpusRank = if (maxKanjiFreq > 0) {
            val multiplier = if (isCommon) 1.4 else 3.2
            val lengthOffset = max(0, kanjiCount - 1) * 180
            (maxKanjiFreq * multiplier + lengthOffset).roundToInt()
        } else {
            null
        }
        return levelBadge to corpusRank
    }

    companion object {
        private val CURATED_JP_WORD_STATS: Map<String, Pair<String, Int>> = mapOf(
            "証拠" to ("N2" to 1420),
            "矛盾" to ("N1" to 2810),
            "検事" to ("N1" to 3105),
            "法廷" to ("N1" to 3420),
            "現場" to ("N3" to 890),
            "真実" to ("N3" to 1150),
            "決定的" to ("N2" to 1680),
            "突きつける" to ("N2" to 2240),
            "足りる" to ("N4" to 610),
            "事件" to ("N3" to 520),
            "犯人" to ("N3" to 980),
            "証言" to ("N2" to 1850),
            "裁判" to ("N3" to 1120),
            "弁護士" to ("N2" to 1540),
            "被告人" to ("N1" to 2950),
            "被害者" to ("N2" to 1390),
            "動機" to ("N2" to 1760),
            "アリバイ" to ("N2" to 2410),
            "逆転" to ("N2" to 1920),
            "異議" to ("N1" to 2640),
        )
    }
}
