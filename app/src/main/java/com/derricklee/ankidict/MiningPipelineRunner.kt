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

    /** v14 console hook: the language pipeline configs, read-only (StremioExtractionEngine). */
    internal fun configFor(tag: LanguageTag): PipelineConfig? = pipelineConfigs[tag]

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
        if (originalPhoto?.isGold == true) {
            // Gold is the finalizing stamp (user 2026-11-13): a rescan would replace the text
            // and derived occurrences it attests to — refuse at the code level, UI hides too.
            return PhotoRescanResult.SKIPPED
        }
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
        // Nameplate audit claims (user 2026-11-13): proxy drafts that LEAK into the sentence
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
        val standaloneFiltered =
            if (hidden.isEmpty()) claims else claims.filterNot { gateCjkOnly(it) in hidden }
        // H9v2 inline-plate PROPOSALS (spike 004, suggestion grade): confirmed cuts re-apply on
        // every mine/rescan (hidden-for-good); fresh firings enter the ledger as PENDING rows.
        // Nothing here deletes or rewrites text without a user verdict (G1/G2).
        val lex = config.nameplateLexicon?.invoke()
        val sentences = if (lex == null || tag != LanguageTag.JAPANESE) {
            standaloneFiltered
        } else {
            val hiddenInline = miningRepository.hiddenInlineClaims(candidate.mediaStoreId)
            val cut = if (hiddenInline.isEmpty()) standaloneFiltered else standaloneFiltered.map { s ->
                val ruling = hiddenInline.firstOrNull { it.contextSentence == s }
                if (ruling == null) s
                else NameplateInlineRule.suggestions(s, lex).firstOrNull { it.plate == ruling.text }?.remainder ?: s
            }
            miningRepository.insertInlineNameplateClaims(
                candidate.mediaStoreId,
                cut.flatMap { s -> NameplateInlineRule.suggestions(s, lex).map { it.plate to s } },
            )
            cut
        }
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

    /**
     * Sentence word editor apply (user feature 2026-11-13): the editor renders this sentence's
     * occurrence list as an editable table (word input + delete, 追加 row); this diffs the final
     * rows against the stored set — renames re-key preserving review state, dropped rows are
     * SOFT-deleted (recoverable: re-adding the word restores them), new words are free-form
     * (user decision: no grounding requirement) and get canonical definitions like mined words.
     * Manual rows survive later reprocessing (modified flag).
     */
    data class WordEditorRow(
        val originalType: ItemType?,
        val originalExpression: String?,
        val expression: String,
    )

    fun saveSentenceWords(photoId: Long, sentence: String, rows: List<WordEditorRow>): Int {
        val photo = miningRepository.getPhoto(photoId)
        val effectiveTag = if (photo == null || photo.languageTag == LanguageTag.NA) {
            LanguageTag.JAPANESE
        } else {
            photo.languageTag
        }
        val config = pipelineConfigs[effectiveTag] ?: pipelineConfigs[LanguageTag.JAPANESE]
            ?: error("No pipeline config for $effectiveTag")
        val finalRows = rows.map { it.copy(expression = it.expression.trim()) }
            .filter { it.expression.isNotEmpty() }
        // Snapshot before writes (reads during a transaction can lag the connection).
        val activeBefore = miningRepository.getSentenceCandidates(photoId, sentence)
        val originals = finalRows.filter { it.originalExpression != null }
        val additions = finalRows.filter { it.originalExpression == null }
        // Consumed = names the editor still accounts for (kept or renamed-away); everything
        // else that was active is what the user deleted.
        val consumed = originals.mapNotNull { it.originalExpression }.toSet()
        val currents = originals.map { it.expression }.toSet()
        // Undo (user 2026-11-15): whole-photo prior state; text never moves in this path.
        val prior = miningRepository.undoPhotoRow(photoId)
        val priorRows = if (prior != null) miningRepository.undoOccRowsForPhoto(photoId) else emptyList()
        return miningRepository.inTransaction {
            var changes = 0
            for (row in originals) {
                val type = row.originalType ?: continue
                val old = row.originalExpression ?: continue
                if (old == row.expression) continue
                ensureCanonicalItemExists(
                    config, type,
                    NormalizedCandidate(expression = row.expression, surfaceForm = row.expression),
                )
                miningRepository.updateOccurrenceExpression(photoId, sentence, old, row.expression)
                changes++
            }
            for (candidate in activeBefore) {
                val expr = candidate.expression
                if (expr in consumed || expr in currents) continue
                miningRepository.setOccurrenceStateForExpression(photoId, sentence, expr, OccurrenceState.DELETED)
                changes++
            }
            for (row in additions) {
                val states = miningRepository.occurrenceStatesForExpression(photoId, sentence, row.expression)
                if (states.any { it != OccurrenceState.DELETED.name }) continue
                ensureCanonicalItemExists(
                    config, ItemType.WORD,
                    NormalizedCandidate(expression = row.expression, surfaceForm = row.expression),
                )
                if (OccurrenceState.DELETED.name in states) {
                    // Re-adding a soft-deleted word restores it instead of duplicating rows.
                    miningRepository.setOccurrenceStateForExpression(
                        photoId, sentence, row.expression, OccurrenceState.UNSEEN,
                    )
                } else {
                    miningRepository.insertOccurrence(
                        languageTag = effectiveTag,
                        itemType = ItemType.WORD,
                        expression = row.expression,
                        photoId = photoId,
                        sentence = sentence,
                        isManual = true,
                    )
                }
                changes++
            }
            if (changes > 0 && prior != null) {
                miningRepository.recordUndo(UndoMemento(
                    UndoAction.SENTENCE_WORDS_EDIT,
                    miningRepository.appContext.getString(R.string.undo_label_words_edit),
                    listOf(photoId.toString(), prior.extractedText),
                    photo = prior,
                    occurrences = priorRows,
                ))
            }
            changes
        }
    }

    /**
     * Media-anchored twin of [saveSentenceWords] for non-photo sources (video cue
     * sentences; user request 2026-11-26). Same edit semantics — rename re-keys (review
     * state rides), removal soft-deletes, free-form rows add with canonical
     * definitions — scoped by (media_refs.id, sentence) instead of (photo_id, sentence),
     * and undone via the MEDIA_WORDS_EDIT scope-replace memento. [languageTag] comes
     * from the caller (the occurrence row's tag) because there is no photo to read.
     */
    fun saveSentenceWordsForMedia(
        mediaRefId: Long,
        languageTag: LanguageTag,
        sentence: String,
        rows: List<WordEditorRow>,
    ): Int {
        val effectiveTag = if (languageTag == LanguageTag.NA) LanguageTag.JAPANESE else languageTag
        val config = pipelineConfigs[effectiveTag] ?: pipelineConfigs[LanguageTag.JAPANESE]
            ?: error("No pipeline config for $effectiveTag")
        val finalRows = rows.map { it.copy(expression = it.expression.trim()) }
            .filter { it.expression.isNotEmpty() }
        // Snapshot before writes (reads during a transaction can lag the connection).
        val activeBefore = miningRepository.getSentenceCandidatesForMedia(mediaRefId, sentence)
        val originals = finalRows.filter { it.originalExpression != null }
        val additions = finalRows.filter { it.originalExpression == null }
        val consumed = originals.mapNotNull { it.originalExpression }.toSet()
        val currents = originals.map { it.expression }.toSet()
        // Undo: whole (media, sentence) scope prior state; text never moves in this path.
        val priorRows = miningRepository.undoOccRowsForMedia(mediaRefId, sentence)
        return miningRepository.inTransaction {
            var changes = 0
            for (row in originals) {
                val type = row.originalType ?: continue
                val old = row.originalExpression ?: continue
                if (old == row.expression) continue
                ensureCanonicalItemExists(
                    config, type,
                    NormalizedCandidate(expression = row.expression, surfaceForm = row.expression),
                )
                miningRepository.updateOccurrenceExpressionForMedia(mediaRefId, sentence, old, row.expression)
                changes++
            }
            for (candidate in activeBefore) {
                val expr = candidate.expression
                if (expr in consumed || expr in currents) continue
                miningRepository.setOccurrenceStateForExpressionForMedia(
                    mediaRefId, sentence, expr, OccurrenceState.DELETED,
                )
                changes++
            }
            for (row in additions) {
                val states = miningRepository.occurrenceStatesForExpressionForMedia(
                    mediaRefId, sentence, row.expression,
                )
                if (states.any { it != OccurrenceState.DELETED.name }) continue
                ensureCanonicalItemExists(
                    config, ItemType.WORD,
                    NormalizedCandidate(expression = row.expression, surfaceForm = row.expression),
                )
                if (OccurrenceState.DELETED.name in states) {
                    miningRepository.setOccurrenceStateForExpressionForMedia(
                        mediaRefId, sentence, row.expression, OccurrenceState.UNSEEN,
                    )
                } else {
                    miningRepository.insertOccurrence(
                        languageTag = effectiveTag,
                        itemType = ItemType.WORD,
                        expression = row.expression,
                        photoId = null,
                        sentence = sentence,
                        isManual = true,
                        mediaId = mediaRefId,
                    )
                }
                changes++
            }
            if (changes > 0 && priorRows.isNotEmpty()) {
                miningRepository.recordUndo(UndoMemento(
                    UndoAction.MEDIA_WORDS_EDIT,
                    miningRepository.appContext.getString(R.string.undo_label_words_edit),
                    listOf(mediaRefId.toString(), sentence),
                    occurrences = priorRows,
                ))
            }
            changes
        }
    }

    /** Soft-deletes one whole sentence's occurrences (media scope) with an undo memento. */
    fun deleteSentence(mediaRefId: Long, sentence: String): Int = miningRepository.inTransaction {
        val priorRows = miningRepository.undoOccRowsForMedia(mediaRefId, sentence)
        val changes = miningRepository.markSentenceDeletedForMedia(mediaRefId, sentence)
        if (changes > 0 && priorRows.isNotEmpty()) {
            miningRepository.recordUndo(UndoMemento(
                UndoAction.SENTENCE_DELETE,
                miningRepository.appContext.getString(R.string.undo_label_sentence_deleted),
                listOf(mediaRefId.toString(), sentence),
                occurrences = priorRows,
            ))
        }
        changes
    }

    // v14 (Stremio console one-shot, 2026-11-23): the three insertion primitives moved
    // VERBATIM to CorpusInsertion so cue mining shares ONE path with photo mining;
    // these delegates keep every existing call site (and its tests) editing-free.
    private fun ensureCanonicalItemExists(
        config: PipelineConfig,
        itemType: ItemType,
        candidate: NormalizedCandidate,
    ) = CorpusInsertion.ensureCanonicalItemExists(miningRepository, config, itemType, candidate)

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
        /** NameplateRuling suppresses the internal record and files a single unified
         *  NAMEPLATE_RULING memento instead (one ruling = one undo step). */
        recordUndo: Boolean = true,
        /** Audit FU1 (2026-11-23, user-approved): a nameplate ruling is a user-APPROVED machine
         *  edit, not authorship. When false, this call sets modified_by_user_flag NOWHERE —
         *  photo row skipped, no per-sentence marking, inserted candidates carry it only via
         *  insertOccurrence's existing-row inheritance (sentences a human really typed). */
        authorship: Boolean = true,
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
        // GOLD (user go 2026-11-15, gold-sentence-edit-reconcile proposal): a gold photo's TEXT
        // edits may re-derive words when the user chose 再解析 — the candidate-granularity
        // reconcile keeps manual rows and review states; untouched wrong rows for vanished
        // sentences are fair game to drop. What gold still forbids is IMAGE reprocessing (OCR) —
        // this path never touches the tagger/extractor or ocr_blocks_json.
        val shouldReprocess = reprocessWords && config != null
        val addedPlans = if (shouldReprocess) {
            addedSentences.map { sentence ->
                sentence to analyzeCandidates(config!!, sentence)
            }
        } else {
            emptyList()
        }
        // Audit FU3 (2026-11-23, user-approved): per-SENTENCE provenance replaces the photo
        // blanket. A re-derived sentence is "typed" when (a) this edit just introduced it
        // (changedSentences), or (b) the photo carries human-edit provenance and the sentence
        // is NOT part of the stored machine baseline (raw OCR fullText) — case (b) catches
        // text typed by an earlier text-only save whose rows went stale. Sentences that raw
        // OCR itself produced no longer inherit the flag just because the photo was once
        // edited (the 628/1002 inherited-noise rows the audit measured).
        val photoEverEdited = originalPhoto.modifiedByUserFlag
        val machineBaseline: Set<String> =
            if (shouldReprocess && photoEverEdited) {
                OcrDocument.fromJsonString(originalPhoto.ocrBlocksJson)
                    ?.let { parsePhotoText(config, it.fullText).toSet() }
                    ?: emptySet()
            } else {
                emptySet()
            }

        // Undo (user 2026-11-15): full prior state captured OUTSIDE the transaction; the memento
        // is recorded INSIDE it (atomic with the mutation). expectedAfter = amendedText verbatim.
        val changed = originalPhoto.extractedText != amendedText
        val prior = if (changed && recordUndo) miningRepository.undoPhotoRow(mediaStoreId) else null
        val priorRows = if (prior != null) miningRepository.undoOccRowsForPhoto(mediaStoreId) else emptyList()

        miningRepository.inTransaction {
            miningRepository.updatePhotoExtractedText(mediaStoreId, amendedText, markModified = authorship)
            if (authorship) {
                miningRepository.markSentencesModifiedByUser(mediaStoreId, changedSentences)
            }
            if (shouldReprocess) {
                for (sentence in removedSentences) {
                    // Manual rows survive even on removed sentence keys (rule 3, same contract
                    // as the candidate reconcile below).
                    miningRepository.deleteNonManualOccurrencesForSentence(mediaStoreId, sentence)
                }
                val baseTimestamp = System.currentTimeMillis() / 1000L
                for ((index, pair) in addedPlans.withIndex()) {
                    val (sentence, candidates) = pair
                    insertCandidates(
                        config = config!!,
                        photoId = mediaStoreId,
                        sentence = sentence,
                        candidates = candidates,
                        timestamp = baseTimestamp + index,
                        // Audit FU3 (2026-11-23, user-approved): per changed/typed SENTENCE,
                        // never the photo-level blanket (see machineBaseline above).
                        modifiedByUserFlag = authorship &&
                            (sentence in changedSentences ||
                                (photoEverEdited && sentence !in machineBaseline)),
                    )
                }
            }
            if (prior != null) {
                miningRepository.recordUndo(UndoMemento(
                    action = UndoAction.SENTENCE_TEXT_EDIT,
                    label = miningRepository.appContext.getString(R.string.undo_label_text_edit),
                    header = listOf(mediaStoreId.toString(), amendedText),
                    photo = prior,
                    occurrences = priorRows,
                ))
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
        val photo = miningRepository.getPhoto(photoId)
        // GOLD reparse (user go 2026-11-15): re-derivation is gated ONLY by the caller's explicit
        // choice. Gold photos re-parse words on 再解析 saves — the reconcile below already preserves
        // manual rows and review states; image OCR is never invoked on this path for any photo.
        //
        // Undo: whole-photo prior state; expectedAfter mirrors updateSentenceText's replace rule.
        val prior = if (oldSentence != trimmedNew) miningRepository.undoPhotoRow(photoId) else null
        val priorRows = if (prior != null) miningRepository.undoOccRowsForPhoto(photoId) else emptyList()
        val expectedAfter = prior?.let { p ->
            if (oldSentence.isNotEmpty() && p.extractedText.contains(oldSentence))
                p.extractedText.replace(oldSentence, trimmedNew)
            else p.extractedText
        } ?: ""
        fun recordAmend() {
            if (prior != null) {
                miningRepository.recordUndo(UndoMemento(
                    UndoAction.SENTENCE_TEXT_EDIT,
                    miningRepository.appContext.getString(R.string.undo_label_text_edit),
                    listOf(photoId.toString(), expectedAfter),
                    photo = prior,
                    occurrences = priorRows,
                ))
            }
        }
        if (!reprocessWords) {
            miningRepository.inTransaction {
                miningRepository.updateSentenceText(photoId, oldSentence, trimmedNew)
                recordAmend()
            }
            return SentenceReprocessSummary(textSaved = true, wordsReprocessed = false,
                keptCandidates = 0, addedCandidates = 0, removedCandidates = 0)
        }

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
                    key !in keepKeys && !row.isManual -> {
                        miningRepository.deleteOccurrenceById(row.id)
                        removed++
                    }
                    key !in keepKeys -> {
                        // Manual occurrence (word editor, schema v10): survives even when the analyzer
                        // wouldn't re-produce it (user rule 2026-11-13, "never accidentally destroyed").
                        // modified_by_user_flag alone does NOT protect — reconcile contract §1.
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
            recordAmend()
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
        CorpusInsertion.analyzeCandidates(config, sentence)

    /** Registers canonical items and inserts occurrence rows for [candidates]. */
    private fun insertCandidates(
        config: PipelineConfig,
        photoId: Long,
        sentence: String,
        candidates: List<Pair<ItemType, NormalizedCandidate>>,
        timestamp: Long,
        modifiedByUserFlag: Boolean,
    ): Int = CorpusInsertion.insertCandidates(
        miningRepository, config,
        mediaId = miningRepository.getOrCreateMediaRef("photo", photoId),
        photoId = photoId,
        sentence = sentence, candidates = candidates,
        timestamp = timestamp, modifiedByUserFlag = modifiedByUserFlag,
    )

    private fun parsePhotoText(config: PipelineConfig?, text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = lines.map { line -> OcrBlock(text = line, lines = listOf(OcrLine(text = line))) }
        return config?.sentenceParser?.parseSentences(OcrDocument(fullText = text, blocks = blocks))
            ?.takeIf { it.isNotEmpty() } ?: lines
    }

    /**
     * Truth for the 導出した単語が古い chip: would reprocessing this display text actually
     * change the derived rows? Re-parses the text with the photo's pipeline (exactly what the
     * refresh would do) and checks candidate-bearing sentences against the stored row keys —
     * so zero-candidate lines (「はい。」) and merged-line edits never leave a permanent flag.
     */
    fun derivedWordsStale(tag: LanguageTag, text: String, rowSentences: Set<String>): Boolean {
        val effective = if (tag == LanguageTag.NA) LanguageTag.JAPANESE else tag
        val config = pipelineConfigs[effective] ?: pipelineConfigs[LanguageTag.JAPANESE]
            ?: return rowSentences != text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val parsed = parsePhotoText(config, text).toSet()
        val withCandidates = parsed.filter { analyzeCandidates(config, it).isNotEmpty() }.toSet()
        return ExtractionStatus.derivedWordsStale(parsed, rowSentences, withCandidates)
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
                    // Spike 004 verdict: the gate's plate-adjacency input is the recurrence
                    // lexicon ∪ ledger-confirmed plates — NOT the A0 proxy (4 content casualties).
                    nameplateStrings = { _ -> miningRepository.nameplateGateStrings() },
                )
            } else {
                null
            }

            val jpConfig = PipelineConfig(
                tag = LanguageTag.JAPANESE,
                textExtractor = jpTextExtractor,
                duplicateDetector = duplicateDetector,
                sentenceParser = sentenceParser,
                // v16 user dictionary (docs/user-dictionary-design.md, RULED 2026-11-26):
                // span-forcing decorator over the stock Kuromoji analyzer + user-word
                // provider FIRST so registered names register with the user's gloss.
                // Suppliers read the repo live -> entries added mid-session apply to
                // the next sentence parsed (MiningPipelineHolder stays valid).
                wordAnalyzer = UserDictionaryWordAnalyzer(
                    KuromojiWordAnalyzer(),
                    { miningRepository.userWordEntries(LanguageTag.JAPANESE) },
                ),
                grammarAnalyzer = JlptGrammarAnalyzer(),
                dictionaryProviders = listOf(
                    UserDictionaryProvider({ miningRepository.userWordEntries(LanguageTag.JAPANESE) }),
                    BundledDictionaryProvider(
                        languageTag = LanguageTag.JAPANESE,
                        dictionaryRepository = dictionaryRepository,
                        hskRepository = hskRepository,
                    ),
                ),
                ankiMatchLookup = ankiMatchLookup,
                correctionPass = jpCorrectionPass,
                // H9v2 inline plate proposals: real lexicon from the bundled dictionary (offline,
                // process-cached). Proposals only — the photo-view verdict is the actor.
                nameplateLexicon = { runCatching { GateLexiconProvider.lexicon(appContext) }.getOrNull() },
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
