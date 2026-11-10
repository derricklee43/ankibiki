package com.derricklee.ankidict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidImagePreprocessor(context: Context) : ImagePreprocessor {
    private val appContext = context.applicationContext

    override fun prepare(
        photo: PhotoCandidate,
        config: ImagePreprocessConfig,
    ): PreparedImage? {
        val uri = Uri.parse(photo.contentUri)

        // 1. Read EXIF orientation if enabled
        val exifOrientation = if (config.autoRotateExif) {
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } else {
            ExifInterface.ORIENTATION_NORMAL
        }

        // 2. Decode bounds to compute initial subsampling
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        }
        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        val targetLongEdge = config.maxLongEdgePx.coerceAtLeast(256)
        while (max(rawWidth / sampleSize, rawHeight / sampleSize).toLong() > targetLongEdge.toLong() * 2L) {
            sampleSize *= 2
        }

        // 3. Decode bitmap with inSampleSize
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }.getOrNull() ?: return null

        // 4. Apply EXIF rotation and exact downscale to config.maxLongEdgePx
        val transformed = transformBitmap(decoded, exifOrientation, targetLongEdge)
        if (transformed !== decoded) {
            decoded.recycle()
        }

        // 5. Compress to JPEG at config.jpegQuality
        val jpegStream = ByteArrayOutputStream()
        val quality = config.jpegQuality.coerceIn(1, 100)
        transformed.compress(Bitmap.CompressFormat.JPEG, quality, jpegStream)
        return PreparedImage(
            bitmap = transformed,
            jpegBytes = jpegStream.toByteArray(),
        )
    }

    private fun transformBitmap(
        source: Bitmap,
        exifOrientation: Int,
        maxLongEdgePx: Int,
    ): Bitmap {
        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }

        val rotatedSwapDimensions = exifOrientation in setOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )
        val orientedWidth = if (rotatedSwapDimensions) source.height else source.width
        val orientedHeight = if (rotatedSwapDimensions) source.width else source.height
        val currentLongEdge = max(orientedWidth, orientedHeight)

        if (currentLongEdge > maxLongEdgePx) {
            val scale = maxLongEdgePx.toFloat() / currentLongEdge.toFloat()
            matrix.postScale(scale, scale)
        }

        if (matrix.isIdentity) return source

        return runCatching {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                true,
            )
        }.getOrElse {
            val scale = maxLongEdgePx.toFloat() / currentLongEdge.toFloat()
            val scaledW = (source.width * scale).roundToInt().coerceAtLeast(1)
            val scaledH = (source.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
        }
    }
}
