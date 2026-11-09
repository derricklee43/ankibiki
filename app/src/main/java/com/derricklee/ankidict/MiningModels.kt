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
) {
    fun formatFrequencyColumn(): String = formatFrequencySummary(occurrenceCount, levelBadge, corpusRank)
}

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
    val occurrenceCount: Int,
    val photos: List<ScrapedPhoto>,
    val sentences: List<String>,
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
