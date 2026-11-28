package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the single-sentence-view provenance label (user request 2026-11-26):
 * sentences mined from Stremio cues show "Series - S1-E1  Episode title". Pure parts
 * only - the DB resolve (getSentenceProvenance) is instrumented-covered.
 */
class MediaProvenanceTest {

    @Test
    fun episodeLabelFromStremioVideoId() {
        assertEquals("S1-E2", VideoSourceLabel.episodeLabel("tt8515016:1:2"))
        assertEquals("S0-E1", VideoSourceLabel.episodeLabel("tt13293588:0:1"))
        assertEquals("S2-E11", VideoSourceLabel.episodeLabel("tt32991344:2:11"))
    }

    @Test
    fun episodeLabelRejectsNonEpisodeShapes() {
        assertNull(VideoSourceLabel.episodeLabel("tt8515016"))          // movie id
        assertNull(VideoSourceLabel.episodeLabel("tmdb:123:movie"))     // non-numeric tail
        assertNull(VideoSourceLabel.episodeLabel(""))
    }

    @Test
    fun baseIdIsFirstSegmentOfColonTriple() {
        assertEquals("tt8515016", VideoSourceLabel.baseId("tt8515016:1:2"))
        // two-colon minimum keeps plain ids (which could carry their own colon) out
        assertNull(VideoSourceLabel.baseId("yt:abcdef"))
    }

    @Test
    fun displayCombinesKnownPartsOnly() {
        assertEquals(
            "Banana Fish - S1-E2",
            VideoSourceLabel.display("Banana Fish", "S1-E2", null),
        )
        assertEquals(
            "Demon Slayer: Kimetsu no Yaiba - S1-E1  Cruelty",
            VideoSourceLabel.display("Demon Slayer: Kimetsu no Yaiba", "S1-E1", "Cruelty"),
        )
        // series gone from the library -> episode label alone still renders
        assertEquals("S1-E3", VideoSourceLabel.display(null, "S1-E3", null))
        // nothing known -> no line
        assertNull(VideoSourceLabel.display(null, null, "Cruelty"))
        assertNull(VideoSourceLabel.display("", null, null))
    }

    @Test
    fun mediaKindBadgeRegistry_handlesKnownAndUnknownKinds() {
        val photo = MediaKindUi.badgeFor("photo")
        assertEquals(R.string.media_kind_photo, photo.labelResId)
        assertFalse(photo.emphasized)

        val video = MediaKindUi.badgeFor("video_cues")
        assertEquals(R.string.media_kind_video, video.labelResId)
        assertTrue(video.emphasized)

        // Forward-compat: unknown future kinds never crash and fall back to raw kind text.
        val future = MediaKindUi.badgeFor("audio_book")
        assertNull(future.labelResId)
        assertEquals("audio_book", future.fallbackLabel)
        assertTrue(future.emphasized)
    }

    @Test
    fun groupNonPhotoSources_skipsPhotos_groupsAndSortsByCountDesc() {
        val sentences = listOf(
            MatchedSentence(1L, 10L, "写真文", mediaKind = "photo", mediaId = 1L),
            MatchedSentence(2L, -1L, "動画A1", mediaKind = "video_cues", mediaLabel = "tt1:1:1", mediaId = 20L),
            MatchedSentence(3L, -1L, "動画B1", mediaKind = "video_cues", mediaLabel = "tt1:1:2", mediaId = 30L),
            MatchedSentence(4L, -1L, "動画B2", mediaKind = "video_cues", mediaLabel = "tt1:1:2", mediaId = 30L),
        )
        val grouped = MediaKindUi.groupNonPhotoSources(sentences) { mid, fallback, _ ->
            if (mid == 30L) "Show - S1-E2" else fallback.orEmpty()
        }
        assertEquals(2, grouped.size)
        assertEquals(MediaSourceItem(30L, "video_cues", "Show - S1-E2", 2), grouped[0])
        assertEquals(MediaSourceItem(20L, "video_cues", "tt1:1:1", 1), grouped[1])
    }
}
