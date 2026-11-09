package com.derricklee.ankidict

import com.atilika.kuromoji.ipadic.Tokenizer

class KuromojiWordAnalyzer : WordAnalyzer {

    override fun extractWords(sentence: String): List<NormalizedCandidate> {
        if (sentence.isBlank()) return emptyList()
        val tokens = sharedTokenizer.tokenize(sentence)
        val candidates = mutableListOf<NormalizedCandidate>()
        val seenExpressionsInSentence = HashSet<String>()

        for (token in tokens) {
            val pos1 = token.partOfSpeechLevel1
            val pos2 = token.partOfSpeechLevel2
            if (pos1 !in ALLOWED_POS1) continue
            if (pos2 in EXCLUDED_POS2) continue

            val surface = token.surface.trim()
            if (surface.isEmpty()) continue

            val baseForm = token.baseForm?.takeIf { it != "*" && it.isNotBlank() } ?: surface
            val cleanExpression = stripNoise(baseForm)
            if (!isValidWordCandidate(cleanExpression)) continue
            if (cleanExpression in STOPWORDS) continue

            if (!seenExpressionsInSentence.add(cleanExpression)) continue

            val rawReading = token.reading?.takeIf { it != "*" && it.isNotBlank() }
            val readingKana = rawReading?.let { katakanaToHiragana(it) }

            candidates.add(
                NormalizedCandidate(
                    expression = cleanExpression,
                    surfaceForm = surface,
                    readingKana = readingKana,
                ),
            )
        }
        return candidates
    }

    companion object {
        private val sharedTokenizer: Tokenizer by lazy { Tokenizer() }

        private val ALLOWED_POS1 = setOf(
            "名詞",
            "動詞",
            "形容詞",
            "副詞",
            "連体詞",
            "接続詞",
        )

        private val EXCLUDED_POS2 = setOf(
            "非自立",
            "代名詞",
            "数",
            "接尾",
            "特殊",
            "空白",
        )

        private val STOPWORDS = setOf(
            "する", "為る", "いる", "居る", "ある", "有る", "在る", "なる", "成る",
            "できる", "出来る", "やる", "いく", "行く", "くる", "来る", "おく", "置く",
            "みる", "見る", "しまう", "くれる", "あげる", "もらう", "いただく", "くださる",
            "れる", "られる", "せる", "させる", "ない", "無い", "よい", "良い", "いい",
            "こと", "事", "もの", "物", "よう", "様", "ため", "為", "ところ", "所",
            "はず", "わけ", "訳", "まま", "ほう", "方", "とき", "時", "うち", "中",
            "これ", "それ", "あれ", "どれ", "ここ", "そこ", "あそこ", "どこ",
            "こう", "そう", "ああ", "どう", "この", "その", "あの", "どの",
            "私", "僕", "俺", "君", "あなた", "彼", "彼女", "自分", "みんな",
        )

        private fun stripNoise(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                if (MlKitImageTagger.isHiragana(ch) ||
                    MlKitImageTagger.isKatakana(ch) ||
                    MlKitImageTagger.isCjkIdeograph(ch) ||
                    ch == 'ー'
                ) {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun isValidWordCandidate(expression: String): Boolean {
            if (expression.isEmpty()) return false
            val hasKanji = expression.any { MlKitImageTagger.isCjkIdeograph(it) }
            if (hasKanji) return true
            // Pure kana words must be at least 2 characters long
            return expression.length >= 2
        }

        fun katakanaToHiragana(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                if (ch in '\u30A1'..'\u30F6') {
                    sb.append((ch.code - 0x60).toChar())
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
