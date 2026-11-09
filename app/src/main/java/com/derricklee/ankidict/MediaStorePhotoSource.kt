package com.derricklee.ankidict

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class MediaStorePhotoSource(
    context: Context,
    private val miningRepository: MiningRepository,
) : PhotoSource {
    private val appContext = context.applicationContext

    override fun findUnprocessedPhotos(config: ScanDiscoveryConfig): List<PhotoCandidate> {
        val processedIds = miningRepository.getAllProcessedMediaStoreIds()
        val isFirstRun = processedIds.isEmpty()
        val maxWindow = if (isFirstRun) config.initialBackfillLimit else Int.MAX_VALUE

        val candidates = mutableListOf<PhotoCandidate>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

        runCatching {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                var inspected = 0
                while (cursor.moveToNext() && inspected < maxWindow && candidates.size < config.batchSize) {
                    inspected++
                    val id = cursor.getLong(idCol)
                    if (id in processedIds) continue
                    val dateAdded = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ).toString()
                    candidates.add(
                        PhotoCandidate(
                            mediaStoreId = id,
                            contentUri = uri,
                            dateAdded = dateAdded,
                        ),
                    )
                }
            }
        }
        return candidates
    }
}
