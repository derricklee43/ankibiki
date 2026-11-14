package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.derricklee.ankidict.databinding.ActivitySentenceDetailBinding
import com.derricklee.ankidict.databinding.ItemSentenceChipBinding

/**
 * The "single sentence view": sentence-centered screen where the photo is an
 * optional field of the sentence (not the owner). Shows the sentence's parsed
 * word/grammar candidates as de-emphasized outline chips ABOVE the sentence
 * text; each chip opens that word's single word view; the photo (when present)
 * opens the photo detail screen.
 *
 * 編集 (user feature 2026-11-13) swaps the chips for an occurrence-table editor:
 * each row is one word occurrence *of this sentence* — rename re-keys it (review
 * state rides along), 削除 soft-deletes it (re-adding restores), free-form rows
 * add new words with canonical definitions. Definitions and other sentences are
 * never touched; manual rows are protected from later reprocessing.
 */
class SentenceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySentenceDetailBinding
    private lateinit var miningRepository: MiningRepository
    private val ioExecutor = AppIo.io

    private var photoId = -1L
    private var sentence = ""
    private var languageTag = LanguageTag.JAPANESE
    private var editorBusy = false
    private val editorRows = mutableListOf<EditorRow>()

    /** One table row; original null = user-added. [input] holds the live expression text. */
    private class EditorRow(
        val originalType: ItemType?,
        val originalExpression: String?,
        val input: EditText,
        val view: View,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySentenceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miningRepository = MiningRepository(this)

        photoId = intent.getLongExtra(EXTRA_PHOTO_ID, -1L)
        sentence = intent.getStringExtra(EXTRA_SENTENCE).orEmpty()
        languageTag = runCatching {
            LanguageTag.valueOf(intent.getStringExtra(EXTRA_LANGUAGE_TAG) ?: LanguageTag.JAPANESE.name)
        }.getOrDefault(LanguageTag.JAPANESE)

        binding.backButton.setOnClickListener { finish() }
        binding.sentenceText.text = sentence
        TextLocales.apply(binding.sentenceText, languageTag)

        if (photoId >= 0L) {
            binding.amendSentenceButton.visibility = View.VISIBLE
            binding.amendSentenceButton.setOnClickListener {
                textEditorLauncher.launch(
                    TextEditorActivity.newSentenceIntent(
                        context = this,
                        sentence = sentence,
                        confirmTitle = getString(R.string.amend_sentence_dialog_title),
                    ),
                )
            }
            binding.wordEditorAddButton.setOnClickListener { addEditorRow(null, null, "") }
            binding.wordEditorCancelButton.setOnClickListener { closeEditor() }
            binding.wordEditorSaveButton.setOnClickListener { saveEditor() }
        }

        loadParsedWords()
        loadPhoto()
    }

    /** Amend result (port from the word view pencil, user UI tweak 2026-11-13). */
    private val textEditorLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val newSentence = result.data?.getStringExtra(TextEditorActivity.RESULT_TEXT)
            ?: return@registerForActivityResult
        val reprocessWords = result.data
            ?.getBooleanExtra(TextEditorActivity.RESULT_REPROCESS_WORDS, true) ?: true
        amendSentence(newSentence, reprocessWords)
    }

    private fun amendSentence(newSentence: String, reprocessWords: Boolean) {
        val oldSentence = sentence
        val trimmed = newSentence.trim()
        if (trimmed.isEmpty() || photoId < 0L) return
        ioExecutor.execute {
            val summary = pipelineRunner().reprocessAmendedSentence(
                photoId = photoId,
                oldSentence = oldSentence,
                newSentence = trimmed,
                reprocessWords = reprocessWords,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sentence = trimmed
                binding.sentenceText.text = trimmed
                val message = if (summary.wordsReprocessed) {
                    getString(R.string.reprocess_sentence_toast,
                        summary.keptCandidates, summary.addedCandidates, summary.removedCandidates) +
                        if (summary.mergedDuplicates > 0)
                            getString(R.string.reprocess_sentence_merged_note, summary.mergedDuplicates)
                        else ""
                } else {
                    getString(R.string.text_saved_toast)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                loadParsedWords()
            }
        }
    }

    private fun loadParsedWords() {
        if (photoId < 0L) return // no photo anchor -> no occurrence rows to derive chips from
        ioExecutor.execute {
            val candidates = miningRepository.getSentenceCandidates(photoId, sentence)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.wordChipGroup.removeAllViews()
                for (candidate in candidates) {
                    val chipBinding = ItemSentenceChipBinding.inflate(
                        layoutInflater, binding.wordChipGroup, false,
                    )
                    val chip: Chip = chipBinding.root
                    chip.text = candidate.expression
                    chip.setOnClickListener {
                        startActivity(
                            WordGrammarDetailActivity.newIntent(
                                context = this,
                                occurrenceId = candidate.occurrenceId,
                                languageTag = languageTag,
                                itemType = candidate.itemType,
                                expression = candidate.expression,
                            ),
                        )
                    }
                    binding.wordChipGroup.addView(chip)
                }
                // 編集: word-list editor entry, chip-styled like the words but in the
                // primary color (user UI tweak 2026-11-13: "same styling except the text
                // and border color ... pink, the primary color").
                val editorChipBinding = ItemSentenceChipBinding.inflate(
                    layoutInflater, binding.wordChipGroup, false,
                )
                val editorChip: Chip = editorChipBinding.root
                editorChip.text = getString(R.string.word_editor_edit)
                val primary = resolvePrimaryColor()
                editorChip.setTextColor(primary)
                editorChip.chipStrokeColor = android.content.res.ColorStateList.valueOf(primary)
                editorChip.setOnClickListener { if (!editorBusy) openEditor() }
                binding.wordChipGroup.addView(editorChip)
            }
        }
    }

    // ---- word editor ----

    private fun openEditor() {
        if (editorBusy) return
        editorBusy = true
        ioExecutor.execute {
            val candidates = miningRepository.getSentenceCandidates(photoId, sentence)
            runOnUiThread {
                editorBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.wordEditorRows.removeAllViews()
                editorRows.clear()
                for (candidate in candidates) {
                    addEditorRow(candidate.itemType, candidate.expression, candidate.expression)
                }
                binding.wordChipGroup.visibility = View.GONE
                binding.wordEditorContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun addEditorRow(type: ItemType?, originalExpression: String?, initialText: String) {
        val rowInput = EditText(this).apply {
            setText(initialText)
            hint = getString(R.string.word_editor_add_hint)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
        }
        val deleteButton = MaterialButton(
            this, null,
            com.google.android.material.R.style.Widget_Material3_Button_TextButton,
        ).apply {
            text = getString(R.string.word_editor_delete)
            setOnClickListener {
                val row = editorRows.firstOrNull { it.view === rowView } ?: return@setOnClickListener
                editorRows.remove(row)
                binding.wordEditorRows.removeView(rowView)
            }
        }
        rowView.addView(rowInput)
        rowView.addView(deleteButton)
        binding.wordEditorRows.addView(rowView)
        editorRows.add(EditorRow(type, originalExpression, rowInput, rowView))
    }

    private fun closeEditor() {
        binding.wordEditorContainer.visibility = View.GONE
        binding.wordChipGroup.visibility = View.VISIBLE
        binding.wordEditorRows.removeAllViews()
        editorRows.clear()
    }

    private fun saveEditor() {
        if (editorBusy) return
        editorBusy = true
        val rows = editorRows.map {
            MiningPipelineRunner.WordEditorRow(it.originalType, it.originalExpression, it.input.text.toString())
        }
        ioExecutor.execute {
            val result = runCatching { pipelineRunner().saveSentenceWords(photoId, sentence, rows) }
            runOnUiThread {
                editorBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { changes ->
                        closeEditor()
                        Toast.makeText(this, getString(R.string.word_editor_saved, changes), Toast.LENGTH_SHORT).show()
                        loadParsedWords()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            getString(R.string.wizard_create_failed, error.message ?: "?"),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    private var runner: MiningPipelineRunner? = null

    /** Built lazily: only the editor save needs the full analysis/dictionary pipeline. */
    private fun pipelineRunner(): MiningPipelineRunner = runner ?: MiningPipelineRunner.createDefault(
        context = this,
        miningRepository = miningRepository,
        dictionaryRepository = DictionaryRepository(this),
        shinjitaiRepository = ShinjitaiRepository(this),
        hskRepository = HskRepository(this),
    ).also { runner = it }

    private fun loadPhoto() {
        if (photoId < 0L) return
        ioExecutor.execute {
            val photo = miningRepository.getPhoto(photoId)
            val bitmap = photo?.let {
                runCatching {
                    PhotoThumbnailCache.decodeFullResolutionOriented(this, it.contentUri)
                }.getOrNull()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (photo == null) return@runOnUiThread
                if (bitmap != null) {
                    binding.photoImage.setImageBitmap(bitmap)
                    binding.photoImage.visibility = View.VISIBLE
                    binding.photoImage.setOnClickListener {
                        startActivity(
                            PhotoDetailActivity.newIntent(
                                context = this,
                                mediaStoreId = photo.mediaStoreId,
                                contentUri = photo.contentUri,
                                dateAdded = photo.dateAdded,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** colorPrimary from the theme (Material3 dynamic; pink on this device's palette). */
    private fun resolvePrimaryColor(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
    }

    override fun onDestroy() {
        miningRepository.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PHOTO_ID = "extra_photo_id"
        private const val EXTRA_SENTENCE = "extra_sentence"
        private const val EXTRA_LANGUAGE_TAG = "extra_language_tag"

        fun newIntent(
            context: Context,
            photoId: Long,
            sentence: String,
            languageTag: LanguageTag,
        ): Intent = Intent(context, SentenceDetailActivity::class.java).apply {
            putExtra(EXTRA_PHOTO_ID, photoId)
            putExtra(EXTRA_SENTENCE, sentence)
            putExtra(EXTRA_LANGUAGE_TAG, languageTag.name)
        }
    }
}
