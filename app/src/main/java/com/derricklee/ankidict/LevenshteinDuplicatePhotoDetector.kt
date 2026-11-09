package com.derricklee.ankidict

import kotlin.math.max
import kotlin.math.min

class LevenshteinDuplicatePhotoDetector(
    private val recentKeepPhotosProvider: (LanguageTag, Int) -> List<ScrapedPhoto>,
) : DuplicatePhotoDetector {

    constructor(miningRepository: MiningRepository) : this(
        recentKeepPhotosProvider = { tag, limit ->
            miningRepository.getRecentKeepPhotosByTag(tag, limit)
        },
    )

    override fun checkDuplicate(
        extractedText: String,
        tag: LanguageTag,
        config: DuplicateDetectionConfig,
    ): DuplicateCheckResult {
        if (!config.enabled || config.lookbackPhotoCount <= 0) {
            return DuplicateCheckResult(isDuplicate = false)
        }
        val normalizedCandidate = normalizeForComparison(extractedText)
        if (normalizedCandidate.isEmpty()) {
            return DuplicateCheckResult(isDuplicate = false)
        }

        val recentPhotos = recentKeepPhotosProvider(tag, config.lookbackPhotoCount)
        var bestPhotoId: Long? = null
        var bestSimilarity = 0.0

        for (existing in recentPhotos) {
            val normalizedExisting = normalizeForComparison(existing.extractedText)
            if (normalizedExisting.isEmpty()) continue
            val sim = normalizedSimilarity(normalizedCandidate, normalizedExisting)
            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestPhotoId = existing.mediaStoreId
            }
        }

        return if (bestPhotoId != null && bestSimilarity >= config.similarityThreshold) {
            DuplicateCheckResult(
                isDuplicate = true,
                duplicateOfPhotoId = bestPhotoId,
            )
        } else {
            DuplicateCheckResult(isDuplicate = false)
        }
    }

    companion object {
        fun normalizeForComparison(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                if (MlKitImageTagger.isHiragana(ch) ||
                    MlKitImageTagger.isKatakana(ch) ||
                    MlKitImageTagger.isCjkIdeograph(ch) ||
                    ch.isLetterOrDigit()
                ) {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun normalizedSimilarity(a: String, b: String): Double {
            if (a == b) return 1.0
            val maxLen = max(a.length, b.length)
            if (maxLen == 0) return 1.0
            val dist = levenshteinDistance(a, b)
            return 1.0 - (dist.toDouble() / maxLen.toDouble())
        }

        fun levenshteinDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)

            for (i in a.indices) {
                curr[0] = i + 1
                val ca = a[i]
                for (j in b.indices) {
                    val cost = if (ca == b[j]) 0 else 1
                    curr[j + 1] = min(
                        min(curr[j] + 1, prev[j + 1] + 1),
                        prev[j] + cost,
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[b.length]
        }
    }
}
