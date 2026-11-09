package com.derricklee.ankidict

class MlKitImageTagger(
    private val dictionaryRepository: DictionaryRepository? = null,
    private val shinjitaiRepository: ShinjitaiRepository? = null,
    private val japaneseExtractor: TextExtractor = MlKitTextExtractor(LanguageTag.JAPANESE),
    private val chineseExtractor: TextExtractor = MlKitTextExtractor(LanguageTag.CHINESE),
) : ImageTagger {

    override fun classify(image: PreparedImage): TagResult {
        val jpDoc = japaneseExtractor.extract(image, null)
        val (tag, confidence) = classifyText(
            text = jpDoc.fullText,
            dictionaryRepository = dictionaryRepository,
            shinjitaiRepository = shinjitaiRepository,
        )
        return when (tag) {
            LanguageTag.JAPANESE -> TagResult(
                tag = LanguageTag.JAPANESE,
                confidence = confidence,
                preflightDocument = jpDoc,
            )
            LanguageTag.CHINESE -> {
                val zhDoc = chineseExtractor.extract(image, null)
                val finalDoc = if (zhDoc.fullText.isNotBlank()) zhDoc else jpDoc
                TagResult(
                    tag = LanguageTag.CHINESE,
                    confidence = confidence,
                    preflightDocument = finalDoc,
                )
            }
            LanguageTag.NA -> {
                // Double-check with Chinese recognizer in case pure Simplified Chinese characters
                // were skipped by the Japanese model.
                val zhDoc = chineseExtractor.extract(image, null)
                val (zhTag, zhConfidence) = classifyText(
                    text = zhDoc.fullText,
                    dictionaryRepository = dictionaryRepository,
                    shinjitaiRepository = shinjitaiRepository,
                )
                if (zhTag != LanguageTag.NA) {
                    TagResult(
                        tag = zhTag,
                        confidence = zhConfidence,
                        preflightDocument = zhDoc,
                    )
                } else {
                    TagResult(
                        tag = LanguageTag.NA,
                        confidence = 1.0f,
                        preflightDocument = null,
                    )
                }
            }
        }
    }

    companion object {
        fun classifyText(
            text: String,
            dictionaryRepository: DictionaryRepository? = null,
            shinjitaiRepository: ShinjitaiRepository? = null,
        ): Pair<LanguageTag, Float> {
            var hiraganaCount = 0
            var katakanaCount = 0
            var cjkCount = 0
            val cjkChars = StringBuilder()

            for (ch in text) {
                when {
                    isHiragana(ch) -> hiraganaCount++
                    isKatakana(ch) -> katakanaCount++
                    isCjkIdeograph(ch) -> {
                        cjkCount++
                        cjkChars.append(ch)
                    }
                }
            }

            val kanaCount = hiraganaCount + katakanaCount
            val totalTargetChars = kanaCount + cjkCount
            if (totalTargetChars < 2) {
                return LanguageTag.NA to 1.0f
            }

            val kanaRatio = kanaCount.toFloat() / totalTargetChars.toFloat()
            if (hiraganaCount >= 2 || (kanaCount >= 2 && kanaRatio >= 0.08f) || (kanaCount >= 1 && totalTargetChars <= 6)) {
                val confidence = (0.65f + kanaRatio * 0.35f).coerceAtMost(0.99f)
                return LanguageTag.JAPANESE to confidence
            }

            if (cjkCount >= 2) {
                val cjkStr = cjkChars.toString()
                var jpScore = 0
                var zhScore = 0

                if (dictionaryRepository != null) {
                    val maxBigrams = (cjkStr.length - 1).coerceAtMost(15)
                    for (i in 0 until maxBigrams) {
                        val bigram = cjkStr.substring(i, i + 2)
                        val inJp = dictionaryRepository.lookupJapaneseWords(bigram).isNotEmpty()
                        val inZh = dictionaryRepository.lookupChineseWords(bigram).isNotEmpty()
                        if (inJp) jpScore += 2
                        if (inZh) zhScore += 2
                    }
                }

                if (shinjitaiRepository != null) {
                    for (ch in cjkStr.take(20)) {
                        if (shinjitaiRepository.shinjitaiFor(ch.toString()) != null) {
                            zhScore += 1
                        }
                    }
                }

                // Common Chinese-only functional characters rarely used in modern Japanese prose
                for (ch in cjkStr) {
                    if (ch in CHINESE_INDICATOR_CHARS) {
                        zhScore += 2
                    }
                }

                return if (zhScore > jpScore && kanaCount == 0) {
                    LanguageTag.CHINESE to 0.80f
                } else {
                    LanguageTag.JAPANESE to 0.70f
                }
            }

            return LanguageTag.NA to 1.0f
        }

        fun isHiragana(ch: Char): Boolean = ch in '\u3040'..'\u309F'

        fun isKatakana(ch: Char): Boolean = ch in '\u30A0'..'\u30FF' || ch in '\uFF66'..'\uFF9F'

        fun isCjkIdeograph(ch: Char): Boolean =
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch == '々'

        private val CHINESE_INDICATOR_CHARS = setOf(
            '这', '那', '们', '吗', '呢', '吧', '没', '很', '给', '让', '说', '话', '对', '个', '着', '过', '得',
        )
    }
}
