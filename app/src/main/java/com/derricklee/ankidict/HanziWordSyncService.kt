package com.derricklee.ankidict

/** hanziCreated: new Hanzi notes auto-created for a word's missing components.
 *  wordsUpdated: Word notes whose HanziBreakdown/Translation/Pinyin/Audio changed.
 *  hanziUpdated: existing Hanzi notes whose UsedInWords changed. */
data class SyncResult(val hanziCreated: Int, val wordsUpdated: Int, val hanziUpdated: Int)

/**
 * Keeps the Chinese Word deck and the Hanzi deck cross-linked (see
 * docs/chinese-word-hanzi-sync-plan.md, Section 6): every word's component hanzi get a note
 * (creating any that don't exist yet) and get told this word uses them (word + pinyin +
 * meaning, one per line); every word gets a readable breakdown of its components.
 *
 * Non-destructive by design: never touches a Hanzi note's Mnemonic, never overwrites a Word's
 * Translation/Pinyin/Audio if you've already filled them in yourself (Audio only ever gets
 * auto-filled when the word is exactly one hanzi, reusing that character's own pronunciation
 * clip -- never synthesized for multi-character words), and only issues a write when the
 * computed value actually differs from what's there -- safe to re-run at any time.
 */
class HanziWordSyncService(
    private val ankiRepository: AnkiRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val idsRepository: IdsRepository,
) {
    private class HanziEntry(val id: Long, val fields: MutableList<String>, var dirty: Boolean = false)

    private val hanziCharIndex = CHARACTER_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)
    private val hanziMeaningIndex = MEANING_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)

    fun sync(): SyncResult {
        val wordNotes = ankiRepository.queryAllNotes(CHINESE_WORD_MODEL_ID)
        val hanziNotes = ankiRepository.queryAllNotes(CHINESE_MNEMONICS_MODEL_ID)

        val hanziByChar = hanziNotes.associateTo(mutableMapOf()) { note ->
            note.fields.getOrNull(hanziCharIndex).orEmpty() to HanziEntry(note.id, note.fields.toMutableList())
        }

        var hanziCreated = 0
        var wordsUpdated = 0

        for (wordNote in wordNotes) {
            val wordFields = wordNote.fields.toMutableList()
            val wordText = wordFields.getOrNull(WORD_FIELD_INDEX).orEmpty()
            if (wordText.isBlank()) continue

            // Resolved once, up front: used both for the word's own Translation/Pinyin (if
            // blank) and for the "used in words" line appended to each component hanzi below --
            // that line needs the word's pinyin/meaning even when this word's own fields are
            // already filled in (nothing to resolve there, but still need the value here).
            val dictEntry = dictionaryRepository.lookupChineseWords(wordText).firstOrNull()
            val resolvedTranslation = wordFields.getOrNull(TRANSLATION_FIELD_INDEX)?.takeIf { it.isNotBlank() }
                ?: dictEntry?.definitions.orEmpty()
            val resolvedPinyin = wordFields.getOrNull(WORD_PINYIN_FIELD_INDEX)?.takeIf { it.isNotBlank() }
                ?: dictEntry?.pinyin?.let(::toDiacriticPinyin).orEmpty()

            val breakdownParts = mutableListOf<String>()
            for (ch in wordText) {
                if (!isCjkCharacter(ch)) continue
                val charStr = ch.toString()

                var entry = hanziByChar[charStr]
                if (entry == null) {
                    val newFields = buildNewHanziFields(charStr)
                    val newId = ankiRepository.createNote(CHINESE_MNEMONICS_MODEL_ID, CHINESE_HANZI_DECK_ID, newFields)
                        ?: continue
                    entry = HanziEntry(newId, newFields.toMutableList())
                    hanziByChar[charStr] = entry
                    hanziCreated++
                }

                val usedIn = parseUsedInWords(entry.fields[HANZI_USED_IN_WORDS_FIELD_INDEX])
                val line = formatUsedInWordLine(wordText, resolvedPinyin, resolvedTranslation)
                if (usedIn[wordText] != line) {
                    usedIn[wordText] = line
                    entry.fields[HANZI_USED_IN_WORDS_FIELD_INDEX] = usedIn.values.joinToString("\n")
                    entry.dirty = true
                }

                breakdownParts.add("$charStr (${entry.fields[hanziMeaningIndex]})")
            }

            // A word that's exactly one hanzi can reuse that character's own pronunciation
            // audio directly -- same [sound:...] filename, already in the shared media folder.
            val singleCharAudio = hanziByChar[wordText]
                ?.takeIf { wordText.length == 1 }
                ?.fields?.getOrNull(HANZI_AUDIO_FIELD_INDEX)
                ?.takeIf { it.isNotBlank() }

            if (updateWordFields(wordFields, breakdownParts, resolvedTranslation, resolvedPinyin, singleCharAudio)) {
                ankiRepository.updateNoteFields(wordNote.id, wordFields)
                wordsUpdated++
            }
        }

        var hanziUpdated = 0
        for (entry in hanziByChar.values) {
            if (entry.dirty) {
                ankiRepository.updateNoteFields(entry.id, entry.fields)
                hanziUpdated++
            }
        }

        return SyncResult(hanziCreated, wordsUpdated, hanziUpdated)
    }

    /** Mutates [wordFields] in place; returns whether anything actually changed. */
    private fun updateWordFields(
        wordFields: MutableList<String>,
        breakdownParts: List<String>,
        resolvedTranslation: String,
        resolvedPinyin: String,
        singleCharAudio: String?,
    ): Boolean {
        var changed = false

        val newBreakdown = breakdownParts.joinToString(", ")
        if (wordFields.getOrNull(HANZI_BREAKDOWN_FIELD_INDEX) != newBreakdown) {
            wordFields[HANZI_BREAKDOWN_FIELD_INDEX] = newBreakdown
            changed = true
        }

        if (singleCharAudio != null && wordFields.getOrNull(WORD_AUDIO_FIELD_INDEX).isNullOrBlank()) {
            wordFields[WORD_AUDIO_FIELD_INDEX] = singleCharAudio
            changed = true
        }

        if (wordFields.getOrNull(TRANSLATION_FIELD_INDEX).isNullOrBlank() && resolvedTranslation.isNotBlank()) {
            wordFields[TRANSLATION_FIELD_INDEX] = resolvedTranslation
            changed = true
        }
        if (wordFields.getOrNull(WORD_PINYIN_FIELD_INDEX).isNullOrBlank() && resolvedPinyin.isNotBlank()) {
            wordFields[WORD_PINYIN_FIELD_INDEX] = resolvedPinyin
            changed = true
        }

        return changed
    }

    private fun buildNewHanziFields(character: String): List<String> {
        val dictEntry = dictionaryRepository.lookupChineseWords(character).firstOrNull()
        val fields = MutableList(HANZI_FIELD_COUNT) { "" }
        fields[hanziMeaningIndex] = dictEntry?.definitions.orEmpty()
        fields[hanziCharIndex] = character
        fields[HANZI_PINYIN_FIELD_INDEX] = dictEntry?.pinyin?.let(::toDiacriticPinyin).orEmpty()
        fields[HANZI_CONSTITUENTS_FIELD_INDEX] = idsRepository.constituentsTreeJson(character)
        // Mnemonic and everything else (Number/Audio/Image/Tags/JP*) stay blank -- yours to fill in.
        return fields
    }

    /** word -> its current line, keyed so re-running can update a line in place (e.g. once a
     *  blank Translation gets filled in later) instead of leaving a stale duplicate behind. */
    private fun parseUsedInWords(raw: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            map[line.substringBefore(" (")] = line
        }
        return map
    }

    private fun formatUsedInWordLine(word: String, pinyin: String, meaning: String): String {
        val pinyinPart = if (pinyin.isNotBlank()) " ($pinyin)" else ""
        val meaningPart = if (meaning.isNotBlank()) " — $meaning" else ""
        return "$word$pinyinPart$meaningPart"
    }
}
