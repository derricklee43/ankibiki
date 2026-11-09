package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityWordGrammarDetailBinding
import java.util.concurrent.Executors

class WordGrammarDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWordGrammarDetailBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var ankiCardService: AnkiCardService
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
        ankiCardService = AnkiCardService(AnkiDroidAccountGateway(this))
        photosAdapter = PhotoThumbnailAdapter(horizontalStrip = true) { photo ->
            startActivity(
                PhotoDetailActivity.newIntent(
                    context = this,
                    mediaStoreId = photo.mediaStoreId,
                    contentUri = photo.contentUri,
                    dateAdded = photo.dateAdded,
                ),
            )
        }

        binding.matchingPhotosStrip.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.matchingPhotosStrip.adapter = photosAdapter

        binding.backButton.setOnClickListener { finish() }
        binding.targetExpressionText.text = expression

        binding.kanjiRevealRow.setOnClickListener {
            isKanjiRevealed = !isKanjiRevealed
            updateRevealTexts()
        }
        binding.kanaRevealRow.setOnClickListener {
            isKanaRevealed = !isKanaRevealed
            updateRevealTexts()
        }
        binding.meaningRevealRow.setOnClickListener {
            isMeaningRevealed = !isMeaningRevealed
            updateRevealTexts()
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
            if (detail != null) {
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
                if (detail == null) {
                    finish()
                    return@runOnUiThread
                }

                photosAdapter.submitList(detail.photos)
                binding.matchingPhotosStrip.visibility =
                    if (detail.photos.isEmpty()) View.GONE else View.VISIBLE

                binding.targetExpressionText.text = detail.expression
                updateRevealTexts()

                binding.corpusFrequencyText.text =
                    getString(R.string.label_corpus_frequency, detail.formatFrequencyLine())

                binding.ankiCardActionButton.text = if (detail.ankiNoteId != null) {
                    getString(R.string.btn_see_card)
                } else {
                    getString(R.string.btn_create_card)
                }

                binding.sentencesContainer.removeAllViews()
                val density = resources.displayMetrics.density
                for (sentence in detail.sentences) {
                    val tv = TextView(this).apply {
                        text = "• $sentence"
                        textSize = 15f
                        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                        setTextIsSelectable(true)
                    }
                    binding.sentencesContainer.addView(tv)
                }
            }
        }
    }

    private fun updateRevealTexts() {
        val detail = currentDetail
        val tapToReveal = getString(R.string.tap_to_reveal_jp)
        binding.kanjiValueText.text =
            if (isKanjiRevealed) detail?.kanji?.ifBlank { "—" } ?: "—" else tapToReveal
        binding.kanaValueText.text =
            if (isKanaRevealed) detail?.kana?.ifBlank { "—" } ?: "—" else tapToReveal
        binding.meaningValueText.text =
            if (isMeaningRevealed) detail?.meaning?.ifBlank { "—" } ?: "—" else tapToReveal
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.markSeenButton.isEnabled = enabled
        binding.hideItemButton.isEnabled = enabled
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
