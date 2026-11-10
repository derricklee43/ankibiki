package com.derricklee.ankidict

/** One toggleable source of search results, shown in the settings menu. */
enum class SearchSource(val label: String) {
    ANKI_CHINESE("Anki: 中文"),
    ANKI_JAPANESE("Anki: 日本語"),
    DICT_KANJI("Dictionary: Kanji (KANJIDIC2)"),
    DICT_RADICALS("Dictionary: Radicals (Heisig)"),
    DICT_JAPANESE_WORDS("Dictionary: Japanese words (JMdict)"),
    DICT_CHINESE_WORDS("Dictionary: Chinese words (CC-CEDICT)"),
}
