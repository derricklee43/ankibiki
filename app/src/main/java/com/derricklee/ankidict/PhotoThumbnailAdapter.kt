package com.derricklee.ankidict

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ItemPhotoThumbnailBinding

class PhotoThumbnailAdapter(
    private val horizontalStrip: Boolean = false,
    private val onPhotoClick: (ScrapedPhoto) -> Unit,
) : RecyclerView.Adapter<PhotoThumbnailAdapter.ViewHolder>() {

    private var items: List<ScrapedPhoto> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun submitList(newItems: List<ScrapedPhoto>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPhotoThumbnailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        if (horizontalStrip) {
            val sizePx = (parent.context.resources.displayMetrics.density * 108).toInt()
            binding.root.layoutParams = RecyclerView.LayoutParams(sizePx, sizePx)
        }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemPhotoThumbnailBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundMediaId: Long = -1L

        fun bind(photo: ScrapedPhoto) {
            boundMediaId = photo.mediaStoreId
            binding.root.setOnClickListener { onPhotoClick(photo) }
            binding.goldChip.visibility = if (photo.isGold) android.view.View.VISIBLE else android.view.View.GONE

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
        private val thumbnailExecutor = AppIo.thumbnails
    }
}
