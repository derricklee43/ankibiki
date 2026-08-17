package com.derricklee.ankidict

// AnkiDroid note type (model) IDs this app knows how to display. Search is restricted to notes
// of these types -- other decks in the collection are left out rather than shown as a raw dump.

// Confirmed against a live sample card (怕 -> dread).
const val CHINESE_MNEMONICS_MODEL_ID = 1691967670208L

// Confirmed against the note type's own field names and card template (01 NihongoShark.com: Kanji).
const val NIHONGO_SHARK_KANJI_MODEL_ID = 1354697467375L

// Which SearchSource (see SearchSource.kt / the settings menu) each Anki note type belongs to.
val SEARCH_SOURCE_MODEL_ID: Map<SearchSource, Long> = mapOf(
    SearchSource.ANKI_CHINESE to CHINESE_MNEMONICS_MODEL_ID,
    SearchSource.ANKI_JAPANESE to NIHONGO_SHARK_KANJI_MODEL_ID,
)

// The field index holding the character/kanji itself (the "question") for each note type, used
// to rank a match there above a match found elsewhere (meaning, mnemonic, vocab, etc.).
val CHARACTER_FIELD_INDEX: Map<Long, Int> = mapOf(
    CHINESE_MNEMONICS_MODEL_ID to 2,
    NIHONGO_SHARK_KANJI_MODEL_ID to 4,
)

// The field index holding the English meaning/keyword, ranked just below a character match.
val MEANING_FIELD_INDEX: Map<Long, Int> = mapOf(
    CHINESE_MNEMONICS_MODEL_ID to 0,
    NIHONGO_SHARK_KANJI_MODEL_ID to 3,
)

// Chinese Word <-> Hanzi sync (see docs/chinese-word-hanzi-sync-plan.md). Created by
// scripts/setup_chinese_word_deck.py against Anki Desktop via AnkiConnect, confirmed on-device
// afterward via AddContentApi (getModelList/getFieldList/getDeckList).
const val CHINESE_WORD_MODEL_ID = 1786984418063L
const val CHINESE_WORD_DECK_ID = 1786984418128L
const val CHINESE_HANZI_DECK_ID = 1786665356369L

// Chinese Word fields, in creation order: [Word, Translation, Pinyin, Audio, HanziBreakdown].
const val WORD_FIELD_INDEX = 0
const val TRANSLATION_FIELD_INDEX = 1
const val WORD_PINYIN_FIELD_INDEX = 2
const val WORD_AUDIO_FIELD_INDEX = 3
const val HANZI_BREAKDOWN_FIELD_INDEX = 4

// Fields appended (at the end -- see the plan doc) to the Hanzi note type for the sync. Both are
// sync-owned: never hand-edit them, never read/write them through CardAdapters.
const val HANZI_CONSTITUENTS_FIELD_INDEX = 12
const val HANZI_USED_IN_WORDS_FIELD_INDEX = 13
