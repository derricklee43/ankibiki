package com.derricklee.ankidict

/**
 * Shared "analyze a sentence, register canonical vocabulary, insert occurrence rows"
 * use case (repo rule: when a second source needs a mutation the first already
 * performs, extract VERBATIM and rewire the original). Bodies copied verbatim from
 * MiningPipelineRunner (analyzeCandidates / insertCandidates / ensureCanonicalItemExists);
 * the runner now delegates here, so photo mining and Stremio cue mining share ONE
 * insertion path. v15 (docs/sentence-media-refs-design.md): every row lands with a
 * media_refs id; photos additionally mirror photo_id (dual-write), non-photo media
 * carry photo_id NULL. The stage-1 negative-photo_id namespace is retired.
 */
internal object CorpusInsertion {

    fun analyzeCandidates(
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
    fun insertCandidates(
        repo: MiningRepository,
        config: PipelineConfig,
        mediaId: Long,
        photoId: Long? = null,
        sentence: String,
        candidates: List<Pair<ItemType, NormalizedCandidate>>,
        timestamp: Long,
        modifiedByUserFlag: Boolean = false,
    ): Int {
        var count = 0
        for ((itemType, candidate) in candidates) {
            ensureCanonicalItemExists(repo, config, itemType, candidate)
            repo.insertOccurrence(
                languageTag = config.tag,
                itemType = itemType,
                expression = candidate.expression,
                photoId = photoId,
                sentence = sentence,
                createdAt = timestamp,
                modifiedByUserFlag = modifiedByUserFlag,
                mediaId = mediaId,
            )
            count++
        }
        return count
    }

    fun ensureCanonicalItemExists(
        repo: MiningRepository,
        config: PipelineConfig,
        itemType: ItemType,
        candidate: NormalizedCandidate,
    ) {
        val tag = config.tag
        if (repo.hasExtractedItem(tag, itemType, candidate.expression)) {
            if (!repo.hasLinkedAnkiCard(tag, itemType, candidate.expression)) {
                val lateMatch = config.ankiMatchLookup?.findMatch(
                    languageTag = tag,
                    itemType = itemType,
                    expression = candidate.expression,
                    reading = candidate.readingKana.orEmpty(),
                )
                if (lateMatch != null) {
                    repo.updateExtractedItemAnkiMatch(
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

        repo.upsertExtractedItem(
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
}
