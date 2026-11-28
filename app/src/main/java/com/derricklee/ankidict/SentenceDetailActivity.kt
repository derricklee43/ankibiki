package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.derricklee.ankidict.databinding.ActivitySentenceDetailBinding
import com.derricklee.ankidict.databinding.ItemSentenceChipBinding
import java.io.File

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
    private var mediaId = -1L
    private var sentence = ""
    private var languageTag = LanguageTag.JAPANESE
    private var editorBusy = false
    private var deleteBusy = false
    private var sliceBusy = false
    private var videoCueCtx: VideoCueContext? = null
    private var currentVideoImageFile: File? = null
    private var currentVideoAudioFile: File? = null
    private var currentVideoBitmap: Bitmap? = null
    private var savedVideoClip: SavedVideoClip? = null
    private var currentSliceOffsetMs: Int = 0
    private var currentSliceAudioTrack: Int? = null
    private var mediaPlayer: MediaPlayer? = null
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
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1L)
        sentence = intent.getStringExtra(EXTRA_SENTENCE).orEmpty()
        languageTag = runCatching {
            LanguageTag.valueOf(intent.getStringExtra(EXTRA_LANGUAGE_TAG) ?: LanguageTag.JAPANESE.name)
        }.getOrDefault(LanguageTag.JAPANESE)

        binding.backButton.setOnClickListener { finish() }
        binding.sentenceText.text = sentence
        TextLocales.apply(binding.sentenceText, languageTag)
        loadProvenance()

        // Delete (user request 2026-11-26): any media-anchored sentence - video cues
        // and photo rows both carry media_refs ids; the flip is soft + undoable.
        if (mediaId >= 0L) {
            binding.deleteSentenceButton.visibility = View.VISIBLE
            binding.deleteSentenceButton.setOnClickListener { confirmDeleteSentence() }
        }

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
        }
        // word editor wiring (2026-11-26): media-anchored video rows get the same
        // edit/add/remove chip editor the photo path has
        if (photoId >= 0L || mediaId >= 0L) {
            binding.wordEditorAddButton.setOnClickListener { addEditorRow(null, null, "") }
            binding.wordEditorCancelButton.setOnClickListener { closeEditor() }
            binding.wordEditorSaveButton.setOnClickListener { saveEditor() }
        }

        binding.loadVideoMediaButton.setOnClickListener { fetchVideoSlice(forceRefresh = false) }
        binding.playVideoAudioButton.setOnClickListener { toggleVideoAudio() }
        binding.saveVideoClipButton.setOnClickListener { toggleSaveVideoClip() }
        binding.adjustVideoMediaButton.setOnClickListener { showVideoAdjustDialog() }

        loadPhoto()
        loadVideoMediaState()
    }

    override fun onResume() {
        super.onResume()
        loadParsedWords()
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
                // undo delivery: the sentence amendment recorded a whole-photo memento.
                UndoUi.maybeShow(binding.root, miningRepository) { loadParsedWords() }
            }
        }
    }

    private fun loadParsedWords() {
        // media anchor covers video-cue sentences (user request 2026-11-26: the chip
        // component + editor exist for mined sentences too, not only photo ones)
        if (photoId < 0L && mediaId < 0L) return // no anchor at all -> text-only viewer
        ioExecutor.execute {
            val candidates = if (photoId >= 0L) {
                miningRepository.getSentenceCandidates(photoId, sentence)
            } else {
                miningRepository.getSentenceCandidatesForMedia(mediaId, sentence)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.wordChipGroup.removeAllViews()
                val surfaceColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
                val onSurfaceColor = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
                val onSurfaceVariantColor = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                val surfaceContainerHighestColor = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
                val outlineVariantColor = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
                for (candidate in candidates) {
                    val chipBinding = ItemSentenceChipBinding.inflate(
                        layoutInflater, binding.wordChipGroup, false,
                    )
                    val chip: Chip = chipBinding.root
                    chip.text = candidate.expression
                    when (candidate.visibility) {
                        ItemVisibility.ACTIVE -> {
                            // Non-hidden: elevated, swap background and text colors.
                            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(onSurfaceColor)
                            chip.setTextColor(surfaceColor)
                            chip.chipStrokeWidth = 0f
                            chip.elevation = 4f
                        }
                        ItemVisibility.HIDDEN -> {
                            // Hidden: keep the current default outline/surface styling.
                        }
                        ItemVisibility.ARCHIVED -> {
                            // Archived: more gray to de-emphasize.
                            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(surfaceContainerHighestColor)
                            chip.setTextColor(onSurfaceVariantColor)
                            chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(outlineVariantColor)
                            chip.alpha = 0.65f
                        }
                    }
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
            val candidates = if (photoId >= 0L) {
                miningRepository.getSentenceCandidates(photoId, sentence)
            } else {
                miningRepository.getSentenceCandidatesForMedia(mediaId, sentence)
            }
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
            val result = runCatching {
                if (photoId >= 0L) {
                    pipelineRunner().saveSentenceWords(photoId, sentence, rows)
                } else {
                    pipelineRunner().saveSentenceWordsForMedia(mediaId, languageTag, sentence, rows)
                }
            }
            runOnUiThread {
                editorBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { changes ->
                        closeEditor()
                        Toast.makeText(this, getString(R.string.word_editor_saved, changes), Toast.LENGTH_SHORT).show()
                        loadParsedWords()
                        // undo delivery: the word-editor save recorded a whole-photo memento.
                        UndoUi.maybeShow(binding.root, miningRepository) { loadParsedWords() }
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

    /** Provenance header (user request 2026-11-26): "Series - S1-E1  Episode title".
     *  Photo rows resolve kind='photo' -> no line; video rows whose library entry is
     *  gone still show the S/E label from the stored video id. */
    private fun loadProvenance() {
        if (mediaId < 0L) return
        ioExecutor.execute {
            val pro = runCatching { miningRepository.getSentenceProvenance(mediaId) }.getOrNull()
            val line = pro?.let {
                VideoSourceLabel.display(it.seriesName, it.episodeLabel, it.episodeTitle)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (line != null) {
                    binding.provenanceLine.text = line
                    binding.provenanceLine.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun confirmDeleteSentence() {
        if (deleteBusy || mediaId < 0L) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.sentence_delete_confirm_title)
            .setMessage(getString(R.string.sentence_delete_confirm_message, sentence.take(80)))
            .setNegativeButton(R.string.word_editor_cancel, null)
            .setPositiveButton(R.string.word_editor_delete) { _, _ -> deleteSentence() }
            .show()
    }

    private fun deleteSentence() {
        if (deleteBusy || mediaId < 0L) return
        deleteBusy = true
        ioExecutor.execute {
            val result = runCatching { pipelineRunner().deleteSentence(mediaId, sentence) }
            runOnUiThread {
                deleteBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        Toast.makeText(this, R.string.sentence_deleted, Toast.LENGTH_SHORT).show()
                        // leave the screen the sentence was on; the parent list reloads
                        // its own content, and the undo snackbar is delivered there
                        finish()
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
    private fun resolvePrimaryColor(): Int =
        resolveThemeColor(com.google.android.material.R.attr.colorPrimary)

    private fun resolveThemeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
    }

    // ---- Stage-3 Step 3 + v17: lazy video screenshot + audio preview, permanent save & adjust ----

    private fun loadVideoMediaState() {
        if (photoId >= 0L || mediaId < 0L) return
        val appCtx = applicationContext
        ioExecutor.execute {
            val saved = miningRepository.getSavedVideoClip(mediaId, sentence)
                ?.takeIf { it.hasValidFiles(appCtx) }
            val ctx = miningRepository.findVideoCueContext(mediaId, sentence)
            val settings = ctx?.let { miningRepository.stremioSettingsFor(it.videoId, it.baseId) }
            val mediaPair: Pair<File, File>? = if (saved != null) {
                saved.imageFile(appCtx) to saved.audioFile(appCtx)
            } else if (ctx != null && settings != null) {
                VideoSliceService.getCachedSlice(appCtx, ctx.docId, ctx.cue, settings)
            } else {
                null
            }
            val bmp = mediaPair?.first?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                videoCueCtx = ctx
                savedVideoClip = saved
                currentSliceOffsetMs = saved?.offsetMs ?: settings?.offsetMs ?: 0
                currentSliceAudioTrack = saved?.audioTrack ?: settings?.audioTrack
                if (saved == null && ctx == null) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                binding.videoMediaControls.visibility = View.VISIBLE
                if (mediaPair != null && bmp != null) {
                    applyVideoSliceVisuals(
                        bmp = bmp,
                        imageFile = mediaPair.first,
                        audioFile = mediaPair.second,
                        isSaved = saved != null,
                        canAdjust = ctx != null,
                    )
                } else {
                    bmp?.recycle()
                    binding.loadVideoMediaButton.visibility = View.VISIBLE
                    binding.playVideoAudioButton.visibility = View.GONE
                    binding.saveVideoClipButton.visibility = View.GONE
                    binding.adjustVideoMediaButton.visibility = View.GONE
                }
            }
        }
    }

    private fun fetchVideoSlice(forceRefresh: Boolean) {
        val ctx = videoCueCtx ?: return
        if (sliceBusy) return
        sliceBusy = true
        stopVideoAudio()
        binding.loadVideoMediaButton.isEnabled = false
        binding.playVideoAudioButton.isEnabled = false
        binding.saveVideoClipButton.isEnabled = false
        binding.adjustVideoMediaButton.isEnabled = false
        binding.videoMediaStatusText.visibility = View.VISIBLE
        binding.videoMediaStatusText.text = getString(R.string.video_slice_load)

        val appCtx = applicationContext
        val wasSaved = savedVideoClip != null
        val provenance = binding.provenanceLine.text?.toString().orEmpty()
        AppIo.pipeline.execute {
            val result = runCatching {
                val repo = MiningRepository(appCtx)
                try {
                    val settings = repo.stremioSettingsFor(ctx.videoId, ctx.baseId)
                    val res = VideoSliceService.sliceOrLoad(
                        context = appCtx,
                        repo = repo,
                        prefs = StremioPrefs(appCtx),
                        ctx = ctx,
                        settings = settings,
                        forceRefresh = forceRefresh,
                        onStatus = { status ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    binding.videoMediaStatusText.text = status
                                }
                            }
                        },
                    )
                    // If this clip is already permanently saved, keep the saved copy in sync
                    // with the newly re-sliced offset / audio track.
                    val updatedSaved = if (wasSaved) {
                        repo.saveVideoClip(
                            context = appCtx,
                            mediaId = mediaId,
                            sentence = sentence,
                            docId = ctx.docId,
                            videoId = ctx.videoId,
                            startMs = ctx.cue.startMs,
                            endMs = ctx.cue.endMs,
                            offsetMs = settings.offsetMs,
                            audioTrack = settings.audioTrack,
                            languageTag = languageTag,
                            provenanceLabel = provenance,
                            srcImage = res.imageFile,
                            srcAudio = res.audioFile,
                        )
                    } else {
                        null
                    }
                    val imgFile = updatedSaved?.imageFile(appCtx) ?: res.imageFile
                    val audFile = updatedSaved?.audioFile(appCtx) ?: res.audioFile
                    val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                        ?: throw IllegalStateException("could not decode extracted WebP")
                    Triple(Pair(imgFile, audFile), Pair(settings, updatedSaved), bmp)
                } finally {
                    repo.close()
                }
            }
            runOnUiThread {
                sliceBusy = false
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.third?.recycle()
                    return@runOnUiThread
                }
                binding.loadVideoMediaButton.isEnabled = true
                binding.playVideoAudioButton.isEnabled = true
                binding.saveVideoClipButton.isEnabled = true
                binding.adjustVideoMediaButton.isEnabled = true
                result.fold(
                    onSuccess = { (files, meta, bmp) ->
                        val (settings, updatedSaved) = meta
                        currentSliceOffsetMs = settings.offsetMs
                        currentSliceAudioTrack = settings.audioTrack
                        if (updatedSaved != null) savedVideoClip = updatedSaved
                        applyVideoSliceVisuals(
                            bmp = bmp,
                            imageFile = files.first,
                            audioFile = files.second,
                            isSaved = savedVideoClip != null,
                            canAdjust = true,
                        )
                        binding.videoMediaStatusText.visibility = View.GONE
                    },
                    onFailure = { err ->
                        binding.videoMediaStatusText.visibility = View.VISIBLE
                        binding.videoMediaStatusText.text =
                            getString(R.string.video_slice_failed, err.message ?: err.javaClass.simpleName)
                    },
                )
            }
        }
    }

    private fun applyVideoSliceVisuals(
        bmp: Bitmap,
        imageFile: File,
        audioFile: File,
        isSaved: Boolean,
        canAdjust: Boolean,
    ) {
        currentVideoBitmap?.recycle()
        currentVideoBitmap = bmp
        currentVideoImageFile = imageFile
        currentVideoAudioFile = audioFile
        binding.photoImage.setImageBitmap(bmp)
        binding.photoImage.isClickable = false
        binding.photoImage.isFocusable = false
        binding.photoImage.setOnClickListener(null)
        binding.photoImage.visibility = View.VISIBLE
        binding.loadVideoMediaButton.visibility = View.GONE
        binding.playVideoAudioButton.visibility = View.VISIBLE
        binding.playVideoAudioButton.text = getString(R.string.video_slice_play)
        binding.saveVideoClipButton.visibility = View.VISIBLE
        binding.saveVideoClipButton.text = getString(
            if (isSaved) R.string.video_slice_saved else R.string.video_slice_save,
        )
        binding.adjustVideoMediaButton.visibility = if (canAdjust) View.VISIBLE else View.GONE
    }

    private fun toggleSaveVideoClip() {
        if (sliceBusy || mediaId < 0L) return
        val appCtx = applicationContext
        val existing = savedVideoClip
        binding.saveVideoClipButton.isEnabled = false
        if (existing != null) {
            stopVideoAudio()
            val ctx = videoCueCtx
            ioExecutor.execute {
                val removed = runCatching {
                    miningRepository.deleteSavedVideoClip(appCtx, mediaId, sentence)
                }.getOrDefault(false)
                val fallbackCache = ctx?.let {
                    val settings = miningRepository.stremioSettingsFor(it.videoId, it.baseId)
                    VideoSliceService.getCachedSlice(appCtx, it.docId, it.cue, settings)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.saveVideoClipButton.isEnabled = true
                    if (removed) {
                        savedVideoClip = null
                        Toast.makeText(this, R.string.video_slice_unsaved_toast, Toast.LENGTH_SHORT).show()
                        if (fallbackCache != null) {
                            currentVideoImageFile = fallbackCache.first
                            currentVideoAudioFile = fallbackCache.second
                            binding.saveVideoClipButton.text = getString(R.string.video_slice_save)
                        } else {
                            // Permanent files were deleted and cache no longer has a copy.
                            currentVideoImageFile = null
                            currentVideoAudioFile = null
                            currentVideoBitmap?.recycle()
                            currentVideoBitmap = null
                            binding.photoImage.setImageDrawable(null)
                            binding.photoImage.visibility = View.GONE
                            binding.playVideoAudioButton.visibility = View.GONE
                            binding.saveVideoClipButton.visibility = View.GONE
                            binding.adjustVideoMediaButton.visibility = View.GONE
                            binding.loadVideoMediaButton.visibility =
                                if (ctx != null) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
            return
        }

        val imgFile = currentVideoImageFile ?: run {
            binding.saveVideoClipButton.isEnabled = true
            return
        }
        val audFile = currentVideoAudioFile ?: run {
            binding.saveVideoClipButton.isEnabled = true
            return
        }
        val ctx = videoCueCtx
        val provenance = binding.provenanceLine.text?.toString().orEmpty()
        val offsetMs = currentSliceOffsetMs
        val audioTrack = currentSliceAudioTrack
        ioExecutor.execute {
            val savedResult = runCatching {
                miningRepository.saveVideoClip(
                    context = appCtx,
                    mediaId = mediaId,
                    sentence = sentence,
                    docId = ctx?.docId ?: 0L,
                    videoId = ctx?.videoId.orEmpty(),
                    startMs = ctx?.cue?.startMs ?: 0L,
                    endMs = ctx?.cue?.endMs ?: 0L,
                    offsetMs = offsetMs,
                    audioTrack = audioTrack,
                    languageTag = languageTag,
                    provenanceLabel = provenance,
                    srcImage = imgFile,
                    srcAudio = audFile,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.saveVideoClipButton.isEnabled = true
                savedResult.fold(
                    onSuccess = { clip ->
                        savedVideoClip = clip
                        currentVideoImageFile = clip.imageFile(appCtx)
                        currentVideoAudioFile = clip.audioFile(appCtx)
                        binding.saveVideoClipButton.text = getString(R.string.video_slice_saved)
                        Toast.makeText(this, R.string.video_slice_saved_toast, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            this,
                            getString(R.string.video_slice_failed, err.message ?: err.javaClass.simpleName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }

    private fun toggleVideoAudio() {
        val mp = mediaPlayer
        if (mp != null && mp.isPlaying) {
            stopVideoAudio()
            return
        }
        val audioFile = currentVideoAudioFile ?: return
        stopVideoAudio()
        runCatching {
            val fresh = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            binding.playVideoAudioButton.text = getString(R.string.video_slice_play)
                        }
                    }
                    stopVideoAudio()
                }
                prepare()
                start()
            }
            mediaPlayer = fresh
            binding.playVideoAudioButton.text = getString(R.string.video_slice_stop)
        }.onFailure { err ->
            stopVideoAudio()
            Toast.makeText(this, getString(R.string.video_slice_failed, err.message ?: "?"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVideoAudio() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
            binding.playVideoAudioButton.text = getString(R.string.video_slice_play)
        }
    }

    private fun showVideoAdjustDialog() {
        val ctx = videoCueCtx ?: return
        if (sliceBusy) return
        val current = miningRepository.stremioSettingsFor(ctx.videoId, ctx.baseId)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 8)
        }
        val offsetInput = EditText(this).apply {
            hint = getString(R.string.video_slice_offset_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current.offsetMs.toString())
        }
        container.addView(offsetInput)

        // Quick offset nudges for BD/Web-DL subtitle shift tuning.
        val nudgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        for (delta in listOf(-1000, -500, 500, 1000)) {
            nudgeRow.addView(
                MaterialButton(this, null, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                    text = if (delta > 0) "+${delta / 1000.0}s" else "${delta / 1000.0}s"
                    textSize = 12f
                    minWidth = 0
                    setPadding(12, 4, 12, 4)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        val cur = offsetInput.text.toString().toIntOrNull() ?: 0
                        offsetInput.setText((cur + delta).toString())
                    }
                },
            )
        }
        container.addView(nudgeRow)

        val tracks = VideoSliceService.probedTracksFor(ctx.docId)
        val radioGroup = RadioGroup(this)
        val trackByBtn = HashMap<Int, Int?>()
        val autoId = View.generateViewId()
        trackByBtn[autoId] = null
        radioGroup.addView(RadioButton(this).apply {
            id = autoId
            text = "Auto (${current.subtitleLang})"
            textSize = 13f
        })
        var checkedId = autoId
        for (t in tracks) {
            val btnId = View.generateViewId()
            trackByBtn[btnId] = t.trackIndex
            radioGroup.addView(RadioButton(this).apply {
                id = btnId
                text = t.displayLabel()
                textSize = 13f
            })
            if (current.audioTrack == t.trackIndex) checkedId = btnId
        }
        radioGroup.check(checkedId)
        if (tracks.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "音声トラック"
                textSize = 12f
                setPadding(0, 8, 0, 4)
            })
            container.addView(radioGroup)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.video_slice_adjust_title)
            .setView(container)
            .setNegativeButton(R.string.word_editor_cancel, null)
            .setPositiveButton(R.string.video_slice_apply_reslice) { _, _ ->
                val newOffset = offsetInput.text.toString().toIntOrNull() ?: 0
                val newTrack = if (tracks.isNotEmpty()) trackByBtn[radioGroup.checkedRadioButtonId] else current.audioTrack
                miningRepository.saveStremioSettings(
                    current.copy(scopeKey = ctx.videoId, audioTrack = newTrack, offsetMs = newOffset),
                    System.currentTimeMillis(),
                )
                fetchVideoSlice(forceRefresh = true)
            }
            .show()
    }

    override fun onStop() {
        stopVideoAudio()
        super.onStop()
    }

    override fun onDestroy() {
        stopVideoAudio()
        currentVideoBitmap?.recycle()
        currentVideoBitmap = null
        miningRepository.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PHOTO_ID = "extra_photo_id"
        private const val EXTRA_SENTENCE = "extra_sentence"
        private const val EXTRA_LANGUAGE_TAG = "extra_language_tag"
        private const val EXTRA_MEDIA_ID = "extra_media_id"

        fun newIntent(
            context: Context,
            photoId: Long,
            sentence: String,
            languageTag: LanguageTag,
            mediaId: Long = -1L,
        ): Intent = Intent(context, SentenceDetailActivity::class.java).apply {
            putExtra(EXTRA_PHOTO_ID, photoId)
            putExtra(EXTRA_SENTENCE, sentence)
            putExtra(EXTRA_LANGUAGE_TAG, languageTag.name)
            putExtra(EXTRA_MEDIA_ID, mediaId)
        }
    }
}
