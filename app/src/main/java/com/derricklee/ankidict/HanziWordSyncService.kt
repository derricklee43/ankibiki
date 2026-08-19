package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** hanziCreated: new Hanzi notes auto-created for a word's missing components.
 *  wordsUpdated: Word notes whose HanziBreakdown/Translation/Pinyin/Audio changed.
 *  hanziUpdated: existing Hanzi notes whose UsedInWords/Constituents/HSK/JP-tile changed. */
data class SyncResult(val hanziCreated: Int, val wordsUpdated: Int, val hanziUpdated: Int)

/**
 * Keeps the Chinese Word deck and the Hanzi deck cross-linked (see
 * docs/chinese-word-hanzi-sync-plan.md, Section 6): every word's component hanzi get a note
 * (creating any that don't exist yet) and get told this word uses them (word + pinyin +
 * meaning, one per line); every word gets a readable breakdown of its components; every hanzi
 * note also gets its Constituents tree, HSK 3.0 level, and Japanese-kanji reference tile
 * (JP Kanji/JP Kanji ID/JP Story/JP Constituent, cross-referenced from the NihongoShark Kanji
 * deck already in this collection) recomputed on every run, not just at creation, so any of
 * these stay current as the data they're derived from changes.
 *
 * Non-destructive by design: never touches a Hanzi note's Mnemonic, never overwrites a Word's
 * Translation/Pinyin/Audio if you've already filled them in yourself (Audio only ever gets
 * auto-filled when the word is exactly one hanzi, reusing that character's own pronunciation
 * clip -- never synthesized for multi-character words), and only issues a write when the
 * computed value actually differs from what's there -- safe to re-run at any time.
 *
 * `sync()` itself is just an orchestrator -- each concern below (word processing, each per-hanzi
 * backfill, the final flush) is its own function taking whatever data it needs as a parameter.
 */
