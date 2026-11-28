package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SavedVideoClipsTest {

    @Test
    fun fileBaseName_isDeterministicAndDistinctPerSentence() {
        val a1 = SavedVideoClipStorage.fileBaseName(42L, "炭治郎、逃げるな！")
        val a2 = SavedVideoClipStorage.fileBaseName(42L, "炭治郎、逃げるな！")
        val b = SavedVideoClipStorage.fileBaseName(42L, "別のセリフ")
        val c = SavedVideoClipStorage.fileBaseName(43L, "炭治郎、逃げるな！")

        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertNotEquals(a1, c)
        assertTrue(a1.matches(Regex("clip_m42_[0-9a-f]{12}")))
    }

    @Test
    fun copyFilesToPermanent_copiesOverwritesSelfSafelyAndDeletes() {
        val root = Files.createTempDirectory("saved_clips_test").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            val perm = File(root, "perm")
            val srcImg = File(cache, "slice.webp").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val srcAud = File(cache, "slice.m4a").apply { writeBytes(byteArrayOf(5, 6, 7, 8, 9)) }

            val base = SavedVideoClipStorage.fileBaseName(7L, "テスト文")
            val (imgName, audName) = SavedVideoClipStorage.copyFilesToPermanent(perm, base, srcImg, srcAud)

            val destImg = File(perm, imgName)
            val destAud = File(perm, audName)
            assertTrue(destImg.isFile && destImg.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
            assertTrue(destAud.isFile && destAud.readBytes().contentEquals(byteArrayOf(5, 6, 7, 8, 9)))

            // Self-copy (re-saving from permanent dir) must not corrupt or delete files.
            val (imgName2, audName2) = SavedVideoClipStorage.copyFilesToPermanent(perm, base, destImg, destAud)
            assertEquals(imgName, imgName2)
            assertEquals(audName, audName2)
            assertTrue(destImg.isFile && destImg.length() == 4L)
            assertTrue(destAud.isFile && destAud.length() == 5L)

            // Overwrite with updated slice bytes.
            srcImg.writeBytes(byteArrayOf(9, 9))
            SavedVideoClipStorage.copyFilesToPermanent(perm, base, srcImg, srcAud)
            assertTrue(destImg.readBytes().contentEquals(byteArrayOf(9, 9)))

            SavedVideoClipStorage.deleteFiles(perm, imgName, audName)
            assertFalse(destImg.exists())
            assertFalse(destAud.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun buildGalleryRows_omitsHeadersWhenNoClipsExist() {
        val photos = listOf(dummyPhoto(1L), dummyPhoto(2L))
        val rows = buildGalleryRows(
            photos = photos,
            clips = emptyList(),
            videoOnly = false,
            videoSectionTitle = { "動画 ($it)" },
            photoSectionTitle = { "写真 ($it)" },
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is GalleryRow.PhotoTile })
    }

    @Test
    fun buildGalleryRows_splitsVideoClipsIntoDedicatedTopSection() {
        val photos = listOf(dummyPhoto(1L), dummyPhoto(2L))
        val clips = listOf(dummyClip(10L, "文A"), dummyClip(11L, "文B"))
        val rows = buildGalleryRows(
            photos = photos,
            clips = clips,
            videoOnly = false,
            videoSectionTitle = { "保存した動画クリップ ($it)" },
            photoSectionTitle = { "写真 ($it)" },
        )
        assertEquals(6, rows.size)
        assertEquals(GalleryRow.SectionHeader("保存した動画クリップ (2)"), rows[0])
        assertTrue(rows[1] is GalleryRow.VideoClipTile)
        assertTrue(rows[2] is GalleryRow.VideoClipTile)
        assertEquals(GalleryRow.SectionHeader("写真 (2)"), rows[3])
        assertTrue(rows[4] is GalleryRow.PhotoTile)
        assertTrue(rows[5] is GalleryRow.PhotoTile)
    }

    @Test
    fun buildGalleryRows_videoOnlyShowsOnlyVideoSection() {
        val photos = listOf(dummyPhoto(1L))
        val clips = listOf(dummyClip(10L, "文A"))
        val rows = buildGalleryRows(
            photos = photos,
            clips = clips,
            videoOnly = true,
            videoSectionTitle = { "保存した動画クリップ ($it)" },
            photoSectionTitle = { "写真 ($it)" },
        )
        assertEquals(2, rows.size)
        assertEquals(GalleryRow.SectionHeader("保存した動画クリップ (1)"), rows[0])
        assertTrue(rows[1] is GalleryRow.VideoClipTile)

        val emptyRows = buildGalleryRows(
            photos = photos,
            clips = emptyList(),
            videoOnly = true,
            videoSectionTitle = { "保存した動画クリップ ($it)" },
            photoSectionTitle = { "写真 ($it)" },
        )
        assertTrue(emptyRows.isEmpty())
    }

    private fun dummyPhoto(id: Long) = ScrapedPhoto(
        mediaStoreId = id,
        contentUri = "content://media/$id",
        dateAdded = 1000L + id,
        languageTag = LanguageTag.JAPANESE,
        extractedText = "テスト",
        ocrBlocksJson = "[]",
        status = PhotoStatus.KEEP,
        duplicateOfPhotoId = null,
    )

    private fun dummyClip(id: Long, sentence: String) = SavedVideoClip(
        id = id,
        mediaId = 100L + id,
        sentence = sentence,
        docId = 5L,
        videoId = "tt123:1:1",
        startMs = 1000L,
        endMs = 3000L,
        offsetMs = 0,
        audioTrack = 1,
        imageFileName = "clip_$id.webp",
        audioFileName = "clip_$id.m4a",
        languageTag = LanguageTag.JAPANESE,
        provenanceLabel = "Show - S1-E1",
        savedAt = 2000L + id,
    )
}
