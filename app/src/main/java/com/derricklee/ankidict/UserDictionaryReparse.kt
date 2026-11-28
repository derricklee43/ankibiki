package com.derricklee.ankidict

/**
 * Retroactive exact-match sentence reparsing for user dictionary entries
 * (docs/user-dictionary-reparse-design.md, RULED 2026-11-27: R1a–R7a).
 *
 * Plan/Apply architecture (same lock-hold discipline as StremioExtractionEngine):
 *  - Phase 1 (caller): persist `user_words` first so user input survives any error
 *    and live analyzer/provider suppliers immediately see the entry.
 *  - Phase 2 (no write lock): find all active sentence scopes containing an exact
 *    substring match of `expression` (item_occurrences + zero-candidate KEEP photos
 *    and active Stremio cues, R3a), analyze via [CorpusInsertion.analyzeCandidates],
 *    reconcile at candidate granularity (R4a), and resolve canonical items + Anki
 *    links once via [CorpusInsertion.planInsertions] with a shared [CorpusInsertion.InsertionMemo].
 *  - Phase 3 (one short transaction): apply canonical upserts/links, delete obsolete
 *    non-manual fragment rows, insert missing candidates (preserving existing sentence
 *    timestamp, R5a), and record a single compact delta [UndoAction.USER_WORD_REPARSE]
 *    memento (R6a).
 */
internal data class SentenceScope(
    /** media_refs.id; null ONLY for a zero-candidate photo with no media_ref yet. */
    val mediaId: Long?,
    /** Non-null iff media_refs.kind == "photo" (v15 dual-write). */
    val photoId: Long?,
    val sentence: String,
    /** Existing MAX(created_at) or source timestamp (epoch seconds) for R5a. */
    val existingCreatedAt: Long?,
)

internal data class ReconcileDiff(
    val keptCount: Int,
    val deleteIds: List<Long>,
    val missingCandidates: List<Pair<ItemType, NormalizedCandidate>>,
    val mergedDuplicateIds: List<Long>,
) {
    val hasChanges: Boolean
        get() = deleteIds.isNotEmpty() || missingCandidates.isNotEmpty() || mergedDuplicateIds.isNotEmpty()
}

data class ReparseSummary(
    val matchedSentences: Int = 0,
    val changedSentences: Int = 0,
    val addedOccurrences: Int = 0,
    val removedOccurrences: Int = 0,
    val mergedDuplicates: Int = 0,
) {
    val hasCorpusChanges: Boolean
        get() = addedOccurrences > 0 || removedOccurrences > 0 || mergedDuplicates > 0
}

internal object UserDictionaryReparse {

    internal data class ScopedDiff(
        val scope: SentenceScope,
        val diff: ReconcileDiff,
    )

    internal class ReparsePlan(
        val matchedSentences: Int,
        val changedScopes: List<ScopedDiff>,
        val insertionPlan: CorpusInsertion.InsertionPlan,
    )

