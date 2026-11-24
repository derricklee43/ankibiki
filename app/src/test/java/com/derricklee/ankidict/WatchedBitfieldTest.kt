package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watched-bitfield port specs (S0-B). Cross-checked against the upstream JS semantics:
 * anchor:{len}:{zlib-b64}, LSB-first bits, front-shift realignment, corrupt -> blank.
 */
class WatchedBitfieldTest {

    private val ids = (1..12).map { "tt123:$it" }

    @Test
    fun roundTripThroughEncodeForTest() {
        val watched = setOf("tt123:2", "tt123:5", "tt123:12")
        val blob = WatchedBitfield.encodeForTest(watched, ids)
        val decoded = WatchedBitfield.decode(blob, ids)
        for (id in ids) {
            assertEquals(id, watched.contains(id), decoded.getVideo(id))
        }
        assertEquals(3, decoded.watchedCount())
    }

    @Test
    fun frontShiftRealignsViaAnchor() {
        // state written when the list was [ep1..ep12]; now the provider shifted one off
        val watched = setOf("tt123:3", "tt123:9")
        val blob = WatchedBitfield.encodeForTest(watched, ids)
        val shiftedList = ids.drop(1) + "tt123:13"
        val decoded = WatchedBitfield.decode(blob, shiftedList)
        // old ep3 is still ep3 by id -> stays watched; ep1 dropped; ep13 new/unwatched
        assertTrue(decoded.getVideo("tt123:3"))
        assertTrue(decoded.getVideo("tt123:9"))
        assertFalse(decoded.getVideo("tt123:13"))
    }

    @Test
    fun anchorNotFoundYieldsBlankNotCrash() {
        val blob = WatchedBitfield.encodeForTest(setOf("tt123:5"), ids)
        val foreign = (1..12).map { "other:$it" }
        val decoded = WatchedBitfield.decode(blob, foreign)
        assertEquals(0, decoded.watchedCount())
    }

    @Test
    fun garbageInputsDegradeToBlank() {
        for (bad in listOf("", "nonsense", "a:1", "a:1:!!notbase64!!", "a:notanumber:AAAA")) {
            assertEquals(0, WatchedBitfield.decode(bad, ids).watchedCount())
        }
        assertEquals(0, WatchedBitfield.decode(null, ids).watchedCount())
    }

    @Test
    fun anchorWithColonsSurvivesSplitAmbiguity() {
        // upstream splits from the right: anchor ids themselves contain ':' (imdb:S:E)
        val blob = WatchedBitfield.encodeForTest(setOf("a:b:c:7"), listOf("a:b:c:7", "a:b:c:8"))
        val decoded = WatchedBitfield.decode(blob, listOf("a:b:c:7", "a:b:c:8"))
        assertTrue(decoded.getVideo("a:b:c:7"))
        assertFalse(decoded.getVideo("a:b:c:8"))
    }
}
