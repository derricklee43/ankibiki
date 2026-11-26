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

    /** Per-episode resolution memo (engine loop, not the photo path): the slow parts
     *  of planning are the AnkiDroid ContentProvider round-trips and dictionary scans,
     *  NOT the SQL — a batch held the single pooled mining.db connection for 60+s
     *  inside those IPC calls (device freeze 2026-11-26 07:54, dumpsys dbinfo:
     *  TRANSACTION-EXCLUSIVE took 60560ms while 5451 statements summed 366ms). One
     *  entry per (kind, expression, reading) for the whole job; negative Anki results
     *  cache too, so repeated subtitle vocabulary costs zero IPC after first sight. */
    class InsertionMemo {
        val ankiMatches = HashMap<Triple<ItemType, String, String>, AnkiCardMatch?>()
        val dictionaries = HashMap<Pair<ItemType, String>, DictionaryMetadata?>()
    }

    data class ItemUpsert(
        val itemType: ItemType,
        val expression: String,
        val kanji: String,
        val kana: String,
        val meaning: String,
        val levelBadge: String?,
        val corpusRank: Int?,
        val ankiMatch: AnkiCardMatch?,
    )

    data class AnkiLinkUpdate(
        val itemType: ItemType,
        val expression: String,
        val match: AnkiCardMatch,
    )

    data class OccurrenceWrite(
        val itemType: ItemType,
        val expression: String,
        val sentence: String,
    )

    /** Write set resolved WITHOUT a long-lived DB connection; apply inside one
     *  short transaction (pure writes; statements are all sub-millisecond). */
    class InsertionPlan(
        val items: List<ItemUpsert>,
        val links: List<AnkiLinkUpdate>,
        val occurrences: List<OccurrenceWrite>,
    )

    /**
     * Resolve a whole cue batch (sentences x their analyzed candidates) into writes,
     * mirroring ensureCanonicalItemExists' semantics exactly, but keeping every slow
     * cross-app call OUTSIDE any transaction. Existence probes arrive as lambdas so
     * this is JVM-specable without SQLite; reading an item's Anki link after its
     * upsert was already planned in the same batch follows the first-sighted
     * reading, same as the sequential path would see after its own commit.
     */
    fun planInsertions(
        config: PipelineConfig,
        batch: List<Pair<String, List<Pair<ItemType, NormalizedCandidate>>>>,
        hasItem: (ItemType, String) -> Boolean,
        hasLink: (ItemType, String) -> Boolean,
        memo: InsertionMemo = InsertionMemo(),
    ): InsertionPlan {
        val items = LinkedHashMap<Pair<ItemType, String>, ItemUpsert>()
        val links = LinkedHashMap<Pair<ItemType, String>, AnkiLinkUpdate>()
        val occurrences = ArrayList<OccurrenceWrite>()

        fun findMatchCached(type: ItemType, expr: String, reading: String): AnkiCardMatch? {
            val lookup = config.ankiMatchLookup ?: return null
            if (expr.isBlank()) return null
            val key = Triple(type, expr, reading)
            memo.ankiMatches[key]?.let { return it }
            return lookup.findMatch(config.tag, type, expr, reading).also {
                memo.ankiMatches[key] = it
            }
        }

        fun dictCached(type: ItemType, expr: String, readingHint: String?): DictionaryMetadata? {
            val key = type to expr
            if (memo.dictionaries.containsKey(key)) return memo.dictionaries[key]
            var md: DictionaryMetadata? = null
            for (provider in config.dictionaryProviders) {
                md = provider.lookup(expr, type, readingHint)
                if (md != null) break
            }
            memo.dictionaries[key] = md
            return md
        }

        for ((sentence, candidates) in batch) {
            for ((itemType, candidate) in candidates) {
                val expr = candidate.expression
                occurrences += OccurrenceWrite(itemType, expr, sentence)
                if (hasItem(itemType, expr)) {
                    if (!hasLink(itemType, expr)) {
                        val late = findMatchCached(itemType, expr, candidate.readingKana.orEmpty())
                        if (late != null) links.putIfAbsent(
                            itemType to expr, AnkiLinkUpdate(itemType, expr, late)
                        )
                    }
                } else {
                    val planned = items[itemType to expr]
                    if (planned == null) {
                        val metadata = dictCached(itemType, expr, candidate.readingKana)
                        val hasKanji = expr.any { MlKitImageTagger.isCjkIdeograph(it) }
                        val resolvedKanji = if (hasKanji) {
                            metadata?.kanji?.takeIf { it.isNotBlank() } ?: expr
                        } else {
                            ""
                        }
                        val resolvedKana = metadata?.kana
                            ?: candidate.readingKana ?: expr
                        val ankiMatch = findMatchCached(itemType, expr, resolvedKana)
                        items[itemType to expr] = ItemUpsert(
                            itemType, expr, resolvedKanji, resolvedKana,
                            metadata?.meaning ?: "—",
                            metadata?.levelBadge, metadata?.corpusRank, ankiMatch,
                        )
                    } else if (planned.ankiMatch == null &&
                        candidate.readingKana != null &&
                        candidate.readingKana != planned.kana
                    ) {
                        // Same-expression, different-reading within the batch: the
                        // sequential path would late-match the second sighting after
                        // the first committed unlinked — adopt its match into the plan.
                        val late = findMatchCached(itemType, expr, candidate.readingKana)
                        if (late != null) items[itemType to expr] = planned.copy(ankiMatch = late)
                    }
                }
            }
        }
        return InsertionPlan(items.values.toList(), links.values.toList(), occurrences)
    }

    /** Applies a plan inside the CALLER's transaction; write-only, no lookups. */
    fun applyInsertions(
        repo: MiningRepository,
        config: PipelineConfig,
        plan: InsertionPlan,
        mediaId: Long,
        photoId: Long? = null,
        timestamp: Long,
        modifiedByUserFlag: Boolean = false,
    ): Int {
        for (u in plan.items) {
            repo.upsertExtractedItem(
                languageTag = config.tag,
                itemType = u.itemType,
                expression = u.expression,
                kanji = u.kanji,
                kana = u.kana,
                meaning = u.meaning,
                levelBadge = u.levelBadge,
                corpusRank = u.corpusRank,
                ankiAccountId = u.ankiMatch?.accountId,
                deckId = u.ankiMatch?.deckId,
                deckName = u.ankiMatch?.deckName,
                ankiNoteId = u.ankiMatch?.noteId,
                isHidden = false,
            )
        }
        for (l in plan.links) {
            repo.updateExtractedItemAnkiMatch(config.tag, l.itemType, l.expression, l.match)
        }
        for (o in plan.occurrences) {
            repo.insertOccurrence(
                languageTag = config.tag,
                itemType = o.itemType,
                expression = o.expression,
                photoId = photoId,
                sentence = o.sentence,
                createdAt = timestamp,
                modifiedByUserFlag = modifiedByUserFlag,
                mediaId = mediaId,
            )
        }
        return plan.occurrences.size
    }

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
