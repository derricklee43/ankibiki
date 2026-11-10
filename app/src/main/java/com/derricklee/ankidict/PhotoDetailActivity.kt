package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.derricklee.ankidict.databinding.ActivityPhotoDetailBinding
import java.util.concurrent.Executors

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var shinjitaiRepository: ShinjitaiRepository
    private lateinit var hskRepository: HskRepository
    private lateinit var miningPipelineRunner: MiningPipelineRunner
    private lateinit var pagerAdapter: PhotoPageAdapter

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var photoIds: MutableList<Long> = mutableListOf()
    private var currentIndex: Int = 0
    private var fallbackContentUri: String = ""
    private var fallbackDateAdded: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        miningRepository = MiningRepository(this)
        dictionaryRepository = DictionaryRepository(this)
        shinjitaiRepository = ShinjitaiRepository(this)
        hskRepository = HskRepository(this)
        miningPipelineRunner = MiningPipelineRunner.createDefault(
            context = this,
            miningRepository = miningRepository,
            dictionaryRepository = dictionaryRepository,
            shinjitaiRepository = shinjitaiRepository,
            hskRepository = hskRepository,
        )

        val initialMediaId = intent.getLongExtra(EXTRA_MEDIA_STORE_ID, -1L)
        fallbackContentUri = intent.getStringExtra(EXTRA_CONTENT_URI).orEmpty()
        fallbackDateAdded = intent.getLongExtra(EXTRA_DATE_ADDED, 0L)
        val passedIds = intent.getLongArrayExtra(EXTRA_PHOTO_IDS)?.toList().orEmpty()
        photoIds = if (passedIds.isNotEmpty()) {
            passedIds.toMutableList()
        } else if (initialMediaId != -1L) {
            mutableListOf(initialMediaId)
        } else {
            mutableListOf()
        }
        currentIndex = photoIds.indexOf(initialMediaId).coerceAtLeast(0)

        binding.backButton.setOnClickListener { finish() }

        pagerAdapter = PhotoPageAdapter()
        binding.photoPager.adapter = pagerAdapter
        binding.photoPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    currentIndex = position
                    updateCounterAndLoadExtractedText(position)
                }
            },
        )
        if (photoIds.isNotEmpty()) {
            binding.photoPager.setCurrentItem(currentIndex, false)
            updateCounterAndLoadExtractedText(currentIndex)
        }

        binding.refreshThumbnailButton.setOnClickListener {
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            binding.refreshThumbnailButton.isEnabled = false
            ioExecutor.execute {
                val photo = miningRepository.getPhoto(mediaId)
                val uri = photo?.contentUri?.takeIf { it.isNotBlank() } ?: fallbackContentUri
                val refreshed = if (uri.isNotBlank()) {
                    PhotoThumbnailCache.refreshThumbnail(this, mediaId, uri)
                } else {
                    null
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.refreshThumbnailButton.isEnabled = true
                    if (refreshed != null) {
                        pagerAdapter.notifyItemChanged(currentIndex)
                        Toast.makeText(this, R.string.thumbnail_refreshed_toast, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.saveTextButton.setOnClickListener {
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            val amendedText = binding.extractedTextEdit.text?.toString().orEmpty().trim()
            promptSaveAndReprocess(mediaId, amendedText)
        }

        binding.deletePhotoButton.setOnClickListener {
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            binding.deletePhotoButton.isEnabled = false
            ioExecutor.execute {
                val photo = miningRepository.getPhoto(mediaId)
                val resolvedUri = photo?.contentUri?.takeIf { it.isNotBlank() } ?: fallbackContentUri
                val resolvedDate = photo?.dateAdded ?: fallbackDateAdded
                miningRepository.markPhotoDeleted(
                    mediaStoreId = mediaId,
                    contentUri = resolvedUri,
                    dateAdded = resolvedDate,
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.photo_excluded_toast, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun currentMediaStoreId(): Long? = photoIds.getOrNull(currentIndex)

    private fun updateCounterAndLoadExtractedText(position: Int) {
        val total = photoIds.size
        binding.photoCounterText.text = if (total > 1) "${position + 1} / $total" else ""
        val mediaId = photoIds.getOrNull(position) ?: return
        ioExecutor.execute {
            val photo = miningRepository.getPhoto(mediaId)
            val text = photo?.extractedText.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed || currentIndex != position) return@runOnUiThread
                binding.extractedTextEdit.setText(text)
            }
        }
    }

    private fun promptSaveAndReprocess(mediaId: Long, amendedText: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reprocess_dialog_title)
            .setMessage(R.string.reprocess_dialog_message)
            .setPositiveButton(R.string.btn_reprocess_and_save) { _, _ ->
                saveAmendedPhotoText(mediaId, amendedText, reprocessWords = true)
            }
            .setNeutralButton(R.string.btn_save_text_only) { _, _ ->
                saveAmendedPhotoText(mediaId, amendedText, reprocessWords = false)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun saveAmendedPhotoText(mediaId: Long, amendedText: String, reprocessWords: Boolean) {
        binding.saveTextButton.isEnabled = false
        ioExecutor.execute {
            miningPipelineRunner.reprocessPhotoText(
                mediaStoreId = mediaId,
                amendedText = amendedText,
                reprocessWords = reprocessWords,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.saveTextButton.isEnabled = true
                val msgRes = if (reprocessWords) {
                    R.string.text_saved_and_reprocessed_toast
                } else {
                    R.string.text_saved_toast
                }
                Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class PhotoPageAdapter : RecyclerView.Adapter<PhotoPageAdapter.PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val imageView = ZoomableImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return PageHolder(imageView)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(photoIds[position])
        }

        override fun getItemCount(): Int = photoIds.size

        inner class PageHolder(
            private val imageView: ZoomableImageView,
        ) : RecyclerView.ViewHolder(imageView) {
            private var boundId: Long = -1L

            fun bind(mediaId: Long) {
                boundId = mediaId
                imageView.resetZoom()
                imageView.setImageDrawable(null)
                ioExecutor.execute {
                    val photo = miningRepository.getPhoto(mediaId)
                    val resolvedUri = photo?.contentUri?.takeIf { it.isNotBlank() } ?: fallbackContentUri
                    val bitmap = if (resolvedUri.isNotBlank()) {
                        PhotoThumbnailCache.decodeFullResolutionOriented(
                            this@PhotoDetailActivity,
                            resolvedUri,
                        )
                    } else {
                        null
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || boundId != mediaId) return@runOnUiThread
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                        } else if (resolvedUri.isNotBlank()) {
                            runCatching { imageView.setImageURI(Uri.parse(resolvedUri)) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MEDIA_STORE_ID = "extra_media_store_id"
        private const val EXTRA_CONTENT_URI = "extra_content_uri"
        private const val EXTRA_DATE_ADDED = "extra_date_added"
        private const val EXTRA_PHOTO_IDS = "extra_photo_ids"

        fun newIntent(
            context: Context,
            mediaStoreId: Long,
            contentUri: String,
            dateAdded: Long,
            photoIds: LongArray? = null,
        ): Intent = Intent(context, PhotoDetailActivity::class.java).apply {
            putExtra(EXTRA_MEDIA_STORE_ID, mediaStoreId)
            putExtra(EXTRA_CONTENT_URI, contentUri)
            putExtra(EXTRA_DATE_ADDED, dateAdded)
            if (photoIds != null && photoIds.isNotEmpty()) {
                putExtra(EXTRA_PHOTO_IDS, photoIds)
            }
        }
    }
}
