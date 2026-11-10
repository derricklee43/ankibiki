package com.derricklee.ankidict

sealed class DictionaryEntry {
    data class Kanji(
        val character: String,
        val onYomi: List<String>,
        val kunYomi: List<String>,
        val meanings: String,
        val strokeCount: Int?,
        val grade: Int?,
        val jlpt: Int?,
        val radicals: List<String>,
        val decompositionTree: DecompositionNode? = null,
    ) : DictionaryEntry()

    data class Radical(
        val character: String,
        val heisigKeyword: String,
        val radicalMeaning: String,
        val strokeCount: Int?,
        val variants: List<String>,
        val decompositionTree: DecompositionNode? = null,
    ) : DictionaryEntry()

    data class JapaneseWord(
        val headword: String,
        val reading: String,
        val glosses: String,
        val isCommon: Boolean,
    ) : DictionaryEntry()

    data class ChineseWord(
        val traditional: String,
        val simplified: String,
        val pinyin: String,
        val definitions: String,
    ) : DictionaryEntry()
}
