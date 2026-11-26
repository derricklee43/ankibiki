package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
