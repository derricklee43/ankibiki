package com.derricklee.ankidict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ItemGallerySectionHeaderBinding
import com.derricklee.ankidict.databinding.ItemPhotoThumbnailBinding
import com.derricklee.ankidict.databinding.ItemVideoClipThumbnailBinding
import kotlin.math.max

/**
 * Row model for すべての写真 (`PhotoLibraryActivity`): splits saved video clips into
 * their own section above mined photos without polluting `scraped_photos`.
 */
sealed class GalleryRow {
    data class SectionHeader(val title: String) : GalleryRow()
    data class VideoClipTile(val clip: SavedVideoClip) : GalleryRow()
    data class PhotoTile(val photo: ScrapedPhoto) : GalleryRow()
}

/**
 * Pure row-builder for [PhotoLibraryActivity]:
 *  - When [videoOnly] is true, emits only [clips] (no redundant photo section).
 *  - When [clips] is non-empty and [videoOnly] is false, renders a dedicated
 *    "保存した動画クリップ" section followed by the "写真" section.
 *  - When [clips] is empty, emits photo tiles directly with no section headers
 *    (byte-identical to the legacy photos-only grid).
 */
internal fun buildGalleryRows(
    photos: List<ScrapedPhoto>,
    clips: List<SavedVideoClip>,
    videoOnly: Boolean,
    videoSectionTitle: (Int) -> String,
    photoSectionTitle: (Int) -> String,
): List<GalleryRow> {
    if (videoOnly) {
        if (clips.isEmpty()) return emptyList()
        return buildList(clips.size + 1) {
            add(GalleryRow.SectionHeader(videoSectionTitle(clips.size)))
            for (c in clips) add(GalleryRow.VideoClipTile(c))
        }
    }
    if (clips.isEmpty()) {
        return photos.map { GalleryRow.PhotoTile(it) }
    }
    return buildList(clips.size + photos.size + 2) {
        add(GalleryRow.SectionHeader(videoSectionTitle(clips.size)))
        for (c in clips) add(GalleryRow.VideoClipTile(c))
        if (photos.isNotEmpty()) {
            add(GalleryRow.SectionHeader(photoSectionTitle(photos.size)))
            for (p in photos) add(GalleryRow.PhotoTile(p))
        }
    }
}

class PhotoLibraryAdapter(
    private val onPhotoClick: (ScrapedPhoto) -> Unit,
    private val onVideoClipClick: (SavedVideoClip) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<GalleryRow> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun submitList(newItems: List<GalleryRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun isFullSpan(position: Int): Boolean =
        items.getOrNull(position) is GalleryRow.SectionHeader

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is GalleryRow.SectionHeader -> VIEW_TYPE_HEADER
        is GalleryRow.VideoClipTile -> VIEW_TYPE_VIDEO_CLIP
        is GalleryRow.PhotoTile -> VIEW_TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemGallerySectionHeaderBinding.inflate(inflater, parent, false),
            )
            VIEW_TYPE_VIDEO_CLIP -> VideoClipViewHolder(
                ItemVideoClipThumbnailBinding.inflate(inflater, parent, false),
            )
            else -> PhotoViewHolder(
                ItemPhotoThumbnailBinding.inflate(inflater, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is GalleryRow.SectionHeader -> (holder as HeaderViewHolder).bind(row.title)
            is GalleryRow.VideoClipTile -> (holder as VideoClipViewHolder).bind(row.clip)
            is GalleryRow.PhotoTile -> (holder as PhotoViewHolder).bind(row.photo)
        }
    }

    override fun getItemCount(): Int = items.size

    private class HeaderViewHolder(
        private val binding: ItemGallerySectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.sectionHeaderText.text = title
        }
    }

    private inner class VideoClipViewHolder(
        private val binding: ItemVideoClipThumbnailBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundKey: String = ""

        fun bind(clip: SavedVideoClip) {
            val cacheKey = "${clip.id}:${clip.savedAt}"
            boundKey = cacheKey
            binding.root.setOnClickListener { onVideoClipClick(clip) }

            val cached = videoThumbCache.get(cacheKey)
            if (cached != null) {
                binding.thumbnailImage.setImageBitmap(cached)
                return
            }

            binding.thumbnailImage.setImageDrawable(null)
            val appContext = binding.root.context.applicationContext
            thumbnailExecutor.execute {
                val bmp = decodeClipThumbnail(clip.imageFile(appContext).absolutePath)
                if (bmp != null) {
                    videoThumbCache.put(cacheKey, bmp)
                }
                mainHandler.post {
                    if (boundKey == cacheKey) {
                        binding.thumbnailImage.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private inner class PhotoViewHolder(
        private val binding: ItemPhotoThumbnailBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundMediaId: Long = -1L

        fun bind(photo: ScrapedPhoto) {
            boundMediaId = photo.mediaStoreId
            binding.root.setOnClickListener { onPhotoClick(photo) }
            binding.goldChip.visibility = if (photo.isGold) View.VISIBLE else View.GONE
            val flagColor = PhotoFlagPalette.resolveColor(photo.flagColor)
            binding.flagDot.visibility = if (flagColor != null) View.VISIBLE else View.GONE
            if (flagColor != null) {
                binding.flagDot.backgroundTintList = android.content.res.ColorStateList.valueOf(flagColor)
            }

            val cached = PhotoThumbnailCache.getMemoryCached(photo.mediaStoreId)
            if (cached != null) {
                binding.thumbnailImage.setImageBitmap(cached)
                return
            }

            binding.thumbnailImage.setImageDrawable(null)
            val appContext = binding.root.context.applicationContext
            thumbnailExecutor.execute {
                val bmp = PhotoThumbnailCache.loadOrGenerateThumbnail(
                    appContext,
                    photo.mediaStoreId,
                    photo.contentUri,
                )
                mainHandler.post {
                    if (boundMediaId == photo.mediaStoreId) {
                        binding.thumbnailImage.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_VIDEO_CLIP = 1
        private const val VIEW_TYPE_PHOTO = 2
        private const val CLIP_THUMB_EDGE_PX = 512

        private val thumbnailExecutor = AppIo.thumbnails
        private val videoThumbCache = object : LruCache<String, Bitmap>(60) {}

        private fun decodeClipThumbnail(path: String): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sampleSize = 1
            while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > CLIP_THUMB_EDGE_PX * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }
}
