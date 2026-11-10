package com.derricklee.ankidict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Generates and caches photo thumbnails *after* OCR extraction (or on demand),
 * and supports explicitly retaking/refreshing a photo's cached thumbnail from the source image.
 */
object PhotoThumbnailCache {
    private const val THUMB_EDGE_PX = 512
    private val memoryCache = object : LruCache<Long, Bitmap>(80) {}

    fun getMemoryCached(mediaStoreId: Long): Bitmap? = memoryCache.get(mediaStoreId)

    fun loadOrGenerateThumbnail(
        context: Context,
        mediaStoreId: Long,
        contentUri: String,
    ): Bitmap? {
        memoryCache.get(mediaStoreId)?.let { return it }

        val thumbFile = thumbFile(context, mediaStoreId)
        if (thumbFile.exists() && thumbFile.length() > 0L) {
            val diskBmp = runCatching { BitmapFactory.decodeFile(thumbFile.absolutePath) }.getOrNull()
            if (diskBmp != null) {
                memoryCache.put(mediaStoreId, diskBmp)
                return diskBmp
            }
        }

        return refreshThumbnail(context, mediaStoreId, contentUri)
    }

    /**
     * Forces regeneration of the thumbnail directly from the source URI (bypassing stale cached files)
     * and updates both the on-disk and in-memory thumbnail cache.
     */
    fun refreshThumbnail(
        context: Context,
        mediaStoreId: Long,
        contentUri: String,
    ): Bitmap? {
        val appContext = context.applicationContext
        val uri = Uri.parse(contentUri)
        val regenerated = decodeFreshThumbnailFromSource(appContext, uri)
            ?: runCatching {
                appContext.contentResolver.loadThumbnail(uri, Size(THUMB_EDGE_PX, THUMB_EDGE_PX), null)
            }.getOrNull()

        if (regenerated != null) {
            memoryCache.put(mediaStoreId, regenerated)
            runCatching {
                val file = thumbFile(appContext, mediaStoreId)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    regenerated.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        } else {
            memoryCache.remove(mediaStoreId)
        }
        return regenerated
    }

    /**
     * Saves a downscaled thumbnail directly from an already-decoded full-res bitmap after OCR finishes.
     */
    fun cacheFromFullBitmap(
        context: Context,
        mediaStoreId: Long,
        fullBitmap: Bitmap,
    ): Bitmap {
        val longEdge = max(fullBitmap.width, fullBitmap.height)
        val thumb = if (longEdge > THUMB_EDGE_PX) {
            val scale = THUMB_EDGE_PX.toFloat() / longEdge.toFloat()
            val w = (fullBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val h = (fullBitmap.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(fullBitmap, w, h, true)
        } else {
            fullBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        memoryCache.put(mediaStoreId, thumb)
        runCatching {
            val file = thumbFile(context.applicationContext, mediaStoreId)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
        return thumb
    }

    fun decodeFullResolutionOriented(
        context: Context,
        contentUri: String,
        maxEdgePx: Int = 2560,
    ): Bitmap? {
        val appContext = context.applicationContext
        val uri = Uri.parse(contentUri)
        return decodeOrientedFromSource(appContext, uri, maxEdgePx)
    }

    private fun decodeFreshThumbnailFromSource(context: Context, uri: Uri): Bitmap? {
        return decodeOrientedFromSource(context, uri, THUMB_EDGE_PX)
    }

    private fun decodeOrientedFromSource(context: Context, uri: Uri, targetEdgePx: Int): Bitmap? {
        val exifOrientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > targetEdgePx * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        }.getOrNull() ?: return null

        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val swap = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            exifOrientation == ExifInterface.ORIENTATION_ROTATE_270
        val orientedW = if (swap) decoded.height else decoded.width
        val orientedH = if (swap) decoded.width else decoded.height
        val currentEdge = max(orientedW, orientedH)
        if (currentEdge > targetEdgePx) {
            val scale = targetEdgePx.toFloat() / currentEdge.toFloat()
            matrix.postScale(scale, scale)
        }
        if (matrix.isIdentity) return decoded
        return runCatching {
            val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (result !== decoded) decoded.recycle()
            result
        }.getOrDefault(decoded)
    }

    private fun thumbFile(context: Context, mediaStoreId: Long): File {
        return File(File(context.filesDir, "photo_thumbs"), "thumb_$mediaStoreId.jpg")
    }
}
