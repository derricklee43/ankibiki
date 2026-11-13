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
                miningRepository.deleteCorrectionsForPhoto(candidate.mediaStoreId)
                // This is an explicit replacement of the same photo, not a newly discovered retake.
                processOcrDocument(candidate, config, document, checkDuplicates = false, prepared = prepared)
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

        return processOcrDocument(candidate, config, ocrDocument, prepared = prepared)
    }

    /**
     * Runs Stages 4–9 (duplicate check, sentence parsing, word/grammar normalization, dictionary/Anki
     * enrichment, and 1-occurrence-per-sentence insertion) on an extracted [OcrDocument].
     * When [prepared] is supplied and the pipeline carries a correction pass, the stored document is
     * first auto-corrected against a second Cloud read (S003-D' gate; docs/roadmap.md §2).
     */
    fun processOcrDocument(
        candidate: PhotoCandidate,
        config: PipelineConfig,
        ocrDocument: OcrDocument,
        checkDuplicates: Boolean = true,
        prepared: PreparedImage? = null,
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

        // Cloud-gate correction (user decision 2026-11-13, spike 003): runs between the duplicate
        // check and parsing so every derived artifact (text, sentences, occurrences) sees the
        // corrected document; raw geometry/bounds survive because only line TEXTS are rewritten.
        // Skipped silently when disabled, unconfigured (no pass), offline, or the doc is already
        // a cloud read. Evidence rows persist for every island — accepted or not — so the
        // annotation loop scores the gate on real data and each write is reversible.
        var workingDocument = ocrDocument
        var correctionEvidence: List<CorrectionEvidenceRow> = emptyList()
        val pass = config.correctionPass
        if (pass != null && config.ocrCorrectionEnabled && prepared != null &&
            tag == LanguageTag.JAPANESE // JMdict-anchored gate is measured for Japanese only
        ) {
            val outcome = pass.correct(candidate, ocrDocument, prepared)
            workingDocument = outcome.document
            correctionEvidence = outcome.evidence
        }

        val sentences0 = config.sentenceParser.parseSentences(workingDocument)
        // Nameplate audit claims (user 2026-11-13): proxy drafts that LEAKED into the sentence
        // list become pending claims — visible until the user rules; a confirmed plate is hidden
        // for good here (rescan re-filter) and by applyNameplateVerdict (retroactive strip).
        // Nothing is removed on draft; the user's verdict is the only destructive action.
        val claims = sentences0.filter { it.isNotBlank() }
        miningRepository.insertNameplateClaims(
            candidate.mediaStoreId,
            runCatching { OcrCorrectionPass.draftNameplateStrings(workingDocument) }
                .getOrDefault(emptySet())
                intersect claims.map { gateCjkOnly(it) }.toSet(),
        )
        val hidden = miningRepository.hiddenNameplateCores(candidate.mediaStoreId)
        val sentences = if (hidden.isEmpty()) claims else claims.filterNot { gateCjkOnly(it) in hidden }
        val displayExtractedText = if (sentences.isNotEmpty()) {
            sentences.joinToString("\n")
        } else {
            workingDocument.fullText.trim()
        }

        miningRepository.upsertPhoto(
            ScrapedPhoto(
                mediaStoreId = candidate.mediaStoreId,
                contentUri = candidate.contentUri,
                dateAdded = candidate.dateAdded,
                languageTag = tag,
                extractedText = displayExtractedText,
                ocrBlocksJson = workingDocument.toJsonString(),
                status = PhotoStatus.KEEP,
                duplicateOfPhotoId = null,
            ),
        )
        miningRepository.insertCorrectionRows(correctionEvidence)

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
     * Saves amended text for [mediaStoreId]. When [reprocessWords] is true, reconciles word and
     * grammar occurrences at SENTENCE granularity instead of rebuilding everything: sentences that
     * survived the edit keep their existing rows (review/deletion triage and user-edit flags
     * included — nothing resurrects silently), removed sentences drop their rows, and only added
     * sentences get fresh extraction. Analysis runs before any write and every mutation shares one
     * transaction, so an injected failure leaves the previous corpus exactly intact (docs/roadmap.md §1).
     */
    fun reprocessPhotoText(
        mediaStoreId: Long,
        amendedText: String,
        reprocessWords: Boolean,
    ): PhotoTextReprocessSummary {
        val originalPhoto = miningRepository.getPhoto(mediaStoreId)
            ?: return PhotoTextReprocessSummary(textSaved = false, keptSentences = 0)
        val effectiveTag = if (originalPhoto.languageTag == LanguageTag.NA) {
            LanguageTag.JAPANESE
        } else {
            originalPhoto.languageTag
        }
        val config = pipelineConfigs[effectiveTag] ?: pipelineConfigs[LanguageTag.JAPANESE]
        val newSentences = parsePhotoText(config, amendedText)
        val oldSentences = parsePhotoText(config, originalPhoto.extractedText)
        val originalCounts = oldSentences.groupingBy { it }.eachCount()
        // Count increases detect edits that become identical to another sentence on this photo.
        val changedSentences = if (originalPhoto.extractedText != amendedText) {
            newSentences.groupingBy { it }.eachCount()
                .filter { (sentence, count) -> count > (originalCounts[sentence] ?: 0) }.keys
        } else {
            emptySet()
        }
        // Reconcile against the occurrence ROWS, not the photo text: a previous text-only save
        // intentionally leaves rows keyed by the old sentences ("derived words are stale"), so
        // only the rows show what extraction state actually exists for this photo.
        val oldSet = miningRepository.getSentenceModificationFlags(mediaStoreId).keys
        val newSet = newSentences.toSet()
        val keptSentences = newSet.count { it in oldSet }
        val removedSentences = (oldSet - newSet).toList()
        val addedSentences = (newSet - oldSet).toList()

        // Extraction may throw; do it BEFORE any write so a failure changes nothing at all.
        val shouldReprocess = reprocessWords && config != null
        val addedPlans = if (shouldReprocess) {
            addedSentences.map { sentence ->
                sentence to analyzeCandidates(config!!, sentence)
            }
        } else {
            emptyList()
        }

        miningRepository.inTransaction {
            miningRepository.updatePhotoExtractedText(mediaStoreId, amendedText)
            miningRepository.markSentencesModifiedByUser(mediaStoreId, changedSentences)
            if (shouldReprocess) {
                for (sentence in removedSentences) {
                    miningRepository.deleteOccurrencesForSentence(mediaStoreId, sentence)
                }
                val baseTimestamp = System.currentTimeMillis() / 1000L
                val photoModified = originalPhoto.modifiedByUserFlag || amendedText != originalPhoto.extractedText
                for ((index, pair) in addedPlans.withIndex()) {
                    val (sentence, candidates) = pair
                    insertCandidates(
                        config = config!!,
                        photoId = mediaStoreId,
                        sentence = sentence,
                        candidates = candidates,
                        timestamp = baseTimestamp + index,
                        modifiedByUserFlag = photoModified || sentence in changedSentences,
                    )
                }
            }
        }
        return PhotoTextReprocessSummary(
            textSaved = true,
            wordsReprocessed = shouldReprocess,
            keptSentences = keptSentences,
            addedSentences = if (shouldReprocess) addedSentences.size else 0,
            removedSentences = if (shouldReprocess) removedSentences.size else 0,
        )
    }

    /**
     * Saves an amended sentence for [photoId]. Renames existing occurrences (preserving their
     * review state), then reconciles at CANDIDATE granularity: words/grammar still produced by
     * the new sentence keep their rows and triage, candidates no longer produced are dropped,
     * and only genuinely new candidates are extracted fresh (UNSEEN). Duplicate rows created when
     * an edit lands on a sentence that already existed on the photo merge to the most-triaged one.
     * Analysis runs before the transaction; injected failures leave the corpus intact.
     */
    fun reprocessAmendedSentence(
        photoId: Long,
        oldSentence: String,
        newSentence: String,
        reprocessWords: Boolean,
    ): SentenceReprocessSummary {
        val trimmedNew = newSentence.trim()
        val preFlags = miningRepository.getSentenceModificationFlags(photoId)
        val sentenceModified = oldSentence != trimmedNew ||
            preFlags[trimmedNew] == true || preFlags[oldSentence] == true
        if (!reprocessWords) {
            miningRepository.updateSentenceText(photoId, oldSentence, trimmedNew)
            return SentenceReprocessSummary(textSaved = true, wordsReprocessed = false,
                keptCandidates = 0, addedCandidates = 0, removedCandidates = 0)
        }

        val photo = miningRepository.getPhoto(photoId)
        val effectiveTag = if (photo == null || photo.languageTag == LanguageTag.NA) {
            LanguageTag.JAPANESE
        } else {
            photo.languageTag
        }
        val config = pipelineConfigs[effectiveTag] ?: pipelineConfigs[LanguageTag.JAPANESE]
            ?: return SentenceReprocessSummary(textSaved = false, wordsReprocessed = false,
                keptCandidates = 0, addedCandidates = 0, removedCandidates = 0).also {
                miningRepository.updateSentenceText(photoId, oldSentence, trimmedNew)
            }

        // Extraction before writes: a throwing analyzer changes nothing.
        val candidates = analyzeCandidates(config, trimmedNew)
        val keepKeys = candidates.map { candidateKey(it) }.toSet()

        val tx = miningRepository.inTransaction {
            miningRepository.updateSentenceText(photoId, oldSentence, trimmedNew)
            var kept = 0
            var removed = 0
            var merged = 0
            val seenKeys = mutableSetOf<String>()
            // Most-triaged first: DELETED > SEEN > UNSEEN, then newest — so merges keep the
            // strongest review decision instead of resurrecting a weaker one.
            val rows = miningRepository.occurrenceRowsForSentence(photoId, trimmedNew)
                .sortedWith(
                    compareByDescending<MiningRepository.OccurrenceRow> { stateRank(it.state) }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.id },
                )
            for (row in rows) {
                val key = row.itemType + "\u0000" + row.expression
                when {
                    key !in keepKeys -> {
                        miningRepository.deleteOccurrenceById(row.id)
                        removed++
                    }
                    !seenKeys.add(key) -> {
                        // Collision merge (rows were newest-first): keep the most-triaged row.
                        miningRepository.deleteOccurrenceById(row.id)
                        merged++
                    }
                    else -> kept++
                }
            }
            val missing = candidates.filter { candidateKey(it) !in seenKeys }
            val added = insertCandidates(
                config = config,
                photoId = photoId,
                sentence = trimmedNew,
                candidates = missing,
                timestamp = System.currentTimeMillis() / 1000L,
                modifiedByUserFlag = sentenceModified,
            )
            ReprocessCounters(kept, added, removed, merged)
        }
        return SentenceReprocessSummary(
            textSaved = true,
            wordsReprocessed = true,
            keptCandidates = tx.kept,
            addedCandidates = tx.added,
            removedCandidates = tx.removed,
            mergedDuplicates = tx.merged,
        )
    }

    /** In-transaction counters (inTransaction returns the block result). */
    private data class ReprocessCounters(val kept: Int, val added: Int, val removed: Int, val merged: Int)

    private fun candidateKey(candidate: Pair<ItemType, NormalizedCandidate>): String =
        candidate.first.name + "\u0000" + candidate.second.expression

    private fun stateRank(state: String): Int = when (state) {
        OccurrenceState.DELETED.name -> 2
        OccurrenceState.SEEN.name -> 1
        else -> 0
    }

    /** Words + grammar produced for a sentence, before any database mutation (may throw). */
    private fun analyzeCandidates(
        config: PipelineConfig,
        sentence: String,
    ): List<Pair<ItemType, NormalizedCandidate>> =
        config.wordAnalyzer.extractWords(sentence)
            .distinctBy { it.expression }
            .map { ItemType.WORD to it } +
            config.grammarAnalyzer.extractGrammar(sentence)
                .distinctBy { it.expression }
                .map { ItemType.GRAMMAR to it }

    /** Registers canonical items and inserts occurrence rows for [candidates]. */
    private fun insertCandidates(
        config: PipelineConfig,
        photoId: Long,
        sentence: String,
        candidates: List<Pair<ItemType, NormalizedCandidate>>,
        timestamp: Long,
        modifiedByUserFlag: Boolean,
    ): Int {
        var count = 0
        for ((itemType, candidate) in candidates) {
            ensureCanonicalItemExists(config, itemType, candidate)
            miningRepository.insertOccurrence(
                languageTag = config.tag,
                itemType = itemType,
                expression = candidate.expression,
                photoId = photoId,
                sentence = sentence,
                createdAt = timestamp,
                modifiedByUserFlag = modifiedByUserFlag,
            )
            count++
        }
        return count
    }

    private fun parsePhotoText(config: PipelineConfig?, text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = lines.map { line -> OcrBlock(text = line, lines = listOf(OcrLine(text = line))) }
        return config?.sentenceParser?.parseSentences(OcrDocument(fullText = text, blocks = blocks))
            ?.takeIf { it.isNotEmpty() } ?: lines
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

            val cloudJp = CloudVisionTextExtractor(LanguageTag.JAPANESE)
            val jpTextExtractor = CompositeTextExtractor(
                cloudExtractor = cloudJp,
                fallbackExtractor = MlKitTextExtractor(LanguageTag.JAPANESE),
            )
            val zhTextExtractor = CompositeTextExtractor(
                cloudExtractor = CloudVisionTextExtractor(LanguageTag.CHINESE),
                fallbackExtractor = MlKitTextExtractor(LanguageTag.CHINESE),
            )

            // S003-D' auto-correction (user decision 2026-11-13): only when the Cloud key is
            // configured; the lexicon and gate build lazily on first corrected scan.
            val jpCorrectionPass: OcrCorrectionPass? = if (cloudJp.isConfigured()) {
                OcrCorrectionPass(
                    gateProvider = { OcrCorrectionGate(GateLexiconProvider.lexicon(appContext)) },
                    reader = CloudReedReader { image ->
                        runCatching { cloudJp.extract(image, null) }.getOrNull()
                    },
                )
            } else {
                null
            }

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
                correctionPass = jpCorrectionPass,
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

/** Photo-text save outcome; sentence-granularity counts (docs/roadmap.md §1 safe reprocessing). */
data class PhotoTextReprocessSummary(
    val textSaved: Boolean,
    val wordsReprocessed: Boolean = false,
    val keptSentences: Int = 0,
    val addedSentences: Int = 0,
    val removedSentences: Int = 0,
)

/** Single-sentence save outcome; candidate-granularity counts. */
data class SentenceReprocessSummary(
    val textSaved: Boolean,
    val wordsReprocessed: Boolean = false,
    val keptCandidates: Int = 0,
    val addedCandidates: Int = 0,
    val removedCandidates: Int = 0,
    val mergedDuplicates: Int = 0,
)
