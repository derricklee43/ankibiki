package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.derricklee.ankidict.databinding.ActivityPhotoDetailBinding
import java.util.concurrent.Executors

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private lateinit var miningRepository: MiningRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var mediaStoreId: Long = -1L
    private var contentUri: String = ""
    private var dateAdded: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        miningRepository = MiningRepository(this)
        mediaStoreId = intent.getLongExtra(EXTRA_MEDIA_STORE_ID, -1L)
        contentUri = intent.getStringExtra(EXTRA_CONTENT_URI).orEmpty()
        dateAdded = intent.getLongExtra(EXTRA_DATE_ADDED, 0L)

        binding.backButton.setOnClickListener { finish() }

        binding.deletePhotoButton.setOnClickListener {
            binding.deletePhotoButton.isEnabled = false
            ioExecutor.execute {
                miningRepository.markPhotoDeleted(
                    mediaStoreId = mediaStoreId,
                    contentUri = contentUri,
                    dateAdded = dateAdded,
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.photo_excluded_toast, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        loadPhotoAndText()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadPhotoAndText() {
        ioExecutor.execute {
            val photo = miningRepository.getPhoto(mediaStoreId)
            val resolvedUri = photo?.contentUri?.takeIf { it.isNotBlank() } ?: contentUri
            val resolvedDate = photo?.dateAdded ?: dateAdded
            val extractedText = photo?.extractedText.orEmpty()

            val bitmap = runCatching {
                contentResolver.openInputStream(Uri.parse(resolvedUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                contentUri = resolvedUri
                dateAdded = resolvedDate
                if (bitmap != null) {
                    binding.photoView.setImageBitmap(bitmap)
                } else if (resolvedUri.isNotBlank()) {
                    runCatching { binding.photoView.setImageURI(Uri.parse(resolvedUri)) }
                }
                binding.extractedTextView.text = extractedText.ifBlank {
                    getString(R.string.no_extracted_text)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MEDIA_STORE_ID = "extra_media_store_id"
        private const val EXTRA_CONTENT_URI = "extra_content_uri"
        private const val EXTRA_DATE_ADDED = "extra_date_added"

        fun newIntent(
            context: Context,
            mediaStoreId: Long,
            contentUri: String,
            dateAdded: Long,
        ): Intent = Intent(context, PhotoDetailActivity::class.java).apply {
            putExtra(EXTRA_MEDIA_STORE_ID, mediaStoreId)
            putExtra(EXTRA_CONTENT_URI, contentUri)
            putExtra(EXTRA_DATE_ADDED, dateAdded)
        }
    }
}
