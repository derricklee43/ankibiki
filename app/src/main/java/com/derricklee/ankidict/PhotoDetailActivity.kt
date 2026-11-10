package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    // Snapshot of the text/uri currently shown, and the photo the editor was
    // launched for (swiping or returning can move currentIndex while editing).
    private var lastLoadedText: String = ""
    private var lastLoadedPhotoUri: String = ""
    private var pendingEditMediaId: Long = -1L
    private var loadingText = true
    private var operationInProgress = false
    private var textLoadGeneration = 0L
    private var rescanDialog: androidx.appcompat.app.AlertDialog? = null
    private var tearingDown = false

    private fun updateActionAvailability() {
        val enabled = !loadingText && !operationInProgress && currentMediaStoreId() != null
        binding.editTextButton.isEnabled = enabled
        binding.deletePhotoButton.isEnabled = enabled
        binding.refreshThumbnailButton.isEnabled = enabled
        binding.photoPager.isUserInputEnabled = enabled
    }

    private val textEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val amendedText = result.data?.getStringExtra(TextEditorActivity.RESULT_TEXT)
                ?: return@registerForActivityResult
            val reprocessWords = result.data
                ?.getBooleanExtra(TextEditorActivity.RESULT_REPROCESS_WORDS, true) ?: true
            if (pendingEditMediaId >= 0L) {
                saveAmendedPhotoText(pendingEditMediaId, amendedText, reprocessWords)
            }
        }
    }

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

        updateActionAvailability()
        binding.refreshThumbnailButton.setOnClickListener {
            if (loadingText || operationInProgress) return@setOnClickListener
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            operationInProgress = true
            updateActionAvailability()
            // Resolve the current database row before any rescan image/thumbnail work.
            // Capture this candidate once: a modal decision must never use currentIndex.
            ioExecutor.execute {
                try {
                    val photo = miningRepository.getPhoto(mediaId)
                    val candidate = PhotoCandidate(
                        mediaStoreId = mediaId,
                        contentUri = photo?.contentUri?.takeIf { it.isNotBlank() } ?: fallbackContentUri,
                        dateAdded = photo?.dateAdded ?: fallbackDateAdded,
                    )
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (photo?.modifiedByUserFlag == true) {
                            confirmRescan(candidate)
                        } else {
                            runRescan(candidate)
                        }
                    }
                } catch (error: Exception) {
                    reportOperationFailure(error)
                }
            }
        }

        binding.editTextButton.setOnClickListener {
            if (loadingText || operationInProgress) return@setOnClickListener
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            pendingEditMediaId = mediaId
            textEditorLauncher.launch(
                TextEditorActivity.newPhotoTextIntent(
                    context = this,
                    extractedText = lastLoadedText,
                    contentUri = lastLoadedPhotoUri,
                    confirmTitle = getString(R.string.reprocess_dialog_title),
                ),
            )
        }

        binding.deletePhotoButton.setOnClickListener {
            if (loadingText || operationInProgress) return@setOnClickListener
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            operationInProgress = true
            updateActionAvailability()
            ioExecutor.execute {
                try {
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
                } catch (error: Exception) {
                    reportOperationFailure(error)
                }
            }
        }
    }

    override fun onDestroy() {
        tearingDown = true
        rescanDialog?.dismiss()
        rescanDialog = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun confirmRescan(candidate: PhotoCandidate) {
        if (isFinishing || isDestroyed || tearingDown) return
        rescanDialog = PhotoRescanDialog.create(this) { decision ->
            rescanDialog = null
            if (isFinishing || isDestroyed || tearingDown) return@create
            if (decision == PhotoRescanDecision.CANCEL) {
                operationInProgress = false
                updateActionAvailability()
            } else {
                runRescan(candidate, decision)
            }
        }.also { it.show() }
    }

    private fun runRescan(candidate: PhotoCandidate, decision: PhotoRescanDecision? = null) {
        ioExecutor.execute {
            try {
                val result = miningPipelineRunner.rescanPhoto(candidate, decision)
                // The runner can discover a new manual edit after our initial row query.
                if (result == PhotoRescanResult.CONFIRMATION_REQUIRED) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) confirmRescan(candidate)
                    }
                    return@execute
                }
                if (result == PhotoRescanResult.COMPLETED || result == PhotoRescanResult.SKIPPED) {
                    checkNotNull(PhotoThumbnailCache.refreshThumbnail(
                        this, candidate.mediaStoreId, candidate.contentUri,
                    )) { getString(R.string.photo_thumbnail_refresh_failed) }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    operationInProgress = false
                    if (result == PhotoRescanResult.COMPLETED || result == PhotoRescanResult.SKIPPED) {
                        val targetIndex = photoIds.indexOf(candidate.mediaStoreId)
                        if (targetIndex >= 0) pagerAdapter.notifyItemChanged(targetIndex)
                        if (result == PhotoRescanResult.COMPLETED && currentMediaStoreId() == candidate.mediaStoreId) {
                            updateCounterAndLoadExtractedText(currentIndex)
                        }
                        val message = if (result == PhotoRescanResult.SKIPPED) {
                            R.string.thumbnail_refreshed_toast
                        } else {
                            R.string.photo_rescanned_toast
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                    updateActionAvailability()
                }
            } catch (error: Exception) {
                // Even if thumbnail regeneration failed after commit, show the new database text.
                reportOperationFailure(error, candidate.mediaStoreId)
            }
        }
    }

    private fun reportOperationFailure(error: Exception, reloadMediaId: Long? = null) {
        runOnUiThread {
            if (isFinishing || isDestroyed || tearingDown) return@runOnUiThread
            operationInProgress = false
            if (reloadMediaId != null && currentMediaStoreId() == reloadMediaId) {
                updateCounterAndLoadExtractedText(currentIndex)
            }
            updateActionAvailability()
            Toast.makeText(this, getString(R.string.scan_failed_toast,
                error.localizedMessage ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    private fun currentMediaStoreId(): Long? = photoIds.getOrNull(currentIndex)

    private fun updateCounterAndLoadExtractedText(position: Int) {
        val total = photoIds.size
        binding.photoCounterText.text = if (total > 1) "${position + 1} / $total" else ""
        val mediaId = photoIds.getOrNull(position) ?: return
        val generation = ++textLoadGeneration
        loadingText = true
        lastLoadedText = ""
        lastLoadedPhotoUri = ""
        binding.extractedTextDisplay.text = ""
        updateActionAvailability()
        ioExecutor.execute {
            try {
                val photo = miningRepository.getPhoto(mediaId)
                val text = photo?.extractedText.orEmpty()
                val resolvedUri = photo?.contentUri?.takeIf { it.isNotBlank() } ?: fallbackContentUri
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != textLoadGeneration ||
                        currentMediaStoreId() != mediaId) return@runOnUiThread
                    lastLoadedText = text
                    lastLoadedPhotoUri = resolvedUri
                    binding.extractedTextDisplay.text =
                        text.ifBlank { getString(R.string.no_extracted_text) }
                    loadingText = false
                    updateActionAvailability()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != textLoadGeneration) return@runOnUiThread
                    loadingText = false
                    reportOperationFailure(error)
                }
            }
        }
    }

    private fun saveAmendedPhotoText(mediaId: Long, amendedText: String, reprocessWords: Boolean) {
        operationInProgress = true
        updateActionAvailability()
        ioExecutor.execute {
            try {
                val summary = miningPipelineRunner.reprocessPhotoText(
                    mediaStoreId = mediaId,
                    amendedText = amendedText,
                    reprocessWords = reprocessWords,
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    operationInProgress = false
                    if (currentMediaStoreId() == mediaId) updateCounterAndLoadExtractedText(currentIndex)
                    updateActionAvailability()
                    // Communicate what reconciliation did to triage history (roadmap §1).
                    val message = if (summary.wordsReprocessed) {
                        getString(R.string.reprocess_photo_toast,
                            summary.keptSentences, summary.addedSentences, summary.removedSentences) +
                            if (summary.removedSentences > 0) getString(R.string.reprocess_photo_removed_note) else ""
                    } else {
                        getString(R.string.text_saved_toast)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                reportOperationFailure(error, mediaId)
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
