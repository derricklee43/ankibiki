package com.derricklee.ankidict

import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ItemDictionaryEntryBinding
import com.derricklee.ankidict.databinding.ItemNoteBinding

/**
 * Field-index layout for one AnkiDroid note type (model). Every deck can lay its fields out
 * differently, so each known model gets its own mapping here. Note types not listed fall back
 * to a raw "Field N: value" dump so new decks are still readable (and inspectable) immediately.
 */
private data class NoteLayout(
    val frequencyField: Int? = null,
    val knownFields: Set<Int> = emptySet(),
    // Starts collapsed to just the character, tap to reveal the rest -- mirrors decks that are
    // actually studied front/back in Anki, as opposed to decks meant to be read all at once.
    val revealable: Boolean = false,
    val deckTagLabel: String? = null,
    val deckTagColorRes: Int? = null,
)

private val NIHONGO_SHARK_FIELD_NAMES = listOf(
    "id", "frameNoV4", "frameNoV6", "keyword", "kanji", "strokeDiagram", "hint", "constituent",
    "strokeCount", "lessonNo", "myStory", "heisigStory", "heisigComment", "koohiiStory1",
    "koohiiStory2", "jouYou", "jlpt", "words", "readingExamples", "onYomi", "kunYomi",
    "kunYomi Count", "onYomi Count",
)

private val NOTE_LAYOUTS: Map<Long, NoteLayout> = mapOf(
    CHINESE_MNEMONICS_MODEL_ID to NoteLayout(
        frequencyField = 1,
        knownFields = (0 until HANZI_FIELD_COUNT).toSet(),
        revealable = true,
        deckTagLabel = "中文",
        deckTagColorRes = R.color.tag_chinese_red,
    ),
    NIHONGO_SHARK_KANJI_MODEL_ID to NoteLayout(
        knownFields = NIHONGO_SHARK_FIELD_NAMES.indices.toSet(),
        revealable = true,
        deckTagLabel = "日本語",
        deckTagColorRes = R.color.tag_japanese_blue,
    ),
)

private const val VIEW_TYPE_ANKI_CARD = 0
private const val VIEW_TYPE_DICTIONARY_ENTRY = 1

