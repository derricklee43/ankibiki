package com.derricklee.ankidict

/**
 * The canonical shape for a Chinese Word note (see docs/chinese-word-hanzi-sync-plan.md).
 * Deliberately separate from [Card] -- a word doesn't have a "mnemonic" the way a hanzi
 * character does, and its breakdown/translation/pinyin are partly sync-owned rather than
 * purely freeform like a Card's fields.
 */
data class Word(
    val word: String,
    val translation: String,
    val pinyin: String,
    val hanziBreakdown: String,
)

fun NoteResult.toWord(): Word? {
    if (modelId != CHINESE_WORD_MODEL_ID) return null
    return Word(
        word = fields.getOrNull(WORD_FIELD_INDEX).orEmpty(),
        translation = fields.getOrNull(TRANSLATION_FIELD_INDEX).orEmpty(),
        pinyin = fields.getOrNull(WORD_PINYIN_FIELD_INDEX).orEmpty(),
        hanziBreakdown = fields.getOrNull(HANZI_BREAKDOWN_FIELD_INDEX).orEmpty(),
    )
}