class HanziWordSyncService(
    private val ankiRepository: AnkiRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val idsRepository: IdsRepository,
    private val hskRepository: HskRepository,
) {
    private class HanziEntry(val id: Long, val fields: MutableList<String>, var dirty: Boolean = false)

    private val hanziCharIndex = CHARACTER_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)
    private val hanziMeaningIndex = MEANING_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)
    private val jpKanjiCharIndex = CHARACTER_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID)
    private val jpKanjiMeaningIndex = MEANING_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID)

    fun sync(): SyncResult {
        val wordNotes = ankiRepository.queryAllNotes(CHINESE_WORD_MODEL_ID)
        val hanziNotes = ankiRepository.queryAllNotes(CHINESE_MNEMONICS_MODEL_ID)
        val jpKanjiNotes = ankiRepository.queryAllNotes(NIHONGO_SHARK_KANJI_MODEL_ID)

        val hanziByChar = buildHanziIndex(hanziNotes)
        val jpKanjiByChar = jpKanjiNotes.associateBy { it.fields.getOrNull(jpKanjiCharIndex).orEmpty() }

        val (hanziCreated, wordsUpdated) = syncWordNotes(wordNotes, hanziByChar)

        backfillConstituents(hanziByChar)
        backfillHskLevel(hanziByChar)
        backfillJpTile(hanziByChar, jpKanjiByChar)

        val hanziUpdated = flushDirtyHanzi(hanziByChar)

        return SyncResult(hanziCreated, wordsUpdated, hanziUpdated)
    }

    private fun buildHanziIndex(hanziNotes: List<NoteResult>): MutableMap<String, HanziEntry> =
        hanziNotes.associateTo(mutableMapOf()) { note ->
            note.fields.getOrNull(hanziCharIndex).orEmpty() to HanziEntry(note.id, note.fields.toMutableList())
        }

    /** Creates any missing component hanzi, keeps each one's UsedInWords current, and updates
     *  the word's own HanziBreakdown/Translation/Pinyin/Audio. Returns (hanziCreated, wordsUpdated). */
    private fun syncWordNotes(wordNotes: List<NoteResult>, hanziByChar: MutableMap<String, HanziEntry>): Pair<Int, Int> {
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

        return hanziCreated to wordsUpdated
    }

    /** Every hanzi note gets its Constituents tree recomputed, not just ones referenced by a
     *  Word note -- covers hanzi that predate the word-sync feature entirely (the original Hanzi
     *  deck) as well as ones a word never happens to reference. Recomputed every run (not just
     *  at creation) so a component that gets its own card later still picks up a meaning
     *  retroactively. */
    private fun backfillConstituents(hanziByChar: Map<String, HanziEntry>) {
        for ((charStr, entry) in hanziByChar) {
            val annotatedTree = annotateWithMeanings(idsRepository.constituentsTreeJson(charStr), hanziByChar)
            setFieldIfChanged(entry, HANZI_CONSTITUENTS_FIELD_INDEX, annotatedTree)
        }
    }

    /** Every hanzi note's HSK 3.0 level, recomputed every run from the bundled hsk.db. */
    private fun backfillHskLevel(hanziByChar: Map<String, HanziEntry>) {
        for ((charStr, entry) in hanziByChar) {
            setFieldIfChanged(entry, HANZI_HSK_FIELD_INDEX, hskRepository.hskLevel(charStr))
        }
    }

    /** Cross-references each hanzi against the NihongoShark Kanji deck already in this
     *  collection and fills in the JP-tile fields the card template renders. Tries an exact
     *  character match first, then -- since Japanese kanji often kept the traditional form a
     *  character Chinese has since simplified (e.g. 绍/紹) -- that character's traditional form
     *  via CC-CEDICT, so those still cross-reference instead of silently missing every time
     *  simplification diverged from Japanese. A character matching neither is left as-is --
     *  never blanks out a value a previous manual pass entered just because this specific deck
     *  doesn't cover that character. */
    private fun backfillJpTile(hanziByChar: Map<String, HanziEntry>, jpKanjiByChar: Map<String, NoteResult>) {
        for ((charStr, entry) in hanziByChar) {
            val jpNote = jpKanjiByChar[charStr] ?: traditionalVariant(charStr)?.let { jpKanjiByChar[it] } ?: continue

            val kanji = jpNote.fields.getOrNull(jpKanjiCharIndex).orEmpty()
            val keyword = jpNote.fields.getOrNull(jpKanjiMeaningIndex).orEmpty()

            setFieldIfChanged(entry, HANZI_JP_KANJI_FIELD_INDEX, "$kanji ($keyword)")
            setFieldIfChanged(entry, HANZI_JP_KANJI_ID_FIELD_INDEX, jpNote.id.toString())
            setFieldIfChanged(entry, HANZI_JP_STORY_FIELD_INDEX, jpNote.fields.getOrNull(NIHONGO_SHARK_STORY_FIELD_INDEX).orEmpty())
            setFieldIfChanged(entry, HANZI_JP_CONSTITUENT_FIELD_INDEX, jpNote.fields.getOrNull(NIHONGO_SHARK_CONSTITUENT_FIELD_INDEX).orEmpty())
        }
    }

    /** [character]'s traditional form per CC-CEDICT, or null if it's already its own traditional
     *  form (nothing to fall back to) or CC-CEDICT has no entry for it at all. */
    private fun traditionalVariant(character: String): String? =
        dictionaryRepository.lookupChineseWords(character).firstOrNull()?.traditional?.takeIf { it != character }

    private fun setFieldIfChanged(entry: HanziEntry, fieldIndex: Int, value: String) {
        if (entry.fields[fieldIndex] != value) {
            entry.fields[fieldIndex] = value
            entry.dirty = true
        }
    }

    private fun flushDirtyHanzi(hanziByChar: Map<String, HanziEntry>): Int {
        var hanziUpdated = 0
        for (entry in hanziByChar.values) {
            if (entry.dirty) {
                ankiRepository.updateNoteFields(entry.id, entry.fields)
                hanziUpdated++
            }
        }
        return hanziUpdated
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
        // Constituents/HSK/JP-tile are left blank here -- the dedicated backfill passes in
        // sync() fill them in (and keep them current on every later run) right after this note
        // exists.
        // Mnemonic and everything else (Number/Audio/Image/Tags) stay blank -- yours to fill in.
        return fields
    }

    /** Walks a raw (unannotated) Constituents tree from [IdsRepository] and adds a "meaning"
     *  string to every node whose character has its own card in this deck -- pulled from that
     *  card's own Meaning field, not a dictionary lookup, since the whole point is showing what
     *  *you already know this piece as*, not a fresh definition. A character with no card of
     *  its own is left without one; the template just shows the bare character for those. */
    private fun annotateWithMeanings(treeJson: String, hanziByChar: Map<String, HanziEntry>): String {
        if (treeJson.isBlank()) return treeJson
        return try {
            val root = JSONObject(treeJson)
            annotateNode(root, hanziByChar)
            root.toString()
        } catch (e: JSONException) {
            treeJson
        }
    }

    private fun annotateNode(node: JSONObject, hanziByChar: Map<String, HanziEntry>) {
        val char = node.optString("char")
        hanziByChar[char]?.fields?.getOrNull(hanziMeaningIndex)?.takeIf { it.isNotBlank() }?.let {
            node.put("meaning", it)
        }
        val children: JSONArray = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            annotateNode(children.getJSONObject(i), hanziByChar)
        }
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
