package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.derricklee.ankidict.databinding.ActivitySentenceDetailBinding
import com.derricklee.ankidict.databinding.ItemSentenceChipBinding
import com.google.android.material.chip.Chip
import java.util.concurrent.Executors

/**
 * The "single sentence view": sentence-centered screen where the photo is an
 * optional field of the sentence (not the owner). Shows the sentence's parsed
 * word/grammar candidates as de-emphasized outline chips ABOVE the sentence
 * text; each chip opens that word's single word view; the photo (when present)
 * opens the photo detail screen.
 */
class SentenceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySentenceDetailBinding
    private lateinit var miningRepository: MiningRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySentenceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miningRepository = MiningRepository(this)

        val photoId = intent.getLongExtra(EXTRA_PHOTO_ID, -1L)
        val sentence = intent.getStringExtra(EXTRA_SENTENCE).orEmpty()
        val languageTag = runCatching {
            LanguageTag.valueOf(intent.getStringExtra(EXTRA_LANGUAGE_TAG) ?: LanguageTag.JAPANESE.name)
        }.getOrDefault(LanguageTag.JAPANESE)

        binding.backButton.setOnClickListener { finish() }
        binding.sentenceText.text = sentence
        TextLocales.apply(binding.sentenceText, languageTag)

        loadParsedWords(photoId, sentence, languageTag)
        loadPhoto(photoId)
    }

    private fun loadParsedWords(photoId: Long, sentence: String, languageTag: LanguageTag) {
        if (photoId < 0L) return // no photo anchor -> no occurrence rows to derive chips from
        ioExecutor.execute {
            val candidates = miningRepository.getSentenceCandidates(photoId, sentence)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
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
            }
        }
    }

    private fun loadPhoto(photoId: Long) {
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

    override fun onDestroy() {
        ioExecutor.shutdownNow()
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