    /**
     * Pure candidate-granularity reconciliation (R4a), matching
     * [MiningPipelineRunner.reprocessAmendedSentence]:
     *  - obsolete non-manual rows drop (e.g. broken Kuromoji fragments);
     *  - manual rows (`isManual == true`) always survive;
     *  - surviving candidates preserve their highest triage state (DELETED > SEEN > UNSEEN),
     *    merging duplicates;
     *  - missing candidates are scheduled for insertion.
     */
    fun reconcileOccurrenceRows(
        existingRows: List<MiningRepository.OccurrenceRow>,
        candidates: List<Pair<ItemType, NormalizedCandidate>>,
    ): ReconcileDiff {
        val keepKeys = candidates.map { candidateKey(it) }.toSet()
        val sorted = existingRows.sortedWith(
            compareByDescending<MiningRepository.OccurrenceRow> { stateRank(it.state) }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id },
        )
        var kept = 0
        val deleteIds = ArrayList<Long>()
        val mergedDuplicateIds = ArrayList<Long>()
        val seenKeys = HashSet<String>()
        for (row in sorted) {
            val key = row.itemType + "\u0000" + row.expression
            when {
                key !in keepKeys && !row.isManual -> deleteIds += row.id
                key !in keepKeys -> Unit // is_manual = 1 survives (schema v10)
                !seenKeys.add(key) -> mergedDuplicateIds += row.id
                else -> kept++
            }
        }
        val missing = candidates.filter { candidateKey(it) !in seenKeys }
        return ReconcileDiff(
            keptCount = kept,
            deleteIds = deleteIds,
            missingCandidates = missing,
            mergedDuplicateIds = mergedDuplicateIds,
        )
    }

    /**
     * Pure JVM-testable planning step: analyzes every matched scope, reconciles against
     * existing rows, skips no-ops (e.g. strict-substring IGNORE hits), and resolves all
     * canonical items / Anki links with one shared [CorpusInsertion.InsertionMemo].
     */
    fun planReparse(
        config: PipelineConfig,
        scopes: List<SentenceScope>,
        rowsForScope: (SentenceScope) -> List<MiningRepository.OccurrenceRow>,
        hasItem: (ItemType, String) -> Boolean,
        hasLink: (ItemType, String) -> Boolean,
        memo: CorpusInsertion.InsertionMemo = CorpusInsertion.InsertionMemo(),
    ): ReparsePlan {
        val changedScopes = ArrayList<ScopedDiff>()
        val insertionBatch = ArrayList<Pair<String, List<Pair<ItemType, NormalizedCandidate>>>>()
        for (scope in scopes) {
            val candidates = CorpusInsertion.analyzeCandidates(config, scope.sentence)
            val existing = rowsForScope(scope)
            val diff = reconcileOccurrenceRows(existing, candidates)
            if (!diff.hasChanges) continue
            changedScopes += ScopedDiff(scope, diff)
            if (diff.missingCandidates.isNotEmpty()) {
                insertionBatch += scope.sentence to diff.missingCandidates
            }
        }
        val insertionPlan = CorpusInsertion.planInsertions(
            config = config,
            batch = insertionBatch,
            hasItem = hasItem,
            hasLink = hasLink,
            memo = memo,
        )
        return ReparsePlan(
            matchedSentences = scopes.size,
            changedScopes = changedScopes,
            insertionPlan = insertionPlan,
        )
    }

    /**
     * Searches all active sentence strings matching [expression] exactly and reparses
     * every match via plan/apply. Safe for gold photos (never runs OCR or modifies
     * `scraped_photos`) and cross-media (`photo` + `video_cues`).
     */
    fun reparseMatchingSentences(
        repo: MiningRepository,
        config: PipelineConfig,
        expression: String,
        nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    ): ReparseSummary {
        val expr = expression.trim()
        if (expr.isEmpty()) return ReparseSummary()

        // Phase 2: collect scopes, analyze, reconcile, and plan OUTSIDE any write transaction.
        val scopes = collectMatchingScopes(repo, config, expr)
        if (scopes.isEmpty()) return ReparseSummary()

        val plan = planReparse(
            config = config,
            scopes = scopes,
            rowsForScope = { scope ->
                val mid = scope.mediaId
                if (mid == null) emptyList() else repo.occurrenceRowsForMediaSentence(mid, scope.sentence)
            },
            hasItem = { type, e -> repo.hasExtractedItem(config.tag, type, e) },
            hasLink = { type, e -> repo.hasLinkedAnkiCard(config.tag, type, e) },
        )
        if (plan.changedScopes.isEmpty()) {
            return ReparseSummary(matchedSentences = plan.matchedSentences)
        }

        val allDeleteIds = plan.changedScopes.flatMap { it.diff.deleteIds + it.diff.mergedDuplicateIds }
        val deletedSnapshots = repo.undoOccRowsByIds(allDeleteIds)

        // Phase 3: apply pure SQLite writes + delta undo memento in ONE short transaction.
        var removedCount = 0
        var mergedCount = 0
        val insertedIds = ArrayList<Long>()
        val touchedMediaIds = LinkedHashSet<Long>()
        val fallbackNow = nowSeconds()

        repo.inTransaction {
            CorpusInsertion.applyItemsAndLinks(repo, config, plan.insertionPlan)
            for (scoped in plan.changedScopes) {
                for (id in scoped.diff.deleteIds) {
                    removedCount += repo.deleteOccurrenceById(id)
                }
                for (id in scoped.diff.mergedDuplicateIds) {
                    mergedCount += repo.deleteOccurrenceById(id)
                }
                val resolvedMediaId = scoped.scope.mediaId
                    ?: repo.getOrCreateMediaRef("photo", scoped.scope.photoId!!)
                touchedMediaIds += resolvedMediaId
                val timestamp = scoped.scope.existingCreatedAt ?: fallbackNow
                for ((itemType, candidate) in scoped.diff.missingCandidates) {
                    val rowId = repo.insertOccurrence(
                        languageTag = config.tag,
                        itemType = itemType,
                        expression = candidate.expression,
                        photoId = scoped.scope.photoId,
                        sentence = scoped.scope.sentence,
                        createdAt = timestamp,
                        modifiedByUserFlag = false,
                        isManual = false,
                        mediaId = resolvedMediaId,
                    )
                    if (rowId > 0L) insertedIds += rowId
                }
            }
            if (insertedIds.isNotEmpty() || deletedSnapshots.isNotEmpty()) {
                repo.recordUndo(
                    UndoMemento(
                        action = UndoAction.USER_WORD_REPARSE,
                        label = repo.appContext.getString(
                            R.string.undo_label_user_word_reparsed,
                            expr,
                            plan.changedScopes.size,
                        ),
                        header = listOf(
                            config.tag.name,
                            expr,
                            insertedIds.joinToString(","),
                            touchedMediaIds.joinToString(","),
                        ),
                        occurrences = deletedSnapshots,
                    ),
                )
            }
        }

        return ReparseSummary(
            matchedSentences = plan.matchedSentences,
            changedSentences = plan.changedScopes.size,
            addedOccurrences = insertedIds.size,
            removedOccurrences = removedCount,
            mergedDuplicates = mergedCount,
        )
    }

    /** Collects active occurrence scopes + zero-candidate photo/cue scopes (R3a). */
    private fun collectMatchingScopes(
        repo: MiningRepository,
        config: PipelineConfig,
        expr: String,
    ): List<SentenceScope> {
        val result = ArrayList<SentenceScope>()
        val seenMediaSentences = HashSet<Pair<Long, String>>()
        val seenNewPhotoSentences = HashSet<Pair<Long, String>>()

        // 1. Active item_occurrences across ALL media kinds (photo + video_cues).
        for (scope in repo.findActiveSentenceScopesContaining(config.tag, expr)) {
            val mid = scope.mediaId ?: continue
            if (seenMediaSentences.add(mid to scope.sentence)) {
                result += scope
            }
        }

        // 2. R3a: zero-candidate KEEP photo sentences not present in item_occurrences.
        for ((photoId, extractedText, dateAdded) in repo.findKeepPhotosContaining(config.tag, expr)) {
            val existingMediaId = repo.findMediaRefId("photo", photoId)
            val parsed = MiningPipelineRunner.parsePhotoText(config, extractedText)
            for (sentence in parsed) {
                if (!sentence.contains(expr)) continue
                if (existingMediaId != null) {
                    if (!seenMediaSentences.add(existingMediaId to sentence)) continue
                    // Skip if ANY row (incl. DELETED) exists -> respects SENTENCE_DELETE.
                    if (repo.hasAnyOccurrenceForMediaSentence(existingMediaId, sentence)) continue
                    result += SentenceScope(
                        mediaId = existingMediaId,
                        photoId = photoId,
                        sentence = sentence,
                        existingCreatedAt = dateAdded.takeIf { it > 0L },
                    )
                } else {
                    if (!seenNewPhotoSentences.add(photoId to sentence)) continue
                    result += SentenceScope(
                        mediaId = null,
                        photoId = photoId,
                        sentence = sentence,
                        existingCreatedAt = dateAdded.takeIf { it > 0L },
                    )
                }
            }
        }

        // 3. R3a: zero-candidate active Stremio cue sentences not present in item_occurrences.
        for (scope in repo.findZeroCandidateStremioCues(config.tag, expr)) {
            val mid = scope.mediaId ?: continue
            if (seenMediaSentences.add(mid to scope.sentence)) {
                result += scope
            }
        }

        return result
    }

    private fun candidateKey(candidate: Pair<ItemType, NormalizedCandidate>): String =
        candidate.first.name + "\u0000" + candidate.second.expression

    private fun stateRank(state: String): Int = when (state) {
        OccurrenceState.DELETED.name -> 2
        OccurrenceState.SEEN.name -> 1
        else -> 0
    }
}
