package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v11 flagger's DECOUPLING contract (user 2026-11-15: "Fixed palette for now,
 * eventually ... a color selector, so make sure the fixed palette is not too tightly
 * coupled with the rest of the feature"): storage treats flag_color as an opaque string;
 * the palette only RESOLVES values for display. A #RRGGBB written by a future color picker
 * must render today without any schema or semantic change, and unknown junk must degrade
 * to a neutral gray dot rather than crash.
 */
class PhotoFlagPaletteTest {

    @Test
    fun paletteKeysResolveToTheirColors() {
        for (entry in PhotoFlagPalette.entries) {
            assertEquals(entry.argb, PhotoFlagPalette.resolveColor(entry.key))
            assertEquals(entry.labelJa, PhotoFlagPalette.displayName(entry.key))
            assertTrue(entry.key, PhotoFlagPalette.isPaletteKey(entry.key))
        }
        // Keys are matched case-insensitively (an old row's casing never loses its color).
        assertEquals(PhotoFlagPalette.resolveColor("red"), PhotoFlagPalette.resolveColor("RED"))
    }

    @Test
    fun hexValuesRoundTripWithoutPaletteMembership() {
        // The future color-selector path: arbitrary hex, no palette entry, still renders.
        val argb = PhotoFlagPalette.resolveColor("#ff8800")
        assertEquals(0xFFFF8800.toInt(), argb)
        assertEquals("#ff8800", PhotoFlagPalette.displayName("#ff8800"))
        assertFalse(PhotoFlagPalette.isPaletteKey("#ff8800"))
        // Short hex expands; uppercase hex parses; both opaque.
        assertEquals(0xFFFF8800.toInt(), PhotoFlagPalette.resolveColor("#f80"))
        assertEquals(0xFF1A2B3C.toInt(), PhotoFlagPalette.resolveColor("#1A2B3C"))
    }

    @Test
    fun unknownValuesDegradeToGray_notCrashAndNotLoseData() {
        assertEquals(PhotoFlagPalette.UNKNOWN_GRAY, PhotoFlagPalette.resolveColor("chartreuse"))
        assertEquals(PhotoFlagPalette.UNKNOWN_GRAY, PhotoFlagPalette.resolveColor("#gg0000"))
        assertEquals(PhotoFlagPalette.UNKNOWN_GRAY, PhotoFlagPalette.resolveColor("#12345"))
        // Display falls back to the raw string, so the user still sees what is stored.
        assertEquals("chartreuse", PhotoFlagPalette.displayName("chartreuse"))
    }

    @Test
    fun nullAndBlankMeanNoFlag() {
        assertNull(PhotoFlagPalette.resolveColor(null))
        assertNull(PhotoFlagPalette.resolveColor(""))
        assertNull(PhotoFlagPalette.resolveColor("   "))
        assertEquals("", PhotoFlagPalette.displayName(null))
        assertFalse(PhotoFlagPalette.isPaletteKey(null))
    }
}
