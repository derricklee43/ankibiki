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
import java.util.concurrent.Executors

private const val MEDIA_PERMISSION_REQUEST_CODE = 100

class PhotoLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoLibraryBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var adapter: PhotoThumbnailAdapter
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var currentPhotos: List<ScrapedPhoto> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        miningRepository = MiningRepository(this)
        adapter = PhotoThumbnailAdapter(horizontalStrip = false) { photo ->
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
        }

        binding.photosGrid.layoutManager = GridLayoutManager(this, 3)
        binding.photosGrid.adapter = adapter

        binding.backButton.setOnClickListener { finish() }

        // library filters (roadmap B3): each facet toggle re-queries the grid
        binding.filterUnseenChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }
        binding.filterGoldChip.setOnCheckedChangeListener { _, _ -> loadPhotos() }
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
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
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
        binding.filterChipGroup.visibility = if (showFullCameraRoll) View.GONE else View.VISIBLE
        val unseen = binding.filterUnseenChip.isChecked
        val gold = binding.filterGoldChip.isChecked
        val tags = buildSet {
            if (binding.filterJapaneseChip.isChecked) add(LanguageTag.JAPANESE)
            if (binding.filterChineseChip.isChecked) add(LanguageTag.CHINESE)
        }
        ioExecutor.execute {
            val photos = if (showFullCameraRoll) {
                miningRepository.getCameraRollPhotos()
            } else {
                miningRepository.getScrapedPhotosFiltered(unseen, gold, tags)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                currentPhotos = photos
                adapter.submitList(photos)
                binding.emptyPhotosText.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
