package com.derricklee.ankidict

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ActivityWordGrammarDetailBinding
import com.derricklee.ankidict.databinding.ItemMatchedSentenceBinding
import java.util.Collections

class WordGrammarDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWordGrammarDetailBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var shinjitaiRepository: ShinjitaiRepository
    private lateinit var hskRepository: HskRepository
    private lateinit var idsRepository: IdsRepository
    private lateinit var heisigRadicalRepository: HeisigRadicalRepository
    private lateinit var ankiCardService: AnkiCardService
    private lateinit var miningPipelineRunner: MiningPipelineRunner
    private lateinit var photosAdapter: PhotoThumbnailAdapter
    private lateinit var sentencesAdapter: MatchedSentenceAdapter
    private lateinit var sentenceTouchHelper: ItemTouchHelper
    private val ioExecutor = AppIo.io

    private var occurrenceId: Long = -1L
    private var languageTag: LanguageTag = LanguageTag.JAPANESE
    private var itemType: ItemType = ItemType.WORD
    private var expression: String = ""

    private var isKanjiRevealed = false
    private var isKanaRevealed = false
    private var isMeaningRevealed = false
    private var currentDetail: ExtractedItemDetail? = null
    private var currentKanjiTrees: List<DecompositionNode> = emptyList()

    // Sentence editing lives in the sentence view now (user UI tweak 2026-11-13).

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordGrammarDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        occurrenceId = intent.getLongExtra(EXTRA_OCCURRENCE_ID, -1L)
        languageTag = intent.getStringExtra(EXTRA_LANGUAGE_TAG)
            ?.let { runCatching { LanguageTag.valueOf(it) }.getOrNull() }
            ?: LanguageTag.JAPANESE
        itemType = intent.getStringExtra(EXTRA_ITEM_TYPE)
            ?.let { runCatching { ItemType.valueOf(it) }.getOrNull() }
            ?: ItemType.WORD
        expression = intent.getStringExtra(EXTRA_EXPRESSION).orEmpty()

        miningRepository = MiningRepository(this)
        dictionaryRepository = DictionaryRepository(this)
        shinjitaiRepository = ShinjitaiRepository(this)
        hskRepository = HskRepository(this)
        idsRepository = IdsRepository(this)
        heisigRadicalRepository = HeisigRadicalRepository(
            idsRepository = idsRepository,
            dictionaryRepository = dictionaryRepository,
            ankiRepository = AnkiRepository(this),
        )
        ankiCardService = AnkiCardService(AnkiDroidAccountGateway(this))
        miningPipelineRunner = MiningPipelineRunner.createDefault(
            context = this,
            miningRepository = miningRepository,
            dictionaryRepository = dictionaryRepository,
            shinjitaiRepository = shinjitaiRepository,
            hskRepository = hskRepository,
            ankiMatchLookup = ankiCardService,
        )

        photosAdapter = PhotoThumbnailAdapter(horizontalStrip = true) { photo ->
            val allPhotoIds = currentDetail?.photos?.map { it.mediaStoreId }?.toLongArray()
            startActivity(
                PhotoDetailActivity.newIntent(
                    context = this,
                    mediaStoreId = photo.mediaStoreId,
                    contentUri = photo.contentUri,
                    dateAdded = photo.dateAdded,
                    photoIds = allPhotoIds,
                ),
            )
        }

        binding.matchingPhotosStrip.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.matchingPhotosStrip.adapter = photosAdapter

        sentencesAdapter = MatchedSentenceAdapter(
            languageTag = languageTag,
            onStartDrag = { holder -> sentenceTouchHelper.startDrag(holder) },
            onOpenSentence = { matched ->
                val detail = currentDetail ?: return@MatchedSentenceAdapter
                val anchorPhotoId = if (matched.photoId >= 0L) matched.photoId
                else detail.photos.firstOrNull()?.mediaStoreId ?: -1L
                startActivity(
                    SentenceDetailActivity.newIntent(
                        context = this,
                        photoId = anchorPhotoId,
                        sentence = matched.sentence,
                        languageTag = detail.languageTag,
                    ),
                )
            },
        )
        sentenceTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                private var moved = false
                override fun isLongPressDragEnabled(): Boolean = false
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                    sentencesAdapter.moveItem(from, to)
                    moved = true
                    return true
                }
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    if (moved) {
                        moved = false
                        persistSentenceOrder(sentencesAdapter.currentSentences())
                    }
                }
            },
        )
        binding.sentencesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sentencesRecyclerView.adapter = sentencesAdapter
        sentenceTouchHelper.attachToRecyclerView(binding.sentencesRecyclerView)

        binding.backButton.setOnClickListener { finish() }
        binding.targetExpressionText.text = expression
        TextLocales.apply(binding.targetExpressionText, languageTag)

        binding.kanjiRevealButton.setOnClickListener {
            isKanjiRevealed = true
            updateRevealViews()
        }
        binding.kanaRevealButton.setOnClickListener {
            isKanaRevealed = true
            updateRevealViews()
        }
        binding.meaningRevealButton.setOnClickListener {
            isMeaningRevealed = true
            updateRevealViews()
        }

        // v11 user definition / notes / folder (user answers 2026-11-15). Saves are targeted
        // UPDATEs and never touch the text-provenance flag: definition != authorship of the
        // sentence text (is_manual/modified_by_user_flag semantics stay pinned).
        binding.userDefinitionEditButton.setOnClickListener {
            showUserTextEditor(
                getString(R.string.user_definition_dialog_title),
                currentDetail?.userDefinition,
            ) { text ->
                ioExecutor.execute {
                    miningRepository.setUserDefinition(languageTag, itemType, expression, text)
                    runOnUiThread { loadDetail() }
                }
            }
        }
        binding.userNotesEditButton.setOnClickListener {
            showUserTextEditor(
                getString(R.string.user_notes_dialog_title),
                currentDetail?.userNotes,
            ) { text ->
                ioExecutor.execute {
                    miningRepository.setUserNotes(languageTag, itemType, expression, text)
                    runOnUiThread { loadDetail() }
                }
            }
        }
        binding.folderEditButton.setOnClickListener { showFolderPicker() }

        binding.ankiCardActionButton.setOnClickListener {
            val detail = currentDetail
            if (detail?.ankiNoteId != null) {
                ankiCardService.openExistingCard(this, detail)
            } else if (detail != null) {
                startActivity(
                    Intent(this, DeckPickerActivity::class.java)
                        .putExtra(WizardKeys.EXTRA_LANGUAGE_TAG, detail.languageTag.name)
                        .putExtra(WizardKeys.EXTRA_ITEM_TYPE, detail.itemType.name)
                        .putExtra(WizardKeys.EXTRA_EXPRESSION, detail.expression),
                )
            }
        }

        binding.markSeenButton.setOnClickListener {
            setActionButtonsEnabled(false)
            ioExecutor.execute {
                if (occurrenceId >= 0L) {
                    miningRepository.markOccurrenceSeen(occurrenceId)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.occurrence_marked_seen_toast, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        binding.hideItemButton.setOnClickListener {
            setActionButtonsEnabled(false)
            ioExecutor.execute {
                miningRepository.hideItem(languageTag, itemType, expression)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.item_hidden_toast, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        binding.archiveItemButton.setOnClickListener {
            setActionButtonsEnabled(false)
            ioExecutor.execute {
                miningRepository.archiveItem(languageTag, itemType, expression)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.item_archived_toast, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        binding.deleteOccurrenceButton.setOnClickListener {
            setActionButtonsEnabled(false)
            ioExecutor.execute {
                if (occurrenceId >= 0L) {
                    miningRepository.deleteOccurrence(occurrenceId)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.occurrence_deleted_toast, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadDetail()
        // undo delivery: a screen resumed behind this one may hold the newest memento offer.
        UndoUi.maybeShow(binding.root, miningRepository) { loadDetail() }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /** Persists drag-and-drop sentence ordering on the word entity. */
    private fun persistSentenceOrder(orderedSentences: List<String>) {
        val detail = currentDetail ?: return
        ioExecutor.execute {
            runCatching {
                miningRepository.saveSentenceOrder(
                    languageTag = detail.languageTag,
                    itemType = detail.itemType,
                    expression = detail.expression,
                    orderedSentences = orderedSentences,
                )
            }
            // undo delivery: a drag persisted a REORDER_SENTENCES memento; offer it here.
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                UndoUi.maybeShow(binding.root, miningRepository) { loadDetail() }
            }
        }
    }

    private fun loadDetail() {
        ioExecutor.execute {
            var detail = miningRepository.getItemDetail(languageTag, itemType, expression)
            var kanjiTrees: List<DecompositionNode> = emptyList()
            if (detail != null) {
                val kanjiSource = detail.kanji
                if (kanjiSource.isNotBlank()) {
                    kanjiTrees = heisigRadicalRepository.buildWordDecompositionTrees(kanjiSource)
                }
                if (detail.ankiNoteId == null) {
                    val match = ankiCardService.findMatch(
                        languageTag = detail.languageTag,
                        itemType = detail.itemType,
                        expression = detail.expression,
                        reading = detail.kana,
                    )
                    if (match != null) {
                        miningRepository.updateExtractedItemAnkiMatch(
                            languageTag = detail.languageTag,
                            itemType = detail.itemType,
                            expression = detail.expression,
                            match = match,
                        )
                        detail = detail.copy(
                            ankiAccountId = match.accountId,
                            deckId = match.deckId,
                            deckName = match.deckName,
                            ankiNoteId = match.noteId,
                        )
                    }
                } else if (detail.deckId != null && detail.deckId > 0L) {
                    val liveDeckName = ankiCardService.resolveDeckDisplayName(
                        accountId = detail.ankiAccountId,
                        deckId = detail.deckId,
                        cachedDeckName = detail.deckName,
                    )
                    if (liveDeckName != null && liveDeckName != detail.deckName) {
                        miningRepository.updateCachedDeckName(
                            accountId = detail.ankiAccountId,
                            deckId = detail.deckId,
                            deckName = liveDeckName,
                        )
                        detail = detail.copy(deckName = liveDeckName)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                currentDetail = detail
                currentKanjiTrees = kanjiTrees
                if (detail == null) {
                    finish()
                    return@runOnUiThread
                }
                renderUserRows()

                photosAdapter.submitList(detail.photos)
                binding.matchingPhotosStrip.visibility =
                    if (detail.photos.isEmpty()) View.GONE else View.VISIBLE

                binding.targetExpressionText.text = detail.expression
                updateRevealViews()

                binding.corpusFrequencyText.text =
                    getString(R.string.label_corpus_frequency, detail.formatFrequencyLine())

                binding.ankiCardActionButton.text = if (detail.ankiNoteId != null) {
                    getString(R.string.btn_see_card)
                } else {
                    getString(R.string.btn_create_card)
                }

                populateMatchedSentences(detail)
            }
        }
    }

    private fun populateMatchedSentences(detail: ExtractedItemDetail) {
        val items: List<MatchedSentence> = if (detail.matchedSentences.isNotEmpty()) {
            detail.matchedSentences
        } else {
            detail.sentences.map { s -> MatchedSentence(occurrenceId = -1L, photoId = -1L, sentence = s) }
        }
        sentencesAdapter.submitList(items)
    }

    private class MatchedSentenceAdapter(
        private val languageTag: LanguageTag,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
        private val onOpenSentence: (MatchedSentence) -> Unit,
    ) : RecyclerView.Adapter<MatchedSentenceAdapter.ViewHolder>() {

        private val items = mutableListOf<MatchedSentence>()

        fun submitList(newItems: List<MatchedSentence>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun moveItem(from: Int, to: Int) {
            if (from !in items.indices || to !in items.indices) return
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
        }

        fun currentSentences(): List<String> = items.map { it.sentence }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemMatchedSentenceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], items.size > 1)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(
            private val binding: ItemMatchedSentenceBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            @SuppressLint("ClickableViewAccessibility")
            fun bind(matched: MatchedSentence, movable: Boolean) {
                binding.sentenceText.text = matched.sentence
                TextLocales.apply(binding.sentenceText, languageTag)
                binding.dragHandle.visibility = if (movable) View.VISIBLE else View.GONE
                binding.dragHandle.setOnTouchListener { _, event ->
                    if (movable && event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag(this)
                    }
                    false
                }
                binding.sentenceViewButton.setOnClickListener { onOpenSentence(matched) }
            }
        }
    }

    private fun updateRevealViews() {
        val detail = currentDetail

        if (isKanjiRevealed) {
            binding.kanjiRevealButton.visibility = View.GONE
            if (currentKanjiTrees.isNotEmpty()) {
                binding.kanjiValueText.visibility = View.GONE
                binding.kanjiDecompositionTree.visibility = View.VISIBLE
                binding.kanjiDecompositionTree.textSelectable = true
                binding.kanjiDecompositionTree.renderTrees(currentKanjiTrees) { clickedChar ->
                    startActivity(MainActivity.newLookupIntent(this, clickedChar))
                }
            } else {
                binding.kanjiDecompositionTree.visibility = View.GONE
                binding.kanjiValueText.visibility = View.VISIBLE
                binding.kanjiValueText.text = detail?.kanji?.ifBlank { "—" } ?: "—"
                TextLocales.apply(binding.kanjiValueText, languageTag)
            }
        } else {
            binding.kanjiRevealButton.visibility = View.VISIBLE
            binding.kanjiValueText.visibility = View.GONE
            binding.kanjiDecompositionTree.visibility = View.GONE
        }

        if (isKanaRevealed) {
            binding.kanaRevealButton.visibility = View.GONE
            binding.kanaValueText.visibility = View.VISIBLE
            binding.kanaValueText.text = detail?.kana?.ifBlank { "—" } ?: "—"
            TextLocales.apply(binding.kanaValueText, languageTag)
        } else {
            binding.kanaRevealButton.visibility = View.VISIBLE
            binding.kanaValueText.visibility = View.GONE
        }

        val meaningText = detail?.meaning?.takeIf { it.isNotBlank() }
        if (detail != null && (meaningText == null || meaningText == "—")) {
            binding.meaningRevealButton.visibility = View.GONE
            binding.meaningValueText.visibility = View.VISIBLE
            binding.meaningValueText.text = "—"
        } else if (isMeaningRevealed) {
            binding.meaningRevealButton.visibility = View.GONE
            binding.meaningValueText.visibility = View.VISIBLE
            binding.meaningValueText.text = meaningText ?: "—"
        } else {
            binding.meaningRevealButton.visibility = View.VISIBLE
            binding.meaningValueText.visibility = View.GONE
        }
    }

    // ---- v11 user rows (definition / notes / folder) ----

    private fun renderUserRows() {
        val d = currentDetail
        binding.userDefinitionValueText.text = d?.userDefinition?.takeIf { it.isNotBlank() } ?: "—"
        binding.userNotesValueText.text = d?.userNotes?.takeIf { it.isNotBlank() } ?: "—"
        binding.folderValueText.text = d?.folderName?.takeIf { it.isNotBlank() } ?: "—"
    }

    /** Multiline text dialog for user_definition/user_notes; blank save clears the value. */
    private fun showUserTextEditor(title: String, initial: String?, onSave: (String?) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(initial.orEmpty())
            setSelection(initial?.length ?: 0)
            minLines = 3
            maxLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.btn_save_text) { _, _ ->
                onSave(input.text.toString().trim().ifEmpty { null })
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 1:1 folder assignment (user answer 2026-11-15). Row 0 clears the assignment. */
    private fun showFolderPicker() {
        ioExecutor.execute {
            val folders = miningRepository.listWordFolders()
            val labels = (listOf(getString(R.string.folder_none)) + folders.map { it.name }).toTypedArray()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.folder_pick_title)
                    .setItems(labels) { _, which ->
                        val target: Long? = if (which == 0) null else folders[which - 1].id
                        ioExecutor.execute {
                            miningRepository.moveItemToFolder(languageTag, itemType, expression, target)
                            runOnUiThread { loadDetail() }
                        }
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.markSeenButton.isEnabled = enabled
        binding.hideItemButton.isEnabled = enabled
        binding.archiveItemButton.isEnabled = enabled
        binding.deleteOccurrenceButton.isEnabled = enabled
    }

    companion object {
        private const val EXTRA_OCCURRENCE_ID = "extra_occurrence_id"
        private const val EXTRA_LANGUAGE_TAG = "extra_language_tag"
        private const val EXTRA_ITEM_TYPE = "extra_item_type"
        private const val EXTRA_EXPRESSION = "extra_expression"

        fun newIntent(
            context: Context,
            occurrenceId: Long,
            languageTag: LanguageTag,
            itemType: ItemType,
            expression: String,
        ): Intent = Intent(context, WordGrammarDetailActivity::class.java).apply {
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(EXTRA_LANGUAGE_TAG, languageTag.name)
            putExtra(EXTRA_ITEM_TYPE, itemType.name)
            putExtra(EXTRA_EXPRESSION, expression)
        }
    }
}
