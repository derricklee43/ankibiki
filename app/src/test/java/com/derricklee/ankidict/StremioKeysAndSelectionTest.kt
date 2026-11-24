package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S0-D2 evidence: episode-reference parsing (video ids + RD filenames) + selection
 * ladder + generation hashing. The parse pass bar from the design: >=80% of realistic
 * release names; this corpus is the JVM-side half of that measurement.
 */
class StremioKeysAndSelectionTest {

    @Test
    fun videoIdEpisodeRefs() {
        val ref = AddonResources.regexEpisodeRef("tt1319145:2:7")
        assertEquals(2, ref?.season)
        assertEquals(7, ref?.episode)
        assertNull(AddonResources.regexEpisodeRef("tt1319145"))
        assertNull(AddonResources.regexEpisodeRef("yt:video:abc"))
    }

    @Test
    fun filenameRefsCoverRealWorldShapes() {
        val cases = listOf(
            "Show.Name.S01E04.1080p.WEB-DL" to (1 to 4),
            "[Group] Show Name - 07 (1080p) [HASH]" to (1 to 7),
            "Show.Name.2x11.720p" to (2 to 11),
            "Show Name Episode 12 [1080p]" to (1 to 12),
            "Show.Name.S03E01v2" to (3 to 1),
            "Show S02 - E08" to (2 to 8),
        )
        var parsed = 0
        for ((name, expected) in cases) {
            val ref = AddonResources.parseEpisodeFromFilename(name)
            if (ref != null) parsed++
            assertEquals(name, expected.first, ref?.season)
            assertEquals(name, expected.second, ref?.episode)
        }
        // pass bar measured against the user's REAL history in S0-D2; this corpus is 100%
        assertTrue(parsed == cases.size)
        assertNull(AddonResources.parseEpisodeFromFilename("random movie file.mkv"))
    }

    @Test
    fun selectionLadderPinThenPatternThenFirst() {
        val choices = listOf(
            SubtitleChoice("s1", "jpn", "[HorribleSubs] Show - 04", "u1", "a"),
            SubtitleChoice("s2", "eng", "English only", "u2", "a"),
        )
        // exact pin wins
        assertEquals("s1", StremioSelection.select(
            StremioSettingsRow("g", "jpn", null, "[HorribleSubs] Show - 04", null, 0), choices,
        ).subId)
        // pattern substring wins when no pin
        assertEquals("s1", StremioSelection.select(
            StremioSettingsRow("g", "jpn", "HorribleSubs", null, null, 0), choices,
        ).subId)
        // pin MISS falls to pattern, then to FIRST — never an error (D5: unopinionated)
        assertEquals("s1", StremioSelection.select(
            StremioSettingsRow("g", "jpn", null, "nonexistent file", null, 0), choices,
        ).subId)
        assertEquals("s1", StremioSelection.select(
            StremioSettingsRow("g", "jpn", null, null, null, 0), choices,
        ).subId)
    }

    @Test
    fun generationIsContentHashStableAndSensitive() {
        val a = StremioSettingsRow("g", "jpn", "grp", null, 2, 0)
        val b = StremioSettingsRow("ep", "jpn", "grp", null, 2, 0) // scope differs, tuple same
        val c = StremioSettingsRow("g", "eng", "grp", null, 2, 0)
        assertEquals(StremioExtractionEngine.Generation.of(a), StremioExtractionEngine.Generation.of(b))
        assertTrue(StremioExtractionEngine.Generation.of(a) !=
            StremioExtractionEngine.Generation.of(c))
        assertEquals(16, StremioExtractionEngine.Generation.of(a).length)
    }

    @Test
    fun sha256KnownVector() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            StremioExtractionEngine.sha256("hello"),
        )
    }

    @Test
    fun languageTagMapping() {
        assertEquals(LanguageTag.JAPANESE, StremioExtractionEngine.tagForLang("jpn"))
        assertEquals(LanguageTag.CHINESE, StremioExtractionEngine.tagForLang("zho"))
        assertEquals(LanguageTag.CHINESE, StremioExtractionEngine.tagForLang("chi"))
        assertEquals(LanguageTag.JAPANESE, StremioExtractionEngine.tagForLang("nonsense"))
    }

    @Test
    fun secretMaskingNeverLeaksWholeValue() {
        val masked = StremioPrefs.mask("abcdefghijklmnop")
        assertTrue(!masked.contains("cdefghijklmn"))
        assertTrue(masked.contains("abc"))
        assertEquals("not configured", StremioPrefs.mask(null))
    }

    @Test
    fun premiumWindowParsing() {
        val future = RdUser("u", "premium", "2035-01-01T00:00:00.000Z")
        assertTrue(RdClient.isPremiumActive(future, System.currentTimeMillis()))
        assertTrue(!RdClient.isPremiumActive(RdUser("u", "free", null), 0L))
        assertTrue(!RdClient.isPremiumActive(RdUser("u", "premium", "garbage"), 0L))
    }
}
