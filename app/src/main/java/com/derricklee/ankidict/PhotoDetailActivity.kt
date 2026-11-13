package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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
    private var lastLoadedDocument: OcrDocument? = null
    private var lastLoadedRows: List<ExtractionStatus.SentenceRows> = emptyList()
    private var rawOcrVisible = false

    private fun updateActionAvailability() {
        val enabled = !loadingText && !operationInProgress && currentMediaStoreId() != null
        binding.editTextButton.isEnabled = enabled
        binding.deletePhotoButton.isEnabled = enabled
        binding.refreshThumbnailButton.isEnabled = enabled
        binding.markGoldButton.isEnabled = !operationInProgress && currentMediaStoreId() != null
        binding.photoPager.isUserInputEnabled = enabled
    }

    /** Gold state of the photo currently shown; owned by the is_gold column, refreshed on load. */
    private var currentIsGold = false

    /**
     * Two-state gold button (user spec 2026-11-13): unmarked = outlined, gold border + gold text
     * "Mark as gold"; marked = background filled gold, text in the previous background color
     * (theme colorSurface, so light/dark both read correctly), label just "Gold".
     */
    private fun renderGoldButton() {
        val gold = androidx.core.content.ContextCompat.getColor(this, R.color.gold)
        val tv = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)
        val surfaceColor = if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
        binding.markGoldButton.apply {
            strokeColor = android.content.res.ColorStateList.valueOf(gold)
            if (currentIsGold) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(gold)
                setTextColor(surfaceColor)
                setText(R.string.btn_gold_marked)
            } else {
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                setTextColor(gold)
                setText(R.string.btn_mark_gold)
            }
        }
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
                if (!reprocessWords) {
                    saveAmendedPhotoText(pendingEditMediaId, amendedText, reprocessWords = false)
                } else {
                    // Show what the word-reprocess will replace BEFORE committing (roadmap §3).
                    showReprocessPreview(pendingEditMediaId, amendedText) {
                        saveAmendedPhotoText(pendingEditMediaId, amendedText, reprocessWords = true)
                    }
                }
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

        // Mark-as-gold: explicit gold-set membership (user 2026-11-13). Gold = marked, replacing
        // the "any touched photo" assumption; marking never sets the edit-provenance flag.
        binding.markGoldButton.setOnClickListener {
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            if (operationInProgress || loadingText) return@setOnClickListener
            val newValue = !currentIsGold
            operationInProgress = true
            updateActionAvailability()
            ioExecutor.execute {
                var failure: Exception? = null
                try {
                    miningRepository.setPhotoIsGold(mediaId, newValue)
                } catch (e: Exception) {
                    failure = e
                }
                runOnUiThread {
                    operationInProgress = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (failure != null) {
                        currentIsGold = !newValue // write failed; keep the truthful state
                        Toast.makeText(this, failure!!.message.orEmpty(), Toast.LENGTH_SHORT).show()
                    } else {
                        currentIsGold = newValue
                        Toast.makeText(
                            this,
                            getString(if (newValue) R.string.gold_marked_toast else R.string.gold_unmarked_toast),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    renderGoldButton()
                    updateActionAvailability()
                }
            }
        }
        renderGoldButton()

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

        binding.toggleRawOcrButton.setOnClickListener {
            rawOcrVisible = !rawOcrVisible
            applyRawOcrVisibility()
        }
        binding.staleWordsChip.setOnClickListener {
            if (loadingText || operationInProgress) return@setOnClickListener
            val mediaId = currentMediaStoreId() ?: return@setOnClickListener
            showReprocessPreview(mediaId, lastLoadedText) {
                saveAmendedPhotoText(mediaId, lastLoadedText, reprocessWords = true)
            }
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
        val shownId = photoIds.getOrNull(position)
        // The MediaStore id is the photo number benchmarks, reports, and the ocr_corrections
        // audit rows refer to — surfacing it here makes annotation and evidence lookup line up.
        binding.photoCounterText.text = when {
            shownId == null -> ""
            total > 1 -> "${position + 1} / $total · #${shownId}"
            else -> "#${shownId}"
        }
        val mediaId = shownId ?: return
        val generation = ++textLoadGeneration
        loadingText = true
        lastLoadedText = ""
        lastLoadedPhotoUri = ""
        lastLoadedDocument = null
        lastLoadedRows = emptyList()
        binding.extractedTextDisplay.text = ""
        bindTransparencyViews()
        updateActionAvailability()
        ioExecutor.execute {
            try {
                val photo = miningRepository.getPhoto(mediaId)
                val text = photo?.extractedText.orEmpty()
                val rows = miningRepository.getOccurrenceSentenceRows(mediaId)
                val document = OcrDocument.fromJsonString(photo?.ocrBlocksJson)
                val resolvedUri = photo?.contentUri?.takeIf { it.isNotBlank() } ?: fallbackContentUri
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != textLoadGeneration ||
                        currentMediaStoreId() != mediaId) return@runOnUiThread
                    lastLoadedText = text
                    lastLoadedPhotoUri = resolvedUri
                    lastLoadedRows = rows
                    lastLoadedDocument = document
                    currentIsGold = photo?.isGold == true
                    renderGoldButton()
                    binding.extractedTextDisplay.text =
                        text.ifBlank { getString(R.string.no_extracted_text) }
                    bindTransparencyViews()
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

    /** Provider line, raw-OCR section, and stale-words chip for the currently loaded photo (roadmap §3). */
    private fun bindTransparencyViews() {
        val document = lastLoadedDocument
        val providerLabel = ExtractionStatus.describeProvider(document)
        binding.ocrSourceText.text = getString(
            R.string.ocr_source_prefix,
            if (providerLabel.isEmpty()) getString(R.string.ocr_source_unknown) else providerLabel,
        ) + document?.note?.let { " — $it" }.orEmpty()
        val raw = document?.fullText.orEmpty()
        binding.rawOcrText.text = raw +
            if (raw.isNotEmpty() && raw == lastLoadedText) " " + getString(R.string.raw_ocr_same_as_display) else ""
        applyRawOcrVisibility()
        binding.staleWordsChip.visibility =
            if (lastLoadedText.isNotBlank() && ExtractionStatus.isStale(lastLoadedText.lines(), lastLoadedRows)) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun applyRawOcrVisibility() {
        binding.toggleRawOcrButton.visibility = if (lastLoadedDocument != null) View.VISIBLE else View.GONE
        binding.rawOcrText.visibility = if (rawOcrVisible && lastLoadedDocument != null) View.VISIBLE else View.GONE
        binding.toggleRawOcrButton.setText(
            if (rawOcrVisible) R.string.btn_hide_raw_ocr else R.string.btn_show_raw_ocr,
        )
    }

    /**
     * Read-only impact preview shown BEFORE any word-reprocess commit:
     * keeps / new / removed sentences plus how much triage and hand-edit history would be replaced.
     */
    private fun showReprocessPreview(mediaId: Long, newText: String, onConfirm: () -> Unit) {
        operationInProgress = true
        updateActionAvailability()
        ioExecutor.execute {
            val rows = runCatching { miningRepository.getOccurrenceSentenceRows(mediaId) }
                .getOrDefault(emptyList())
            val preview = ExtractionStatus.preview(newText.lines(), rows)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                operationInProgress = false
                updateActionAvailability()
                var message = getString(
                    R.string.reprocess_preview_message,
                    preview.keptSentences, preview.addedSentences, preview.removedSentences,
                )
                if (preview.triagedRowsInRemoved > 0) {
                    message += getString(R.string.reprocess_preview_triaged_note, preview.triagedRowsInRemoved)
                }
                if (preview.editedSentencesInRemoved > 0) {
                    message += getString(R.string.reprocess_preview_edited_note, preview.editedSentencesInRemoved)
                }
                if (preview.triagedRowsInRemoved == 0 && preview.editedSentencesInRemoved == 0) {
                    message += getString(R.string.reprocess_preview_none_lost)
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.reprocess_preview_title)
                    .setMessage(message)
                    .setNegativeButton(R.string.btn_cancel) { dialog, _ -> dialog.dismiss() }
                    .setPositiveButton(R.string.btn_reprocess_and_save) { _, _ -> onConfirm() }
                    .show()
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
