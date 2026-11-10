package com.derricklee.ankidict

class CjkSentenceParser(
    private val furiganaHeightRatioThreshold: Double = 0.55,
) : SentenceParser {

    override fun parseSentences(document: OcrDocument): List<String> {
        val rawBlocks = if (document.blocks.isNotEmpty()) {
            document.blocks
        } else if (document.fullText.isNotBlank()) {
            listOf(
                OcrBlock(
                    text = document.fullText,
                    lines = document.fullText.lines().map { OcrLine(text = it, boundingBoxHeightPx = null) },
                ),
            )
        } else {
            emptyList()
        }

        val globalMainLineHeights = rawBlocks
            .flatMap { it.lines }
            .filter { line -> hasKanji(line.text) && line.boundingBoxHeightPx != null && line.boundingBoxHeightPx > 0 }
            .mapNotNull { it.boundingBoxHeightPx }
        val globalMedianHeight = medianInt(globalMainLineHeights)

        val sentences = mutableListOf<String>()
        val stitchedBuffer = StringBuilder()
        var previousBlock: OcrBlock? = null

        for (block in rawBlocks) {
            // OCR blocks are layout guesses, not sentence boundaries. Continue across adjacent,
            // aligned blocks in the same reading flow, but never blindly merge columns/regions.
            if (stitchedBuffer.isNotEmpty() && !continuesReadingFlow(previousBlock, block)) {
                sentences.addAll(splitIntoSentences(stitchedBuffer.toString()))
                stitchedBuffer.setLength(0)
            }
            val blockKanjiHeights = block.lines
                .filter { line -> hasKanji(line.text) && line.boundingBoxHeightPx != null && line.boundingBoxHeightPx > 0 }
                .mapNotNull { it.boundingBoxHeightPx }
            val referenceMedianHeight = medianInt(blockKanjiHeights) ?: globalMedianHeight

            for (line in block.lines) {
                val cleaned = cleanLine(line.text)
                if (cleaned.isEmpty()) continue
                if (isUiNoiseLine(cleaned)) continue
                if (isFuriganaLine(cleaned, line.boundingBoxHeightPx, referenceMedianHeight)) continue

                if (stitchedBuffer.isEmpty()) {
                    stitchedBuffer.append(cleaned)
                } else {
                    val lastChar = stitchedBuffer.last()
                    if (isSentenceTerminalChar(lastChar) || lastChar == '」' || lastChar == '』') {
                        sentences.addAll(splitIntoSentences(stitchedBuffer.toString()))
                        stitchedBuffer.setLength(0)
                        stitchedBuffer.append(cleaned)
                    } else {
                        stitchedBuffer.append(cleaned)
                    }
                }
            }

            previousBlock = block
        }
        if (stitchedBuffer.isNotEmpty()) {
            sentences.addAll(splitIntoSentences(stitchedBuffer.toString()))
        }

        return sentences
            .map { it.trim() }
            .filter { countCjkAndKana(it) >= 2 }
    }

    private fun continuesReadingFlow(previous: OcrBlock?, next: OcrBlock): Boolean {
        val before = previous?.bounds ?: return false
        val after = next.bounds ?: return false
        val previousHeight = medianInt(previous.lines.mapNotNull { it.boundingBoxHeightPx?.takeIf { h -> h > 0 } })
            ?: return false
        val nextHeight = medianInt(next.lines.mapNotNull { it.boundingBoxHeightPx?.takeIf { h -> h > 0 } })
            ?: return false
        val height = maxOf(previousHeight, nextHeight)
        if (minOf(previousHeight, nextHeight) < height * 0.65) return false
        val verticalGap = after.top - before.bottom
        val horizontalOverlap = minOf(before.right, after.right) - maxOf(before.left, after.left)
        return horizontalOverlap > 0 &&
            kotlin.math.abs(after.left - before.left) <= height * 1.5 &&
            verticalGap >= -height * 0.25 && verticalGap <= height * 1.5
    }

    private fun isFuriganaLine(
        text: String,
        lineHeightPx: Int?,
        referenceMedianHeightPx: Int?,
    ): Boolean {
        if (lineHeightPx == null || referenceMedianHeightPx == null || referenceMedianHeightPx <= 0) {
            return false
        }
        if (hasKanji(text)) return false
        val isPureKanaOrPunct = text.all { ch ->
            MlKitImageTagger.isHiragana(ch) ||
                MlKitImageTagger.isKatakana(ch) ||
                ch == 'ー' ||
                ch == '・' ||
                ch.isWhitespace()
        }
        if (!isPureKanaOrPunct) return false
        return lineHeightPx.toDouble() < referenceMedianHeightPx.toDouble() * furiganaHeightRatioThreshold
    }

    private fun isUiNoiseLine(text: String): Boolean {
        val cjkKanaCount = countCjkAndKana(text)
        if (cjkKanaCount == 0) return true
        val upper = text.uppercase().trim()
        if (upper in UI_NOISE_LABELS) return true
        return false
    }

    private fun cleanLine(raw: String): String =
        raw.replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private val UI_NOISE_LABELS = setOf(
            "START", "SELECT", "AUTO", "SKIP", "BACK", "MENU", "LOG", "SAVE", "LOAD", "OPTION", "OPTIONS",
        )

        fun splitIntoSentences(text: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                current.append(ch)
                if (isSentenceTerminalChar(ch)) {
                    // Consume any consecutive terminal punctuation (e.g. "！？", "……") and closing quotes
                    while (i + 1 < text.length && (isSentenceTerminalChar(text[i + 1]) || isClosingBracket(text[i + 1]))) {
                        i++
                        current.append(text[i])
                    }
                    val sentence = current.toString().trim()
                    if (sentence.isNotEmpty()) {
                        result.add(sentence)
                    }
                    current.setLength(0)
                }
                i++
            }
            val trailing = current.toString().trim()
            if (trailing.isNotEmpty()) {
                result.add(trailing)
            }
            return result
        }

        private fun isSentenceTerminalChar(ch: Char): Boolean =
            ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '…' || ch == '．'

        private fun isClosingBracket(ch: Char): Boolean =
            ch == '」' || ch == '』' || ch == '）' || ch == ')' || ch == '】'

        private fun hasKanji(text: String): Boolean =
            text.any { MlKitImageTagger.isCjkIdeograph(it) }

        private fun countCjkAndKana(text: String): Int =
            text.count {
                MlKitImageTagger.isHiragana(it) ||
                    MlKitImageTagger.isKatakana(it) ||
                    MlKitImageTagger.isCjkIdeograph(it)
            }

        private fun medianInt(values: List<Int>): Int? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}
