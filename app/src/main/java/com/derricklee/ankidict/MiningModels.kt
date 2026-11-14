package com.derricklee.ankidict

enum class LanguageTag {
    JAPANESE,
    CHINESE,
    NA,
}

enum class PhotoStatus {
    KEEP,
    IGNORED,
    DELETED,
    DUPLICATE,
}

enum class ItemType {
    WORD,
    GRAMMAR,
}

enum class OccurrenceState {
    UNSEEN,
    SEEN,
    DELETED,
}

enum class ItemVisibility {
    ACTIVE,
    HIDDEN,
    ARCHIVED,
}

enum class SortField {
    OCCURRENCE_COUNT,
    DATE_ADDED,
    CORPUS_RANK,
}

enum class SortDirection {
    OFF,
    DESC,
    ASC,
}

enum class OccurrenceSortOrder {
    NEWEST_FIRST,
    OCCURRENCE_COUNT_DESC,
    CORPUS_RANK_ASC,
    LEVEL_ASC,
}

enum class OccurrenceFilter {
    HAS_ANKI_CARD,
    NO_ANKI_CARD,
}

data class OccurrenceQuery(
    val languageTag: LanguageTag = LanguageTag.JAPANESE,
    val itemType: ItemType,
    val sortOrder: OccurrenceSortOrder = OccurrenceSortOrder.NEWEST_FIRST,
    val sortCriteria: List<Pair<SortField, SortDirection>> = emptyList(),
    val visibilities: Set<ItemVisibility> = setOf(ItemVisibility.ACTIVE),
    val filters: Set<OccurrenceFilter> = emptySet(),
    val limit: Int? = null,
)

data class ScrapedPhoto(
    val mediaStoreId: Long,
    val contentUri: String,
    val dateAdded: Long,
    val languageTag: LanguageTag,
    val extractedText: String,
    val ocrBlocksJson: String? = null,
    val status: PhotoStatus,
    val duplicateOfPhotoId: Long? = null,
    /** Set by a confirmed user text change; only an accepted image rescan clears photo protection. */
    val modifiedByUserFlag: Boolean = false,
    /**
     * Explicitly marked GOLD in the photo view (user decision 2026-11-13): gold membership is
     * the mark itself, NOT the touched/edit flag — partial edits across multiple passes no longer
     * imply verification, and never-edited perfect photos can join the gold set. A full image
     * rescan replaces the text, so it clears the mark; user text edits keep it.
     */
    val isGold: Boolean = false,
)

data class ExtractedOccurrenceRow(
    val occurrenceId: Long,
    val languageTag: LanguageTag,
    val itemType: ItemType,
    val expression: String,
    val deckName: String?,
    val occurrenceCount: Int,
    val levelBadge: String?,
    val corpusRank: Int?,
    val createdAt: Long,
    /** Stored reading (kana for JP, pinyin for ZH) — feeds the romaji search matcher. */
    val kana: String? = null,
) {
    fun formatFrequencyColumn(): String = formatFrequencySummary(occurrenceCount, levelBadge, corpusRank)
}

/**
 * One canonical word/grammar for the Words (definition) tab: unique per
 * (languageTag, itemType, expression) — the `extracted_items` primary key.
 * [occurrenceId] is the most recent live occurrence (tap target for the detail view),
 * or -1 when the item has none on a kept photo.
 */
data class WordDefinitionRow(
    val occurrenceId: Long,
    val languageTag: LanguageTag,
    val itemType: ItemType,
    val expression: String,
    val kana: String?,
    val meaning: String,
    val occurrenceCount: Int,
    val levelBadge: String?,
    val corpusRank: Int?,
) {
    fun formatFrequencyColumn(): String = formatFrequencySummary(occurrenceCount, levelBadge, corpusRank)
}

data class MatchedSentence(
    val occurrenceId: Long,
    val photoId: Long,
    val sentence: String,
    /** Persists through text reprocessing; fresh image rescans generate new, unmodified sentences. */
    val modifiedByUserFlag: Boolean = false,
    val createdAt: Long = 0L,
)

data class ExtractedItemDetail(
    val languageTag: LanguageTag,
    val itemType: ItemType,
    val expression: String,
    val kanji: String,
    val kana: String,
    val meaning: String,
    val levelBadge: String?,
    val corpusRank: Int?,
    val ankiAccountId: String?,
    val deckId: Long?,
    val deckName: String?,
    val ankiNoteId: Long?,
    val isHidden: Boolean,
    val visibility: ItemVisibility = if (isHidden) ItemVisibility.HIDDEN else ItemVisibility.ACTIVE,
    val occurrenceCount: Int,
    val photos: List<ScrapedPhoto>,
    val sentences: List<String>,
    val matchedSentences: List<MatchedSentence> = emptyList(),
) {
    fun formatFrequencyLine(): String = formatFrequencySummary(occurrenceCount, levelBadge, corpusRank)
}

fun formatFrequencySummary(
    occurrenceCount: Int,
    levelBadge: String?,
    corpusRank: Int?,
): String {
    val parts = mutableListOf("${occurrenceCount}回")
    val corpusParts = listOfNotNull(
        levelBadge?.takeIf { it.isNotBlank() },
        corpusRank?.let { "#$it" },
    )
    if (corpusParts.isNotEmpty()) {
        parts.add(corpusParts.joinToString(" "))
    }
    return parts.joinToString(" · ")
}
