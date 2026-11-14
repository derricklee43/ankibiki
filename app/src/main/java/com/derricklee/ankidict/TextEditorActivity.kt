package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.derricklee.ankidict.databinding.ActivityTextEditorBinding

/**
 * Full-screen text editor opened from the pencil buttons on the photo-detail and
 * word/grammar-detail screens (text is no longer editable inline there).
 *
 * Layout contract: Save/Cancel sit at the top, ABOVE the optional photo; the
 * associated photo renders below the action bar, and the editable sentence text
 * below the photo. Sentence edits (word view) hide the photo entirely.
 *
 * Save opens the same reprocess-confirmation modal the inline editors used to
 * use; its choice is returned to the caller together with the amended text, so
 * the actual persistence/reprocess logic stays in the originating activity.
 */
class TextEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextEditorBinding
    private val ioExecutor = AppIo.pipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sentenceEditText.setText(intent.getStringExtra(EXTRA_INITIAL_TEXT).orEmpty())
        binding.sentenceEditText.requestFocus()
        binding.sentenceEditText.setSelection(binding.sentenceEditText.text?.length ?: 0)

        val photoUri = intent.getStringExtra(EXTRA_PHOTO_URI).orEmpty()
        if (photoUri.isBlank()) {
            binding.photoImage.visibility = View.GONE
        } else {
            binding.photoImage.visibility = View.VISIBLE
            ioExecutor.execute {
                val bitmap = runCatching {
                    PhotoThumbnailCache.decodeFullResolutionOriented(this, photoUri)
                }.getOrNull()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (bitmap != null) binding.photoImage.setImageBitmap(bitmap)
                }
            }
        }

        binding.cancelButton.setOnClickListener { finish() }

        binding.saveButton.setOnClickListener {
            val amendedText = binding.sentenceEditText.text?.toString().orEmpty().trim()
            AlertDialog.Builder(this)
                .setTitle(
                    intent.getStringExtra(EXTRA_CONFIRM_TITLE)
                        ?: getString(R.string.reprocess_dialog_title),
                )
                .setMessage(R.string.reprocess_dialog_message)
                .setPositiveButton(R.string.btn_reprocess_and_save) { _, _ ->
                    finishWithResult(amendedText, reprocessWords = true)
                }
                .setNeutralButton(R.string.btn_save_text_only) { _, _ ->
                    finishWithResult(amendedText, reprocessWords = false)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun finishWithResult(amendedText: String, reprocessWords: Boolean) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(RESULT_TEXT, amendedText)
                .putExtra(RESULT_REPROCESS_WORDS, reprocessWords),
        )
        finish()
    }

    companion object {
        private const val EXTRA_INITIAL_TEXT = "extra_initial_text"
        private const val EXTRA_PHOTO_URI = "extra_photo_uri"
        private const val EXTRA_CONFIRM_TITLE = "extra_confirm_title"
        const val RESULT_TEXT = "result_text"
        const val RESULT_REPROCESS_WORDS = "result_reprocess_words"

        /** Photo-detail editor: renders the photo above the editable extracted text. */
        fun newPhotoTextIntent(
            context: Context,
            extractedText: String,
            contentUri: String,
            confirmTitle: String,
        ): Intent = Intent(context, TextEditorActivity::class.java).apply {
            putExtra(EXTRA_INITIAL_TEXT, extractedText)
            putExtra(EXTRA_PHOTO_URI, contentUri)
            putExtra(EXTRA_CONFIRM_TITLE, confirmTitle)
        }

        /** Word/grammar-detail editor: sentence text only, no photo. */
        fun newSentenceIntent(
            context: Context,
            sentence: String,
            confirmTitle: String,
        ): Intent = Intent(context, TextEditorActivity::class.java).apply {
            putExtra(EXTRA_INITIAL_TEXT, sentence)
            putExtra(EXTRA_CONFIRM_TITLE, confirmTitle)
        }
    }
}
