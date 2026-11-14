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
    private val radicalRepository: HeisigRadicalRepository? = null,
) {

    fun search(query: String, enabledSources: Set<SearchSource>): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val enabledModelIds = enabledSources.mapNotNull { SEARCH_SOURCE_MODEL_ID[it] }

        val trimmed = query.trim()
        val queries = searchQueries(trimmed)

        val perQuery = queries.map { q ->
            val tiered = ankiRepository.searchTiered(q, enabledModelIds)
            val rawDictHits = dictionaryRepository.lookupExact(q, enabledSources).map { entry ->
                if (entry is DictionaryEntry.Kanji && radicalRepository != null) {
                    entry.copy(decompositionTree = radicalRepository.buildDecompositionTree(entry.character))
                } else {
                    entry
                }
            }
            val radicalHits = if (q.codePointCount(0, q.length) == 1 && SearchSource.DICT_RADICALS in enabledSources && radicalRepository != null) {
                val rad = radicalRepository.lookupRadical(q)
                if (rad != null) {
                    listOf(
                        DictionaryEntry.Radical(
                            character = rad.character,
                            heisigKeyword = rad.heisigKeyword,
                            radicalMeaning = rad.radicalMeaning,
                            strokeCount = rad.strokeCount,
                            variants = rad.variants,
                            decompositionTree = radicalRepository.buildDecompositionTree(rad.character),
                        ),
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
            Triple(tiered.exact, radicalHits + rawDictHits, tiered.rest)
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

/**
 * Track C (user 2026-11-14): a multi-character CJK compound now ALSO looks up the whole string —
 * the old per-character-only split made dictionary words (海賊, 海盗) unreachable from 引き even
 * though the word tables match exact multi-character headwords/ readings. The compound is
 * prepended so it takes the top priority slots, and the per-character exploration path is
 * preserved exactly as before. Queries that already ran whole (single char, kana/mixed text,
 * romaji) are untouched.
 */
internal fun searchQueries(trimmed: String): List<String> =
    if (trimmed.length > 1 && trimmed.all(::isCjkCharacter)) {
        listOf(trimmed) + trimmed.map { it.toString() }
    } else {
        listOf(trimmed)
    }
