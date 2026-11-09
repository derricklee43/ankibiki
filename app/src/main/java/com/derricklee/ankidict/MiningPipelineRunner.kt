package com.derricklee.ankidict

import android.content.Context

class MiningPipelineRunner(
    private val miningRepository: MiningRepository,
    private val photoSource: PhotoSource,
    private val imagePreprocessor: ImagePreprocessor,
    private val imageTagger: ImageTagger,
    private val pipelineConfigs: Map<LanguageTag, PipelineConfig>,
    private val discoveryConfig: ScanDiscoveryConfig = ScanDiscoveryConfig(),
    private val defaultPreprocessConfig: ImagePreprocessConfig = ImagePreprocessConfig(),
) {

    fun runScanBatch(): ScanSummary {
        val discovered = photoSource.findUnprocessedPhotos(discoveryConfig)
        if (discovered.isEmpty()) {
            return ScanSummary(
                scannedCount = 0,
                keptCount = 0,
                duplicateCount = 0,
                ignoredCount = 0,
                newOccurrencesCount = 0,
            )
        }

        // Process chronologically (oldest to newest within the discovered batch) so the original
        // photo is stored as KEEP first and any subsequent retake is detected as DUPLICATE.
        val orderedCandidates = discovered.sortedWith(
            compareBy<PhotoCandidate> { it.dateAdded }.thenBy { it.mediaStoreId },
        )

        var keptCount = 0
        var duplicateCount = 0
        var ignoredCount = 0
        var newOccurrencesCount = 0

        for (candidate in orderedCandidates) {
            val prepared = imagePreprocessor.prepare(candidate, defaultPreprocessConfig)
            if (prepared == null) {
                miningRepository.upsertPhoto(
                    ScrapedPhoto(
                        mediaStoreId = candidate.mediaStoreId,
                        contentUri = candidate.contentUri,
                        dateAdded = candidate.dateAdded,
                        languageTag = LanguageTag.NA,
                        extractedText = "",
                        ocrBlocksJson = null,
                        status = PhotoStatus.IGNORED,
                    ),
                )
                ignoredCount++
                continue
            }

            try {
                val outcome = processPreparedPhoto(candidate, prepared)
                when (outcome.status) {
                    PhotoStatus.KEEP -> {
                        keptCount++
                        newOccurrencesCount += outcome.occurrencesCreated
                    }
                    PhotoStatus.DUPLICATE -> duplicateCount++
                    PhotoStatus.IGNORED, PhotoStatus.DELETED -> ignoredCount++
                }
            } finally {
                if (!prepared.bitmap.isRecycled) {
                    prepared.bitmap.recycle()
                }
            }
        }

        return ScanSummary(
            scannedCount = orderedCandidates.size,
            keptCount = keptCount,
            duplicateCount = duplicateCount,
            ignoredCount = ignoredCount,
            newOccurrencesCount = newOccurrencesCount,
        )
    }

    data class PhotoProcessOutcome(
        val status: PhotoStatus,
        val occurrencesCreated: Int,
    )

    fun processPreparedPhoto(
        candidate: PhotoCandidate,
        prepared: PreparedImage,
    ): PhotoProcessOutcome {
        val tagResult = imageTagger.classify(prepared)
        val config = pipelineConfigs[tagResult.tag]
        if (tagResult.tag == LanguageTag.NA || config == null) {
            miningRepository.upsertPhoto(
                ScrapedPhoto(
                    mediaStoreId = candidate.mediaStoreId,
                    contentUri = candidate.contentUri,
                    dateAdded = candidate.dateAdded,
                    languageTag = LanguageTag.NA,
                    extractedText = "",
                    ocrBlocksJson = null,
                    status = PhotoStatus.IGNORED,
                ),
            )
            return PhotoProcessOutcome(status = PhotoStatus.IGNORED, occurrencesCreated = 0)
        }

        val ocrDocument = config.textExtractor.extract(prepared, tagResult.preflightDocument)
        if (ocrDocument.fullText.isBlank()) {
            miningRepository.upsertPhoto(
                ScrapedPhoto(
                    mediaStoreId = candidate.mediaStoreId,
                    contentUri = candidate.contentUri,
                    dateAdded = candidate.dateAdded,
                    languageTag = LanguageTag.NA,
                    extractedText = "",
                    ocrBlocksJson = null,
                    status = PhotoStatus.IGNORED,
                ),
            )
            return PhotoProcessOutcome(status = PhotoStatus.IGNORED, occurrencesCreated = 0)
        }

        return processOcrDocument(candidate, config, ocrDocument)
    }

    /**
     * Runs Stages 4–9 (duplicate check, sentence parsing, word/grammar normalization, dictionary/Anki
     * enrichment, and 1-occurrence-per-sentence insertion) on an extracted [OcrDocument].
     */
    fun processOcrDocument(
        candidate: PhotoCandidate,
        config: PipelineConfig,
        ocrDocument: OcrDocument,
    ): PhotoProcessOutcome {
        val tag = config.tag
        val dupCheck = config.duplicateDetector.checkDuplicate(
            extractedText = ocrDocument.fullText,
            tag = tag,
            config = config.duplicateDetectionConfig,
        )
        if (dupCheck.isDuplicate) {
            miningRepository.upsertPhoto(
                ScrapedPhoto(
                    mediaStoreId = candidate.mediaStoreId,
                    contentUri = candidate.contentUri,
                    dateAdded = candidate.dateAdded,
                    languageTag = tag,
                    extractedText = ocrDocument.fullText.trim(),
                    ocrBlocksJson = ocrDocument.toJsonString(),
                    status = PhotoStatus.DUPLICATE,
                    duplicateOfPhotoId = dupCheck.duplicateOfPhotoId,
                ),
            )
            // Crucial: DUPLICATE photos never create occurrences or modify extracted_items!
            return PhotoProcessOutcome(status = PhotoStatus.DUPLICATE, occurrencesCreated = 0)
        }

        val sentences = config.sentenceParser.parseSentences(ocrDocument)
        val displayExtractedText = if (sentences.isNotEmpty()) {
            sentences.joinToString("\n")
        } else {
            ocrDocument.fullText.trim()
        }

        miningRepository.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = candidate.mediaStoreId,
                contentUri = candidate.contentUri,
                dateAdded = candidate.dateAdded,
                languageTag = tag,
                extractedText = displayExtractedText,
                ocrBlocksJson = ocrDocument.toJsonString(),
                status = PhotoStatus.KEEP,
                duplicateOfPhotoId = null,
            ),
        )

        var occurrencesCreated = 0
        val baseTimestamp = if (candidate.dateAdded > 0L) {
            candidate.dateAdded
        } else {
            System.currentTimeMillis() / 1000L
        }

        for ((sentenceIdx, sentence) in sentences.withIndex()) {
            val sentenceTimestamp = baseTimestamp + sentenceIdx
            val wordsInSentence = config.wordAnalyzer
                .extractWords(sentence)
                .distinctBy { it.expression }
            for (word in wordsInSentence) {
                ensureCanonicalItemExists(config, ItemType.WORD, word)
                miningRepository.insertOccurrence(
                    languageTag = tag,
                    itemType = ItemType.WORD,
                    expression = word.expression,
                    photoId = candidate.mediaStoreId,
                    sentence = sentence,
                    createdAt = sentenceTimestamp,
                )
                occurrencesCreated++
            }

            val grammarInSentence = config.grammarAnalyzer
                .extractGrammar(sentence)
                .distinctBy { it.expression }
            for (grammar in grammarInSentence) {
                ensureCanonicalItemExists(config, ItemType.GRAMMAR, grammar)
                miningRepository.insertOccurrence(
                    languageTag = tag,
                    itemType = ItemType.GRAMMAR,
                    expression = grammar.expression,
                    photoId = candidate.mediaStoreId,
                    sentence = sentence,
                    createdAt = sentenceTimestamp,
                )
                occurrencesCreated++
            }
        }

        return PhotoProcessOutcome(
            status = PhotoStatus.KEEP,
            occurrencesCreated = occurrencesCreated,
        )
    }

    private fun ensureCanonicalItemExists(
        config: PipelineConfig,
        itemType: ItemType,
        candidate: NormalizedCandidate,
    ) {
        val tag = config.tag
        if (miningRepository.hasExtractedItem(tag, itemType, candidate.expression)) {
            if (!miningRepository.hasLinkedAnkiCard(tag, itemType, candidate.expression)) {
                val lateMatch = config.ankiMatchLookup?.findMatch(
                    languageTag = tag,
                    itemType = itemType,
                    expression = candidate.expression,
                    reading = candidate.readingKana.orEmpty(),
                )
                if (lateMatch != null) {
                    miningRepository.updateExtractedItemAnkiMatch(
                        languageTag = tag,
                        itemType = itemType,
                        expression = candidate.expression,
                        match = lateMatch,
                    )
                }
            }
            return
        }

        var metadata: DictionaryMetadata? = null
        for (provider in config.dictionaryProviders) {
            metadata = provider.lookup(
                expression = candidate.expression,
                itemType = itemType,
                readingHint = candidate.readingKana,
            )
            if (metadata != null) break
        }

        val resolvedKanji = metadata?.kanji ?: candidate.expression
        val resolvedKana = metadata?.kana ?: candidate.readingKana ?: candidate.expression
        val resolvedMeaning = metadata?.meaning ?: "—"
        val ankiMatch = config.ankiMatchLookup?.findMatch(
            languageTag = tag,
            itemType = itemType,
            expression = candidate.expression,
            reading = resolvedKana,
        )

        miningRepository.upsertExtractedItem(
            languageTag = tag,
            itemType = itemType,
            expression = candidate.expression,
            kanji = resolvedKanji,
            kana = resolvedKana,
            meaning = resolvedMeaning,
            levelBadge = metadata?.levelBadge,
            corpusRank = metadata?.corpusRank,
            ankiAccountId = ankiMatch?.accountId,
            deckId = ankiMatch?.deckId,
            deckName = ankiMatch?.deckName,
            ankiNoteId = ankiMatch?.noteId,
            isHidden = false,
        )
    }

    companion object {
        fun createDefault(
            context: Context,
            miningRepository: MiningRepository,
            dictionaryRepository: DictionaryRepository,
            shinjitaiRepository: ShinjitaiRepository,
            hskRepository: HskRepository,
            ankiMatchLookup: AnkiMatchLookup = AnkiCardService(
                AnkiDroidAccountGateway(context.applicationContext),
            ),
        ): MiningPipelineRunner {
            val appContext = context.applicationContext
            val duplicateDetector = LevenshteinDuplicatePhotoDetector(miningRepository)
            val sentenceParser = CjkSentenceParser()

            val jpTextExtractor = CompositeTextExtractor(
                cloudExtractor = CloudVisionTextExtractor(LanguageTag.JAPANESE),
                fallbackExtractor = MlKitTextExtractor(LanguageTag.JAPANESE),
            )
            val zhTextExtractor = CompositeTextExtractor(
                cloudExtractor = CloudVisionTextExtractor(LanguageTag.CHINESE),
                fallbackExtractor = MlKitTextExtractor(LanguageTag.CHINESE),
            )

            val jpConfig = PipelineConfig(
                tag = LanguageTag.JAPANESE,
                textExtractor = jpTextExtractor,
                duplicateDetector = duplicateDetector,
                sentenceParser = sentenceParser,
                wordAnalyzer = KuromojiWordAnalyzer(),
                grammarAnalyzer = JlptGrammarAnalyzer(),
                dictionaryProviders = listOf(
                    BundledDictionaryProvider(
                        languageTag = LanguageTag.JAPANESE,
                        dictionaryRepository = dictionaryRepository,
                        hskRepository = hskRepository,
                    ),
                ),
                ankiMatchLookup = ankiMatchLookup,
            )

            val zhConfig = PipelineConfig(
                tag = LanguageTag.CHINESE,
                textExtractor = zhTextExtractor,
                duplicateDetector = duplicateDetector,
                sentenceParser = sentenceParser,
                wordAnalyzer = ChineseWordAnalyzer(dictionaryRepository),
                grammarAnalyzer = NoOpGrammarAnalyzer(),
                dictionaryProviders = listOf(
                    BundledDictionaryProvider(
                        languageTag = LanguageTag.CHINESE,
                        dictionaryRepository = dictionaryRepository,
                        hskRepository = hskRepository,
                    ),
                ),
                ankiMatchLookup = ankiMatchLookup,
            )

            return MiningPipelineRunner(
                miningRepository = miningRepository,
                photoSource = MediaStorePhotoSource(appContext, miningRepository),
                imagePreprocessor = AndroidImagePreprocessor(appContext),
                imageTagger = MlKitImageTagger(
                    dictionaryRepository = dictionaryRepository,
                    shinjitaiRepository = shinjitaiRepository,
                ),
                pipelineConfigs = mapOf(
                    LanguageTag.JAPANESE to jpConfig,
                    LanguageTag.CHINESE to zhConfig,
                ),
            )
        }
    }
}
