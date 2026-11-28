package com.derricklee.ankidict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.derricklee.ankidict.databinding.ActivityPhotoLibraryBinding

private const val MEDIA_PERMISSION_REQUEST_CODE = 100

class PhotoLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoLibraryBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var adapter: PhotoLibraryAdapter
    private val ioExecutor = AppIo.io
    private var currentPhotos: List<ScrapedPhoto> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        miningRepository = MiningRepository(this)
        adapter = PhotoLibraryAdapter(
            onPhotoClick = { photo ->
                val allIds = currentPhotos.map { it.mediaStoreId }.toLongArray()
                startActivity(
                    PhotoDetailActivity.newIntent(
                        context = this,
                        mediaStoreId = photo.mediaStoreId,
                        contentUri = photo.contentUri,
                        dateAdded = photo.dateAdded,
                        photoIds = allIds,
                    ),
                )
            },
            onVideoClipClick = { clip ->
                startActivity(
                    SentenceDetailActivity.newIntent(
                        context = this,
                        photoId = -1L,
                        sentence = clip.sentence,
                        languageTag = clip.languageTag,
                        mediaId = clip.mediaId,
                    ),
                )
            },
        )

        val gridLayoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isFullSpan(position)) 3 else 1
            }
        }
        binding.photosGrid.layoutManager = gridLayoutManager
        binding.photosGrid.adapter = adapter

        binding.backButton.setOnClickListener { finish() }

        // library filters (roadmap B3 + v17 video clips): each facet toggle re-queries the grid
        binding.filterUnseenChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }
        binding.filterVideoChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }
        binding.filterGoldChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }
        binding.filterFlagChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }
        binding.filterJapaneseChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }
        binding.filterChineseChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }

        binding.cameraRollSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasReadMediaPermission()) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                    MEDIA_PERMISSION_REQUEST_CODE,
                )
                return@setOnCheckedChangeListener
            }
            loadPhotos()
        }
    }

    override fun onResume() {
        super.onResume()
        loadPhotos()
        // undo delivery (library): a photo delete finished back here — offer the undo.
        UndoUi.maybeShow(binding.root, miningRepository) { loadPhotos() }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MEDIA_PERMISSION_REQUEST_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                loadPhotos()
            } else {
                binding.cameraRollSwitch.isChecked = false
            }
        }
    }

    private fun hasReadMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED

    private fun loadPhotos() {
        val showFullCameraRoll = binding.cameraRollSwitch.isChecked && hasReadMediaPermission()
        // library facets scope the mined corpus; camera roll shows un-mined media — no chip row there
        binding.filterChipScroll.visibility = if (showFullCameraRoll) View.GONE else View.VISIBLE
        val unseen = binding.filterUnseenChip.isChecked
        val videoOnly = binding.filterVideoChip.isChecked
        val gold = binding.filterGoldChip.isChecked
        val flagged = binding.filterFlagChip.isChecked
        val tags = buildSet {
            if (binding.filterJapaneseChip.isChecked) add(LanguageTag.JAPANESE)
            if (binding.filterChineseChip.isChecked) add(LanguageTag.CHINESE)
        }
        val appCtx = applicationContext
        ioExecutor.execute {
            val photos: List<ScrapedPhoto>
            val clips: List<SavedVideoClip>
            if (showFullCameraRoll) {
                photos = miningRepository.getCameraRollPhotos()
                clips = emptyList()
            } else {
                photos = if (videoOnly) {
                    emptyList()
                } else {
                    miningRepository.getScrapedPhotosFiltered(unseen, gold, tags, flagged)
                }
                clips = if (gold || flagged) {
                    emptyList()
                } else {
                    miningRepository.getSavedVideoClipsFiltered(unseen, tags)
                        .filter { it.hasValidFiles(appCtx) }
                }
            }
            val rows = buildGalleryRows(
                photos = photos,
                clips = clips,
                videoOnly = !showFullCameraRoll && videoOnly,
                videoSectionTitle = { count -> getString(R.string.gallery_section_video_clips, count) },
                photoSectionTitle = { count -> getString(R.string.gallery_section_photos, count) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                currentPhotos = photos
                adapter.submitList(rows)
                binding.emptyPhotosText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
