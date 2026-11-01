package com.derricklee.ankidict

/**
 * The canonical shape the app depends on for an Anki card's content, independent of how any
 * particular note type currently lays out its fields. If a note type's fields get restructured
 * in AnkiDroid, only that note type's CardAdapter needs to change -- nothing that reads a Card
 * does. See CardAdapter.kt.
 */
data class Card(
    val word: String,
    val meaning: String,
    // Keys are free-form (e.g. "Pinyin", "onYomi", "kunYomi") since different languages carry
    // different kinds of readings. Absent/empty readings are omitted, not present-with-blank.
    val reading: Map<String, List<String>>,
    val mnemonic: String,
    val vocab: String = "",
)
