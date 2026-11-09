package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningPipelineUnitTest {

    @Test
    fun duplicatePhotoDetector_flagsRetakeAndPreservesOriginalId() {
        val recentKeep = listOf(
            ScrapedPhoto(
                mediaStoreId = 101L,
                contentUri = "content://media/external/images/media/101",
                dateAdded = 1700000010L,
                languageTag = LanguageTag.JAPANESE,
                extractedText = "しかし決定的な証拠がまだ足りない。\nこの証拠品を法廷で突きつけるしかない！",
                status = PhotoStatus.KEEP,
            ),
        )
        val detector = LevenshteinDuplicatePhotoDetector { _, _ -> recentKeep }
        val config = DuplicateDetectionConfig(
            enabled = true,
            similarityThreshold = 0.90,
            lookbackPhotoCount = 10,
        )

        // Slight OCR variation from a second photo of the same DS screen
        val retakeResult = detector.checkDuplicate(
            extractedText = "しかし決定的な証拠がまだ足りない\nこの証拠品を法廷で突きつけるしかない!",
            tag = LanguageTag.JAPANESE,
            config = config,
        )
        assertTrue(retakeResult.isDuplicate)
        assertEquals(101L, retakeResult.duplicateOfPhotoId)

        // Completely different dialogue screen
        val uniqueResult = detector.checkDuplicate(
            extractedText = "犯人は現場に必ず戻ってくるはずだ。",
            tag = LanguageTag.JAPANESE,
            config = config,
        )
        assertFalse(uniqueResult.isDuplicate)
        assertNull(uniqueResult.duplicateOfPhotoId)
    }

    @Test
    fun cjkSentenceParser_filtersFuriganaAndUiNoiseAndStitchesLineWraps() {
        val document = OcrDocument(
            fullText = "START\nしょうこ\nしかし決定的な証\n拠がまだ足りない。\nこの証拠品を法廷で\n突きつけるしかない！",
            blocks = listOf(
                OcrBlock(
                    text = "START",
                    lines = listOf(OcrLine(text = "START", boundingBoxHeightPx = 30)),
                ),
                OcrBlock(
                    text = "しょうこ\nしかし決定的な証\n拠がまだ足りない。\nこの証拠品を法廷で\n突きつけるしかない！",
                    lines = listOf(
                        OcrLine(text = "しょうこ", boundingBoxHeightPx = 18), // Furigana (< 55% of 44px)
                        OcrLine(text = "しかし決定的な証", boundingBoxHeightPx = 44),
                        OcrLine(text = "拠がまだ足りない。", boundingBoxHeightPx = 44),
                        OcrLine(text = "この証拠品を法廷で", boundingBoxHeightPx = 45),
                        OcrLine(text = "突きつけるしかない！", boundingBoxHeightPx = 43),
                    ),
                ),
            ),
        )

        val parser = CjkSentenceParser()
        val sentences = parser.parseSentences(document)

        assertEquals(
            listOf(
                "しかし決定的な証拠がまだ足りない。",
                "この証拠品を法廷で突きつけるしかない！",
            ),
            sentences,
        )
    }

    @Test
    fun kuromojiWordAnalyzer_lemmatizesAndExtractsOneOccurrencePerSentence() {
        val analyzer = KuromojiWordAnalyzer()

        val s1Words = analyzer.extractWords("しかし決定的な証拠がまだ足りない。").map { it.expression }
        val s2Words = analyzer.extractWords("この証拠品を法廷で突きつけるしかない！").map { it.expression }

        // Both sentences yield "証拠" -> 2 occurrences total across the 2 sentences!
        assertTrue(s1Words.contains("証拠"))
        assertTrue(s1Words.contains("足りる"))
        assertTrue(s2Words.contains("証拠"))
        assertTrue(s2Words.contains("法廷"))
        assertTrue(s2Words.contains("突きつける"))

        // If a word appears twice within the SAME sentence, only 1 candidate is emitted for that sentence
        val repeatedInSameSentence = analyzer.extractWords("証拠だ、その証拠を見せてくれ。")
        assertEquals(1, repeatedInSameSentence.count { it.expression == "証拠" })

        // Conjugated verbs/adjectives lemmatize to base dictionary form
        val conjugated = analyzer.extractWords("美しい花を食べさせられた。").map { it.expression }
        assertTrue(conjugated.contains("美しい"))
        assertTrue(conjugated.contains("食べる"))
        assertFalse(conjugated.contains("られる"))
    }

    @Test
    fun jlptGrammarAnalyzer_normalizesConjugatedGrammarPatterns() {
        val analyzer = JlptGrammarAnalyzer()

        val g1 = analyzer.extractGrammar("この証拠品を法廷で突きつけるしかない！").map { it.expression }
        assertTrue(g1.contains("しかない"))

        val g2 = analyzer.extractGrammar("それが真実というわけだ。").map { it.expression }
        assertTrue(g2.contains("というわけだ"))

        val g3 = analyzer.extractGrammar("計画を変更せざるを得なかった。").map { it.expression }
        assertTrue(g3.contains("ざるを得ない"))

        val g4 = analyzer.extractGrammar("彼が犯人に違いありません。").map { it.expression }
        assertTrue(g4.contains("に違いない"))
    }

    @Test
    fun imageTagger_classifiesJapaneseChineseAndNaText() {
        val (jpTag, _) = MlKitImageTagger.classifyText("しかし決定的な証拠がまだ足りない。")
        assertEquals(LanguageTag.JAPANESE, jpTag)

        val (zhTag, _) = MlKitImageTagger.classifyText("我们在这里没有看到那个人。")
        assertEquals(LanguageTag.CHINESE, zhTag)

        val (naTag, _) = MlKitImageTagger.classifyText("12:34 PM Battery 95%")
        assertEquals(LanguageTag.NA, naTag)
    }

    @Test
    fun ocrDocument_roundTripsJsonWithBoundingHeights() {
        val original = OcrDocument(
            fullText = "証拠",
            blocks = listOf(
                OcrBlock(
                    text = "証拠",
                    lines = listOf(OcrLine(text = "証拠", boundingBoxHeightPx = 42)),
                ),
            ),
        )
        val json = original.toJsonString()
        val restored = OcrDocument.fromJsonString(json)
        assertNotNull(restored)
        assertEquals("証拠", restored?.fullText)
        assertEquals(1, restored?.blocks?.size)
        assertEquals(42, restored?.blocks?.first()?.lines?.first()?.boundingBoxHeightPx)
    }
}
