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

    /** Replaces one photo's OCR and derived occurrences atomically; failures retain the old corpus. */
    fun rescanPhoto(
        candidate: PhotoCandidate,
        decision: PhotoRescanDecision? = null,
    ): PhotoRescanResult {
        if (decision == PhotoRescanDecision.CANCEL) return PhotoRescanResult.CANCELLED
        if (decision == PhotoRescanDecision.SKIP_TEXT_EXTRACTION) return PhotoRescanResult.SKIPPED
        val originalPhoto = miningRepository.getPhoto(candidate.mediaStoreId)
        if (originalPhoto?.modifiedByUserFlag == true && decision != PhotoRescanDecision.ACCEPT) {
            return PhotoRescanResult.CONFIRMATION_REQUIRED
        }
        val originalSentenceFlags = miningRepository.getSentenceModificationFlags(candidate.mediaStoreId)
        val prepared = imagePreprocessor.prepare(candidate, defaultPreprocessConfig)
            ?: error("Could not load the photo for rescanning")
        try {
            val tagResult = imageTagger.classify(prepared)
            val config = pipelineConfigs[tagResult.tag]
                ?: error("No text extraction pipeline is available for this photo")
            val document = config.textExtractor.extract(prepared, tagResult.preflightDocument)
            check(document.fullText.isNotBlank()) { "No text was extracted; existing text was retained" }

            val db = miningRepository.writableDatabase
            db.beginTransaction()
            try {
                // OCR can take seconds: never overwrite an edit made after this attempt began.
                if (miningRepository.getPhoto(candidate.mediaStoreId) != originalPhoto ||
                    miningRepository.getSentenceModificationFlags(candidate.mediaStoreId) != originalSentenceFlags
                ) {
                    return PhotoRescanResult.CONFIRMATION_REQUIRED
                }
                miningRepository.deleteOccurrencesForPhoto(candidate.mediaStoreId)
                // This is an explicit replacement of the same photo, not a newly discovered retake.
                processOcrDocument(candidate, config, document, checkDuplicates = false)
                miningRepository.clearPhotoUserModificationFlag(candidate.mediaStoreId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return PhotoRescanResult.COMPLETED
        } finally {
            if (!prepared.bitmap.isRecycled) prepared.bitmap.recycle()
        }
    }

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
        checkDuplicates: Boolean = true,
    ): PhotoProcessOutcome {
        val tag = config.tag
        val dupCheck = if (checkDuplicates) {
            config.duplicateDetector.checkDuplicate(
                extractedText = ocrDocument.fullText,
                tag = tag,
                config = config.duplicateDetectionConfig,
            )
        } else {
            DuplicateCheckResult(isDuplicate = false)
        }
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

        val hasKanji = candidate.expression.any { MlKitImageTagger.isCjkIdeograph(it) }
        val resolvedKanji = if (hasKanji) {
            metadata?.kanji?.takeIf { it.isNotBlank() } ?: candidate.expression
        } else {
            ""
        }
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

    /**
     * Saves amended text for [mediaStoreId]. When [reprocessWords] is true, removes all previous
     * occurrences from this photo and re-extracts + re-enriches words and grammar from [amendedText].
     */
    fun reprocessPhotoText(
        mediaStoreId: Long,
        amendedText: String,
        reprocessWords: Boolean,
    ): Int {
        val originalPhoto = miningRepository.getPhoto(mediaStoreId) ?: return 0
        val effectiveTag = if (originalPhoto.languageTag == LanguageTag.NA) {
            LanguageTag.JAPANESE
        } else {
            originalPhoto.languageTag
        }
        val config = pipelineConfigs[effectiveTag] ?: pipelineConfigs[LanguageTag.JAPANESE]
        val sentences = parsePhotoText(config, amendedText)
        val originalCounts = parsePhotoText(config, originalPhoto.extractedText).groupingBy { it }.eachCount()
        // Count increases detect edits that become identical to another sentence on this photo.
        // Persist those flags even on text-only saves, before any occurrence rebuild can erase them.
        val changedSentences = if (originalPhoto.extractedText != amendedText) {
            sentences.groupingBy { it }.eachCount()
                .filter { (sentence, count) -> count > (originalCounts[sentence] ?: 0) }.keys
        } else {
            emptySet()
        }
        miningRepository.updatePhotoExtractedText(mediaStoreId, amendedText)
        miningRepository.markSentencesModifiedByUser(mediaStoreId, changedSentences)
        if (!reprocessWords || config == null) return 0

        val previousSentenceFlags = miningRepository.getSentenceModificationFlags(mediaStoreId)
        val photo = miningRepository.getPhoto(mediaStoreId) ?: return 0
        miningRepository.deleteOccurrencesForPhoto(mediaStoreId)

        val baseTimestamp = System.currentTimeMillis() / 1000L
        var created = 0
        for ((idx, sentence) in sentences.withIndex()) {
            created += extractAndInsertSentenceOccurrences(
                config = config,
                photoId = mediaStoreId,
                sentence = sentence,
                timestamp = baseTimestamp + idx,
                modifiedByUserFlag = previousSentenceFlags[sentence] ?: photo.modifiedByUserFlag,
            )
        }
        return created
    }

    /**
     * Saves an amended sentence for [photoId]. When [reprocessWords] is true, removes all occurrences
     * previously generated by [oldSentence] on [photoId] and re-extracts + re-enriches words and
     * grammar from [newSentence].
     */
    fun reprocessAmendedSentence(
        photoId: Long,
        oldSentence: String,
        newSentence: String,
        reprocessWords: Boolean,
    ): Int {
        val trimmedNew = newSentence.trim()
        miningRepository.updateSentenceText(photoId, oldSentence, trimmedNew)
        val sentenceModified = oldSentence != trimmedNew ||
            miningRepository.getSentenceModificationFlags(photoId)[trimmedNew] == true
        if (!reprocessWords) return 0

        miningRepository.deleteOccurrencesForSentence(photoId, oldSentence)
        miningRepository.deleteOccurrencesForSentence(photoId, trimmedNew)
        if (trimmedNew.isEmpty()) return 0

        val photo = miningRepository.getPhoto(photoId)
        val effectiveTag = if (photo == null || photo.languageTag == LanguageTag.NA) {
            LanguageTag.JAPANESE
        } else {
            photo.languageTag
        }
        val config = pipelineConfigs[effectiveTag] ?: pipelineConfigs[LanguageTag.JAPANESE] ?: return 0

        return extractAndInsertSentenceOccurrences(
            config = config,
            photoId = photoId,
            sentence = trimmedNew,
            timestamp = System.currentTimeMillis() / 1000L,
            modifiedByUserFlag = sentenceModified,
        )
    }

    private fun parsePhotoText(config: PipelineConfig?, text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = lines.map { line -> OcrBlock(text = line, lines = listOf(OcrLine(text = line))) }
        return config?.sentenceParser?.parseSentences(OcrDocument(fullText = text, blocks = blocks))
            ?.takeIf { it.isNotEmpty() } ?: lines
    }

    private fun extractAndInsertSentenceOccurrences(
        config: PipelineConfig,
        photoId: Long,
        sentence: String,
        timestamp: Long,
        modifiedByUserFlag: Boolean = false,
    ): Int {
        val tag = config.tag
        var count = 0
        val wordsInSentence = config.wordAnalyzer
            .extractWords(sentence)
            .distinctBy { it.expression }
        for (word in wordsInSentence) {
            ensureCanonicalItemExists(config, ItemType.WORD, word)
            miningRepository.insertOccurrence(
                languageTag = tag,
                itemType = ItemType.WORD,
                expression = word.expression,
                photoId = photoId,
                sentence = sentence,
                createdAt = timestamp,
                modifiedByUserFlag = modifiedByUserFlag,
            )
            count++
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
                photoId = photoId,
                sentence = sentence,
                createdAt = timestamp,
                modifiedByUserFlag = modifiedByUserFlag,
            )
            count++
        }
        return count
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
