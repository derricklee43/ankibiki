package com.derricklee.ankidict

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

data class ImagePreprocessConfig(
    val maxLongEdgePx: Int = Int.MAX_VALUE,
    val jpegQuality: Int = 95,
    val autoRotateExif: Boolean = true,
)

data class ScanDiscoveryConfig(
    val initialBackfillLimit: Int = 50,
    val batchSize: Int = 25,
)

data class DuplicateDetectionConfig(
    // Disabled in production by user decision 2026-11-12 ("for the time being"): mining is
    // deliberate screenshots, and near-identical short VN lines risk false DUPLICATE links.
    // The detector stays fully implemented + tested; tests and any future re-enable pass
    // enabled = true explicitly.
    val enabled: Boolean = false,
    val similarityThreshold: Double = 0.90,
    val lookbackPhotoCount: Int = 10,
)

data class PhotoCandidate(
    val mediaStoreId: Long,
    val contentUri: String,
    val dateAdded: Long,
)

data class PreparedImage(
    val bitmap: Bitmap,
    val jpegBytes: ByteArray,
)

data class OcrLine(
    val text: String,
    val boundingBoxHeightPx: Int? = null,
)

/** Image-space bounds retained so reading-flow decisions do not depend on OCR block grouping. */
data class OcrBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class OcrBlock(
    val text: String,
    val lines: List<OcrLine>,
    val bounds: OcrBounds? = null,
)

data class OcrDocument(
    val fullText: String,
    val blocks: List<OcrBlock>,
    /** Which extractor produced this document (e.g. "cloud-vision", "on-device"); null = pre-transparency scan. */
    val provider: String? = null,
    /** Human-readable caveat, e.g. a cloud fallback reason or an on-device failure class. */
    val note: String? = null,
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("fullText", fullText)
        provider?.let { root.put("provider", it) }
        note?.let { root.put("note", it) }
        val blocksArr = JSONArray()
        for (block in blocks) {
            val blockObj = JSONObject()
            blockObj.put("text", block.text)
            block.bounds?.let { bounds ->
                blockObj.put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            }
            val linesArr = JSONArray()
            for (line in block.lines) {
                val lineObj = JSONObject()
                lineObj.put("text", line.text)
                if (line.boundingBoxHeightPx != null) {
                    lineObj.put("height", line.boundingBoxHeightPx)
                }
                linesArr.put(lineObj)
            }
            blockObj.put("lines", linesArr)
            blocksArr.put(blockObj)
        }
        root.put("blocks", blocksArr)
        return root.toString()
    }

    companion object {
        fun fromJsonString(json: String?): OcrDocument? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(json)
                val fullText = root.optString("fullText", "")
                val blocksArr = root.optJSONArray("blocks") ?: JSONArray()
                val blocks = mutableListOf<OcrBlock>()
                for (i in 0 until blocksArr.length()) {
                    val blockObj = blocksArr.getJSONObject(i)
                    val blockText = blockObj.optString("text", "")
                    val linesArr = blockObj.optJSONArray("lines") ?: JSONArray()
                    val lines = mutableListOf<OcrLine>()
                    for (j in 0 until linesArr.length()) {
                        val lineObj = linesArr.getJSONObject(j)
                        val height = if (lineObj.has("height") && !lineObj.isNull("height")) {
                            lineObj.getInt("height")
                        } else {
                            null
                        }
                        lines.add(
                            OcrLine(
                                text = lineObj.optString("text", ""),
                                boundingBoxHeightPx = height,
                            ),
                        )
                    }
                    val box = blockObj.optJSONArray("bounds")
                    val bounds = if (box != null && box.length() == 4) {
                        OcrBounds(box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
                    } else {
                        null
                    }
                    blocks.add(OcrBlock(text = blockText, lines = lines, bounds = bounds))
                }
                OcrDocument(
                    fullText = fullText,
                    blocks = blocks,
                    provider = root.optString("provider").takeIf { it.isNotBlank() },
                    note = root.optString("note").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }
}

data class TagResult(
    val tag: LanguageTag,
    val confidence: Float,
    val preflightDocument: OcrDocument? = null,
)

data class DuplicateCheckResult(
    val isDuplicate: Boolean,
    val duplicateOfPhotoId: Long? = null,
)

data class NormalizedCandidate(
    val expression: String,
    val surfaceForm: String,
    val readingKana: String? = null,
)

data class DictionaryMetadata(
    val kanji: String,
    val kana: String,
    val meaning: String,
    val levelBadge: String? = null,
    val corpusRank: Int? = null,
)

data class AnkiCardMatch(
    val accountId: String,
    val deckId: Long,
    val deckName: String,
    val noteId: Long,
)

interface PhotoSource {
    fun findUnprocessedPhotos(config: ScanDiscoveryConfig): List<PhotoCandidate>
}

interface ImagePreprocessor {
    fun prepare(photo: PhotoCandidate, config: ImagePreprocessConfig): PreparedImage?
}

interface ImageTagger {
    fun classify(image: PreparedImage): TagResult
}

interface TextExtractor {
    fun extract(image: PreparedImage, preflightDocument: OcrDocument? = null): OcrDocument
}

interface DuplicatePhotoDetector {
    fun checkDuplicate(
        extractedText: String,
        tag: LanguageTag,
        config: DuplicateDetectionConfig,
    ): DuplicateCheckResult
}

interface SentenceParser {
    fun parseSentences(document: OcrDocument): List<String>
}

interface WordAnalyzer {
    fun extractWords(sentence: String): List<NormalizedCandidate>
}

interface GrammarAnalyzer {
    fun extractGrammar(sentence: String): List<NormalizedCandidate>
}

interface DictionaryProvider {
    fun lookup(
        expression: String,
        itemType: ItemType,
        readingHint: String? = null,
    ): DictionaryMetadata?
}

interface AnkiMatchLookup {
    fun findMatch(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        reading: String,
    ): AnkiCardMatch?
}

data class PipelineConfig(
    val tag: LanguageTag,
    val imagePreprocessConfig: ImagePreprocessConfig = ImagePreprocessConfig(),
    val duplicateDetectionConfig: DuplicateDetectionConfig = DuplicateDetectionConfig(),
    val textExtractor: TextExtractor,
    val duplicateDetector: DuplicatePhotoDetector,
    val sentenceParser: SentenceParser,
    val wordAnalyzer: WordAnalyzer,
    val grammarAnalyzer: GrammarAnalyzer,
    val dictionaryProviders: List<DictionaryProvider>,
    val ankiMatchLookup: AnkiMatchLookup? = null,
    /** S003-D' stored-vs-Cloud correction pass; null = correction disabled for this pipeline. */
    val correctionPass: OcrCorrectionPass? = null,
    // Default ON by user decision 2026-11-13 ("implement the gate to auto correct"),
    // overriding spike 003's suggestion-first rollout. Tests pass values explicitly.
    val ocrCorrectionEnabled: Boolean = true,
)

data class ScanSummary(
    val scannedCount: Int,
    val keptCount: Int,
    val duplicateCount: Int,
    val ignoredCount: Int,
    val newOccurrencesCount: Int,
)