class SearchResultAdapter(
    private val audioRepository: AudioRepository,
    private val onCharacterLinkClick: (String) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var results: List<SearchResult> = emptyList()
    private val expandedNoteIds = mutableSetOf<Long>()
    private var mediaPlayer: MediaPlayer? = null
    private var playingNoteId: Long? = null

    fun submitList(newResults: List<SearchResult>) {
        stopAudio()
        results = newResults
        notifyDataSetChanged()
    }

    /** Releases the player -- call when the screen holding this adapter goes away. */
    fun stopAudio() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingNoteId = null
    }

    private fun onPlayClick(note: NoteResult, character: String) {
        if (playingNoteId == note.id) {
            stopAudio()
            notifyDataSetChanged()
            return
        }
        stopAudio()
        val file = audioRepository.audioFileFor(character) ?: return
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                stopAudio()
                notifyDataSetChanged()
            }
            prepare()
            start()
        }
        playingNoteId = note.id
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (results[position]) {
        is SearchResult.AnkiCard -> VIEW_TYPE_ANKI_CARD
        is SearchResult.DictionaryHit -> VIEW_TYPE_DICTIONARY_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_DICTIONARY_ENTRY -> {
                val binding = ItemDictionaryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                DictionaryViewHolder(binding, onCharacterLinkClick)
            }
            else -> {
                val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                AnkiCardViewHolder(binding, expandedNoteIds, audioRepository, { id -> id == playingNoteId }, ::onPlayClick)
            }
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val result = results[position]) {
            is SearchResult.AnkiCard -> (holder as AnkiCardViewHolder).bind(result.note)
            is SearchResult.DictionaryHit -> (holder as DictionaryViewHolder).bind(result.entry)
        }
    }

    override fun getItemCount(): Int = results.size

    class DictionaryViewHolder(
        private val binding: ItemDictionaryEntryBinding,
        private val onCharacterLinkClick: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DictionaryEntry) {
            val tag = if (entry is DictionaryEntry.ChineseWord) LanguageTag.CHINESE else LanguageTag.JAPANESE
            when (entry) {
                is DictionaryEntry.Kanji -> bindKanji(entry)
                is DictionaryEntry.Radical -> bindRadical(entry)
                is DictionaryEntry.JapaneseWord -> bindJapaneseWord(entry)
                is DictionaryEntry.ChineseWord -> bindChineseWord(entry)
            }
            // System Translate chip in the long-press toolbar needs the text's locale declared.
            TextLocales.apply(binding.dictHeadwordText, tag)
            TextLocales.apply(binding.dictReadingText, tag)
        }

        private fun bindKanji(entry: DictionaryEntry.Kanji) {
            binding.dictTagText.setText(R.string.dictionary_tag)
            binding.dictHeadwordText.text = entry.character
            val reading = listOfNotNull(
                entry.onYomi.takeIf { it.isNotEmpty() }?.joinToString("、"),
                entry.kunYomi.takeIf { it.isNotEmpty() }?.joinToString("、"),
            ).joinToString("  ")
            binding.dictReadingText.text = reading
            binding.dictMeaningText.text = entry.meanings
            val detailParts = listOfNotNull(
                entry.strokeCount?.let { "$it strokes" },
                entry.grade?.let { "grade $it" },
                entry.jlpt?.let { "JLPT N$it" },
                entry.radicals.takeIf { it.isNotEmpty() }?.let { "radicals: ${it.joinToString("、")}" },
            )
            binding.dictDetailText.text = detailParts.joinToString("  ·  ")
            bindDecompositionTree(entry.decompositionTree)
        }

        private fun bindRadical(entry: DictionaryEntry.Radical) {
            binding.dictTagText.setText(R.string.radical_dictionary_tag)
            binding.dictHeadwordText.text = entry.character
            binding.dictReadingText.text = "Heisig: ${entry.heisigKeyword}"
            binding.dictMeaningText.text = entry.radicalMeaning
            val detailParts = listOfNotNull(
                entry.strokeCount?.let { "$it strokes" },
                entry.variants.takeIf { it.isNotEmpty() }?.let { "variants: ${it.joinToString("、")}" },
            )
            binding.dictDetailText.text = detailParts.joinToString("  ·  ")
            bindDecompositionTree(entry.decompositionTree)
        }

        private fun bindJapaneseWord(entry: DictionaryEntry.JapaneseWord) {
            binding.dictTagText.setText(R.string.dictionary_tag)
            binding.dictHeadwordText.text = entry.headword
            binding.dictReadingText.text = entry.reading
            binding.dictMeaningText.text = entry.glosses
            binding.dictDetailText.text = if (entry.isCommon) "common word" else ""
            bindDecompositionTree(null)
        }

        private fun bindChineseWord(entry: DictionaryEntry.ChineseWord) {
            binding.dictTagText.setText(R.string.dictionary_tag)
            binding.dictHeadwordText.text = entry.simplified
            binding.dictReadingText.text = entry.pinyin
            binding.dictMeaningText.text = entry.definitions
            binding.dictDetailText.text =
                if (entry.traditional != entry.simplified) "traditional: ${entry.traditional}" else ""
            bindDecompositionTree(null)
        }

        private fun bindDecompositionTree(tree: DecompositionNode?) {
            if (tree != null && tree.children.isNotEmpty()) {
                binding.dictDecompositionTree.visibility = View.VISIBLE
                binding.dictDecompositionTree.renderTrees(listOf(tree), onCharacterLinkClick)
            } else {
                binding.dictDecompositionTree.visibility = View.GONE
            }
        }
    }

    class AnkiCardViewHolder(
        private val binding: ItemNoteBinding,
        private val expandedNoteIds: MutableSet<Long>,
        private val audioRepository: AudioRepository,
        private val isPlaying: (Long) -> Boolean,
        private val onPlayClick: (NoteResult, String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(note: NoteResult) {
            val layout = NOTE_LAYOUTS[note.modelId]
            val card = note.toCard()

            bindDeckTag(layout)
            bindAudio(note, card?.word)
            bindOptional(binding.characterText, card?.word)
            bindOptional(binding.readingText, card?.reading?.let(::formatReading))
            bindOptional(binding.meaningText, card?.meaning)
            bindOptional(binding.mnemonicText, card?.mnemonic)
            bindOptional(binding.vocabText, card?.vocab)

            // Unmapped model: dump every non-empty field raw. Mapped model: dump only the
            // fields we haven't identified the purpose of yet, so nothing silently disappears.
            val known = layout?.knownFields.orEmpty()
            val fieldNames = if (note.modelId == NIHONGO_SHARK_KANJI_MODEL_ID) NIHONGO_SHARK_FIELD_NAMES else null
            val extras = note.fields
                .mapIndexedNotNull { index, value ->
                    if (index !in known && value.isNotBlank()) {
                        val label = fieldNames?.getOrNull(index) ?: "Field ${index + 1}"
                        "$label: $value"
                    } else {
                        null
                    }
                }
                .joinToString("\n")
            bindOptional(binding.extraText, extras)

            val frequency = layout?.frequencyField?.let { note.fields.getOrNull(it) }
            val metaParts = listOfNotNull(
                "Note #${note.id}",
                "model ${note.modelId}".takeIf { layout == null },
                frequency?.takeIf { it.isNotBlank() }?.let { "rank $it" },
                note.tags.trim().takeIf { it.isNotBlank() }?.let { "tags: $it" },
            )
            binding.metaText.text = metaParts.joinToString("  ·  ")

            applyRevealState(layout, note.id)
        }

        private fun bindAudio(note: NoteResult, character: String?) {
            // audio.db is Chinese-only today. Gate on the deck, not just character presence --
            // Chinese hanzi and Japanese kanji often share a Unicode code point (e.g. 自), and a
            // bare hasAudio(character) check would wrongly offer the Mandarin recording on a
            // Japanese card too.
            val isChineseNote = note.modelId == CHINESE_MNEMONICS_MODEL_ID
            if (!isChineseNote || character.isNullOrBlank() || !audioRepository.hasAudio(character)) {
                binding.playButton.visibility = View.GONE
                binding.playButton.setOnClickListener(null)
                return
            }
            binding.playButton.visibility = View.VISIBLE
            binding.playButton.setImageResource(
                if (isPlaying(note.id)) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )
            binding.playButton.setOnClickListener { onPlayClick(note, character) }
        }

        private fun bindDeckTag(layout: NoteLayout?) {
            if (layout?.deckTagLabel == null || layout.deckTagColorRes == null) {
                binding.deckTagText.visibility = View.GONE
                return
            }
            binding.deckTagText.visibility = View.VISIBLE
            binding.deckTagText.text = layout.deckTagLabel
            binding.deckTagText.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, layout.deckTagColorRes))
        }

        private fun applyRevealState(layout: NoteLayout?, noteId: Long) {
            if (layout?.revealable != true) {
                binding.revealGroup.visibility = View.VISIBLE
                binding.revealHint.visibility = View.GONE
                binding.toggleExpandButton.visibility = View.GONE
                binding.toggleExpandButton.setOnClickListener(null)
                binding.root.setOnClickListener(null)
                return
            }

            binding.toggleExpandButton.visibility = View.VISIBLE

            fun render(expanded: Boolean) {
                binding.revealGroup.visibility = if (expanded) View.VISIBLE else View.GONE
                binding.revealHint.visibility = if (expanded) View.GONE else View.VISIBLE
                binding.toggleExpandButton.setImageResource(
                    if (expanded) R.drawable.ic_minimize else R.drawable.ic_expand,
                )
                binding.toggleExpandButton.contentDescription = binding.root.context.getString(
                    if (expanded) R.string.minimize_card else R.string.expand_card,
                )
                // Only allow tapping the card body to open when collapsed; once expanded,
                // disable root click so long-pressing/tapping text to copy never closes it.
                if (expanded) {
                    binding.root.setOnClickListener(null)
                    binding.root.isClickable = false
                } else {
                    binding.root.setOnClickListener {
                        expandedNoteIds.add(noteId)
                        render(true)
                    }
                }
            }

            render(noteId in expandedNoteIds)
            binding.toggleExpandButton.setOnClickListener {
                val expanded = noteId in expandedNoteIds
                if (expanded) expandedNoteIds.remove(noteId) else expandedNoteIds.add(noteId)
                render(!expanded)
            }
        }

        private fun bindOptional(view: TextView, value: String?) {
            if (value.isNullOrBlank()) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = value
            }
        }
    }
}

private fun formatReading(reading: Map<String, List<String>>): String =
    reading.entries.joinToString("   ") { (label, values) -> "$label: ${values.joinToString("、")}" }
