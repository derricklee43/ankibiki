package com.derricklee.ankidict

class ChineseWordAnalyzer(
    private val dictionaryRepository: DictionaryRepository? = null,
) : WordAnalyzer {

    override fun extractWords(sentence: String): List<NormalizedCandidate> {
        if (sentence.isBlank()) return emptyList()
        val candidates = mutableListOf<NormalizedCandidate>()
        val seenInSentence = HashSet<String>()

        var i = 0
        while (i < sentence.length) {
            val ch = sentence[i]
            if (!MlKitImageTagger.isCjkIdeograph(ch)) {
                i++
                continue
            }

            var matchedLength = 1
            var matchedSimplified = ch.toString()
            var matchedReading: String? = null

            if (dictionaryRepository != null) {
                for (len in 4 downTo 1) {
                    if (i + len > sentence.length) continue
                    val sub = sentence.substring(i, i + len)
                    if (!sub.all { MlKitImageTagger.isCjkIdeograph(it) }) continue
                    val matches = dictionaryRepository.lookupChineseWords(sub)
                    if (matches.isNotEmpty()) {
                        val best = matches.first()
                        matchedLength = len
                        matchedSimplified = best.simplified
                        matchedReading = toDiacriticPinyin(best.pinyin)
                        break
                    }
                }
            }

            val surface = sentence.substring(i, i + matchedLength)
            if (matchedSimplified !in CHINESE_STOPWORDS && seenInSentence.add(matchedSimplified)) {
                candidates.add(
                    NormalizedCandidate(
                        expression = matchedSimplified,
                        surfaceForm = surface,
                        readingKana = matchedReading,
                    ),
                )
            }
            i += matchedLength
        }

        return candidates
    }

    companion object {
        private val CHINESE_STOPWORDS = setOf(
            "的", "了", "吗", "呢", "吧", "啊", "呀", "着", "过", "得", "地",
            "我", "你", "他", "她", "它", "们", "我们", "你们", "他们",
            "这", "那", "这个", "那个", "这里", "那里", "什么", "怎么",
            "是", "在", "有", "和", "与", "跟", "就", "都", "也", "还", "又", "不", "没", "没有", "很",
        )
    }
}
