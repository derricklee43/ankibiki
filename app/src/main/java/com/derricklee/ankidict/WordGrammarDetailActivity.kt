package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityWordGrammarDetailBinding
import com.derricklee.ankidict.databinding.ItemMatchedSentenceBinding
import java.util.concurrent.Executors

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
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var occurrenceId: Long = -1L
    private var languageTag: LanguageTag = LanguageTag.JAPANESE
    private var itemType: ItemType = ItemType.WORD
    private var expression: String = ""

    private var isKanjiRevealed = false
    private var isKanaRevealed = false
    private var isMeaningRevealed = false
    private var currentDetail: ExtractedItemDetail? = null
    private var currentKanjiTrees: List<DecompositionNode> = emptyList()

    // The sentence whose pencil button opened the editor (editor result dispatch).
    private var pendingAmend: MatchedSentence? = null

    private val textEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matched = pendingAmend
            pendingAmend = null
            val newSentence = result.data?.getStringExtra(TextEditorActivity.RESULT_TEXT)
                ?: return@registerForActivityResult
            val reprocessWords = result.data
                ?.getBooleanExtra(TextEditorActivity.RESULT_REPROCESS_WORDS, true) ?: true
            if (matched != null) {
                applyAmendedSentence(matched, newSentence, reprocessWords)
            }
        }
    }

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

        binding.backButton.setOnClickListener { finish() }
        binding.targetExpressionText.text = expression

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

        binding.ankiCardActionButton.setOnClickListener {
            val detail = currentDetail
            if (detail?.ankiNoteId != null) {
                ankiCardService.openExistingCard(this, detail)
            } else {
                Toast.makeText(this, R.string.card_creation_pending_toast, Toast.LENGTH_SHORT).show()
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
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
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
        binding.sentencesContainer.removeAllViews()
        val items: List<MatchedSentence> = if (detail.matchedSentences.isNotEmpty()) {
            detail.matchedSentences
        } else {
            detail.sentences.map { s -> MatchedSentence(occurrenceId = -1L, photoId = -1L, sentence = s) }
        }

        for (matched in items) {
            val row = ItemMatchedSentenceBinding.inflate(layoutInflater, binding.sentencesContainer, false)
            row.sentenceText.text = "• ${matched.sentence}"
            // Sentences are read-only here; the pencil opens the text editor view.
            row.editSentenceButton.setOnClickListener {
                pendingAmend = matched
                textEditorLauncher.launch(
                    TextEditorActivity.newSentenceIntent(
                        context = this,
                        sentence = matched.sentence,
                        confirmTitle = getString(R.string.amend_sentence_dialog_title),
                    ),
                )
            }
            binding.sentencesContainer.addView(row.root)
        }
    }

    private fun applyAmendedSentence(
        matched: MatchedSentence,
        newSentence: String,
        reprocessWords: Boolean,
    ) {
        val photoId = if (matched.photoId >= 0L) {
            matched.photoId
        } else {
            currentDetail?.photos?.firstOrNull()?.mediaStoreId ?: return
        }
        ioExecutor.execute {
            miningPipelineRunner.reprocessAmendedSentence(
                photoId = photoId,
                oldSentence = matched.sentence,
                newSentence = newSentence,
                reprocessWords = reprocessWords,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val msgRes = if (reprocessWords) {
                    R.string.text_saved_and_reprocessed_toast
                } else {
                    R.string.text_saved_toast
                }
                Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
                loadDetail()
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
        } else {
            binding.kanaRevealButton.visibility = View.VISIBLE
            binding.kanaValueText.visibility = View.GONE
        }

        if (isMeaningRevealed) {
            binding.meaningRevealButton.visibility = View.GONE
            binding.meaningValueText.visibility = View.VISIBLE
            binding.meaningValueText.text = detail?.meaning?.ifBlank { "—" } ?: "—"
        } else {
            binding.meaningRevealButton.visibility = View.VISIBLE
            binding.meaningValueText.visibility = View.GONE
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
