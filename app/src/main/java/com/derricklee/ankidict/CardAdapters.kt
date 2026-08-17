package com.derricklee.ankidict

/**
 * Adapts one AnkiDroid note type's raw, positional fields into the canonical [Card] shape --
 * the anti-corruption boundary between "however this note type currently stores things" and
 * "what the rest of the app depends on". Add one object + a registry entry per note type.
 */
fun interface CardAdapter {
    fun toCard(note: NoteResult): Card
}

private fun NoteResult.field(index: Int): String = fields.getOrNull(index).orEmpty()

private fun splitReadingList(value: String): List<String> =
    value.split("、").map { it.trim() }.filter { it.isNotEmpty() }

private fun buildReading(vararg entries: Pair<String, List<String>>): Map<String, List<String>> =
    entries.filter { (_, values) -> values.isNotEmpty() }.toMap()

private object ChineseCardAdapter : CardAdapter {
    private const val MNEMONIC_FIELD = 3

    override fun toCard(note: NoteResult): Card {
        val pinyin = note.field(HANZI_PINYIN_FIELD_INDEX)
        return Card(
            word = note.field(CHARACTER_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)),
            meaning = note.field(MEANING_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)),
            reading = buildReading("Pinyin" to listOfNotNull(pinyin.takeIf { it.isNotBlank() })),
            mnemonic = note.field(MNEMONIC_FIELD),
        )
    }
}

private object NihongoSharkCardAdapter : CardAdapter {
    private const val MNEMONIC_FIELD = 10 // myStory -- the field the deck's own template renders
    private const val ON_YOMI_FIELD = 19
    private const val KUN_YOMI_FIELD = 20

    override fun toCard(note: NoteResult): Card = Card(
        word = note.field(CHARACTER_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID)),
        meaning = note.field(MEANING_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID)),
        reading = buildReading(
            "onYomi" to splitReadingList(note.field(ON_YOMI_FIELD)),
            "kunYomi" to splitReadingList(note.field(KUN_YOMI_FIELD)),
        ),
        mnemonic = note.field(MNEMONIC_FIELD),
    )
}

val CARD_ADAPTERS: Map<Long, CardAdapter> = mapOf(
    CHINESE_MNEMONICS_MODEL_ID to ChineseCardAdapter,
    NIHONGO_SHARK_KANJI_MODEL_ID to NihongoSharkCardAdapter,
)

fun NoteResult.toCard(): Card? = CARD_ADAPTERS[modelId]?.toCard(this)
