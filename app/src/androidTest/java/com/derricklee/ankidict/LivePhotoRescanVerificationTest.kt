package com.derricklee.ankidict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in diagnostic for a user-requested real-photo rescan. NEVER runs on the live
 * corpus in the normal suite: pass liveRescanPhotoId explicitly to enable writes.
 * Example runner arguments: -e liveRescanPhotoId 953 -e expectedSentenceCount 2
 * ACCEPT explicitly authorizes replacement of edits on only that photo.
 */
@RunWith(AndroidJUnit4::class)
class LivePhotoRescanVerificationTest {
    @Test
    fun explicitlyRequestedPhoto_isRescannedThroughTheProductionPipeline() {
        val arguments = InstrumentationRegistry.getArguments()
        val id = arguments.getString("liveRescanPhotoId")?.toLongOrNull()
        assumeTrue("Live rescans require an explicitly supplied photo id", id != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = MiningRepository(context)
        try {
            val photo = repository.getPhoto(id!!)!!
            val runner = MiningPipelineRunner.createDefault(
                context, repository, DictionaryRepository(context), ShinjitaiRepository(context), HskRepository(context),
            )
            assertEquals(PhotoRescanResult.COMPLETED, runner.rescanPhoto(
                PhotoCandidate(photo.mediaStoreId, photo.contentUri, photo.dateAdded), PhotoRescanDecision.ACCEPT,
            ))
            val rescanned = repository.getPhoto(id)!!
            val document = OcrDocument.fromJsonString(rescanned.ocrBlocksJson)
            assertNotNull(document)
            val sentences = CjkSentenceParser().parseSentences(document!!)
            println("LIVE_RESCAN_PHOTO_ID=$id")
            println("LIVE_RESCAN_OCR=${document.toJsonString()}")
            println("LIVE_RESCAN_SENTENCES=${org.json.JSONArray(sentences)}")
            println("LIVE_RESCAN_DISPLAY=${org.json.JSONObject.quote(rescanned.extractedText)}")
            println("LIVE_RESCAN_SENTENCE_COUNT=${sentences.size}")
            val expected = arguments.getString("expectedSentenceCount")?.toIntOrNull()
            if (expected != null) assertEquals("Actual production-parser sentence count", expected, sentences.size)
            assertEquals(sentences.joinToString("\n"), rescanned.extractedText)
        } finally {
            repository.close()
        }
    }
}
