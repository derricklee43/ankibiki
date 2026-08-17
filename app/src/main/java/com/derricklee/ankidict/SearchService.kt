package com.derricklee.ankidict

/**
 * Combines your Anki decks with the bundled offline dictionary (KANJIDIC2/JMdict/RADKFILE +
 * CC-CEDICT) for a single query, restricted to whichever [SearchSource]s are enabled. Per query
 * (or per character, for a multi-character CJK query), results are ordered: your exact/
 * character-field Anki matches, then dictionary exact matches, then everything else from your
 * decks (meaning-field matches, then general substring matches -- deck-interleaved as before so
 * one deck's long tail can't bury another's).
 */
class SearchService(
    private val ankiRepository: AnkiRepository,
    private val dictionaryRepository: DictionaryRepository,
) {

    fun search(query: String, enabledSources: Set<SearchSource>): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val enabledModelIds = enabledSources.mapNotNull { SEARCH_SOURCE_MODEL_ID[it] }

        val trimmed = query.trim()
        val queries = if (trimmed.length > 1 && trimmed.all(::isCjkCharacter)) {
            trimmed.map { it.toString() }
        } else {
            listOf(trimmed)
        }

        val perQuery = queries.map { q ->
            val tiered = ankiRepository.searchTiered(q, enabledModelIds)
            val dictHits = dictionaryRepository.lookupExact(q, enabledSources)
            Triple(tiered.exact, dictHits, tiered.rest)
        }

        val priority = perQuery.flatMap { (exact, dictHits, _) ->
            exact.map { SearchResult.AnkiCard(it) } + dictHits.map { SearchResult.DictionaryHit(it) }
        }
        val rest = interleave(perQuery.map { it.third }, chunkSize = enabledModelIds.size)
            .map { SearchResult.AnkiCard(it) }

        return priority + rest
    }
}

// Also used by HanziWordSyncService to split a word into its component hanzi.
fun isCjkCharacter(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
}
